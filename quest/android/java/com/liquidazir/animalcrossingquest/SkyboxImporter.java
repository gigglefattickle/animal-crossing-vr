package com.liquidazir.animalcrossingquest;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Imports only the Wild World sky resource directory from a user-supplied asset ZIP. */
final class SkyboxImporter {
    private static final int MAX_ENTRIES = 64;
    private static final long MAX_FILE_BYTES = 128 * 1024;
    private static final long MAX_TOTAL_BYTES = 2 * 1024 * 1024;

    private SkyboxImporter() {}

    static String importZip(Context context, Uri uri) throws Exception {
        File root = context.getFilesDir();
        File staging = new File(root, "acww_sky.importing");
        File target = new File(root, "acww_sky");
        File backup = new File(root, "acww_sky.previous");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new java.io.IOException("Could not create sky import folder.");

        Set<String> seen = new HashSet<>();
        long total = 0;
        int imported = 0;
        InputStream source = context.getContentResolver().openInputStream(uri);
        if (source == null) throw new java.io.IOException("Could not open the selected sky ZIP.");
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            byte[] buffer = new byte[16384];
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IllegalArgumentException("Sky ZIP contains too many entries.");
                if (entry.isDirectory()) { zip.closeEntry(); continue; }
                String name = entry.getName().replace('\\', '/');
                if (name.equals("IMPORT-INFO.txt")) { zip.closeEntry(); continue; }
                if (!name.startsWith("sky/") || name.length() <= 4 ||
                    name.indexOf("..") >= 0 || name.startsWith("/") || name.indexOf(':') >= 0 ||
                    name.substring(4).indexOf('/') >= 0 ||
                    !name.matches("sky/[A-Za-z0-9_.-]+\\.bin")) {
                    throw new IllegalArgumentException("ZIP must contain only files from the Wild World sky/ folder.");
                }
                if (!seen.add(name)) throw new IllegalArgumentException("Sky ZIP contains duplicate files.");
                File output = new File(staging, name);
                File parent = output.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs())
                    throw new java.io.IOException("Could not create sky asset folder.");
                long entryBytes = 0;
                try (FileOutputStream out = new FileOutputStream(output)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        entryBytes += read;
                        total += read;
                        if (entryBytes > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES)
                            throw new IllegalArgumentException("Sky ZIP exceeds the safe import size limit.");
                        out.write(buffer, 0, read);
                    }
                }
                if (entryBytes == 0) throw new IllegalArgumentException("Sky ZIP contains an empty asset.");
                imported++;
                zip.closeEntry();
            }
        } catch (Exception error) {
            deleteTree(staging);
            throw error;
        }

        String[] required = {
            "sky/d_2d_sky00_ncg.bin", "sky/d_2d_sky00_ncl.bin", "sky/d_2d_sky00_nsc.bin",
            "sky/d_2d_b_cld_bg_ncg.bin", "sky/d_2d_b_cld_f0_bg_ncl.bin",
            "sky/d_2d_b_cld_f0_bg_nsc.bin", "sky/d_2d_b_cld_c0_bg_nsc.bin",
            "sky/d_2d_b_cld_r0_bg_ncl.bin", "sky/d_2d_b_cld_r0_bg_nsc.bin",
            "sky/d_2d_star_ncg.bin", "sky/d_2d_star_ncl.bin", "sky/d_2d_star_nsc.bin"
        };
        for (String name : required) {
            File asset = new File(staging, name);
            if (!asset.isFile() || asset.length() == 0) {
                deleteTree(staging);
                throw new IllegalArgumentException("Sky ZIP is missing required asset: " + name);
            }
        }
        if (new File(staging, "sky/d_2d_sky00_ncg.bin").length() != 16384 ||
            new File(staging, "sky/d_2d_sky00_ncl.bin").length() != 32 ||
            new File(staging, "sky/d_2d_sky00_nsc.bin").length() != 1024 ||
            new File(staging, "sky/d_2d_b_cld_bg_ncg.bin").length() != 2048 ||
            new File(staging, "sky/d_2d_star_ncg.bin").length() != 512) {
            deleteTree(staging);
            throw new IllegalArgumentException("Sky ZIP contains incompatible Wild World graphics data.");
        }

        deleteTree(backup);
        if (target.exists() && !target.renameTo(backup)) {
            deleteTree(staging);
            throw new java.io.IOException("Could not preserve the previous sky assets.");
        }
        if (!staging.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target);
            deleteTree(staging);
            throw new java.io.IOException("Could not activate the imported sky assets.");
        }
        deleteTree(backup);
        return imported + " Wild World sky files";
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
