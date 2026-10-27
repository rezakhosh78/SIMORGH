package com.rkh.callisto.core;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.rkh.callisto.log.DebugLog;
import com.rkh.callisto.service.SnowflakeTransportService;

import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import net.freehaven.tor.control.RawEventListener;
import net.freehaven.tor.control.TorControlConnection;

final class SnowflakeEngine {
    static final int SOCKS_PORT = 2020;
    static final int HTTP_PORT = 2021;
    private static final int CONNECT_REFUSED_RECOVERY_THRESHOLD = 4;
    private static final int DATA_CHANNEL_RECOVERY_THRESHOLD = 5;
    private static final int LOCAL_ROUTE_PROBE_ROUNDS = 2;
    private static final int LOCAL_ROUTE_PROBE_SECONDS = 4;
    private static final long LOCAL_ROUTE_PROBE_RETRY_DELAY_MS = 750L;
    private TorService torService;
    private ServiceConnection connection;
    private BroadcastReceiver statusReceiver;
    private RawEventListener controlLogListener;
    private Context appContext;
    private volatile boolean stopping;
    private Messenger transportService;
    private Messenger transportClient;
    private ServiceConnection transportConnection;
    private Context transportContext;
    private volatile boolean transportBound;
    private volatile boolean transportConnected;
    private volatile CountDownLatch transportReply;
    private volatile CountDownLatch transportShutdownReply;
    private final AtomicReference<String> transportAddress = new AtomicReference<>();
    private final AtomicReference<Exception> transportStartError = new AtomicReference<>();
    private volatile StartupAttempt activeStartup;
    private volatile BridgeDirectory.BridgePlan activePlan;
    private final AtomicInteger consecutiveDataChannelTimeouts = new AtomicInteger();
    private CountDownLatch serviceOff;

