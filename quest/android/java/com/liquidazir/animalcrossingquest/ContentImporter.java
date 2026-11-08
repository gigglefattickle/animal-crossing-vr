package com.liquidazir.animalcrossingquest;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Imports safe DDS-only resource packs into the native texture_pack scanner. */
final class ContentImporter {
    private static final long MAX_ENTRY_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 10000;

    private ContentImporter() {}

    static String importContent(Context context, Uri uri, boolean modPackage) throws Exception {
        String name = displayName(context, uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if (modPackage && !lower.endsWith(".zip"))
            throw new IllegalArgumentException("Mod packages must be ZIP files containing supported DDS texture replacements.");
        if (!modPackage && lower.endsWith(".dds")) {
            if (!isTextureName(new File(name).getName()))
                throw new IllegalArgumentException("Texture filename must use Dolphin's tex1_...dds naming format.");
            File folder = createPackFolder(context, false, name);
            File destination = new File(folder, new File(name).getName());
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(destination)) {
                if (input == null) throw new java.io.IOException("Could not open selected file.");
                copyLimited(input, output, MAX_ENTRY_BYTES);
            } catch (Exception error) {
                deleteTree(folder);
                throw error;
            }
            try {
                validateDds(destination);
            } catch (Exception error) {
                deleteTree(folder);
                throw error;
            }
            return "1 texture file (" + destination.getName() + ")";
        }
        if (!lower.endsWith(".zip"))
            throw new IllegalArgumentException(modPackage ? "Choose a .zip mod package." : "Choose a .dds texture or .zip texture pack.");

        InputStream source = context.getContentResolver().openInputStream(uri);
        if (source == null) throw new java.io.IOException("Could not open selected ZIP.");
        File folder = createPackFolder(context, modPackage, name);
        int imported = 0;
        long total = 0;
        Set<String> seen = new HashSet<>();
        try (InputStream raw = source;
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            ZipEntry entry;
            int count = 0;
            byte[] buffer = new byte[32768];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new IllegalArgumentException("ZIP contains too many entries.");
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                String path = safeRelativePath(entry.getName());
                if (path == null) { zip.closeEntry(); continue; }
                if (!path.toLowerCase(Locale.ROOT).endsWith(".dds")) { zip.closeEntry(); continue; }
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                if (!isTextureName(fileName)) { zip.closeEntry(); continue; }
                if (!seen.add(path.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("ZIP contains duplicate texture paths.");
                File outputFile = new File(folder, path);
                File parent = outputFile.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Could not create texture folder.");
                long entryBytes = 0;
                try (FileOutputStream output = new FileOutputStream(outputFile)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        entryBytes += read;
                        total += read;
                        if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES)
                            throw new IllegalArgumentException("Texture pack exceeds the import size limit.");
                        output.write(buffer, 0, read);
                    }
                    output.flush();
                    output.getFD().sync();
                }
                validateDds(outputFile);
                imported++;
                zip.closeEntry();
            }
        } catch (Exception error) {
            deleteTree(folder);
            throw error;
        }
        if (imported == 0) {
            deleteTree(folder);
            throw new IllegalArgumentException("No supported texture files were found. DDS files must use Dolphin's tex1_...dds naming format.");
        }
        return imported + (modPackage ? " mod texture replacements" : " texture replacements") + " imported";
    }

    private static File createPackFolder(Context context, boolean mod, String sourceName) throws Exception {
        String base = sourceName.replaceAll("(?i)\\.(zip|dds)$", "");
        base = base.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "");
        if (base.length() == 0) base = mod ? "imported_mod" : "imported_pack";
        if (base.length() > 48) base = base.substring(0, 48);
        File root = new File(context.getFilesDir(), mod ? "texture_pack/mods" : "texture_pack/imported");
        if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("Could not create texture storage.");
        File folder = new File(root, base);
        int suffix = 2;
        while (folder.exists()) folder = new File(root, base + "_" + suffix++);
        if (!folder.mkdirs()) throw new java.io.IOException("Could not create import folder.");
        return folder;
    }

    private static String safeRelativePath(String value) {
        if (value == null || value.length() == 0 || value.length() > 240) return null;
        String path = value.replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) return null;
        String[] parts = path.split("/");
        StringBuilder clean = new StringBuilder();
        for (String part : parts) {
            if (part.length() == 0 || part.equals(".") || part.equals("..")) return null;
            if (part.indexOf(':') >= 0 || part.indexOf(0) >= 0) return null;
            if (clean.length() > 0) clean.append('/');
            clean.append(part);
        }
        return clean.toString();
    }

    private static boolean isTextureName(String name) {
        return name.matches("(?i)^tex1_[1-9][0-9]*x[1-9][0-9]*_[0-9a-f]{1,16}(_([0-9a-f]{1,16}|\\$))?_[0-9]+\\.dds$");
    }

    private static void validateDds(File file) throws Exception {
        if (file.length() < 128 || file.length() > MAX_ENTRY_BYTES)
            throw new IllegalArgumentException("Invalid DDS file size: " + file.getName());
        byte[] header = new byte[128];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read(header) != header.length) throw new IllegalArgumentException("Truncated DDS file.");
        }
        if (header[0] != 'D' || header[1] != 'D' || header[2] != 'S' || header[3] != ' ')
            throw new IllegalArgumentException("Invalid DDS header: " + file.getName());
        int height = littleInt(header, 12);
        int width = littleInt(header, 16);
        if (width < 1 || height < 1 || width > 16384 || height > 16384)
            throw new IllegalArgumentException("DDS dimensions are invalid: " + file.getName());
    }

    private static int littleInt(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset + 1] & 255) << 8) |
               ((data[offset + 2] & 255) << 16) | ((data[offset + 3] & 255) << 24);
    }

    private static long copyLimited(InputStream input, FileOutputStream output, long limit) throws Exception {
        byte[] buffer = new byte[32768];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IllegalArgumentException("File exceeds the import size limit.");
            output.write(buffer, 0, read);
        }
        output.flush();
        output.getFD().sync();
        return total;
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception ignored) {}
        return "import.zip";
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
