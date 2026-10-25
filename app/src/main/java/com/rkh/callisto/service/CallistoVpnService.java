package com.rkh.callisto.service;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import com.rkh.callisto.core.NativeCore;
import com.rkh.callisto.log.DebugLog;
import com.rkh.callisto.model.ConnectionMode;
import com.rkh.callisto.model.ConnectionStateStore;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

public final class CallistoVpnService extends VpnService {
    public static final String EXTRA_EXIT_COUNTRY = "exit_country";
    public static final String EXTRA_PSIPHON_ENABLED = "psiphon_enabled";
    public static final String ACTION_STOP = "com.rkh.callisto.STOP_VPN";
    private ParcelFileDescriptor tunnel;
    private volatile boolean stopping;
    private volatile boolean started;
    private volatile boolean connected;
    private volatile boolean cleanupRequested;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLog.initialize(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            ConnectionStateStore.setDesiredActive(this, false);
            stopTunnel(true);
            return START_NOT_STICKY;
        }
        if (intent == null && !ConnectionStateStore.isDesiredActive(this)) {
            DebugLog.add("VPN", "Ignored Android restart after user disconnect");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (started && !stopping) {
            DebugLog.add("VPN", "Ignoring duplicate VPN start request");
            return START_STICKY;
        }

        started = true;
        ConnectionStateStore.setDesiredActive(this, true);
        startForeground(ServiceSupport.NOTIFICATION_ID,
                ServiceSupport.notification(this, "Connecting", CallistoVpnService.class, ACTION_STOP));

        stopping = false;
        connected = false;
        ConnectionStateStore.update(this, ConnectionStateStore.Status.CONNECTING,
                ConnectionMode.VPN, "Preparing secure route");
        DebugLog.add("VPN", "Preparing the connection core before creating Android VPN");
        android.content.SharedPreferences routePrefs = getSharedPreferences(
                "callisto_preferences", MODE_PRIVATE);
        boolean psiphonEnabled = intent == null
                ? routePrefs.getBoolean(EXTRA_PSIPHON_ENABLED, true)
                : intent.getBooleanExtra(EXTRA_PSIPHON_ENABLED, true);
        if (!NativeCore.isAvailable(this, psiphonEnabled)) {
            fail("A bundled connection component is missing");
            return START_NOT_STICKY;
        }

        String exitCountry = intent == null
                ? routePrefs.getString(EXTRA_EXIT_COUNTRY, "BEST")
                : intent.getStringExtra(EXTRA_EXIT_COUNTRY);
        DebugLog.add("VPN", "Connection request received with Snowflake, route="
                + (psiphonEnabled ? "Snowflake+Psiphon" : "Snowflake only"));
        NativeCore.startRoute(this, exitCountry, "SNOWFLAKE", psiphonEnabled,
                false, new NativeCore.Listener() {
            @Override public void onProgress(String message) {
                if (stopping) return;
                if (connected) return;
                ConnectionStateStore.update(CallistoVpnService.this,
                        ConnectionStateStore.Status.CONNECTING, ConnectionMode.VPN, message);
            }

            @Override public void onReady(String route) {
                if (stopping) return;
                main.post(() -> {
                    if (!stopping) establishVpnAfterCoreReady();
                });
            }

            @Override public void onError(String message) {
                fail(message);
            }
        });
        return START_STICKY;
    }

