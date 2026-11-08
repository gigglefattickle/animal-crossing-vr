package com.liquidazir.animalcrossingquest;

import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import org.libsdl.app.SDLActivity;

public class QuestActivity extends SDLActivity {
    @Override protected String[] getLibraries() {
        return new String[] { "SDL2", "openxr_loader", "main" };
    }
    @Override protected String[] getArguments() {
        // Diagnostic launches can request timing without making every normal
        // play session pay for detailed per-draw profiling.
        if (getIntent() != null && getIntent().getBooleanExtra("quest_profile", false)) {
            return new String[] { "--vr", "--verbose", "--profile", "30" };
        }
        return new String[] { "--vr", "--verbose" };
    }
    @Override protected void onCreate(Bundle state) {
        Diagnostics.record(this, "quest_activity.onCreate_entered");
        try {
            File root = getFilesDir();
            if (root == null) throw new java.io.IOException("App storage unavailable");
            copyAsset("shaders/default.vert", new File(root, "shaders/default.vert"), true);
            copyAsset("shaders/default.frag", new File(root, "shaders/default.frag"), true);
            copyAsset("quest-settings.ini", new File(root, "settings.ini"), false);
        } catch (Exception error) {
            Log.e("ACQuest", "Preparing app storage", error);
        }
        super.onCreate(state);
        Diagnostics.record(this, "quest_activity.super_onCreate_returned");
    }
    // Called by the native OpenXR backend before action bindings are suggested.
    public String getControllerBinding(String key) {
        File file = new File(getFilesDir(), "settings.ini");
        if (!file.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                int equals = trimmed.indexOf('=');
                if (equals <= 0) continue;
                if (trimmed.substring(0, equals).trim().equals(key))
                    return trimmed.substring(equals + 1).trim();
            }
        } catch (Exception error) {
            Log.w("ACQuest", "Could not read controller binding " + key, error);
        }
        return null;
    }

    private void copyAsset(String source, File destination, boolean replace) throws Exception {
        if (!replace && destination.exists()) return;
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Cannot create " + parent);
        File temporary = new File(parent, destination.getName() + ".asset-tmp");
        try (InputStream input = getAssets().open(source); FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] bytes = new byte[16384];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            output.getFD().sync();
        }
        if (!temporary.renameTo(destination)) throw new java.io.IOException("Cannot install " + destination);
    }
}
