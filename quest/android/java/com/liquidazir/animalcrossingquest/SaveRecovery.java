package com.liquidazir.animalcrossingquest;

import android.content.Context;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/** Crash-recovery backups of the native GameCube memory-card files (not emulator RAM states). */
final class SaveRecovery {
    private static final int MAX_SNAPSHOTS = 5;
    private SaveRecovery() {}

    private static File root(Context context) {
        return new File(context.getFilesDir(), "recovery/snapshots");
    }

    static synchronized File createSnapshot(Context context, String reason) throws IOException {
        File save = new File(context.getFilesDir(), "save");
        File a = new File(save, "card_a"), b = new File(save, "card_b");
        if (!hasGci(a) && !hasGci(b)) return null;
        File parent = root(context);
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create recovery storage.");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        File staging = new File(parent, ".snapshot-" + stamp + ".tmp");
        File target = new File(parent, stamp);
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("Could not prepare recovery snapshot.");
        try {
            copyGciFiles(a, new File(staging, "card_a"));
            copyGciFiles(b, new File(staging, "card_b"));
            writeSynced(new File(staging, "snapshot.txt"),
                    "created=" + new Date().toString() + "\nreason=" + reason + "\n");
            if (!staging.renameTo(target)) throw new IOException("Could not finalize recovery snapshot.");
        } catch (Exception error) {
            deleteTree(staging);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not copy memory-card files.", error);
        }
        prune(parent);
        return target;
    }

    static File[] listSnapshots(Context context) {
        File parent = root(context);
        File[] files = parent.listFiles(file -> file.isDirectory() && !file.getName().startsWith("."));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files;
    }

    static String describe(File snapshot) {
        String reason = "";
        File info = new File(snapshot, "snapshot.txt");
        if (info.isFile()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(info))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("reason=")) reason = " • " + line.substring(7);
                }
            } catch (Exception ignored) { }
        }
        return snapshot.getName() + reason;
    }

    static synchronized void restoreSnapshot(Context context, File snapshot) throws IOException {
        File parent = root(context).getCanonicalFile();
        File selected = snapshot.getCanonicalFile();
        if (!selected.getParentFile().equals(parent) || !new File(selected, "snapshot.txt").isFile())
            throw new IOException("The selected recovery snapshot is invalid.");
        File save = new File(context.getFilesDir(), "save");
        File staging = new File(save, "recovery_restore_staging");
        File backup = new File(save, "recovery_restore_backup");
        deleteTree(staging);
        deleteTree(backup);
        if (!staging.mkdirs()) throw new IOException("Could not prepare restore staging.");
        try {
            copyGciFiles(new File(selected, "card_a"), new File(staging, "card_a"));
            copyGciFiles(new File(selected, "card_b"), new File(staging, "card_b"));
            if (!backup.mkdirs()) throw new IOException("Could not prepare restore safety backup.");
            File liveA = new File(save, "card_a"), liveB = new File(save, "card_b");
            File backupA = new File(backup, "card_a"), backupB = new File(backup, "card_b");
            boolean oldA = !liveA.exists() || liveA.renameTo(backupA);
            boolean oldB = !liveB.exists() || liveB.renameTo(backupB);
            if (!oldA || !oldB) throw new IOException("Could not move current memory cards into the safety backup.");
            boolean newA = new File(staging, "card_a").renameTo(liveA);
            boolean newB = new File(staging, "card_b").renameTo(liveB);
            if (!newA || !newB) {
                deleteTree(liveA); deleteTree(liveB);
                if (backupA.exists()) backupA.renameTo(liveA);
                if (backupB.exists()) backupB.renameTo(liveB);
                throw new IOException("Could not install the recovery snapshot; previous cards were restored.");
            }
            deleteTree(staging);
            // Keep the safety backup until the next successful snapshot/restore operation.
        } catch (Exception error) {
            deleteTree(staging);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Recovery restore failed.", error);
        }
    }

    private static boolean hasGci(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return false;
        for (File file : files) if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".gci")) return true;
        return false;
    }

    private static void copyGciFiles(File source, File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("Could not create " + destination.getName() + ".");
        File[] files = source.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[32768];
        for (File file : files) {
            if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".gci")) continue;
            File out = new File(destination, file.getName());
            try (InputStream in = new FileInputStream(file); FileOutputStream stream = new FileOutputStream(out)) {
                int count;
                while ((count = in.read(buffer)) != -1) stream.write(buffer, 0, count);
                stream.getFD().sync();
            }
        }
    }

    private static void writeSynced(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes("UTF-8"));
            output.getFD().sync();
        }
    }

    private static void prune(File parent) {
        File[] files = parent.listFiles(file -> file.isDirectory() && !file.getName().startsWith("."));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = MAX_SNAPSHOTS; i < files.length; i++) deleteTree(files[i]);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
