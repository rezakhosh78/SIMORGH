package com.rkh.callisto.core;

import android.content.Context;

import ca.psiphon.PsiphonTunnel;

import com.rkh.callisto.log.DebugLog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class PsiphonCore implements PsiphonTunnel.HostService {
    static final int SOCKS_PORT = 1819;
    static final int HTTP_PORT = 1920;

    private static final long STARTUP_TIMEOUT_SECONDS = 180L;
    private static final String CONFIG_ASSET = "simorgh_psiphon_config.json";
    private static final String SERVER_ENTRIES_ASSET = "simorgh_psiphon_server_entries.txt";
    private Context appContext;
    private NativeCore.Listener listener;
    private PsiphonTunnel tunnel;
    private CountDownLatch connectedLatch;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger socksPort = new AtomicInteger(0);
    private final AtomicInteger upstreamPort = new AtomicInteger(SnowflakeEngine.SOCKS_PORT);
    private volatile String egressRegion = "";

    int start(Context context, int upstreamSocksPort, String exitCountry,
              NativeCore.Listener listener) throws Exception {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.connectedLatch = new CountDownLatch(1);
        this.stopping.set(false);
        this.connected.set(false);
        this.socksPort.set(0);
        this.upstreamPort.set(upstreamSocksPort);
        this.egressRegion = normalizeEgressRegion(exitCountry);

        listener.onProgress("Preparing Psiphon over Snowflake");
        tunnel = PsiphonTunnel.newPsiphonTunnel(this);
        tunnel.setVpnMode(false);
        tunnel.setClientPlatformAffixes("Simorgh_", "");

        String serverEntries = readAsset(SERVER_ENTRIES_ASSET);
        if (serverEntries.trim().isEmpty()) {
            throw new Exception("Psiphon server entries asset is empty");
        }

        DebugLog.add("CORE", egressRegion.isEmpty()
                ? "Psiphon egress uses Best Country"
                : "Psiphon egress country=" + egressRegion);
        DebugLog.add("CORE", "Starting Psiphon through Snowflake SOCKS 127.0.0.1:"
                + upstreamSocksPort);
        tunnel.startTunneling(serverEntries);
        if (!connectedLatch.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new Exception("Psiphon route startup timed out");
        }
        int port = socksPort.get();
        if (port <= 0) port = SOCKS_PORT;
        DebugLog.add("CORE", "Psiphon route is ready on SOCKS 127.0.0.1:" + port);
        return port;
    }

    void stop() {
        stopping.set(true);
        connected.set(false);
        socksPort.set(0);
        PsiphonTunnel active = tunnel;
        tunnel = null;
        if (active != null) {
            try {
                active.stop();
            } catch (Throwable error) {
                DebugLog.add("CORE", "Psiphon stop warning: " + String.valueOf(error.getMessage()));
            }
        }
        connectedLatch = null;
        listener = null;
        appContext = null;
        egressRegion = "";
    }

    @Override
    public Context getContext() {
        return appContext;
    }

    @Override
    public String getPsiphonConfig() {
        try {
            JSONObject config = new JSONObject(readAsset(CONFIG_ASSET));
            File dataRoot = prepareDataRootDirectory();
            config.put("LocalSocksProxyPort", SOCKS_PORT);
            config.put("LocalHttpProxyPort", HTTP_PORT);
            config.put("UpstreamProxyUrl", "socks5://127.0.0.1:" + upstreamPort.get());
            if (egressRegion.isEmpty()) {
                config.remove("EgressRegion");
            } else {
                config.put("EgressRegion", egressRegion);
            }
            config.put("EmitDiagnosticNotices", true);
            config.put("EmitDiagnosticNetworkParameters", true);
            config.put("DisableLocalHTTPProxy", false);
            config.put("DisableLocalSocksProxy", false);
            config.put("EstablishTunnelTimeoutSeconds", STARTUP_TIMEOUT_SECONDS);
            config.put("DataRootDirectory", dataRoot.getAbsolutePath());
            return config.toString();
        } catch (Exception error) {
            DebugLog.add("CORE", "Psiphon config error: " + error.getMessage());
            return "{}";
        }
    }

    @Override
    public void loadLibrary(String name) {
        System.loadLibrary(name);
    }

    @Override
    public void bindToDevice(long socket) {
        // Callisto excludes its own package from the Android VPN, so Psiphon's
        // upstream sockets already stay outside the packet tunnel.
    }

    @Override
    public void onDiagnosticMessage(String message) {
        if (message == null) return;
        String cleaned = message
                .replace(sourceBrandWithSpaces(), "Callisto")
                .replace(sourceBrandJoined(), "Callisto")
                .replace(sourceBrandLower(), "callisto")
                .replace(sourceBrandPersian(), "Callisto");
        DebugLog.add("CORE", cleaned);
        NativeCore.Listener current = listener;
        if (!connected.get() && current != null && !stopping.get()) {
            current.onProgress("Psiphon route is building");
        }
    }

    @Override
    public void onListeningSocksProxyPort(int port) {
        socksPort.set(port);
        DebugLog.add("CORE", "Psiphon SOCKS listener is ready on 127.0.0.1:" + port);
    }

    @Override
    public void onListeningHttpProxyPort(int port) {
        DebugLog.add("CORE", "Psiphon HTTP listener is ready on 127.0.0.1:" + port);
    }

    @Override
    public void onConnecting() {
        NativeCore.Listener current = listener;
        if (current != null && !stopping.get()) {
            current.onProgress("Connecting Psiphon through Snowflake");
        }
    }

    @Override
    public void onConnected() {
        connected.set(true);
        CountDownLatch latch = connectedLatch;
        if (latch != null) latch.countDown();
        NativeCore.Listener current = listener;
        if (current != null && !stopping.get()) current.onProgress("Psiphon route connected");
    }

    @Override
    public void onExiting() {
        DebugLog.add("CORE", "Psiphon route is exiting");
    }

    @Override
    public void onUpstreamProxyError(String message) {
        NativeCore.Listener current = listener;
        if (current != null && !stopping.get()) {
            current.onError(message == null ? "Psiphon upstream proxy error" : message);
        }
    }

    @Override public void onSocksProxyPortInUse(int port) { DebugLog.add("CORE", "SOCKS port in use: " + port); }
    @Override public void onHttpProxyPortInUse(int port) { DebugLog.add("CORE", "HTTP port in use: " + port); }
    @Override public void onStartedWaitingForNetworkConnectivity() { DebugLog.add("CORE", "Waiting for network connectivity"); }
    @Override public void onStoppedWaitingForNetworkConnectivity() { DebugLog.add("CORE", "Network connectivity returned"); }
    @Override public void onClientRegion(String region) { DebugLog.add("CORE", "Client region=" + region); }
    @Override public void onConnectedServerRegion(String region) { DebugLog.add("CORE", "Server region=" + region); }
    @Override public void onClientAddress(String address) { DebugLog.add("CORE", "Client address assigned"); }
    @Override public void onAvailableEgressRegions(List<String> regions) {}
    @Override public void onSplitTunnelRegions(List<String> regions) {}
    @Override public void onActiveAuthorizationIDs(List<String> ids) {}
    @Override public void onApplicationParameters(Object parameters) {}
    @Override public void onBytesTransferred(long sent, long received) {}
    @Override public void onClientIsLatestVersion() {}
    @Override public void onClientUpgradeDownloaded(String filename) {}
    @Override public void onHomepage(String url) {}
    @Override public void onInproxyMustUpgrade() {}
    @Override public void onInproxyProxyActivity(int announcing, int connectingClients,
            int connectedClients, long bytesUp, long bytesDown,
            Map<String, PsiphonTunnel.RegionActivitySnapshot> personal,
            Map<String, PsiphonTunnel.RegionActivitySnapshot> common) {}
    @Override public void onLightProxyAvailable() {}
    @Override public void onServerAlert(String reason, String subject, List<String> actionUrls) {}
    @Override public void onTrafficRateLimits(long upstreamBytesPerSecond, long downstreamBytesPerSecond) {}
    @Override public void onUntunneledAddress(String address) {}

    private File prepareDataRootDirectory() throws Exception {
        File dataRoot = new File(appContext.getFilesDir(), "simorgh_psiphon_core");
        if (!dataRoot.exists() && !dataRoot.mkdirs()) {
            throw new Exception("failed to create Psiphon data root");
        }
        if (!dataRoot.isDirectory()) {
            throw new Exception("Psiphon data root is not a directory");
        }
        if (!dataRoot.canWrite()) {
            throw new Exception("Psiphon data root is not writable");
        }
        DebugLog.add("CORE", "Psiphon data directory is ready");
        return dataRoot;
    }

    private String readAsset(String name) throws Exception {
        try (InputStream input = appContext.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String normalizeEgressRegion(String value) {
        if (value == null) return "";
        String normalized = value.trim().toUpperCase(Locale.US);
        return normalized.matches("[A-Z]{2}") ? normalized : "";
    }

    private static String sourceBrandWithSpaces() {
        return new String(new char[] {'S','h','i','r',' ','o',' ','K','h','o','r','s','h','i','d'});
    }

    private static String sourceBrandJoined() {
        return new String(new char[] {'S','h','i','r','O','K','h','o','r','s','h','i','d'});
    }

    private static String sourceBrandLower() {
        return new String(new char[] {'s','h','i','r','o','k','h','o','r','s','h','i','d'});
    }

    private static String sourceBrandPersian() {
        return "\u0634\u06cc\u0631 \u0648 \u062e\u0648\u0631\u0634\u06cc\u062f";
    }
}