    private void establishVpnAfterCoreReady() {
        try {
            ConnectionStateStore.update(this, ConnectionStateStore.Status.CONNECTING,
                    ConnectionMode.VPN, "Creating Android VPN interface");
            Builder builder = new Builder()
                    .setSession("Callisto")
                    .setMtu(1280)
                    .addAddress("10.47.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1");
            if (Build.VERSION.SDK_INT >= 29) {
                builder.setBlocking(true);
                builder.setMetered(false);
            }

            android.content.SharedPreferences prefs = getSharedPreferences(
                    "callisto_preferences", MODE_PRIVATE);
            boolean splitEnabled = prefs.getBoolean("split_tunneling", false);
            String splitMode = prefs.getString("split_mode", "BYPASS");
            if (splitEnabled && "VPN_ONLY".equals(splitMode)) {
                Set<String> packages = prefs.getStringSet("split_apps", Collections.emptySet());
                int applied = 0;
                for (String packageName : packages) {
                    if (getPackageName().equals(packageName)) continue;
                    try {
                        builder.addAllowedApplication(packageName);
                        applied++;
                    } catch (PackageManager.NameNotFoundException ignored) {
                        DebugLog.add("VPN", "Skipped an app that is no longer installed");
                    }
                }
                if (applied == 0) {
                    throw new IOException("Select at least one app for VPN Only mode");
                }
                // Only explicitly allowed apps enter the tunnel, so Callisto remains
                // outside automatically and its upstream sockets cannot loop.
                DebugLog.add("VPN", "VPN Only apps applied=" + applied);
            } else {
                // The app process owns the upstream sockets. Excluding our package keeps
                // those sockets outside the VPN and prevents a routing loop.
                builder.addDisallowedApplication(getPackageName());
            }
            if (splitEnabled && !"VPN_ONLY".equals(splitMode)) {
                Set<String> packages = prefs.getStringSet("split_apps", Collections.emptySet());
                int applied = 0;
                for (String packageName : packages) {
                    if (getPackageName().equals(packageName)) continue;
                    try {
                        builder.addDisallowedApplication(packageName);
                        applied++;
                    } catch (PackageManager.NameNotFoundException ignored) {
                        DebugLog.add("VPN", "Skipped an app that is no longer installed");
                    }
                }
                DebugLog.add("VPN", "Split tunneling bypass apps applied=" + applied);
            }
            tunnel = builder.establish();
            if (tunnel == null) throw new IOException("Android did not create the VPN interface");
            DebugLog.add("VPN", "Android VPN interface created; TUN fd=" + tunnel.getFd());

            NativeCore.startTun2proxy(tunnel.getFd(), new NativeCore.Listener() {
                @Override public void onProgress(String message) {
                    if (stopping) return;
                    if (connected) return;
                    ConnectionStateStore.update(CallistoVpnService.this,
                            ConnectionStateStore.Status.CONNECTING, ConnectionMode.VPN, message);
                }

                @Override public void onReady(String route) {
                    if (stopping) return;
                    connected = true;
                    ConnectionStateStore.update(CallistoVpnService.this,
                            ConnectionStateStore.Status.CONNECTED, ConnectionMode.VPN, "Connected");
                    startForeground(ServiceSupport.NOTIFICATION_ID,
                            ServiceSupport.notification(CallistoVpnService.this, "Connected",
                                    CallistoVpnService.class, ACTION_STOP));
                    DebugLog.add("VPN", "VPN is connected; Callisto is excluded from its own tunnel");
                }

                @Override public void onError(String message) {
                    fail(message);
                }
            });
        } catch (Exception error) {
            fail(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    @Override
    public void onRevoke() {
        DebugLog.add("VPN", "VPN permission revoked by Android");
        ConnectionStateStore.setDesiredActive(this, false);
        stopTunnel(true);
    }

    @Override
    public void onDestroy() {
        if (!cleanupRequested) {
            NativeCore.stop();
            closeTunnelAndForeground();
            ConnectionStateStore.Snapshot state = ConnectionStateStore.get(this);
            if (state.isActive()) {
                ConnectionStateStore.update(this, ConnectionStateStore.Status.DISCONNECTED,
                        ConnectionMode.VPN, "Disconnected");
            }
        }
        super.onDestroy();
    }

    private void fail(String message) {
        if (stopping) return;
        stopping = true;
        ConnectionStateStore.setDesiredActive(this, false);
        DebugLog.add("VPN", "Startup failed: " + message);
        ConnectionStateStore.update(this, ConnectionStateStore.Status.ERROR,
                ConnectionMode.VPN, message);
        requestCleanup(false);
    }

    private void stopTunnel(boolean userRequested) {
        if (stopping) return;
        stopping = true;
        ConnectionStateStore.update(this, ConnectionStateStore.Status.DISCONNECTING,
                ConnectionMode.VPN, "Disconnecting");
        requestCleanup(userRequested);
    }

    private void requestCleanup(boolean userRequested) {
        if (cleanupRequested) return;
        cleanupRequested = true;
        // Remove the active VPN and its "Connected" notification immediately.
        // Native route teardown may take a few seconds and must not leave stale
        // connection status visible to the user during that work.
        closeTunnelAndForeground();
        NativeCore.stopAsync(() -> {
            if (userRequested) {
                ConnectionStateStore.update(CallistoVpnService.this,
                        ConnectionStateStore.Status.DISCONNECTED, ConnectionMode.VPN, "Disconnected");
            }
            DebugLog.add("VPN", "VPN resources were released cleanly");
            stopSelf();
            restartTunnelProcess();
        });
    }

    private void restartTunnelProcess() {
        DebugLog.flush();
        main.postDelayed(() ->
                android.os.Process.killProcess(android.os.Process.myPid()), 350L);
    }

    private void closeTunnelAndForeground() {
        if (tunnel != null) {
            try {
                tunnel.close();
            } catch (IOException ignored) {}
            tunnel = null;
        }
        stopForeground(true);
        ServiceSupport.cancelNotification(this);
    }
}
