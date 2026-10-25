package com.rkh.callisto.service;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.rkh.callisto.log.DebugLog;

import java.io.File;

import IPtProxy.Controller;
import IPtProxy.OnTransportEvents;

/**
 * Owns IPtProxy in a process that never loads Psiphon's Go runtime.
 *
 * Both dependencies are built with gomobile. Renaming their JNI entry points
 * avoids packaging collisions, but loading two Go runtimes in one Linux
 * process can still interpose runtime symbols and terminate the process.
 */
public final class SnowflakeTransportService extends Service {
    public static final int COMMAND_START = 1;
    public static final int COMMAND_RESTART = 2;
    public static final int COMMAND_SHUTDOWN = 3;
    public static final int EVENT_READY = 10;
    public static final int EVENT_CONNECTED = 11;
    public static final int EVENT_ERROR = 12;
    public static final int EVENT_STOPPED = 13;
    public static final String KEY_ADDRESS = "address";
    public static final String KEY_ERROR = "error";
    public static final String KEY_TRANSPORT = "transport";
    public static final String KEY_SPEED_PROFILE = "speed_profile";

    private static final String BROKER = "https://1098762253.rsc.cdn77.org/";
    private static final String FRONTS = "www.cdn77.com,www.phpmyadmin.net";
    private static final String ICE =
            "stun:stun.antisip.com:3478,stun:stun.epygi.com:3478," +
            "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
            "stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443";

    private final Messenger incoming = new Messenger(
            new Handler(Looper.getMainLooper(), this::handleMessage));
    private Controller controller;
    private OnTransportEvents transportEvents;
    private Messenger client;
    private boolean transportStarted;
    private boolean terminating;
    private String activeTransport;

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLog.initialize(this);
        DebugLog.add("CORE", "Isolated pluggable-transport process created");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return incoming.getBinder();
    }

    private boolean handleMessage(Message message) {
        if (message.what != COMMAND_START
                && message.what != COMMAND_RESTART
                && message.what != COMMAND_SHUTDOWN) return false;
        if (message.replyTo != null) client = message.replyTo;
        if (message.what == COMMAND_SHUTDOWN) {
            /*
             * A gomobile Controller cannot be safely started again after its
             * Java references have been released. Do not call Controller.stop()
             * during a user disconnect; acknowledge the request and terminate
             * this dedicated process so the next connection gets a completely
             * fresh Go runtime and reference table.
             */
            Message stopped = Message.obtain(null, EVENT_STOPPED);
            Bundle data = new Bundle();
            data.putString(KEY_ERROR, "Transport process reset");
            stopped.setData(data);
            send(stopped);
            terminateProcess();
            return true;
        }
        try {
            String requestedTransport = message.getData().getString(KEY_TRANSPORT, "snowflake")
                    .trim().toLowerCase(java.util.Locale.US);
            String speedProfile = normalizeSpeedProfile(
                    message.getData().getString(KEY_SPEED_PROFILE, "FAST"));
            if (!"snowflake".equals(requestedTransport)
                    && !"obfs4".equals(requestedTransport)
                    && !"webtunnel".equals(requestedTransport)) {
                throw new IllegalArgumentException("Unsupported transport");
            }
            if (message.what == COMMAND_RESTART || controller != null || transportStarted) {
                throw new StaleRuntimeException(
                        "A fresh transport process is required for this connection");
            }
            ensureController(peersForProfile(speedProfile));
            if (!transportStarted) {
                controller.start(requestedTransport, "");
                activeTransport = requestedTransport;
                transportStarted = true;
            }
            String address = controller.localAddress(requestedTransport);
            if (address == null || address.trim().isEmpty()) {
                throw new IllegalStateException(
                        "Pluggable transport did not expose a SOCKS listener");
            }
            Message ready = Message.obtain(null, EVENT_READY);
            Bundle data = new Bundle();
            data.putString(KEY_ADDRESS, address);
            ready.setData(data);
            send(ready);
        } catch (Throwable error) {
            sendError(error);
            terminateProcess();
        }
        return true;
    }

    private void ensureController(long parallelPeers) throws Exception {
        if (controller != null) return;
        File stateDirectory = new File(getCacheDir(), "callisto_transport");
        if (!stateDirectory.isDirectory() && !stateDirectory.mkdirs()) {
            throw new Exception("Cannot create transport state directory");
        }
        transportEvents = new OnTransportEvents() {
            @Override public void connected(String name) {
                DebugLog.add("CORE", "Transport connected: " + safeTransport(name));
                send(Message.obtain(null, EVENT_CONNECTED));
            }

            @Override public void error(String name, Exception error) {
                Message event = Message.obtain(null, EVENT_ERROR);
                Bundle data = new Bundle();
                data.putString(KEY_ERROR, safe(error));
                event.setData(data);
                send(event);
                if (isStaleGoReference(error)) terminateProcess();
            }

            @Override public void stopped(String name, Exception error) {
                transportStarted = false;
                Message event = Message.obtain(null, EVENT_STOPPED);
                Bundle data = new Bundle();
                data.putString(KEY_ERROR, safe(error));
                event.setData(data);
                send(event);
                // A stopped gomobile Controller must never be started again:
                // its Java/Go reference tables are no longer reusable.
                terminateProcess();
            }
        };
        controller = new Controller(stateDirectory.getAbsolutePath(), false, false,
                "WARN", transportEvents);
        if (controller == null) {
            throw new Exception("Connection controller could not start");
        }
        controller.setSnowflakeBrokerUrl(BROKER);
        controller.setSnowflakeFrontDomains(FRONTS);
        controller.setSnowflakeIceServers(ICE);
        controller.setSnowflakeMaxPeers(parallelPeers);
        DebugLog.add("CORE", "Transport peer capacity=" + parallelPeers);
    }

    private void sendError(Throwable error) {
        Message event = Message.obtain(null, EVENT_ERROR);
        Bundle data = new Bundle();
        data.putString(KEY_ERROR, describe(error));
        event.setData(data);
        send(event);
    }

    private void send(Message message) {
        Messenger destination = client;
        if (destination == null) return;
        try {
            destination.send(message);
        } catch (RemoteException error) {
            client = null;
        }
    }

    private void terminateProcess() {
        if (terminating) return;
        terminating = true;
        transportStarted = false;
        activeTransport = null;
        controller = null;
        transportEvents = null;
        stopSelf();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 180L);
    }

    private static final class StaleRuntimeException extends Exception {
        StaleRuntimeException(String message) {
            super(message);
        }
    }

    private static String safe(Exception error) {
        return error == null ? "none" : String.valueOf(error.getMessage());
    }

    private static boolean isStaleGoReference(Exception error) {
        if (error == null || error.getMessage() == null) return false;
        String message = error.getMessage().toLowerCase(java.util.Locale.US);
        return message.contains("trackgoref")
                || message.contains("java refnum")
                || message.contains("referenced java object is not found");
    }

    private static String describe(Throwable error) {
        if (error == null) return "Unknown transport error";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }

    private static String safeTransport(String value) {
        if ("webtunnel".equalsIgnoreCase(value)) return "WebTunnel";
        if ("obfs4".equalsIgnoreCase(value)) return "obfs4";
        if ("snowflake".equalsIgnoreCase(value)) return "Snowflake";
        return "pluggable";
    }

    private static String normalizeSpeedProfile(String value) {
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
}
