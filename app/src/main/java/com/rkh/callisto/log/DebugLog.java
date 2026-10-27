package com.rkh.callisto.log;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DebugLog {
    public static final String ACTION_ENTRY = "com.rkh.callisto.DEBUG_LOG_ENTRY";
    private static final String EXTRA_TIME = "time";
    private static final String EXTRA_TAG = "tag";
    private static final String EXTRA_MESSAGE = "message";
    private static final String EXTRA_PROCESS = "process";
    private static final String PREFS = "callisto_debug_log";
    private static final String KEY_ENTRIES = "entries";

    public interface Listener {
        void onLogChanged();
    }

    public static final class Entry {
        public final long time;
        public final String tag;
        public final String message;

        Entry(long time, String tag, String message) {
            this.time = time;
            this.tag = tag;
            this.message = message;
        }
    }

    private static final int MAX_ENTRIES = 400;
    private static final int MAX_DISPLAY_ENTRIES = 220;
    private static final int MAX_MESSAGE_CHARS = 4000;
    private static final CopyOnWriteArrayList<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService STORAGE =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "callisto-log-storage");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean NOTIFY_QUEUED = new AtomicBoolean(false);
    private static final Object PERSIST_LOCK = new Object();
    private static volatile ScheduledFuture<?> pendingPersist;
    private static volatile Context appContext;
    private static volatile boolean crashHandlerInstalled;

    private DebugLog() {}

    public static void initialize(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
            refresh(context);
        }
    }

    public static synchronized void installCrashHandler(Context context) {
        if (crashHandlerInstalled || context == null) return;
        initialize(context);
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                add("CRASH", summarizeThrowable(thread, error));
                Context app = appContext;
                if (app != null) {
                    app.getSharedPreferences("callisto_connection_state", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("desired_active", false)
                            .putString("status", "ERROR")
                            .putString("detail", "Tunnel process crashed; open Logs")
                            .commit();
                }
            } catch (Throwable ignored) {
                // Never mask the original crash while recording diagnostics.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error);
                else Process.killProcess(Process.myPid());
            }
        });
        crashHandlerInstalled = true;
    }

    public static void importLastProcessExit(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 30) return;
        try {
            Context app = context.getApplicationContext();
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long importedAt = prefs.getLong("last_exit_imported_at", 0L);
            ActivityManager manager =
                    (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            List<ApplicationExitInfo> exits =
                    manager.getHistoricalProcessExitReasons(app.getPackageName(), 0, 6);
            ApplicationExitInfo newest = null;
            for (ApplicationExitInfo exit : exits) {
                int reason = exit.getReason();
                if (exit.getTimestamp() <= importedAt
                        || (reason != ApplicationExitInfo.REASON_CRASH
                        && reason != ApplicationExitInfo.REASON_CRASH_NATIVE
                        && reason != ApplicationExitInfo.REASON_ANR)) {
                    continue;
                }
                if (newest == null || exit.getTimestamp() > newest.getTimestamp()) newest = exit;
            }
            if (newest == null) return;
            prefs.edit().putLong("last_exit_imported_at", newest.getTimestamp()).commit();
            String process = newest.getProcessName() == null ? "unknown" : newest.getProcessName();
            String description = newest.getDescription();
            add("CRASH", "Previous process exit: reason=" + newest.getReason()
                    + ", process=" + process
                    + (description == null || description.isEmpty()
                    ? "" : ", detail=" + description));
        } catch (Throwable ignored) {
            // Historical diagnostics are optional on vendor-modified Android builds.
        }
    }

    public static void refresh(Context context) {
        if (context != null) appContext = context.getApplicationContext();
        Context app = appContext;
        if (app == null) return;
        List<Entry> restored = readPersisted(app);
        ENTRIES.clear();
        ENTRIES.addAll(restored);
        notifyChanged();
    }

    public static void add(String tag, String message) {
        String safeTag = tag == null ? "APP" : tag.toUpperCase(Locale.US);
        if ("TOR".equals(safeTag) || "SNOWFLAKE".equals(safeTag) || "MASQUE".equals(safeTag)) {
            safeTag = "CORE";
        }
        String safeMessage = limitMessage(redact(message == null ? "" : message));
        long time = System.currentTimeMillis();
        addLocal(new Entry(time, safeTag, safeMessage));
        Context context = appContext;
        if (context != null) {
            Intent event = new Intent(ACTION_ENTRY)
                    .setPackage(context.getPackageName())
                    .putExtra(EXTRA_TIME, time)
                    .putExtra(EXTRA_TAG, safeTag)
                    .putExtra(EXTRA_MESSAGE, safeMessage)
                    .putExtra(EXTRA_PROCESS, Process.myPid());
            try { context.sendBroadcast(event); } catch (RuntimeException ignored) {}
        }
    }

    public static boolean acceptBroadcast(Intent intent) {
        if (intent == null || !ACTION_ENTRY.equals(intent.getAction())) return false;
        if (intent.getIntExtra(EXTRA_PROCESS, Process.myPid()) == Process.myPid()) return true;
        String tag = intent.getStringExtra(EXTRA_TAG);
        String message = intent.getStringExtra(EXTRA_MESSAGE);
        addLocal(new Entry(intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis()),
                tag == null ? "CORE" : tag, limitMessage(message == null ? "" : message)));
        return true;
    }

    private static void addLocal(Entry entry) {
        ENTRIES.add(entry);
        while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.remove(0);
        schedulePersist();
        notifyChanged();
    }

    private static void notifyChanged() {
        if (!NOTIFY_QUEUED.compareAndSet(false, true)) return;
        MAIN.postDelayed(() -> {
            NOTIFY_QUEUED.set(false);
            for (Listener listener : LISTENERS) listener.onLogChanged();
        }, 250L);
    }

    public static List<Entry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }

    public static void clear() {
        ENTRIES.clear();
        schedulePersist();
    }

    public static void flush() {
        synchronized (PERSIST_LOCK) {
            ScheduledFuture<?> previous = pendingPersist;
            if (previous != null) previous.cancel(false);
            pendingPersist = null;
        }
        persistNow();
    }

    public static void subscribe(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
    }

    public static void unsubscribe(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static String asText(String filter) {
        return formatText(filter, Integer.MAX_VALUE, false);
    }

    public static String asDisplayText(String filter) {
        return formatText(filter, MAX_DISPLAY_ENTRIES, true);
    }

    private static String formatText(String filter, int maximum, boolean display) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        StringBuilder output = new StringBuilder("Callisto debug log\n");
        List<Entry> entries = snapshot();
        int matches = 0;
        for (Entry entry : entries) {
            if (filter == null || "ALL".equals(filter) || filter.equals(entry.tag)) matches++;
        }
        int skip = Math.max(0, matches - maximum);
        if (display && skip > 0) {
            output.append("Showing latest ").append(maximum)
                    .append(" entries; export or copy for the complete log.\n");
        }
        for (Entry entry : entries) {
            if (filter != null && !"ALL".equals(filter) && !filter.equals(entry.tag)) continue;
            if (skip > 0) {
                skip--;
                continue;
            }
            output.append(format.format(new Date(entry.time)))
                    .append("  [").append(entry.tag).append("]  ")
                    .append(entry.message).append('\n');
        }
        return output.toString();
    }

    private static String redact(String text) {
        return text
                .replaceAll("(?i)(token|password|secret|private[_ -]?key)=\\S+", "$1=<redacted>")
                .replaceAll("(?i)bearer\\s+[a-z0-9._~+/-]+=*", "Bearer <redacted>")
                .replaceAll("(?i)snowflake", "route")
                .replaceAll("(?i)\\btor\\b", "core");
    }

    private static String limitMessage(String message) {
        if (message == null) return "";
        if (message.length() <= MAX_MESSAGE_CHARS) return message;
        return message.substring(0, MAX_MESSAGE_CHARS) + "… [truncated]";
    }

    private static String summarizeThrowable(Thread thread, Throwable error) {
        StringWriter output = new StringWriter();
        PrintWriter writer = new PrintWriter(output);
        writer.println("Uncaught exception on "
                + (thread == null ? "unknown thread" : thread.getName()));
        if (error != null) error.printStackTrace(writer);
        writer.flush();
        String value = output.toString();
        return value.length() > 12000 ? value.substring(0, 12000) : value;
    }

    private static void schedulePersist() {
        synchronized (PERSIST_LOCK) {
            ScheduledFuture<?> previous = pendingPersist;
            if (previous != null) previous.cancel(false);
            pendingPersist = STORAGE.schedule(DebugLog::persistNow, 500L, TimeUnit.MILLISECONDS);
        }
    }

    private static void persistNow() {
        Context context = appContext;
        if (context == null) return;
        StringBuilder output = new StringBuilder();
        for (Entry entry : snapshot()) {
            output.append(entry.time).append('\t')
                    .append(entry.tag == null ? "APP" : entry.tag).append('\t')
                    .append(Base64.encodeToString(
                            (entry.message == null ? "" : entry.message).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            Base64.NO_WRAP))
                    .append('\n');
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENTRIES, output.toString())
                .commit();
    }

    private static List<Entry> readPersisted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_ENTRIES, "");
        List<Entry> restored = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return restored;
        String[] lines = raw.split("\\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\t", 3);
            if (parts.length != 3) continue;
            try {
                long time = Long.parseLong(parts[0]);
                String message = new String(Base64.decode(parts[2], Base64.NO_WRAP),
                        java.nio.charset.StandardCharsets.UTF_8);
                restored.add(new Entry(time, parts[1], message));
            } catch (Exception ignored) {}
        }
        while (restored.size() > MAX_ENTRIES) restored.remove(0);
        return restored;
    }
}
