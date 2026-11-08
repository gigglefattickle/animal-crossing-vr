package com.liquidazir.animalcrossingquest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * First-run shell for the standalone Quest app.
 *
 * Android owns file selection and import. The native game only sees the
 * validated, app-private rom/ directory and never receives a content:// URI.
 */
public final class SetupActivity extends Activity {
    private static final int PICK_GAME = 4107;
    private static final int EXPORT_DIAGNOSTICS = 4108;
    private static final int IMPORT_TEXTURES = 4109;
    private static final int IMPORT_MOD = 4110;
    private static final int IMPORT_SKYBOX = 4111;
    private static final int IMPORT_SAVES = 4112;
    private static final int EXPORT_SAVES = 4113;
    private static final String TAG = "ACQuestSetup";
    private TextView status;
    private Button playButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Diagnostics.installCrashHandler(this);
        Diagnostics.record(this, "launcher.onCreate");
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshState();
    }

    private File romDirectory() {
        return new File(getFilesDir(), "rom");
    }

    private File importedGame() {
        File dir = romDirectory();
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (file.isFile() && (name.endsWith(".iso") || name.endsWith(".gcm") ||
                    name.endsWith(".ciso") || name.endsWith(".rvz"))) return file;
        }
        return null;
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(28, 24, 28, 24);
        root.setBackgroundColor(android.graphics.Color.rgb(18, 20, 25));

        TextView heading = new TextView(this);
        heading.setText("ANIMAL CROSSING");
        heading.setTextColor(android.graphics.Color.WHITE);
        heading.setTextSize(24);
        heading.setLetterSpacing(0.12f);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading, new LinearLayout.LayoutParams(-1, 64));

        TextView subtitle = new TextView(this);
        subtitle.setText("GAME LIBRARY");
        subtitle.setTextColor(android.graphics.Color.LTGRAY);
        subtitle.setTextSize(12);
        subtitle.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, 42);
        subtitleParams.topMargin = 14;
        root.addView(subtitle, subtitleParams);

        LinearLayout gameCard = new LinearLayout(this);
        gameCard.setOrientation(LinearLayout.VERTICAL);
        gameCard.setPadding(22, 22, 22, 22);
        gameCard.setBackground(makeCardBackground());
        ImageView cover = new ImageView(this);
        cover.setImageBitmap(loadLauncherCover());
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(-1, 220);
        coverParams.bottomMargin = 18;
        gameCard.addView(cover, coverParams);

        TextView gameTitle = new TextView(this);
        gameTitle.setText("Animal Crossing");
        gameTitle.setTextColor(android.graphics.Color.WHITE);
        gameTitle.setTextSize(22);
        gameCard.addView(gameTitle);
        status = new TextView(this);
        status.setTextColor(android.graphics.Color.LTGRAY);
        status.setTextSize(14);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 10;
        gameCard.addView(status, statusParams);

        playButton = new Button(this);
        playButton.setText("PLAY");
        playButton.setOnClickListener(v -> launchGame());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(-1, -2);
        playParams.topMargin = 22;
        gameCard.addView(playButton, playParams);
        root.addView(gameCard, new LinearLayout.LayoutParams(-1, -2));

        Button addGame = new Button(this);
        addGame.setText("Add / Change Game File");
        addGame.setOnClickListener(v -> chooseGame());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = 18;
        root.addView(addGame, actionParams);

        Button options = new Button(this);
        options.setText("OPTIONS");
        options.setOnClickListener(v -> showOptions());
        root.addView(options, actionParams);

        setContentView(root);
        refreshState();
    }

    private android.graphics.Bitmap loadLauncherCover() {
        try (InputStream input = getAssets().open("launcher-cover.jpg.b64");
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            byte[] jpeg = Base64.decode(bytes.toByteArray(), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        } catch (Exception error) {
            Log.w(TAG, "Launcher cover artwork unavailable", error);
            return null;
        }
    }

    private android.graphics.drawable.Drawable makeCardBackground() {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(android.graphics.Color.rgb(31, 35, 43));
        shape.setCornerRadius(22.0f);
        shape.setStroke(1, android.graphics.Color.rgb(65, 72, 84));
        return shape;
    }

    private void showOptions() {
        String[] options = {"VR / Graphics Settings", "Controller Binding Settings", "Import Wild World Sky ZIP",
                "Import Textures", "Import Mod ZIP", "Load Save Archive", "Export Save Archive", "Export Diagnostics"};
        new AlertDialog.Builder(this).setTitle("Options")
            .setItems(options, (dialog, which) -> {
                if (which == 0) showGraphicsSettings();
                else if (which == 1) showControllerBindings();
                else if (which == 2) chooseSkybox();
                else if (which == 3) chooseContent(IMPORT_TEXTURES, "Import texture files or a ZIP texture pack");
                else if (which == 4) chooseContent(IMPORT_MOD, "Import a ZIP mod package");
                else if (which == 5) chooseSaveArchive();
                else if (which == 6) exportSaveArchive();
                else if (which == 7) exportDiagnostics();
            }).show();
    }

    private void showResult(String message, boolean success) {
        if (status != null) status.setText((success ? "✓ " : "✕ ") + message);
        Toast.makeText(this, (success ? "✓ " : "✕ ") + message, Toast.LENGTH_LONG).show();
    }

    private void refreshState() {
        if (status == null || playButton == null) return;
        File game = importedGame();
        if (game == null) {
            status.setText("Select your own Animal Crossing USA disc image to import it onto this headset.");
            playButton.setEnabled(false);
        } else {
            status.setText("Game imported: " + game.getName() + "\nReady to play.");
            playButton.setEnabled(true);
        }
    }

    private void exportDiagnostics() {
        Diagnostics.record(this, "launcher.export_diagnostics_requested");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "AnimalCrossingQuest-diagnostics.txt");
        startActivityForResult(intent, EXPORT_DIAGNOSTICS);
    }

    private void writeDiagnostics(Uri destination) {
        try (OutputStream output = getContentResolver().openOutputStream(destination);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, "UTF-8"))) {
            if (output == null) throw new java.io.IOException("Could not open the selected export location.");
            writer.write("Animal Crossing Quest - Diagnostics");
            writer.newLine();
            writer.write("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
            writer.newLine();
            writer.write("Package: " + getPackageName());
            writer.newLine();
            writer.write("App version: " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName
                    + " (" + getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode() + ")");
            writer.newLine();
            writer.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
            writer.newLine();
            writer.write("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            writer.newLine();
            writer.write("ABIs: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
            writer.newLine();
            writer.write("App storage free bytes: " + getFilesDir().getUsableSpace());
            writer.newLine();
            File game = importedGame();
            writer.write("Imported game: " + (game == null ? "not found" :
                    game.getName() + ", " + game.length() + " bytes"));
            writer.newLine();
            writer.newLine();
            writer.write("App event log (most recent activity first in chronological order):");
            writer.newLine();
            writer.write(Diagnostics.readEvents(this));
            writer.newLine();
            writer.write("Note: This export contains app/device metadata and app-generated breadcrumbs only. " +
                    "It does not include the disc image, save files, headset account details, or full Android logcat. " +
                    "A native process crash may require an additional Quest/ADB logcat capture.");
            writer.newLine();
            writer.flush();
            Diagnostics.record(this, "launcher.diagnostics_exported");
        } catch (Exception error) {
            Log.e(TAG, "Diagnostics export failed", error);
            Diagnostics.record(this, "launcher.diagnostics_export_failed " + Log.getStackTraceString(error));
            if (status != null) status.setText("Diagnostics export failed: " + error.getMessage());
        }
    }

    private void chooseSkybox() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] { "application/zip", "application/x-zip-compressed", "application/octet-stream" });
        startActivityForResult(intent, IMPORT_SKYBOX);
    }

    private void chooseContent(int requestCode, String title) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, title);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/zip", "application/x-zip-compressed", "application/octet-stream",
                "image/vnd-ms.dds"
        });
        startActivityForResult(intent, requestCode);
    }

    private File settingsFile() { return new File(getFilesDir(), "settings.ini"); }

    private void showControllerBindings() {
        File file = settingsFile();
        String ini;
        try {
            if (file.isFile()) ini = new String(Files.readAllBytes(file.toPath()), "UTF-8");
            else try (InputStream defaults = getAssets().open("quest-settings.ini")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = defaults.read(buffer)) != -1) bytes.write(buffer, 0, count);
                ini = bytes.toString("UTF-8");
            }
        } catch (Exception error) {
            showResult("Could not read controller settings: " + error.getMessage(), false);
            return;
        }
        final String baseIni = ini;
        final String[] digitalLabels = {"A button", "B button", "X button", "Y button",
                "Left stick click", "Right stick click", "Left trigger click", "Right trigger click",
                "Left grip click", "Right grip click"};
        final String[] digitalPaths = {
                "/user/hand/right/input/a/click", "/user/hand/right/input/b/click",
                "/user/hand/left/input/x/click", "/user/hand/left/input/y/click",
                "/user/hand/left/input/thumbstick/click", "/user/hand/right/input/thumbstick/click",
                "/user/hand/left/input/trigger/click", "/user/hand/right/input/trigger/click",
                "/user/hand/left/input/squeeze/click", "/user/hand/right/input/squeeze/click"};
        final String[] stickLabels = {"Left thumbstick", "Right thumbstick"};
        final String[] stickPaths = {"/user/hand/left/input/thumbstick", "/user/hand/right/input/thumbstick"};
        final String[] analogLabels = {"Left trigger", "Right trigger", "Left grip", "Right grip"};
        final String[] analogPaths = {"/user/hand/left/input/trigger/value", "/user/hand/right/input/trigger/value",
                "/user/hand/left/input/squeeze/value", "/user/hand/right/input/squeeze/value"};
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(28, 8, 28, 8);
        java.util.LinkedHashMap<String, Spinner> controls = new java.util.LinkedHashMap<>();
        String[] digitalKeys = {"bind_a", "bind_b", "bind_x", "bind_y", "bind_z", "bind_start"};
        String[] digitalNames = {"Confirm / use tool", "Cancel / run", "X action", "Y action", "Z action", "Start menu"};
        for (int i = 0; i < digitalKeys.length; i++) {
            Spinner spinner = makeSpinner(digitalLabels, findPathIndex(baseIni, digitalKeys[i], digitalPaths, digitalPaths[ i == 0 ? 0 : i == 1 ? 1 : i == 2 ? 2 : i == 3 ? 3 : i == 4 ? 5 : 4 ]));
            controls.put(digitalKeys[i], spinner); addSetting(panel, digitalNames[i], spinner);
        }
        Spinner move = makeSpinner(stickLabels, findPathIndex(baseIni, "bind_move", stickPaths, stickPaths[0]));
        Spinner camera = makeSpinner(stickLabels, findPathIndex(baseIni, "bind_camera", stickPaths, stickPaths[1]));
        controls.put("bind_move", move); controls.put("bind_camera", camera);
        addSetting(panel, "Move", move); addSetting(panel, "Turn camera", camera);
        String[] analogKeys = {"bind_left_grip", "bind_right_grip", "bind_left_trigger", "bind_right_trigger"};
        String[] analogNames = {"Left grip action", "Right grip action", "Left trigger action", "Right trigger action"};
        for (int i = 0; i < analogKeys.length; i++) {
            Spinner spinner = makeSpinner(analogLabels, findPathIndex(baseIni, analogKeys[i], analogPaths, analogPaths[i]));
            controls.put(analogKeys[i], spinner); addSetting(panel, analogNames[i], spinner);
        }
        TextView note = new TextView(this);
        note.setText("Choose any supported Touch-controller input for each action. Changes are saved to this headset and apply after restarting the game. Avoid assigning the same button to multiple actions.");
        note.setTextSize(12); note.setPadding(0, 14, 0, 4);
        panel.addView(note);
        new AlertDialog.Builder(this).setTitle("Controller Bindings")
            .setView(new android.widget.ScrollView(this) {{ addView(panel); }})
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Restore Defaults", (dialog, which) -> {
                try { saveControllerBindings(file, baseIni, null); showResult("Default controller bindings restored. Restart the game to apply them.", true); }
                catch (Exception error) { showResult("Could not restore bindings: " + error.getMessage(), false); }
            })
            .setPositiveButton("Save", (dialog, which) -> {
                try {
                    String updated = baseIni;
                    for (java.util.Map.Entry<String, Spinner> entry : controls.entrySet()) {
                        String[] values;
                        if (entry.getKey().equals("bind_move") || entry.getKey().equals("bind_camera")) values = stickPaths;
                        else if (entry.getKey().contains("grip") || entry.getKey().contains("trigger")) values = analogPaths;
                        else values = digitalPaths;
                        updated = setTextSetting(updated, entry.getKey(), values[entry.getValue().getSelectedItemPosition()]);
                    }
                    writeSettings(file, updated);
                    showResult("Controller bindings saved. Restart the game to apply them.", true);
                } catch (Exception error) { showResult("Could not save controller bindings: " + error.getMessage(), false); }
            }).show();
    }

    private int findPathIndex(String ini, String key, String[] paths, String fallback) {
        String value = readTextSetting(ini, key, fallback);
        for (int i = 0; i < paths.length; i++) if (paths[i].equals(value)) return i;
        for (int i = 0; i < paths.length; i++) if (paths[i].equals(fallback)) return i;
        return 0;
    }

    private String readTextSetting(String ini, String key, String fallback) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\s*" +
                java.util.regex.Pattern.quote(key) + "\\s*=\\s*(\\S+)").matcher(ini);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private String setTextSetting(String ini, String key, String value) {
        String line = key + " = " + value;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?m)^\\s*" +
                java.util.regex.Pattern.quote(key) + "\\s*=\\s*\\S+\\s*$");
        if (pattern.matcher(ini).find()) return pattern.matcher(ini).replaceFirst(java.util.regex.Matcher.quoteReplacement(line));
        return ini + (ini.endsWith("\n") || ini.length() == 0 ? "" : "\n") + line + "\n";
    }

    private void writeSettings(File file, String updated) throws Exception {
        File temp = new File(file.getParentFile(), "settings.ini.tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(updated.getBytes("UTF-8")); output.getFD().sync();
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void saveControllerBindings(File file, String ini, String ignored) throws Exception {
        String updated = ini;
        String[] keys = {"bind_a","bind_b","bind_x","bind_y","bind_z","bind_start","bind_move","bind_camera",
                "bind_left_grip","bind_right_grip","bind_left_trigger","bind_right_trigger"};
        for (String key : keys) updated = updated.replaceAll("(?m)^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*=.*\\n?", "");
        writeSettings(file, updated);
    }

    private void showGraphicsSettings() {
        File file = settingsFile();
        String ini = "";
        try {
            if (file.isFile()) ini = new String(Files.readAllBytes(file.toPath()), "UTF-8");
            else try (InputStream defaults = getAssets().open("quest-settings.ini")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = defaults.read(buffer)) != -1) bytes.write(buffer, 0, count);
                ini = bytes.toString("UTF-8");
            }
        } catch (Exception error) {
            status.setText("Could not read graphics settings: " + error.getMessage());
            return;
        }
        final String baseIni = ini;
        String[] aaOptions = {"Off (fastest)", "2x", "4x"};
        String[] preloadOptions = {"On demand (recommended)", "Preload at startup", "Preload + cache"};
        String[] drawOptions = {"Whole town", "1 acre", "2 acres", "3 acres", "4 acres", "5 acres"};
        Spinner aa = makeSpinner(aaOptions, readSetting(ini, "msaa", 0) == 2 ? 1 : readSetting(ini, "msaa", 0) == 4 ? 2 : 0);
        Spinner preload = makeSpinner(preloadOptions, Math.max(0, Math.min(2, readSetting(ini, "preload_textures", 0))));
        Spinner draw = makeSpinner(drawOptions, Math.max(0, Math.min(5, readSetting(ini, "vr_draw_radius", 0))));
        String[] heightOptions = {"Standard view height", "Match villager model height"};
        Spinner playerHeight = makeSpinner(heightOptions, readSetting(ini, "fp_eye_height", 52) >= 60 ? 1 : 0);
        android.widget.CheckBox filtering = new android.widget.CheckBox(this);
        filtering.setText("Use game texture filtering");
        filtering.setChecked(readSetting(ini, "texture_filtering", 1) != 0);
        android.widget.CheckBox skybox = new android.widget.CheckBox(this);
        skybox.setText("Enhanced sky and clouds");
        skybox.setChecked(readSetting(ini, "skybox", 1) != 0);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(32, 12, 32, 8);
        addSetting(panel, "Anti-aliasing", aa);
        addSetting(panel, "HD texture loading", preload);
        addSetting(panel, "VR terrain draw distance", draw);
        addSetting(panel, "VR player viewpoint", playerHeight);
        panel.addView(filtering);
        panel.addView(skybox);
        new AlertDialog.Builder(this).setTitle("Graphics Settings")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (dialog, which) -> {
                String updated = setSetting(baseIni, "msaa", new int[]{0,2,4}[aa.getSelectedItemPosition()]);
                updated = setSetting(updated, "preload_textures", preload.getSelectedItemPosition());
                updated = setSetting(updated, "vr_draw_radius", draw.getSelectedItemPosition());
                updated = setSetting(updated, "fp_eye_height", playerHeight.getSelectedItemPosition() == 1 ? 64 : 52);
                updated = setSetting(updated, "texture_filtering", filtering.isChecked() ? 1 : 0);
                updated = setSetting(updated, "skybox", skybox.isChecked() ? 1 : 0);
                try {
                    File temp = new File(file.getParentFile(), "settings.ini.tmp");
                    try (FileOutputStream output = new FileOutputStream(temp)) {
                        output.write(updated.getBytes("UTF-8"));
                        output.getFD().sync();
                    }
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    status.setText("Graphics settings saved. Restart the game to apply them.");
                } catch (Exception error) {
                    status.setText("Could not save graphics settings: " + error.getMessage());
                }
            }).show();
    }

    private Spinner makeSpinner(String[] values, int selected) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setSelection(selected);
        return spinner;
    }

    private void addSetting(LinearLayout panel, String label, View control) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(16);
        title.setPadding(0, 12, 0, 2);
        panel.addView(title);
        panel.addView(control);
    }

    private int readSetting(String ini, String key, int fallback) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*=\\s*(-?\\d+)").matcher(ini);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
    }

    private String setSetting(String ini, String key, int value) {
        String line = key + " = " + value;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?m)^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*=\\s*-?\\d+\\s*$");
        if (pattern.matcher(ini).find()) return pattern.matcher(ini).replaceFirst(java.util.regex.Matcher.quoteReplacement(line));
        return ini + (ini.endsWith("\n") || ini.length() == 0 ? "" : "\n") + line + "\n";
    }

    private void chooseGame() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/octet-stream", "application/x-iso9660-image"
        });
        startActivityForResult(intent, PICK_GAME);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMPORT_SKYBOX) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    String imported = SkyboxImporter.importZip(this, data.getData());
                    showResult("Imported " + imported + ". Restart the game to activate the Wild World sky.", true);
                    Diagnostics.record(this, "launcher.wild_world_sky_imported");
                } catch (Exception error) {
                    Log.e(TAG, "Wild World sky import failed", error);
                    showResult("Sky import failed: " + error.getMessage(), false);
                }
            }
            return;
        }
        if (requestCode == IMPORT_TEXTURES || requestCode == IMPORT_MOD) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    String name = ContentImporter.importContent(this, data.getData(), requestCode == IMPORT_MOD);
                    showResult("Imported: " + name + ". Restart the game to load the new content.", true);
                } catch (Exception error) {
                    Log.e(TAG, "Content import failed", error);
                    showResult("Import failed: " + error.getMessage(), false);
                }
            }
            return;
        }
        if (requestCode == IMPORT_SAVES) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    showResult(SaveArchive.importZip(this, data.getData()), true);
                    Diagnostics.record(this, "launcher.save_archive_imported");
                } catch (Exception error) {
                    Log.e(TAG, "Save archive import failed", error);
                    showResult("Save import failed: " + error.getMessage(), false);
                }
            }
            return;
        }
        if (requestCode == EXPORT_SAVES) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    showResult(SaveArchive.exportZip(this, data.getData()), true);
                    Diagnostics.record(this, "launcher.save_archive_exported");
                } catch (Exception error) {
                    Log.e(TAG, "Save archive export failed", error);
                    showResult("Save export failed: " + error.getMessage(), false);
                }
            }
            return;
        }
        if (requestCode == EXPORT_DIAGNOSTICS) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writeDiagnostics(data.getData());
            }
            return;
        }
        if (requestCode != PICK_GAME || resultCode != RESULT_OK || data == null || data.getData() == null) {
            refreshState();
            return;
        }
        try {
            importSelected(data.getData());
            showResult("Game imported and validated. Ready to play.", true);
        } catch (Exception error) {
            Log.e(TAG, "Game import failed", error);
            showResult("Import failed: " + error.getMessage() + ". Your previous game and saves were kept.", false);
        }
        refreshState();
    }

    private void chooseSaveArchive() {
        new AlertDialog.Builder(this)
            .setTitle("Replace memory-card saves?")
            .setMessage("Importing this archive replaces both memory-card slots. Export a backup first if you want to keep the current saves.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", (dialog, which) -> openSaveArchivePicker())
            .show();
    }

    private void openSaveArchivePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, IMPORT_SAVES);
    }

    private void exportSaveArchive() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "AnimalCrossing-Quest-Saves.zip");
        startActivityForResult(intent, EXPORT_SAVES);
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Exception ignored) { }
        return "AnimalCrossing.iso";
    }

    private void importSelected(Uri uri) throws Exception {
        String sourceName = displayName(uri);
        String lower = sourceName.toLowerCase(Locale.ROOT);
        String extension;
        if (lower.endsWith(".iso")) extension = ".iso";
        else if (lower.endsWith(".gcm")) extension = ".gcm";
        else if (lower.endsWith(".ciso")) extension = ".ciso";
        else if (lower.endsWith(".rvz")) extension = ".rvz";
        else throw new IllegalArgumentException("Choose a .rvz, .iso, .gcm, or .ciso GameCube image.");

        File dir = romDirectory();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Cannot create game storage.");
        File temporary = new File(dir, "game.importing");
        File destination = new File(dir, "AnimalCrossing" + extension);

        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            if (input == null) throw new java.io.IOException("The selected file could not be opened.");
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        } catch (Exception error) {
            temporary.delete();
            throw error;
        }

        if (temporary.length() < (extension.equals(".rvz") ? 0x124 : (extension.equals(".ciso") ? 0x8000 : 0x440))) {
            temporary.delete();
            throw new IllegalArgumentException("The selected file is too small to be a GameCube disc image.");
        }
        if (extension.equals(".rvz")) {
            byte[] containerMagic = new byte[4];
            byte[] discMagic = new byte[4];
            byte[] id = new byte[6];
            try (RandomAccessFile file = new RandomAccessFile(temporary, "r")) {
                file.readFully(containerMagic);
                file.seek(0x58);
                file.readFully(id);
                file.seek(0x74);
                file.readFully(discMagic);
            }
            if (containerMagic[0] != 'R' || containerMagic[1] != 'V' ||
                    containerMagic[2] != 'Z' || containerMagic[3] != 1 ||
                    discMagic[0] != (byte)0xc2 || discMagic[1] != 0x33 ||
                    discMagic[2] != (byte)0x9f || discMagic[3] != 0x3d) {
                temporary.delete();
                throw new IllegalArgumentException("The RVZ disc header is invalid.");
            }
            String discId = new String(id, "US-ASCII");
            if (!discId.startsWith("GAFE01")) {
                temporary.delete();
                throw new IllegalArgumentException("This build requires the Animal Crossing USA disc (GAFE01). Found " + discId + ".");
            }
        } else if (extension.equals(".ciso")) {
            byte[] magic = new byte[4];
            try (RandomAccessFile file = new RandomAccessFile(temporary, "r")) {
                file.readFully(magic);
            }
            if (magic[0] != 'C' || magic[1] != 'I' || magic[2] != 'S' || magic[3] != 'O') {
                temporary.delete();
                throw new IllegalArgumentException("The CISO header is invalid.");
            }
        } else {
            byte[] magic = new byte[4];
            byte[] id = new byte[6];
            try (RandomAccessFile file = new RandomAccessFile(temporary, "r")) {
                file.seek(0x1c);
                file.readFully(magic);
                file.seek(0);
                file.readFully(id);
            }
            if (magic[0] != (byte)0xc2 || magic[1] != 0x33 ||
                    magic[2] != (byte)0x9f || magic[3] != 0x3d) {
                temporary.delete();
                throw new IllegalArgumentException("This is not a valid GameCube disc image.");
            }
            String discId = new String(id, "US-ASCII");
            if (!discId.startsWith("GAFE01")) {
                temporary.delete();
                throw new IllegalArgumentException("This build requires the Animal Crossing USA disc (GAFE01). Found " + discId + ".");
            }
        }

        try {
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception moveError) {
            temporary.delete();
            throw new java.io.IOException("Could not finish installing the imported game.", moveError);
        }
        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File file : existing) {
                if (!file.equals(destination) && !file.equals(temporary) &&
                        (file.getName().endsWith(".iso") || file.getName().endsWith(".gcm") ||
                         file.getName().endsWith(".ciso") || file.getName().endsWith(".rvz"))) {
                    // Remove only the previous imported disc after the new one is in place.
                    file.delete();
                }
            }
        }
    }

    private void launchGame() {
        if (importedGame() == null) {
            refreshState();
            return;
        }
        Diagnostics.record(this, "launcher.play_pressed game=" + importedGame().getName());
        try {
            startActivity(new Intent(this, QuestActivity.class));
        } catch (RuntimeException error) {
            Diagnostics.record(this, "launcher.start_activity_failed " + Log.getStackTraceString(error));
            throw error;
        }
    }
}
