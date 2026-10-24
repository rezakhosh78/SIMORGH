package com.rkh.callisto.core;

import android.content.Context;

import com.github.shadowsocks.bg.Tun2proxy;
import com.rkh.callisto.log.DebugLog;
import ca.psiphon.PsiphonTunnel;
import psi.Psi;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

/** Coordinates adaptive Tor bridges, optional Psiphon, and the tun2proxy data plane. */
public final class NativeCore {
    public interface Listener {
        default void onProgress(String message) {}
        void onReady(String route);
        void onError(String message);
    }

    public interface StopListener {
        void onStopped();
    }

    private static final ExecutorService LIFECYCLE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "callisto-core");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicLong GENERATION = new AtomicLong(0);
    private static final SnowflakeEngine SNOWFLAKE = new SnowflakeEngine();
    private static final PsiphonCore PSIPHON = new PsiphonCore();
    private static final int MAX_ROUTE_ATTEMPTS = 3;
    private static volatile Future<?> activeStart;
    private static volatile Thread tunnelThread;
    private static volatile int activeRouteSocksPort = SnowflakeEngine.SOCKS_PORT;

    private NativeCore() {}

    public static boolean isAvailable(Context context) {
        return isAvailable(context, false);
    }

    public static boolean isAvailable(Context context, boolean requirePsiphon) {
        try {
            boolean torAvailable = Tun2proxy.isAvailable()
                    && typeAvailable(IPtProxy.Controller.class)
                    && typeAvailable(org.torproject.jni.TorService.class);
            if (!torAvailable) {
                DebugLog.add("CORE", "Component check: base connection engine unavailable");
                return false;
            }
            if (requirePsiphon && (!typeAvailable(PsiphonTunnel.class)
                    || !typeAvailable(Psi.class))) {
                DebugLog.add("CORE", "Component check: Psiphon bindings unavailable");
                return false;
            }
            return true;
        } catch (Throwable error) {
            DebugLog.add("CORE", "Component check failed: " + describe(error));
            return false;
        }
    }

    public static String version(Context context) {
        if (!Tun2proxy.isAvailable()) return "tun2proxy missing";
        if (!typeAvailable(IPtProxy.Controller.class)
                || !typeAvailable(org.torproject.jni.TorService.class)) {
            return "Snowflake engine missing";
        }
        if (!typeAvailable(PsiphonTunnel.class) || !typeAvailable(Psi.class)) {
            return "Psiphon engine missing";
        }
        return "0.7.11 / Snowflake -> optional Psiphon -> tun2proxy";
    }

    /** Starts the upstream route. Android's VPN is established only after this reports ready. */
    public static synchronized void startRoute(Context context, String exitCountry, Listener listener) {
        startRoute(context, exitCountry, "SNOWFLAKE", false, false, listener);
    }

    public static String normalizedTransportMode(String value) {
        if (value == null) return "SNOWFLAKE";
        String normalized = value.trim().toUpperCase(java.util.Locale.US);
        if ("WEBTUNNEL".equals(normalized) || "OBFS4".equals(normalized)
                || "SNOWFLAKE".equals(normalized)) {
            return normalized;
        }
        return "SNOWFLAKE";
    }

    /**
     * Starts the upstream route. Proxy mode can become usable as soon as the
     * local listener and transport are alive; VPN mode must wait for a public
     * circuit before system traffic is routed into tun2proxy.
     */
    public static synchronized void startRoute(Context context, String exitCountry,
                                               boolean allowEarlyProxyReady,
                                               Listener listener) {
        startRoute(context, exitCountry, "SNOWFLAKE", false, allowEarlyProxyReady, listener);
    }

    public static synchronized void startRoute(Context context, String exitCountry,
                                               String requestedTransportMode,
                                               boolean usePsiphon,
                                               boolean allowEarlyProxyReady,
                                               Listener listener) {
        final String transportMode = normalizedTransportMode(requestedTransportMode);
        long generation = GENERATION.incrementAndGet();
        RUNNING.set(false);
        Future<?> previous = activeStart;
        if (previous != null) previous.cancel(true);
        stopPacketTunnel();
        activeStart = LIFECYCLE.submit(() -> {
            try {
                PSIPHON.stop();
                SNOWFLAKE.stop();
            } catch (Throwable error) {
                RUNNING.set(false);
                String message = "Core reset failed: " + describe(error);
                DebugLog.add("CORE", message);
                listener.onError(message);
                return;
            }
            if (generation != GENERATION.get()) return;
            RUNNING.set(true);
            activeRouteSocksPort = SnowflakeEngine.SOCKS_PORT;
            DebugLog.add("BRIDGE", "Using transport request delivered by VPN command: "
                    + BridgeDirectory.displayMode(transportMode));
            DebugLog.add("CORE", usePsiphon
                    ? "Optional Psiphon route is enabled"
                    : "Snowflake-only route is enabled; Psiphon will not be started");
            List<BridgeDirectory.BridgePlan> plans =
                    BridgeDirectory.connectionPlans(
                            context.getApplicationContext(), transportMode);
            boolean automaticTransport = "AUTO".equals(transportMode);
            boolean manualDirectoryTransport = "WEBTUNNEL".equals(transportMode)
                    || "OBFS4".equals(transportMode);
            if (plans.isEmpty() && manualDirectoryTransport) {
                try {
                    plans = bootstrapManualBridgeCache(
                            context.getApplicationContext(), transportMode,
                            usePsiphon, generation, listener);
                } catch (Throwable error) {
                    if (Thread.currentThread().isInterrupted() || !RUNNING.get()
                            || generation != GENERATION.get()) return;
                    RUNNING.set(false);
                    String name = BridgeDirectory.displayMode(transportMode);
                    String message = "Could not automatically fetch a valid " + name
                            + " bridge through the temporary Snowflake route: "
                            + describe(error);
                    DebugLog.add("CORE", message);
                    listener.onError(message);
                    return;
                }
            }
            if (plans.isEmpty()) {
                RUNNING.set(false);
                String name = BridgeDirectory.displayMode(transportMode);
                String message = "No valid " + name + " bridge is available";
                DebugLog.add("CORE", message);
                listener.onError(message);
                return;
            }
            int maximumAttempts = automaticTransport
                    ? Math.max(MAX_ROUTE_ATTEMPTS, plans.size())
                    : Math.max(plans.size(), connectionAttempts(context));
            DebugLog.add("CORE", "Route search attempts=" + maximumAttempts);
            for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
                BridgeDirectory.BridgePlan plan =
                        plans.get(Math.min(attempt - 1, plans.size() - 1));
                try {
                    listener.onProgress((automaticTransport ? "Automatic bridge " : "Selected bridge ")
                            + attempt + "/" + maximumAttempts + ": " + plan.displayName());
                    DebugLog.add("BRIDGE", "Trying " + plan.displayName()
                            + " candidate " + attempt + "/" + maximumAttempts);
                    int torPort = SNOWFLAKE.start(context.getApplicationContext(), plan,
                            allowEarlyProxyReady, new Listener() {
                                @Override public void onProgress(String message) {
                                    listener.onProgress(message);
                                }

                                @Override public void onReady(String route) {
                                    listener.onProgress(plan.displayName() + " route is ready");
                                }

                                @Override public void onError(String message) {
                                    listener.onError(message);
                                }
                            });
                    int finalSocksPort = torPort;
                    if (usePsiphon) {
                        listener.onProgress("Starting Psiphon through " + plan.displayName());
                        finalSocksPort = PSIPHON.start(
                                context.getApplicationContext(), torPort, exitCountry, listener);
                    }
                    if (RUNNING.get() && generation == GENERATION.get()) {
                        activeRouteSocksPort = finalSocksPort;
                        if (usePsiphon) {
                            BridgeDirectory.refreshThroughPsiphon(
                                    context.getApplicationContext());
                            listener.onReady(plan.displayName() + " -> Psiphon route");
                        } else {
                            BridgeDirectory.refreshThroughTor(
                                    context.getApplicationContext(), torPort);
                            listener.onReady(plan.displayName() + " route");
                        }
                    }
                    return;
                } catch (Throwable error) {
                    if (Thread.currentThread().isInterrupted() || !RUNNING.get()
                            || generation != GENERATION.get()) return;
                    PSIPHON.stop();
                    SNOWFLAKE.stop();
                    if (attempt < maximumAttempts) {
                        try {
                            DebugLog.add("BRIDGE", plan.displayName()
                                    + (automaticTransport
                                    ? " failed; moving to the next automatic candidate"
                                    : " failed; retrying the selected transport"));
                            listener.onProgress(plan.displayName()
                                    + " unavailable; trying another bridge");
                            SNOWFLAKE.resetTransportForRecovery();
                            Thread.sleep(retryDelayMillis(context, attempt));
                            continue;
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Throwable recoveryError) {
                            error = recoveryError;
                        }
                    }
                    RUNNING.set(false);
                    String message = describe(error);
                    DebugLog.add("CORE", "Core startup failed: " + message);
                    listener.onError(message);
                    return;
                }
            }
        });
    }

    private static List<BridgeDirectory.BridgePlan> bootstrapManualBridgeCache(
            Context context, String transportMode, boolean usePsiphon, long generation,
            Listener listener) throws Exception {
        String selectedName = BridgeDirectory.displayMode(transportMode);
        DebugLog.add("BRIDGE", "No cached " + selectedName
                + " bridge; starting temporary Snowflake bootstrap");
        listener.onProgress("Fetching " + selectedName
                + " bridges through a temporary Snowflake route");

        Listener bootstrapListener = new Listener() {
            @Override public void onProgress(String message) {
                if (RUNNING.get() && generation == GENERATION.get()) {
                    listener.onProgress("Bridge download: " + message);
                }
            }

            @Override public void onReady(String route) {
                // The temporary route must never be reported as the final VPN route.
            }

            @Override public void onError(String message) {
                DebugLog.add("BRIDGE", "Temporary bootstrap notice: " + message);
            }
        };

        try {
            BridgeDirectory.BridgePlan bootstrap =
                    BridgeDirectory.BridgePlan.snowflakeFallback();
            int torPort = SNOWFLAKE.start(context, bootstrap, false, bootstrapListener);
            if (!RUNNING.get() || generation != GENERATION.get()) {
                throw new InterruptedException("Bridge bootstrap cancelled");
            }
            listener.onProgress("Downloading fresh " + selectedName + " bridges");
            boolean refreshed;
            if (usePsiphon) {
                bootstrapListener.onProgress("Starting temporary Psiphon route");
                PSIPHON.start(context, torPort, "", bootstrapListener);
                if (!RUNNING.get() || generation != GENERATION.get()) {
                    throw new InterruptedException("Bridge bootstrap cancelled");
                }
                refreshed = BridgeDirectory.refreshThroughPsiphonBlocking(context);
            } else {
                refreshed = BridgeDirectory.refreshThroughTorBlocking(context, torPort);
            }
            if (!refreshed) {
                throw new Exception("official bridge directory was unavailable");
            }
        } finally {
            PSIPHON.stop();
            SNOWFLAKE.stop();
            try {
                SNOWFLAKE.resetTransportForRecovery();
            } catch (Throwable error) {
                DebugLog.add("BRIDGE", "Temporary transport reset warning: "
                        + describe(error));
            }
        }

        if (!RUNNING.get() || generation != GENERATION.get()) {
            throw new InterruptedException("Bridge bootstrap cancelled");
        }
        List<BridgeDirectory.BridgePlan> selectedPlans =
                BridgeDirectory.connectionPlans(context, transportMode);
        if (selectedPlans.isEmpty()) {
            throw new Exception("the downloaded directory had no valid "
                    + selectedName + " bridge");
        }
        DebugLog.add("BRIDGE", "Temporary Snowflake bootstrap closed; switching to "
                + selectedName);
        listener.onProgress("Fresh " + selectedName
                + " bridges received; starting the selected transport");
        return selectedPlans;
    }

    public static int routeSocksPort() {
        return activeRouteSocksPort;
    }

    public static void startTun2proxy(int tunFd, Listener listener) {
        if (tunFd < 0) {
            listener.onError("Invalid Android TUN descriptor");
            return;
        }
        final long generation = GENERATION.get();
        // Keep this command in the exact shape accepted by tun2proxy's Android JNI.
        // --setup and --ipv6-enabled are flags, so passing a literal "false" after
        // either one makes clap reject the whole command before the data plane starts.
        int routePort = activeRouteSocksPort;
        String arguments = "tun2proxy-bin --tun-fd " + tunFd
                + " --close-fd-on-drop false --proxy socks5://127.0.0.1:" + routePort
                + " --dns virtual"
                + " --verbosity info --exit-on-fatal-error";
        DebugLog.add("VPN", "Starting packet tunnel with Android TUN fd=" + tunFd
                + " through SOCKS 127.0.0.1:" + routePort);
        AtomicBoolean resultDelivered = new AtomicBoolean(false);
        Thread tunnel = new Thread(() -> {
            int result = Tun2proxy.run(arguments, (char) 1280);
            DebugLog.add("VPN", "Packet tunnel stopped with code=" + result);
            if (tunnelThread == Thread.currentThread()) tunnelThread = null;
            if (RUNNING.get() && generation == GENERATION.get()
                    && resultDelivered.compareAndSet(false, true)) {
                listener.onError("Packet tunnel stopped unexpectedly with code " + result);
            }
        }, "callisto-tun2proxy");
        tunnel.setDaemon(true);
        tunnelThread = tunnel;
        tunnel.start();
        Thread readiness = new Thread(() -> {
            try { Thread.sleep(1200L); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
            if (RUNNING.get() && generation == GENERATION.get() && tunnel.isAlive()
                    && resultDelivered.compareAndSet(false, true)) {
                DebugLog.add("VPN", "Packet tunnel data plane is running");
                listener.onReady("VPN");
            }
        }, "callisto-tun-ready");
        readiness.setDaemon(true);
        readiness.start();
    }

    public static void stop() {
        stopAsync(null);
    }

    public static synchronized void stopAsync(StopListener listener) {
        GENERATION.incrementAndGet();
        RUNNING.set(false);
        Future<?> task = activeStart;
        activeStart = null;
        if (task != null) task.cancel(true);
        stopPacketTunnel();
        LIFECYCLE.submit(() -> {
            PSIPHON.stop();
            SNOWFLAKE.stop();
            Thread tunnel = tunnelThread;
            if (tunnel != null && tunnel != Thread.currentThread()) {
                try { tunnel.join(1500L); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            if (listener != null) MAIN.post(listener::onStopped);
        });
    }

    private static void stopPacketTunnel() {
        if (Tun2proxy.isAvailable()) {
            try { Tun2proxy.stop(); } catch (Throwable ignored) {}
        }
    }

    private static boolean typeAvailable(Class<?> type) {
        // A class literal does not initialize the gomobile runtime. Unlike
        // Class.forName with a hard-coded string, R8 safely rewrites this
        // reference when producing a minified Release build.
        return type != null;
    }

    private static int connectionAttempts(Context context) {
        String profile = context.getSharedPreferences(
                "callisto_preferences", Context.MODE_PRIVATE)
                .getString("connection_speed", "FAST");
        if ("STABLE".equals(profile)) return 2;
        if ("BALANCED".equals(profile)) return 3;
        return 4;
    }

    private static long retryDelayMillis(Context context, int attempt) {
        String profile = context.getSharedPreferences(
                "callisto_preferences", Context.MODE_PRIVATE)
                .getString("connection_speed", "FAST");
        if ("STABLE".equals(profile)) return 1_000L * attempt;
        if ("BALANCED".equals(profile)) return 500L * attempt;
        return 200L * attempt;
    }

    private static String describe(Throwable error) {
        if (error == null) return "Unknown startup error";
        String message = error.getMessage();
        String type = error.getClass().getSimpleName();
        return message == null || message.trim().isEmpty()
                ? type : type + ": " + message;
    }
}
