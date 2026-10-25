package com.rkh.callisto.model;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.CopyOnWriteArrayList;

/** Single source of truth for the connection UI and foreground services. */
public final class ConnectionStateStore {
    public static final String ACTION_STATE_CHANGED = "com.rkh.callisto.CONNECTION_STATE_CHANGED";
    private static final String EXTRA_STATUS = "status";
    private static final String EXTRA_MODE = "mode";
    private static final String EXTRA_DETAIL = "detail";
    private static final String EXTRA_PROCESS = "process";
    private static final String KEY_DESIRED_ACTIVE = "desired_active";

    public enum Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        ERROR
    }

    public interface Listener {
        void onConnectionStateChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final Status status;
        public final ConnectionMode mode;
        public final String detail;

        Snapshot(Status status, ConnectionMode mode, String detail) {
            this.status = status;
            this.mode = mode;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isActive() {
            return status == Status.CONNECTING
                    || status == Status.CONNECTED
                    || status == Status.DISCONNECTING;
        }
    }

    private static final String PREFS = "callisto_connection_state";
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Snapshot current;

    private ConnectionStateStore() {}

    public static Snapshot get(Context context) {
        Snapshot value = current;
        if (value != null) return value;
        synchronized (ConnectionStateStore.class) {
            if (current == null) current = read(context.getApplicationContext());
            return current;
        }
    }

    public static Snapshot refresh(Context context) {
        Context app = context.getApplicationContext();
        Snapshot next;
        synchronized (ConnectionStateStore.class) {
            next = read(app);
            current = next;
        }
        notifyListeners(next);
        return next;
    }

    public static void update(Context context, Status status, ConnectionMode mode, String detail) {
        Context app = context.getApplicationContext();
        Snapshot next = new Snapshot(status, mode, frontendSafe(detail));
        current = next;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("status", status.name())
                .putString("mode", mode.name())
                .putString("detail", next.detail)
                .commit();
        notifyListeners(next);
        Intent event = new Intent(ACTION_STATE_CHANGED)
                .setPackage(app.getPackageName())
                .putExtra(EXTRA_STATUS, status.name())
                .putExtra(EXTRA_MODE, mode.name())
                .putExtra(EXTRA_DETAIL, next.detail)
                .putExtra(EXTRA_PROCESS, Process.myPid());
        try { app.sendBroadcast(event); } catch (RuntimeException ignored) {}
    }

    public static boolean acceptBroadcast(Intent intent) {
        if (intent == null || !ACTION_STATE_CHANGED.equals(intent.getAction())) return false;
        if (intent.getIntExtra(EXTRA_PROCESS, Process.myPid()) == Process.myPid()) return true;
        try {
            Snapshot next = new Snapshot(
                    Status.valueOf(intent.getStringExtra(EXTRA_STATUS)),
                    ConnectionMode.valueOf(intent.getStringExtra(EXTRA_MODE)),
                    frontendSafe(intent.getStringExtra(EXTRA_DETAIL)));
            current = next;
            notifyListeners(next);
        } catch (Exception ignored) {}
        return true;
    }

    public static void forceConnected(Context context, ConnectionMode mode, String detail) {
        Snapshot state = get(context);
        if (state.status != Status.CONNECTING) return;
        update(context, Status.CONNECTED, mode, detail);
    }

    public static void setDesiredActive(Context context, boolean active) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DESIRED_ACTIVE, active).commit();
    }

    public static boolean isDesiredActive(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DESIRED_ACTIVE, false);
    }

    private static void notifyListeners(Snapshot next) {
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) listener.onConnectionStateChanged(next);
        });
    }

    public static void subscribe(Context context, Listener listener) {
        if (listener == null) return;
        if (!LISTENERS.contains(listener)) LISTENERS.add(listener);
        Snapshot snapshot = get(context);
        MAIN.post(() -> listener.onConnectionStateChanged(snapshot));
    }

    public static void unsubscribe(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static Snapshot read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Status status;
        ConnectionMode mode;
        try {
            status = Status.valueOf(prefs.getString("status", Status.DISCONNECTED.name()));
        } catch (IllegalArgumentException error) {
            status = Status.DISCONNECTED;
        }
        try {
            mode = ConnectionMode.valueOf(prefs.getString("mode", ConnectionMode.VPN.name()));
        } catch (IllegalArgumentException error) {
            mode = ConnectionMode.VPN;
        }
        return new Snapshot(status, mode, frontendSafe(prefs.getString("detail", "")));
    }

    private static String frontendSafe(String detail) {
        if (detail == null) return "";
        return detail
                .replaceAll("(?i)snowflake", "route")
                .replaceAll("(?i)\\btor\\b", "core")
                .replaceAll("(?i)masque", "route");
    }
}
