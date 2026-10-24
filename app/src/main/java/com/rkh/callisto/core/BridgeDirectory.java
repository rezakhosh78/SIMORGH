package com.rkh.callisto.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.rkh.callisto.log.DebugLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Downloads Tor's current built-in bridge set and keeps a private, bounded cache.
 *
 * The distributor is attempted directly before the route starts. When that host
 * is blocked, automatic mode uses the last good cache (or Snowflake). A manual
 * WebTunnel/obfs4 request may temporarily bootstrap Snowflake and refresh this
 * cache through Tor directly, or through Psiphon when the user enabled it.
 */
final class BridgeDirectory {
    private static final String ENDPOINT =
            "https://bridges.torproject.org/moat/circumvention/builtin";
    private static final String PREFS = "callisto_bridge_directory";
    private static final String KEY_JSON = "validated_json";
    private static final String KEY_UPDATED_AT = "updated_at";
    private static final long DIRECT_REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long MAX_CACHE_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int DIRECT_TIMEOUT_MS = 5_500;
    private static final int TUNNELED_TIMEOUT_MS = 15_000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_BRIDGES_PER_TRANSPORT = 8;
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);

    private static final String ADDRESS =
            "(?:\\d{1,3}(?:\\.\\d{1,3}){3}|\\[[0-9A-Fa-f:]+\\]):\\d{1,5}";
    private static final Pattern OBFS4 = Pattern.compile(
            "^obfs4\\s+" + ADDRESS + "\\s+[A-Fa-f0-9]{40}"
                    + "(?=.*\\scert=\\S+)(?=.*\\siat-mode=\\d+)\\s+.+$");
    private static final Pattern WEBTUNNEL = Pattern.compile(
            "^webtunnel\\s+" + ADDRESS + "\\s+[A-Fa-f0-9]{40}"
                    + "(?=.*\\surl=https://\\S+)\\s+.+$");

    private BridgeDirectory() {}

    static List<BridgePlan> connectionPlans(Context context, String requestedMode) {
        Context app = context.getApplicationContext();
        String selectedMode = normalizeMode(requestedMode);
        if ("SNOWFLAKE".equals(selectedMode)) {
            DebugLog.add("BRIDGE", "Manual transport selection: Snowflake");
            return Collections.singletonList(BridgePlan.snowflakeFallback());
        }

        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L);
        String cached = prefs.getString(KEY_JSON, "");

        if (cached.isEmpty() || now - updatedAt >= DIRECT_REFRESH_INTERVAL_MS) {
            try {
                String downloaded = download(Proxy.NO_PROXY, DIRECT_TIMEOUT_MS);
                if (validatePayload(downloaded)) {
                    save(prefs, downloaded, now);
                    cached = downloaded;
                    updatedAt = now;
                    DebugLog.add("BRIDGE", "Official bridge directory refreshed directly");
                }
            } catch (Exception error) {
                DebugLog.add("BRIDGE", "Direct bridge refresh unavailable; using safe fallback");
            }
        }

        List<BridgePlan> plans = new ArrayList<>();
        if (!cached.isEmpty() && now - updatedAt <= MAX_CACHE_AGE_MS) {
            try {
                JSONObject root = new JSONObject(cached);
                if ("AUTO".equals(selectedMode) || "WEBTUNNEL".equals(selectedMode)) {
                    addPlan(plans, "webtunnel", validLines(root, "webtunnel"));
                }
                if ("AUTO".equals(selectedMode) || "OBFS4".equals(selectedMode)) {
                    addPlan(plans, "obfs4", validLines(root, "obfs4"));
                }
            } catch (Exception error) {
                DebugLog.add("BRIDGE", "Cached bridge directory was rejected");
            }
        }
        if ("AUTO".equals(selectedMode)) {
            plans.add(BridgePlan.snowflakeFallback());
            DebugLog.add("BRIDGE", "Automatic transport plan has " + plans.size()
                    + " candidate" + (plans.size() == 1 ? "" : "s"));
        } else if (plans.isEmpty()) {
            DebugLog.add("BRIDGE", "No valid " + displayMode(selectedMode)
                    + " bridge is currently available");
        } else {
            DebugLog.add("BRIDGE", "Manual transport selection: "
                    + displayMode(selectedMode));
        }
        return Collections.unmodifiableList(plans);
    }

    private static String normalizeMode(String value) {
        if ("WEBTUNNEL".equals(value) || "OBFS4".equals(value)
                || "SNOWFLAKE".equals(value)) {
            return value;
        }
        return "AUTO";
    }

    static String displayMode(String mode) {
        if ("WEBTUNNEL".equals(mode)) return "WebTunnel";
        if ("OBFS4".equals(mode)) return "obfs4";
        if ("SNOWFLAKE".equals(mode)) return "Snowflake";
        return "Auto";
    }

    static void refreshThroughPsiphon(Context context) {
        Context app = context.getApplicationContext();
        if (!REFRESHING.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                refreshThroughConnectedRoute(app, "connected Psiphon route",
                        new Proxy(Proxy.Type.HTTP,
                                new InetSocketAddress("127.0.0.1", PsiphonCore.HTTP_PORT)));
            } catch (Exception error) {
                DebugLog.add("BRIDGE", "Connected bridge refresh deferred");
            } finally {
                REFRESHING.set(false);
            }
        }, "callisto-bridge-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    static void refreshThroughTor(Context context, int socksPort) {
        Context app = context.getApplicationContext();
        if (!REFRESHING.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                refreshThroughConnectedRoute(app, "connected core route",
                        new Proxy(Proxy.Type.SOCKS,
                                new InetSocketAddress("127.0.0.1", socksPort)));
            } catch (Exception error) {
                DebugLog.add("BRIDGE", "Connected bridge refresh deferred");
            } finally {
                REFRESHING.set(false);
            }
        }, "callisto-tor-bridge-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Refreshes the bridge cache while Psiphon's local HTTP listener is alive.
     * This blocking variant is used only by the temporary Snowflake bootstrap
     * for a manually selected WebTunnel or obfs4 connection.
     */
    static boolean refreshThroughPsiphonBlocking(Context context) {
        Context app = context.getApplicationContext();
        if (!REFRESHING.compareAndSet(false, true)) {
            DebugLog.add("BRIDGE", "Bridge refresh is already running");
            return false;
        }
        try {
            refreshThroughConnectedRoute(app, "temporary Psiphon route",
                    new Proxy(Proxy.Type.HTTP,
                            new InetSocketAddress("127.0.0.1", PsiphonCore.HTTP_PORT)));
            return true;
        } catch (Exception error) {
            DebugLog.add("BRIDGE", "Manual bridge bootstrap refresh failed");
            return false;
        } finally {
            REFRESHING.set(false);
        }
    }

    static boolean refreshThroughTorBlocking(Context context, int socksPort) {
        Context app = context.getApplicationContext();
        if (!REFRESHING.compareAndSet(false, true)) {
            DebugLog.add("BRIDGE", "Bridge refresh is already running");
            return false;
        }
        try {
            refreshThroughConnectedRoute(app, "temporary core route",
                    new Proxy(Proxy.Type.SOCKS,
                            new InetSocketAddress("127.0.0.1", socksPort)));
            return true;
        } catch (Exception error) {
            DebugLog.add("BRIDGE", "Core bridge bootstrap refresh failed");
            return false;
        } finally {
            REFRESHING.set(false);
        }
    }

    private static void refreshThroughConnectedRoute(Context app, String routeLabel,
                                                     Proxy proxy)
            throws Exception {
        String downloaded = download(proxy, TUNNELED_TIMEOUT_MS);
        if (!validatePayload(downloaded)) {
            throw new Exception("Bridge directory validation failed");
        }
        save(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                downloaded, System.currentTimeMillis());
        JSONObject root = new JSONObject(downloaded);
        int webtunnel = validLines(root, "webtunnel").size();
        int obfs4 = validLines(root, "obfs4").size();
        DebugLog.add("BRIDGE", "Official bridge cache updated through " + routeLabel + ": "
                + webtunnel + " WebTunnel, " + obfs4 + " obfs4");
    }

    private static void addPlan(List<BridgePlan> plans, String transport,
                                List<String> lines) {
        if (!lines.isEmpty()) plans.add(new BridgePlan(transport, lines, "official-cache"));
    }

    private static boolean validatePayload(String payload) {
        try {
            JSONObject root = new JSONObject(payload);
            return !validLines(root, "webtunnel").isEmpty()
                    || !validLines(root, "obfs4").isEmpty();
        } catch (Exception error) {
            return false;
        }
    }

    private static List<String> validLines(JSONObject root, String transport) {
        JSONArray values = root.optJSONArray(transport);
        if (values == null) return Collections.emptyList();
        Pattern pattern = "webtunnel".equals(transport) ? WEBTUNNEL : OBFS4;
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < values.length()
                && result.size() < MAX_BRIDGES_PER_TRANSPORT; index++) {
            String line = values.optString(index, "").trim()
                    .replace('\r', ' ').replace('\n', ' ');
            line = line.replaceAll("\\s+", " ");
            if (line.length() > 2048 || line.indexOf('\0') >= 0) continue;
            if (pattern.matcher(line).matches() && unique.add(line)) result.add(line);
        }
        return result;
    }

    private static String download(Proxy proxy, int timeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(ENDPOINT).openConnection(proxy);
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Callisto/0.7.5 Android");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new Exception("Bridge directory HTTP " + status);
            }
            String contentType = String.valueOf(connection.getContentType())
                    .toLowerCase(Locale.US);
            if (!contentType.contains("json")) {
                throw new Exception("Bridge directory returned non-JSON content");
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_RESPONSE_BYTES) {
                        throw new Exception("Bridge directory response is too large");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void save(SharedPreferences prefs, String payload, long updatedAt)
            throws Exception {
        if (!prefs.edit().putString(KEY_JSON, payload)
                .putLong(KEY_UPDATED_AT, updatedAt).commit()) {
            throw new Exception("Could not save bridge directory");
        }
    }

    static final class BridgePlan {
        final String transport;
        final List<String> bridges;
        final String source;

        BridgePlan(String transport, List<String> bridges, String source) {
            this.transport = transport;
            this.bridges = Collections.unmodifiableList(new ArrayList<>(bridges));
            this.source = source;
        }

        static BridgePlan snowflakeFallback() {
            List<String> lines = new ArrayList<>();
            lines.add("snowflake 192.0.2.3:80 "
                    + "2B280B23E1107BB62ABFC40DDCC8824814F80A72 "
                    + "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72");
            lines.add("snowflake 192.0.2.4:80 "
                    + "8838024498816A039FCBBAB14E6F40A0843051FA "
                    + "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA");
            return new BridgePlan("snowflake", lines, "built-in-fallback");
        }

        String displayName() {
            if ("webtunnel".equals(transport)) return "WebTunnel";
            if ("obfs4".equals(transport)) return "obfs4";
            return "Snowflake";
        }
    }
}
