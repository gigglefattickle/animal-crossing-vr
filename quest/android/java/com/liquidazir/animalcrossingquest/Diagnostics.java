package com.liquidazir.animalcrossingquest;

import android.content.Context;
import android.os.Process;
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
    private static final long MAX_LOG_BYTES = 128 * 1024;
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
