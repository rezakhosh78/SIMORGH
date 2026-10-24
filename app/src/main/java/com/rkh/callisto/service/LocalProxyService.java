package com.rkh.callisto.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import com.rkh.callisto.core.NativeCore;
import com.rkh.callisto.log.DebugLog;
import com.rkh.callisto.model.ConnectionMode;
import com.rkh.callisto.model.ConnectionStateStore;

public final class LocalProxyService extends Service {
    public static final String EXTRA_EXIT_COUNTRY = "exit_country";
    public static final String EXTRA_PSIPHON_ENABLED = "psiphon_enabled";
    public static final String ACTION_STOP = "com.rkh.callisto.STOP_PROXY";
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
            stopProxy(true);
            return START_NOT_STICKY;
        }
        if (intent == null && !ConnectionStateStore.isDesiredActive(this)) {
            DebugLog.add("PROXY", "Ignored Android restart after user disconnect");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (started && !stopping) {
            DebugLog.add("PROXY", "Ignoring duplicate proxy start request");
            return START_STICKY;
        }
        started = true;
        ConnectionStateStore.setDesiredActive(this, true);
        startForeground(ServiceSupport.NOTIFICATION_ID,
                ServiceSupport.notification(this, "Connecting", LocalProxyService.class, ACTION_STOP));

        stopping = false;
        connected = false;
        ConnectionStateStore.update(this, ConnectionStateStore.Status.CONNECTING,
                ConnectionMode.PROXY, "Preparing local proxy");
        DebugLog.add("PROXY", "Starting local proxy");
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
        final boolean effectivePsiphonEnabled = psiphonEnabled;
        NativeCore.startRoute(this, exitCountry, "SNOWFLAKE", effectivePsiphonEnabled,
                true, new NativeCore.Listener() {
            @Override public void onProgress(String message) {
                if (stopping) return;
                if (connected) return;
                if (isUsableProxySignal(message)) {
                    connected = true;
                    ConnectionStateStore.update(LocalProxyService.this,
                            ConnectionStateStore.Status.CONNECTED, ConnectionMode.PROXY, "Connected");
                    startForeground(ServiceSupport.NOTIFICATION_ID,
                            ServiceSupport.notification(LocalProxyService.this, "Proxy connected",
                                    LocalProxyService.class, ACTION_STOP));
                    return;
                }
                ConnectionStateStore.update(LocalProxyService.this,
                        ConnectionStateStore.Status.CONNECTING, ConnectionMode.PROXY, message);
            }

            @Override public void onReady(String route) {
                if (stopping) return;
                connected = true;
                ConnectionStateStore.update(LocalProxyService.this,
                        ConnectionStateStore.Status.CONNECTED, ConnectionMode.PROXY, "Connected");
                DebugLog.add("PROXY", effectivePsiphonEnabled
                        ? "SOCKS5 127.0.0.1:1819 and HTTP CONNECT 127.0.0.1:1920 are ready"
                        : "SOCKS5 127.0.0.1:" + NativeCore.routeSocksPort() + " is ready");
                startForeground(ServiceSupport.NOTIFICATION_ID,
                        ServiceSupport.notification(LocalProxyService.this, "Proxy connected",
                                LocalProxyService.class, ACTION_STOP));
            }

            @Override public void onError(String message) {
                fail(message);
            }
        });
        return START_STICKY;
    }

    private boolean isUsableProxySignal(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.US);
        return lower.contains("proxy port is ready")
                || lower.contains("traffic can flow");
    }

    private void fail(String message) {
        if (stopping) return;
        stopping = true;
        ConnectionStateStore.setDesiredActive(this, false);
        DebugLog.add("PROXY", "Startup failed: " + message);
        ConnectionStateStore.update(this, ConnectionStateStore.Status.ERROR,
                ConnectionMode.PROXY, message);
        requestCleanup(false);
    }

    private void stopProxy(boolean userRequested) {
        if (stopping) return;
        stopping = true;
        ConnectionStateStore.update(this, ConnectionStateStore.Status.DISCONNECTING,
                ConnectionMode.PROXY, "Disconnecting");
        requestCleanup(userRequested);
    }

    private void requestCleanup(boolean userRequested) {
        if (cleanupRequested) return;
        cleanupRequested = true;
        stopForeground(true);
        ServiceSupport.cancelNotification(this);
        NativeCore.stopAsync(() -> {
            if (userRequested) {
                ConnectionStateStore.update(LocalProxyService.this,
                        ConnectionStateStore.Status.DISCONNECTED, ConnectionMode.PROXY, "Disconnected");
            }
            DebugLog.add("PROXY", "Proxy resources were released cleanly");
            stopSelf();
            restartTunnelProcess();
        });
    }

    private void restartTunnelProcess() {
        DebugLog.flush();
        main.postDelayed(() ->
                android.os.Process.killProcess(android.os.Process.myPid()), 350L);
    }

    @Override
    public void onDestroy() {
        if (!cleanupRequested) {
            NativeCore.stop();
            stopForeground(true);
            ServiceSupport.cancelNotification(this);
            ConnectionStateStore.Snapshot state = ConnectionStateStore.get(this);
            if (state.isActive()) {
                ConnectionStateStore.update(this, ConnectionStateStore.Status.DISCONNECTED,
                        ConnectionMode.PROXY, "Disconnected");
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
