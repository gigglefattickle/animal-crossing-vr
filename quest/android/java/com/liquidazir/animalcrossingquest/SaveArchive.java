package com.liquidazir.animalcrossingquest;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable archive for both emulated GameCube memory-card slots. */
final class SaveArchive {
    private static final long MAX_FILE = 64L * 1024L * 1024L;
    private static final long MAX_TOTAL = 256L * 1024L * 1024L;
    private SaveArchive() {}

    static String exportZip(Context context, Uri destination) throws Exception {
        OutputStream raw = context.getContentResolver().openOutputStream(destination, "w");
        if (raw == null) throw new IOException("Could not open the selected export location.");
        int count = 0;
        byte[] buffer = new byte[32768];
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
            for (String slot : new String[]{"card_a", "card_b"}) {
                File folder = new File(context.getFilesDir(), "save/" + slot);
                File[] files = folder.listFiles();
                if (files == null) continue;
                for (File file : files) {
                    if (!file.isFile() || !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".gci")) continue;
                    if (file.length() > MAX_FILE) throw new IOException("Save file exceeds the export size limit.");
                    zip.putNextEntry(new ZipEntry(slot + "/" + file.getName()));
                    try (InputStream in = new FileInputStream(file)) {
                        int n; while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                    }
                    zip.closeEntry(); count++;
                }
            }
            zip.finish();
        }
        if (count == 0) return "No GCI saves were found. An empty save archive was exported.";
        return count + " memory-card save file(s) exported.";
    }

    static String importZip(Context context, Uri source) throws Exception {
        InputStream raw = context.getContentResolver().openInputStream(source);
        if (raw == null) throw new IOException("Could not open the selected save archive.");
        File root = new File(context.getFilesDir(), "save");
        File staging = new File(root, "import_staging");
        File backup = new File(root, "import_backup");
        deleteTree(staging); deleteTree(backup);
        File a = new File(staging, "card_a"), b = new File(staging, "card_b");
        if (!a.mkdirs() || !b.mkdirs()) throw new IOException("Could not prepare save import folders.");
        int count = 0; long total = 0; Set<String> seen = new HashSet<>();
        byte[] buffer = new byte[32768];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                String name = entry.getName().replace('\\','/');
                if (name.startsWith("/") || name.contains("..") || name.indexOf(':') >= 0)
                    throw new IOException("Unsafe path in save archive.");
                String[] parts = name.split("/");
                if (parts.length != 2 || !(parts[0].equals("card_a") || parts[0].equals("card_b")) ||
                    !parts[1].toLowerCase(java.util.Locale.ROOT).endsWith(".gci") ||
                    parts[1].length() > 120 || parts[1].equals(".") || parts[1].equals(".."))
                    throw new IOException("Save archive entries must be card_a/*.gci or card_b/*.gci.");
                if (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) throw new IOException("Duplicate save entry.");
                File out = new File(staging, name);
                long bytes = 0;
                try (FileOutputStream file = new FileOutputStream(out)) {
                    int n; while ((n = zip.read(buffer)) != -1) {
                        bytes += n; total += n;
                        if (bytes > MAX_FILE || total > MAX_TOTAL) throw new IOException("Save archive exceeds the size limit.");
                        file.write(buffer, 0, n);
                    }
                    file.getFD().sync();
                }
                if (bytes < 64) throw new IOException("A save file is too small to be a valid GCI.");
                count++; zip.closeEntry();
            }
        } catch (Exception error) { deleteTree(staging); throw error; }
        if (count == 0) { deleteTree(staging); throw new IOException("No GCI saves were found in the archive."); }

        File liveA = new File(root, "card_a"), liveB = new File(root, "card_b");
        File backupA = new File(backup, "card_a"), backupB = new File(backup, "card_b");
        if (!backup.mkdirs()) { deleteTree(staging); throw new IOException("Could not prepare save backup."); }
        boolean oldA = liveA.exists() && liveA.renameTo(backupA);
        boolean oldB = liveB.exists() && liveB.renameTo(backupB);
        boolean newA = new File(staging, "card_a").renameTo(liveA);
        boolean newB = new File(staging, "card_b").renameTo(liveB);
        if (!newA || !newB) {
            deleteTree(liveA); deleteTree(liveB);
            if (oldA) backupA.renameTo(liveA);
            if (oldB) backupB.renameTo(liveB);
            deleteTree(staging); deleteTree(backup);
            throw new IOException("Could not safely install imported saves; previous saves were restored where possible.");
        }
        deleteTree(staging); deleteTree(backup);
        return count + " memory-card save file(s) imported. Restart the game to load them.";
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