    int start(Context context, BridgeDirectory.BridgePlan plan, boolean allowEarlyProxyReady,
              NativeCore.Listener listener) throws Exception {
        appContext = context.getApplicationContext();
        activePlan = plan;
        stopping = false;
        consecutiveDataChannelTimeouts.set(0);
        String speedProfile = connectionSpeedProfile(appContext);
        DebugLog.add("CORE", "Connection speed profile=" + speedProfile);
        StartupAttempt startup = new StartupAttempt(
                listener, allowEarlyProxyReady, plan, speedProfile);
        activeStartup = startup;
        try {
            String isolatedTransportAddress =
                    ensureTransportService(false, plan, speedProfile, listener);
            writeTorrc(appContext, plan, isolatedTransportAddress, speedProfile);
            CountDownLatch bound = new CountDownLatch(1);
            serviceOff = new CountDownLatch(1);
            registerStatusReceiver(startup);
            connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    torService = ((TorService.LocalBinder) binder).getService();
                    startup.recordProgress("Core service bound");
                    bound.countDown();
                    attachControlLogger(startup);
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    DebugLog.add("CORE", "Core service disconnected");
                    torService = null;
                }
            };
            Intent intent = new Intent(appContext, TorService.class)
                    .setAction(TorService.ACTION_START)
                    .putExtra(TorService.EXTRA_PACKAGE_NAME, appContext.getPackageName());
            appContext.startService(intent);
            if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                throw new Exception("Could not bind core service");
            }
            if (!bound.await(15, TimeUnit.SECONDS)) {
                throw new Exception("Core service bind timed out");
            }

            DebugLog.add("CORE", "Building route with " + plan.displayName()
                    + "; adaptive startup window is active");
            listener.onProgress("Trying " + plan.displayName() + " bridges");
            if (!startup.awaitSignal()) {
                throw new RecoverableRouteException("Secure route startup timed out");
            }
            Exception startupError = startup.error.get();
            if (startupError != null) throw startupError;
            if (startup.earlyProxyReady.get()) {
                DebugLog.add("CORE", "Local proxy listener is ready; circuits will build on demand");
                return SOCKS_PORT;
            }

            DebugLog.add("CORE", "Public route circuit is ready; verifying local traffic");
            listener.onProgress("Public circuit built; verifying traffic");
            if (!verifyLocalRoute(listener)) {
                throw new RecoverableRouteException(
                        "Local route did not accept traffic after repeated checks");
            }
            DebugLog.add("CORE", "Secure route is ready on 127.0.0.1:" + SOCKS_PORT);
            return SOCKS_PORT;
        } finally {
            if (activeStartup == startup) activeStartup = null;
        }
    }

    private String ensureTransportService(boolean restart, BridgeDirectory.BridgePlan plan,
                                          String speedProfile,
                                          NativeCore.Listener listener) throws Exception {
        ensureTransportClient();
        if (transportService == null || !transportBound) {
            CountDownLatch bound = new CountDownLatch(1);
            transportConnection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    transportService = new Messenger(binder);
                    transportBound = true;
                    bound.countDown();
                }

                @Override public void onServiceDisconnected(ComponentName name) {
                    transportBound = false;
                    transportService = null;
                    transportConnected = false;
                    CountDownLatch shutdown = transportShutdownReply;
                    if (shutdown != null) shutdown.countDown();
                    StartupAttempt startup = activeStartup;
                    if (!stopping && startup != null) {
                        startup.failRecoverable("Isolated transport process disconnected");
                    }
                }
            };
            Context bindingContext = appContext != null ? appContext : transportContext;
            if (bindingContext == null) {
                throw new Exception("No application context for isolated transport");
            }
            transportContext = bindingContext.getApplicationContext();
            Intent intent = new Intent(transportContext, SnowflakeTransportService.class);
            if (!transportContext.bindService(intent, transportConnection,
                    Context.BIND_AUTO_CREATE)) {
                throw new Exception("Could not bind isolated transport service");
            }
            if (!bound.await(15, TimeUnit.SECONDS)) {
                throw new Exception("Isolated transport service bind timed out");
            }
        }

        transportAddress.set(null);
        transportStartError.set(null);
        CountDownLatch reply = new CountDownLatch(1);
        transportReply = reply;
        Message command = Message.obtain(null, restart
                ? SnowflakeTransportService.COMMAND_RESTART
                : SnowflakeTransportService.COMMAND_START);
        Bundle commandData = new Bundle();
        commandData.putString(SnowflakeTransportService.KEY_TRANSPORT, plan.transport);
        commandData.putString(SnowflakeTransportService.KEY_SPEED_PROFILE, speedProfile);
        command.setData(commandData);
        command.replyTo = transportClient;
        try {
            transportService.send(command);
        } catch (RemoteException error) {
            transportBound = false;
            transportService = null;
            throw new Exception("Could not start isolated transport", error);
        }
        listener.onProgress(restart ? "Refreshing " + plan.displayName()
                : "Starting " + plan.displayName());
        if (!reply.await(30, TimeUnit.SECONDS)) {
            throw new Exception("Isolated transport startup timed out");
        }
        Exception error = transportStartError.get();
        if (error != null) throw error;
        String address = transportAddress.get();
        if (address == null || address.trim().isEmpty()) {
            throw new Exception("Isolated transport returned no SOCKS listener");
        }
        int listenerPort = portFromAddress(address);
        if (listenerPort <= 0 || !ProxyProbe.waitUntilListening(listenerPort, 3_000)) {
            throw new RecoverableRouteException(
                    "Pluggable transport listener was not ready");
        }
        if (transportConnected) {
            StartupAttempt startup = activeStartup;
            if (startup != null) startup.markTransportConnected();
        }
        DebugLog.add("CORE", plan.displayName()
                + " discovery started in isolated process"
                + ("snowflake".equals(plan.transport)
                ? "; peer capacity=" + peersForProfile(speedProfile) : ""));
        DebugLog.add("CORE", "Local transport is listening on " + address);
        listener.onProgress("Transport listener is ready");
        return address;
    }

    private void ensureTransportClient() {
        if (transportClient != null) return;
        transportClient = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) {
                Bundle data = message.getData();
                switch (message.what) {
                    case SnowflakeTransportService.EVENT_READY:
                        transportAddress.set(data.getString(
                                SnowflakeTransportService.KEY_ADDRESS));
                        CountDownLatch ready = transportReply;
                        if (ready != null) ready.countDown();
                        break;
                    case SnowflakeTransportService.EVENT_CONNECTED:
                        transportConnected = true;
                        consecutiveDataChannelTimeouts.set(0);
                        DebugLog.add("CORE", "Transport connected");
                        StartupAttempt startup = activeStartup;
                        if (startup != null) startup.markTransportConnected();
                        break;
                    case SnowflakeTransportService.EVENT_ERROR:
                        handleTransportError(data.getString(
                                SnowflakeTransportService.KEY_ERROR));
                        break;
                    case SnowflakeTransportService.EVENT_STOPPED:
                        transportConnected = false;
                        CountDownLatch shutdown = transportShutdownReply;
                        if (shutdown != null) shutdown.countDown();
                        String stoppedMessage = data.getString(
                                SnowflakeTransportService.KEY_ERROR);
                        DebugLog.add("CORE", "Transport stopped: " + stoppedMessage);
                        StartupAttempt current = activeStartup;
                        if (!stopping && current != null) {
                            current.failRecoverable(
                                    "Pluggable transport stopped during startup");
                        }
                        break;
                    default:
                        super.handleMessage(message);
                }
            }
        });
    }

    private void handleTransportError(String message) {
        String safeMessage = message == null ? "Unknown transport error" : message;
        DebugLog.add("CORE", "Transport error: " + safeMessage);
        if (transportAddress.get() == null) {
            transportStartError.compareAndSet(null,
                    isStaleGoReference(safeMessage)
                            ? new RecoverableRouteException(
                            "Transport runtime reference table was reset")
                            : new Exception(safeMessage));
            CountDownLatch ready = transportReply;
            if (ready != null) ready.countDown();
        }
        if (isStaleGoReference(safeMessage)) {
            StartupAttempt startup = activeStartup;
            if (startup != null && startup.failRecoverable(
                    "Transport runtime reference table was reset")) {
                DebugLog.add("CORE", "Stale Go reference detected; a fresh process is required");
            }
        }
        BridgeDirectory.BridgePlan currentPlan = activePlan;
        if (currentPlan != null && "snowflake".equals(currentPlan.transport)
                && safeMessage.toLowerCase(java.util.Locale.US)
                .contains("timeout waiting for datachannel.onopen")) {
            int failures = consecutiveDataChannelTimeouts.incrementAndGet();
            StartupAttempt startup = activeStartup;
            if (startup != null && failures >= DATA_CHANNEL_RECOVERY_THRESHOLD) {
                startup.failRecoverable(
                        "Snowflake DataChannel repeatedly failed to open");
                DebugLog.add("CORE",
                        "Repeated DataChannel.OnOpen timeouts; automatic recovery requested");
            }
        }
    }

    private void registerStatusReceiver(StartupAttempt startup) {
        statusReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (TorService.ACTION_ERROR.equals(intent.getAction())) {
                    String message = intent.getStringExtra(Intent.EXTRA_TEXT);
                    Exception error = new Exception(
                            message == null ? "Core startup failed" : message);
                    startup.fail(error);
                    DebugLog.add("CORE", "Startup error: " + safe(error));
                    return;
                }
                String status = intent.getStringExtra(TorService.EXTRA_STATUS);
                if (status == null) return;
                DebugLog.add("CORE", "Service status: " + status);
                // STATUS_ON means the Tor process is alive, not that a public
                // three-hop circuit can already carry application traffic.
                if (TorService.STATUS_ON.equals(status)) {
                    startup.recordProgress("Core service is running; waiting for public circuit");
                }
                if (TorService.STATUS_OFF.equals(status) && serviceOff != null) serviceOff.countDown();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(TorService.ACTION_STATUS);
        filter.addAction(TorService.ACTION_ERROR);
        LocalBroadcastManager.getInstance(appContext).registerReceiver(statusReceiver, filter);
    }

    private void attachControlLogger(StartupAttempt startup) {
        Thread logger = new Thread(() -> {
            AtomicInteger consecutiveRefusals = new AtomicInteger();
            for (int attempt = 0; attempt < 120 && !stopping; attempt++) {
                try {
                    TorService service = torService;
                    TorControlConnection control = service == null ? null : service.getTorControlConnection();
                    if (control == null) {
                        Thread.sleep(100L);
                        continue;
                    }
                    controlLogListener = (keyword, data) -> {
                        if (data == null || data.trim().isEmpty()) return;
                        DebugLog.add("CORE", keyword + ": " + data);
                        // tor-android 0.4.9.5 does not reliably broadcast STATUS_ON.
                        // Its control connection does reliably emit CIRCUIT_ESTABLISHED
                        // (and bootstrap 100), which is the actual readiness signal seen
                        // in device logs. Accept either so VPN creation is not left waiting
                        // after the route is already usable.
                        String normalized = data.toUpperCase(java.util.Locale.US);
                        if ("CIRC".equalsIgnoreCase(keyword)
                                && (normalized.contains(" LAUNCHED ")
                                || normalized.contains(" EXTENDED ")
                                || normalized.contains(" BUILT "))) {
                            startup.recordProgress("Circuit progress detected");
                        }
                        if ("STATUS_CLIENT".equalsIgnoreCase(keyword)
                                && normalized.contains("PROGRESS=1")
                                && normalized.contains("TAG=CONN_PT")
                                && normalized.contains("REASON=CONNECTREFUSED")) {
                            int refused = consecutiveRefusals.incrementAndGet();
                            if (refused >= CONNECT_REFUSED_RECOVERY_THRESHOLD
                                    && startup.failRecoverable(
                                    "Pluggable transport repeatedly refused the route")) {
                                DebugLog.add("CORE", "Unusable route detected; automatic recovery requested");
                            }
                        }
                        if (normalized.contains("CIRCUIT_ESTABLISHED")
                                || (normalized.contains("BOOTSTRAP")
                                && normalized.contains("PROGRESS=100"))) {
                            startup.markReady();
                        }
                        if ("CIRC".equalsIgnoreCase(keyword)
                                && normalized.contains(" BUILT ")
                                && normalized.contains("PURPOSE=GENERAL")
                                && !normalized.contains("ONEHOP_TUNNEL")
                                && !normalized.contains("IS_INTERNAL")) {
                            startup.markReady();
                        }
                    };
                    control.addRawEventListener(controlLogListener);
                    control.setEvents(Arrays.asList(
                            "STATUS_CLIENT", "CIRC", "NOTICE", "WARN", "ERR"));
                    DebugLog.add("CORE", "Detailed core event logging enabled");
                    return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception error) {
                    DebugLog.add("CORE", "Could not attach detailed logging: " + safe(error));
                    return;
                }
            }
        }, "callisto-core-events");
        logger.setDaemon(true);
        logger.start();
    }

    private boolean verifyLocalRoute(NativeCore.Listener listener) throws InterruptedException {
        for (int round = 1; round <= LOCAL_ROUTE_PROBE_ROUNDS; round++) {
            listener.onProgress("Verifying local traffic " + round + "/"
                    + LOCAL_ROUTE_PROBE_ROUNDS);
            if (ProxyProbe.waitForSocks(
                    "cloudflare.com", SOCKS_PORT, LOCAL_ROUTE_PROBE_SECONDS)) {
                return true;
            }
            if (round < LOCAL_ROUTE_PROBE_ROUNDS) {
                DebugLog.add("CORE", "Local route probe " + round + "/"
                        + LOCAL_ROUTE_PROBE_ROUNDS
                        + " did not pass yet; keeping the core alive and retrying");
                Thread.sleep(LOCAL_ROUTE_PROBE_RETRY_DELAY_MS);
            }
        }
        return false;
    }

    void resetTransportForRecovery() {
        DebugLog.add("CORE", "A fresh isolated transport process will be used for recovery");
        transportConnected = false;
        consecutiveDataChannelTimeouts.set(0);
    }

    static boolean isRecoverable(Throwable error) {
        return error instanceof RecoverableRouteException;
    }

    private void writeTorrc(Context context, BridgeDirectory.BridgePlan plan,
                            String transportAddress, String speedProfile) throws Exception {
        DebugLog.add("CORE", "Relay selection is automatic; country is handled by Psiphon");
        StringBuilder content = new StringBuilder()
                .append("UseBridges 1\n")
                .append("ClientTransportPlugin ").append(plan.transport)
                .append(" socks5 ").append(transportAddress).append('\n');
        for (String bridge : plan.bridges) {
            content.append("Bridge ").append(bridge).append('\n');
        }
        content.append("SocksPort ").append(SOCKS_PORT).append('\n')
                .append("HTTPTunnelPort 127.0.0.1:").append(HTTP_PORT).append('\n')
                .append("ClientOnly 1\n")
                // Allow more candidate circuits and begin directory fetching early.
                // The values are intentionally capped for Android 7/ARMv7 devices.
                .append("MaxClientCircuitsPending ")
                .append(pendingCircuitsForProfile(speedProfile)).append('\n')
                .append("FetchDirInfoEarly 1\n")
                .append("FetchDirInfoExtraEarly 1\n")
                .append("AvoidDiskWrites 1\n")
                .append("UseMicrodescriptors 1\n")
                .append("SafeLogging 1\n")
                .append("Log notice syslog\n");
        File torrc = TorService.getTorrc(context);
        File parent = torrc.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new Exception("Cannot create core state directory");
        }
        try (FileOutputStream output = new FileOutputStream(torrc, false)) {
            output.write(content.toString().getBytes(StandardCharsets.UTF_8));
        }
        DebugLog.add("BRIDGE", "Core configured with " + plan.bridges.size() + " "
                + plan.displayName() + " bridge"
                + (plan.bridges.size() == 1 ? "" : "s")
                + " from " + plan.source);
    }

    void stop() {
        stopping = true;
        TorControlConnection control = torService == null
                ? null : torService.getTorControlConnection();
        if (control != null && controlLogListener != null) {
            try { control.removeRawEventListener(controlLogListener); }
            catch (Exception ignored) {}
        }
        boolean gracefulShutdown = false;
        if (control != null) {
            try {
                // TorService.ACTION_STOP is not handled by tor-android 0.4.9.5.
                // Ask the running core to exit through its real control channel so
                // native cleanup finishes before Android destroys the service.
                control.shutdownTor("SHUTDOWN");
                gracefulShutdown = true;
                DebugLog.add("CORE", "Graceful core shutdown requested");
            } catch (Exception error) {
                DebugLog.add("CORE", "Graceful core shutdown was unavailable: " + safe(error));
            }
        }
        CountDownLatch stopped = serviceOff;
        boolean confirmedOff = false;
        if (stopped != null) {
            try { confirmedOff = stopped.await(gracefulShutdown ? 2 : 1, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        if (appContext != null && connection != null) {
            try { appContext.unbindService(connection); } catch (Exception ignored) {}
        }
        if (appContext != null && !confirmedOff) {
            DebugLog.add("CORE", "Core shutdown timed out; using Android service fallback");
            try { appContext.stopService(new Intent(appContext, TorService.class)); } catch (Exception ignored) {}
        }
        if (appContext != null && statusReceiver != null) {
            try { LocalBroadcastManager.getInstance(appContext).unregisterReceiver(statusReceiver); }
            catch (Exception ignored) {}
        }
        try { ProxyProbe.waitUntilClosed(SOCKS_PORT, 1); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        shutdownTransportProcess();
        connection = null;
        statusReceiver = null;
        controlLogListener = null;
        torService = null;
        serviceOff = null;
        appContext = null;
        activePlan = null;
    }

    private void shutdownTransportProcess() {
        Context context = transportContext != null ? transportContext : appContext;
        ServiceConnection isolatedConnection = transportConnection;
        Messenger isolatedService = transportService;
        CountDownLatch shutdown = new CountDownLatch(1);
        transportShutdownReply = shutdown;
        if (isolatedService != null) {
            Message command = Message.obtain(null, SnowflakeTransportService.COMMAND_SHUTDOWN);
            command.replyTo = transportClient;
            try {
                isolatedService.send(command);
                shutdown.await(2, TimeUnit.SECONDS);
                Thread.sleep(250L);
            } catch (RemoteException ignored) {
                // Binder death means the dedicated process has already exited.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        if (context != null && isolatedConnection != null) {
            try { context.unbindService(isolatedConnection); } catch (Exception ignored) {}
        }
        if (context != null) {
            try {
                context.stopService(new Intent(context, SnowflakeTransportService.class));
            } catch (Exception ignored) {}
        }
        transportService = null;
        transportConnection = null;
        transportBound = false;
        transportConnected = false;
        transportAddress.set(null);
        transportStartError.set(null);
        transportReply = null;
        transportShutdownReply = null;
        DebugLog.add("CORE", "Isolated transport process was reset for the next connection");
    }

    private static String safe(Exception error) {
        return error == null ? "none" : String.valueOf(error.getMessage());
    }

    private static String connectionSpeedProfile(Context context) {
        String value = context.getSharedPreferences(
                "callisto_preferences", Context.MODE_PRIVATE)
                .getString("connection_speed", "FAST");
        if (value == null) return "FAST";
        String normalized = value.trim().toUpperCase(java.util.Locale.US);
        if ("BALANCED".equals(normalized) || "STABLE".equals(normalized)) {
            return normalized;
        }
        return "FAST";
    }

    private static long peersForProfile(String profile) {
        if ("STABLE".equals(profile)) return 8L;
        if ("BALANCED".equals(profile)) return 16L;
        return 32L;
    }

    private static int pendingCircuitsForProfile(String profile) {
        if ("STABLE".equals(profile)) return 24;
        if ("BALANCED".equals(profile)) return 40;
        return 64;
    }

    private static int portFromAddress(String address) {
        if (address == null) return -1;
        int separator = address.lastIndexOf(':');
        if (separator < 0 || separator + 1 >= address.length()) return -1;
        try {
            return Integer.parseInt(address.substring(separator + 1).trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean isStaleGoReference(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(java.util.Locale.US);
        return normalized.contains("trackgoref")
                || normalized.contains("java refnum")
                || normalized.contains("referenced java object is not found");
    }

    private static final class RecoverableRouteException extends Exception {
        RecoverableRouteException(String message) {
            super(message);
        }
    }

    private static final class StartupAttempt {
        private final long startedAt;
        private final long hardDeadline;
        private final AtomicLong deadline;
        private final CountDownLatch signal = new CountDownLatch(1);
        private final AtomicReference<Exception> error = new AtomicReference<>();
        private final NativeCore.Listener listener;
        private final boolean allowEarlyProxyReady;
        private final long progressExtensionMs;
        private final java.util.concurrent.atomic.AtomicBoolean transportConnected =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicBoolean earlyProxyReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        StartupAttempt(NativeCore.Listener listener, boolean allowEarlyProxyReady,
                       BridgeDirectory.BridgePlan plan, String speedProfile) {
            this.listener = listener;
            this.allowEarlyProxyReady = allowEarlyProxyReady;
            this.startedAt = System.currentTimeMillis();
            long initialTimeout;
            long maximumTimeout;
            if ("webtunnel".equals(plan.transport)) {
                initialTimeout = 45_000L;
                maximumTimeout = 90_000L;
            } else if ("obfs4".equals(plan.transport)) {
                initialTimeout = 60_000L;
                maximumTimeout = 105_000L;
            } else {
                if ("STABLE".equals(speedProfile)) {
                    initialTimeout = 240_000L;
                    maximumTimeout = 420_000L;
                } else if ("BALANCED".equals(speedProfile)) {
                    initialTimeout = 180_000L;
                    maximumTimeout = 300_000L;
                } else {
                    initialTimeout = 75_000L;
                    maximumTimeout = 150_000L;
                }
            }
            this.progressExtensionMs = "STABLE".equals(speedProfile)
                    ? 60_000L : ("BALANCED".equals(speedProfile) ? 45_000L : 20_000L);
            this.hardDeadline = startedAt + maximumTimeout;
            this.deadline = new AtomicLong(startedAt + initialTimeout);
        }

        void recordProgress() {
            recordProgress(null);
        }

        void recordProgress(String message) {
            long candidate = Math.min(
                    hardDeadline,
                    System.currentTimeMillis() + progressExtensionMs);
            long current;
            do {
                current = deadline.get();
                if (candidate <= current) {
                    publish(message);
                    return;
                }
            } while (!deadline.compareAndSet(current, candidate));
            publish(message);
        }

        private void publish(String message) {
            if (message != null && !message.isEmpty() && listener != null) {
                listener.onProgress(message);
            }
        }

        void markReady() {
            signal.countDown();
        }

        void markTransportConnected() {
            transportConnected.set(true);
            recordProgress("Transport connected; building route");
            maybeMarkEarlyProxyReady();
        }

        void fail(Exception failure) {
            if (error.compareAndSet(null, failure)) signal.countDown();
        }

        boolean failRecoverable(String message) {
            RecoverableRouteException failure = new RecoverableRouteException(message);
            if (!error.compareAndSet(null, failure)) return false;
            signal.countDown();
            return true;
        }

        boolean awaitSignal() throws InterruptedException {
            while (true) {
                maybeMarkEarlyProxyReady();
                long remaining = Math.min(deadline.get(), hardDeadline)
                        - System.currentTimeMillis();
                if (remaining <= 0) return false;
                if (signal.await(Math.min(remaining, 1_000L), TimeUnit.MILLISECONDS)) {
                    return true;
                }
            }
        }

        private void maybeMarkEarlyProxyReady() {
            if (!allowEarlyProxyReady || !transportConnected.get() || earlyProxyReady.get()) return;
            if (ProxyProbe.isListening(SOCKS_PORT)) {
                earlyProxyReady.set(true);
                publish("Proxy port is ready; traffic can flow");
                signal.countDown();
            }
        }
    }

}
