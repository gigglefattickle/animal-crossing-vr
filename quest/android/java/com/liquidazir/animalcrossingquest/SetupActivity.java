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
        root.setGravity(Gravity.CENTER);
        root.setPadding(36, 28, 36, 28);

        TextView heading = new TextView(this);
        heading.setText("ANIMAL CROSSING");
        heading.setTextSize(28);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextSize(18);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = 20;
        root.addView(status, statusParams);

        Button chooseButton = new Button(this);
        chooseButton.setText("Choose Game File");
        chooseButton.setOnClickListener(v -> chooseGame());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
        buttonParams.topMargin = 24;
        root.addView(chooseButton, buttonParams);

        playButton = new Button(this);
        playButton.setText("Play");
        playButton.setOnClickListener(v -> launchGame());
        root.addView(playButton, buttonParams);

        Button textureButton = new Button(this);
        textureButton.setText("Import Textures");
        textureButton.setOnClickListener(v -> chooseContent(IMPORT_TEXTURES, "Import texture files or a ZIP texture pack"));
        root.addView(textureButton, buttonParams);

        Button modButton = new Button(this);
        modButton.setText("Import Mod ZIP");
        modButton.setOnClickListener(v -> chooseContent(IMPORT_MOD, "Import a ZIP mod package"));
        root.addView(modButton, buttonParams);

        Button graphicsButton = new Button(this);
        graphicsButton.setText("Graphics Settings");
        graphicsButton.setOnClickListener(v -> showGraphicsSettings());
        root.addView(graphicsButton, buttonParams);

        Button diagnosticsButton = new Button(this);
        diagnosticsButton.setText("Export Diagnostics");
        diagnosticsButton.setOnClickListener(v -> exportDiagnostics());
        root.addView(diagnosticsButton, buttonParams);

        setContentView(root);
        refreshState();
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

    private void showGraphicsSettings() {
        File file = settingsFile();
        String ini = "";
        try {
            if (file.isFile()) ini = new String(Files.readAllBytes(file.toPath()), "UTF-8");
        } catch (Exception error) {
            status.setText("Could not read graphics settings: " + error.getMessage());
            return;
        }
        String[] aaOptions = {"Off (fastest)", "2x", "4x"};
        String[] preloadOptions = {"On demand (recommended)", "Preload at startup", "Preload + cache"};
        String[] drawOptions = {"Whole town", "1 acre", "2 acres", "3 acres", "4 acres", "5 acres"};
        Spinner aa = makeSpinner(aaOptions, readSetting(ini, "msaa", 0) == 2 ? 1 : readSetting(ini, "msaa", 0) == 4 ? 2 : 0);
        Spinner preload = makeSpinner(preloadOptions, Math.max(0, Math.min(2, readSetting(ini, "preload_textures", 0))));
        Spinner draw = makeSpinner(drawOptions, Math.max(0, Math.min(5, readSetting(ini, "vr_draw_radius", 0))));
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
        panel.addView(filtering);
        panel.addView(skybox);
        new AlertDialog.Builder(this).setTitle("Graphics Settings")
            .setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (dialog, which) -> {
                String updated = setSetting(ini, "msaa", new int[]{0,2,4}[aa.getSelectedItemPosition()]);
                updated = setSetting(updated, "preload_textures", preload.getSelectedItemPosition());
                updated = setSetting(updated, "vr_draw_radius", draw.getSelectedItemPosition());
                updated = setSetting(updated, "texture_filtering", filtering.isChecked() ? 1 : 0);
                updated = setSetting(updated, "skybox", skybox.isChecked() ? 1 : 0);
                try {
                    File temp = new File(file.getParentFile(), "settings.ini.tmp");
                    try (FileOutputStream output = new FileOutputStream(temp)) {
                        output.write(updated.getBytes("UTF-8"));
                        output.getFD().sync();
                    }
                    if (!temp.renameTo(file)) throw new java.io.IOException("Could not save settings file.");
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
        if (requestCode == IMPORT_TEXTURES || requestCode == IMPORT_MOD) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    String name = ContentImporter.importContent(this, data.getData(), requestCode == IMPORT_MOD);
                    status.setText("Imported: " + name + "\nRestart the game to load the new content.");
                } catch (Exception error) {
                    Log.e(TAG, "Content import failed", error);
                    status.setText("Import failed: " + error.getMessage());
                }
            }
            refreshState();
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
            status.setText("Game imported and validated. Ready to play.");
        } catch (Exception error) {
            Log.e(TAG, "Game import failed", error);
            status.setText("Import failed: " + error.getMessage() + "\nYour previous game and saves were kept.");
        }
        refreshState();
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
