package com.liquidazir.animalcrossingquest;

import android.content.Context;
import android.os.Process;
import android.os.Build;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** App-private diagnostic breadcrumbs; never records disc or save contents. */
public final class Diagnostics {
    private static final String TAG = "ACQuestDiag";
    private static final long MAX_LOG_BYTES = 512 * 1024;
    private static volatile boolean crashHandlerInstalled;

    private Diagnostics() {}

    public static synchronized void record(Context context, String event) {
        try {
            File directory = new File(context.getFilesDir(), "diagnostics");
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            File log = new File(directory, "events.log");
            if (log.exists() && log.length() > MAX_LOG_BYTES) {
                File old = new File(directory, "events.previous.log");
                if (old.exists()) old.delete();
                if (!log.renameTo(old)) log.delete();
            }
            String stamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
            try (FileOutputStream output = new FileOutputStream(log, true)) {
                output.write((stamp + " " + event.replace('\n', ' ') + "\n").getBytes("UTF-8"));
                output.flush();
                output.getFD().sync();
            }
            Log.i(TAG, event);
        } catch (Exception error) {
            Log.e(TAG, "Could not write diagnostic event", error);
        }
    }

    public static synchronized void installCrashHandler(Context context) {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            record(app, "JAVA_UNCAUGHT thread=" + thread.getName() + " " + stack);
            if (previous != null) previous.uncaughtException(thread, error);
            else Process.killProcess(Process.myPid());
        });
    }


    /**
     * Reads Android's historical exit records for this app. On Android 11+
     * this can recover native-crash reason/description and the system-provided
     * tombstone trace after the game process has already died.
     */
    /** Returns the timestamp of the latest recorded crash exit, or zero. */
    public static long latestCrashTimestamp(Context context) {
        if (Build.VERSION.SDK_INT < 30) return 0L;
        try {
            Object manager = context.getSystemService(Context.ACTIVITY_SERVICE);
            Method history = manager.getClass().getMethod(
                    "getHistoricalProcessExitReasons", String.class, int.class, int.class);
            Object value = history.invoke(manager, context.getPackageName(), 0, 5);
            if (!(value instanceof List)) return 0L;
            for (Object exit : (List<?>) value) {
                Class<?> type = exit.getClass();
                int reason = ((Number) type.getMethod("getReason").invoke(exit)).intValue();
                // ActivityManager.ProcessExitInfo: REASON_CRASH=4, REASON_CRASH_NATIVE=5.
                if (reason == 4 || reason == 5) {
                    return ((Number) type.getMethod("getTimestamp").invoke(exit)).longValue();
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Could not inspect previous crash exit", error);
        }
        return 0L;
    }

    public static String readPreviousCrash(Context context) {
        StringBuilder result = new StringBuilder();
        if (Build.VERSION.SDK_INT < 30) {
            return "Historical process-exit reports require Android 11 or newer.\n";
        }
        try {
            Object manager = context.getSystemService(Context.ACTIVITY_SERVICE);
            Method history = manager.getClass().getMethod(
                    "getHistoricalProcessExitReasons", String.class, int.class, int.class);
            Object value = history.invoke(manager, context.getPackageName(), 0, 5);
            if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
                return "No previous process-exit records are available.\n";
            }
            for (Object exit : (List<?>) value) {
                Class<?> type = exit.getClass();
                result.append("Exit timestamp: ").append(type.getMethod("getTimestamp").invoke(exit)).append('\n');
                result.append("PID: ").append(type.getMethod("getPid").invoke(exit)).append('\n');
                result.append("Reason code: ").append(type.getMethod("getReason").invoke(exit)).append('\n');
                result.append("Status: ").append(type.getMethod("getStatus").invoke(exit)).append('\n');
                result.append("Description: ").append(type.getMethod("getDescription").invoke(exit)).append('\n');
                try {
                    InputStream trace = (InputStream) type.getMethod("getTraceInputStream").invoke(exit);
                    if (trace != null) {
                        result.append("--- System crash trace ---\n");
                        byte[] buffer = new byte[8192];
                        int total = 0;
                        try (InputStream input = trace) {
                            int count;
                            while (total < 128 * 1024 &&
                                    (count = input.read(buffer, 0, Math.min(buffer.length, 128 * 1024 - total))) > 0) {
                                result.append(new String(buffer, 0, count, "UTF-8"));
                                total += count;
                            }
                        }
                        result.append("\n--- End crash trace ---\n");
                    }
                } catch (Exception unavailable) {
                    result.append("Crash trace unavailable: ").append(unavailable.getClass().getSimpleName()).append('\n');
                }
                result.append('\n');
            }
        } catch (Exception error) {
            result.append("Could not read Android process-exit history: ")
                    .append(error.getClass().getSimpleName()).append(": ")
                    .append(error.getMessage()).append('\n');
        }
        return result.toString();
    }


    /** Export-time process snapshot. Thread stacks are explicitly not represented as crash-time stacks. */
    public static String readRuntimeSnapshot(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("=== Runtime snapshot at export time ===\n");
        out.append("Wall clock: ").append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date())).append('\n');
        out.append("Elapsed realtime ms: ").append(android.os.SystemClock.elapsedRealtime()).append('\n');
        out.append("PID: ").append(Process.myPid()).append(" UID: ").append(Process.myUid()).append('\n');
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            out.append("System memory available bytes: ").append(mi.availMem).append('\n');
            out.append("System memory total bytes: ").append(mi.totalMem).append('\n');
            out.append("System low-memory: ").append(mi.lowMemory).append('\n');
            android.os.Debug.MemoryInfo[] mem = am.getProcessMemoryInfo(new int[] { Process.myPid() });
            if (mem != null && mem.length > 0) {
                out.append("App PSS KB: ").append(mem[0].getTotalPss()).append('\n');
                out.append("App private dirty KB: ").append(mem[0].getTotalPrivateDirty()).append('\n');
                out.append("App shared dirty KB: ").append(mem[0].getTotalSharedDirty()).append('\n');
            }
        } catch (Exception error) {
            out.append("Memory snapshot unavailable: ").append(error.getClass().getSimpleName()).append(": ").append(error.getMessage()).append('\n');
        }
        Runtime runtime = Runtime.getRuntime();
        out.append("JVM max/total/free heap bytes: ").append(runtime.maxMemory()).append("/")
                .append(runtime.totalMemory()).append("/").append(runtime.freeMemory()).append('\n');
        try {
            File files = context.getFilesDir();
            out.append("App storage usable/total bytes: ").append(files.getUsableSpace()).append("/")
                    .append(files.getTotalSpace()).append('\n');
        } catch (Exception ignored) { }
        out.append("=== Java thread stacks at export time (not crash-time) ===\n");
        try {
            java.util.Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
            out.append("Thread count: ").append(stacks.size()).append('\n');
            for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
                Thread thread = entry.getKey();
                out.append("\nTHREAD ").append(thread.getName()).append(" id=").append(thread.getId())
                        .append(" state=").append(thread.getState()).append(" daemon=").append(thread.isDaemon()).append('\n');
                StackTraceElement[] frames = entry.getValue();
                int count = Math.min(frames.length, 80);
                for (int i = 0; i < count; i++) out.append("  at ").append(frames[i]).append('\n');
                if (frames.length > count) out.append("  ... ").append(frames.length - count).append(" more frames\n");
            }
        } catch (Exception error) {
            out.append("Thread snapshot failed: ").append(error.getClass().getSimpleName()).append('\n');
        }
        return out.toString();
    }

    /**
     * Return the native capture only when it belongs to Android's newest
     * recorded crash process. A file left by an earlier run must never be
     * presented as evidence for a newer process exit.
     */
    public static String readNativeCrashCapture(Context context) {
        long expectedPid = 0L;
        long expectedTimestamp = 0L;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Object manager = context.getSystemService(Context.ACTIVITY_SERVICE);
                Method history = manager.getClass().getMethod(
                        "getHistoricalProcessExitReasons", String.class, int.class, int.class);
                Object value = history.invoke(manager, context.getPackageName(), 0, 10);
                if (value instanceof List) {
                    for (Object exit : (List<?>) value) {
                        Class<?> type = exit.getClass();
                        int reason = ((Number) type.getMethod("getReason").invoke(exit)).intValue();
                        int status = ((Number) type.getMethod("getStatus").invoke(exit)).intValue();
                        // Quest native crashes may be reported by Android as
                        // REASON_EXIT_SELF (1) with a nonzero signal-style status
                        // after our fatal-signal handler re-raises the signal.
                        // Include any nonzero exit status, then require an exact
                        // PID match below so ordinary/stale captures are rejected.
                        if (reason == 2 || reason == 4 || reason == 5 || status != 0) {
                            expectedPid = ((Number) type.getMethod("getPid").invoke(exit)).longValue();
                            expectedTimestamp = ((Number) type.getMethod("getTimestamp").invoke(exit)).longValue();
                            break;
                        }
                    }
                }
            } catch (Exception error) {
                return "Could not match native capture to Android crash history: "
                        + error.getClass().getSimpleName() + ": " + error.getMessage() + "\\n";
            }
        }
        if (expectedPid == 0L) return "No matching Android crash process is available. Capture not attached.\\n";

        File capture = new File(context.getFilesDir(), "native-crash.txt");
        if (!capture.isFile()) return "No native capture file exists for newest crash PID "
                + expectedPid + " (timestamp " + expectedTimestamp + ").\\n";
        try {
            byte[] bytes = new byte[(int)Math.min(capture.length(), 64 * 1024)];
            int length = 0;
            try (java.io.FileInputStream input = new java.io.FileInputStream(capture)) {
                while (length < bytes.length) {
                    int count = input.read(bytes, length, bytes.length - length);
                    if (count < 0) break;
                    length += count;
                }
            }
            String contents = new String(bytes, 0, length, "UTF-8");
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?m)^pid=0x([0-9a-fA-F]+)\\s*$").matcher(contents);
            if (!matcher.find()) return "Native capture has no readable PID; not attached to avoid misattribution.\\n";
            long capturedPid = Long.parseLong(matcher.group(1), 16);
            if (capturedPid != expectedPid) {
                return "STALE NATIVE CAPTURE NOT ATTACHED: capture PID " + capturedPid
                        + " does not match newest Android crash PID " + expectedPid
                        + " (timestamp " + expectedTimestamp + ").\\n";
            }
            return "Matched native capture PID " + capturedPid + " to newest Android crash exit.\\n" + contents;
        } catch (Exception error) {
            return "Could not validate native capture: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage() + "\\n";
        }
    }

    public static String readEvents(Context context) {
        StringBuilder result = new StringBuilder();
        File directory = new File(context.getFilesDir(), "diagnostics");
        appendFile(result, new File(directory, "events.previous.log"));
        appendFile(result, new File(directory, "events.log"));
        if (result.length() == 0) result.append("(No app diagnostic events recorded.)\n");
        return result.toString();
    }

    private static void appendFile(StringBuilder target, File file) {
        if (!file.isFile()) return;
        try {
            byte[] bytes = new byte[(int)Math.min(file.length(), MAX_LOG_BYTES)];
            try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
                target.append(new String(bytes, 0, offset, "UTF-8"));
            }
        } catch (Exception error) {
            target.append("(Could not read ").append(file.getName()).append(": ")
                    .append(error.getClass().getSimpleName()).append(")\n");
        }
    }
}
