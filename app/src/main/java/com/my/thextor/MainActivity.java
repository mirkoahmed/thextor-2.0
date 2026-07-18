package com.my.thextor;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.ColorUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private int colorBackground;
    private int colorForeground;
    private int colorHint;
    private static final long MAX_FILE_SIZE = (long) (1.6 * 1024 * 1024);
    private static final int BINARY_CHECK_SIZE = 4096;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler noticeHandler = new Handler(Looper.getMainLooper());
    private Runnable noticeRunnable;

    private EditText editor;
    private TextView txtFilePath;
    private ViewGroup mainLayout;
    private TextView[] navButtons;
    private View[] separators;
    private View noticeContainer;
    private TextView txtNotice;
    private android.widget.ImageView imgNoticeIcon;

    private Uri currentFileUri = null;
    private boolean isModified = false;

    private int currentFontIndex = 2;
    private float currentTextSize = 18f;

    private ScaleGestureDetector scaleGestureDetector;
    private SharedPreferences prefs;

    private ActivityResultLauncher<Intent> openFileLauncher;
    private ActivityResultLauncher<Intent> saveAsLauncher;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        colorBackground = ContextCompat.getColor(this, R.color.obsidian);
        colorForeground = ContextCompat.getColor(this, R.color.lichen);
        colorHint = ContextCompat.getColor(this, R.color.hint_color);

        boolean isLight = ColorUtils.calculateLuminance(colorBackground) > 0.5;
        if (isLight) {
            EdgeToEdge.enable(this,
                SystemBarStyle.light(colorBackground, colorBackground),
                SystemBarStyle.light(colorBackground, colorBackground)
            );
        } else {
            EdgeToEdge.enable(this,
                SystemBarStyle.dark(colorBackground),
                SystemBarStyle.dark(colorBackground)
            );
        }
        
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("ThextorPrefs", MODE_PRIVATE);

        editor = findViewById(R.id.editor);
        txtFilePath = findViewById(R.id.txt_file_path);
        mainLayout = findViewById(R.id.main_layout);
        noticeContainer = findViewById(R.id.notice_container);
        txtNotice = findViewById(R.id.txt_notice);
        imgNoticeIcon = findViewById(R.id.img_notice_icon);

        navButtons = new TextView[]{
                findViewById(R.id.btn_open), findViewById(R.id.btn_new),
                findViewById(R.id.btn_undo), findViewById(R.id.btn_redo),
                findViewById(R.id.btn_font), findViewById(R.id.btn_save)
        };

        separators = new View[]{
                findViewById(R.id.sep1), findViewById(R.id.sep2),
                findViewById(R.id.path_sep1), findViewById(R.id.path_sep2),
                findViewById(R.id.sep4), findViewById(R.id.bottom_border),
                findViewById(R.id.path_bottom_border),
                findViewById(R.id.left_border), findViewById(R.id.right_border),
                findViewById(R.id.top_border_edge), findViewById(R.id.screen_bottom_border)
        };

        setupLaunchers();
        setupScaleDetector();
        setupListeners();
        restoreState();
    }

    private void showNotice(final String message) {
        if (noticeRunnable != null) {
            noticeHandler.removeCallbacks(noticeRunnable);
        }
        txtNotice.setText(message);
        noticeContainer.setVisibility(View.VISIBLE);

        noticeRunnable = () -> {
            noticeContainer.setVisibility(View.GONE);
            noticeRunnable = null;
        };
        noticeHandler.postDelayed(noticeRunnable, 1200);
    }

    private void setupScaleDetector() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull final ScaleGestureDetector detector) {
                final float scaleFactor = detector.getScaleFactor();
                float newSp;
                if (scaleFactor > 1.0f) {
                    newSp = currentTextSize * (1.0f + (scaleFactor - 1.0f) * 1.2f);
                } else if (scaleFactor < 1.0f) {
                    newSp = currentTextSize * (1.0f - (1.0f - scaleFactor) * 1.2f);
                } else {
                    newSp = currentTextSize * scaleFactor;
                }

                currentTextSize = Math.max(2f, Math.min(300f, newSp));
                editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSize);
                return true;
            }
        });
    }

    private void setupLaunchers() {
        openFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                final Uri uri = result.getData().getData();
                if (uri != null) {
                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    try {
                        getContentResolver().takePersistableUriPermission(uri, takeFlags);
                    } catch (SecurityException ignored) {}
                    readFile(uri);
                }
            }
        });

        saveAsLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                final Uri uri = result.getData().getData();
                if (uri != null) {
                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    try {
                        getContentResolver().takePersistableUriPermission(uri, takeFlags);
                    } catch (SecurityException ignored) {}
                    
                    final String content = editor.getText().toString();
                    ioExecutor.execute(() -> {
                        try {
                            writeToFile(uri, content);
                            runOnUiThread(() -> {
                                currentFileUri = uri;
                                isModified = false;
                                updatePathDisplay();
                                showNotice("File Saved");
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                showNotice("Save Failed");
                                resetEditorState();
                            });
                        }
                    });
                }
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        navButtons[0].setOnClickListener(v -> openFileRequest());
        navButtons[1].setOnClickListener(v -> actionNewFile());
        navButtons[2].setOnClickListener(v -> editor.onTextContextMenuItem(android.R.id.undo));
        navButtons[3].setOnClickListener(v -> editor.onTextContextMenuItem(android.R.id.redo));
        navButtons[4].setOnClickListener(v -> cycleFont());
        navButtons[5].setOnClickListener(v -> saveAsRequest());

        editor.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return false;
        });

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(final Editable s) { isModified = true; }
        });
    }

    private void openFileRequest() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
        // Impostiamo text/* come tipo primario per velocizzare il filtraggio iniziale
        intent.setType("text/*");
        
        final String[] mimeTypes = {
            "text/*", 
            "application/octet-stream" // Per supportare file senza estensione (vi-style)
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        
        openFileLauncher.launch(intent);
    }

    private void readFile(final Uri uri) {
        ioExecutor.execute(() -> {
            try {
                // 1. Check file size
                final long size = getFileSize(uri);
                if (size > MAX_FILE_SIZE) {
                    runOnUiThread(() -> {
                        showNotice("ERROR: File too large (Max 1.6MB)");
                        resetEditorState();
                    });
                    return;
                }

                // 2. Check for binary content (null bytes)
                if (isLikelyBinary(uri)) {
                    runOnUiThread(() -> {
                        showNotice("ERROR: Unsupported file type");
                        resetEditorState();
                    });
                    return;
                }

                // 3. Read content
                try (InputStream inputStream = getContentResolver().openInputStream(uri);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    
                    // Pre-allocate StringBuilder capacity to avoid repeated resizing
                    final StringBuilder sb = new StringBuilder((int) Math.max(1024, size));
                    String lineContent;
                    while ((lineContent = reader.readLine()) != null) {
                        sb.append(lineContent).append('\n');
                    }
                    if (sb.length() > 0) sb.setLength(sb.length() - 1);
                    final String content = sb.toString();
                    runOnUiThread(() -> {
                        currentFileUri = uri;
                        editor.setText(content);
                        isModified = false;
                        updatePathDisplay();
                        showNotice("File Opened");
                    });
                }
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    showNotice("ERROR: Failed to open file");
                    resetEditorState();
                });
            }
        });
    }

    private long getFileSize(final Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex != -1) return cursor.getLong(sizeIndex);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private boolean isLikelyBinary(final Uri uri) {
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) return false;
            final byte[] buffer = new byte[BINARY_CHECK_SIZE];
            final int bytesRead = inputStream.read(buffer);
            for (int i = 0; i < bytesRead; i++) {
                if (buffer[i] == 0) return true; // Null byte found
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void actionNewFile() {
        if (isModified && editor.getText().length() > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Unsaved Changes")
                    .setMessage("Do you want to abandon the current file and start a new one?")
                    .setPositiveButton("Yes, start NEW", (dialog, which) -> {
                        resetEditorState();
                        showNotice("New File created");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            resetEditorState();
            showNotice("New File created");
        }
    }

    private void resetEditorState() {
        currentFileUri = null;
        editor.setText("");
        isModified = false;
        updatePathDisplay();
    }

    private void saveAsRequest() {
        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
        // Usiamo */* per permettere il salvataggio di qualsiasi file (con o senza estensione)
        // senza che il sistema forzi l'aggiunta di .txt
        intent.setType("*/*");

        String fullFileName = currentFileUri != null ? getFileNameFromUri(currentFileUri) : getString(R.string.default_filename);
        
        // Passiamo il nome completo. In caso di duplicato, Android metterà il numero alla fine
        // (es. "file.c (1)"), ma grazie al filtro */* potremo sempre riaprirlo.
        intent.putExtra(Intent.EXTRA_TITLE, fullFileName);
        
        saveAsLauncher.launch(intent);
    }

    private void writeToFile(final Uri uri, final String content) throws Exception {
        try (OutputStream outputStream = getContentResolver().openOutputStream(uri, "wt");
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            writer.write(content);
            writer.flush();
        }
    }

    private String getFileNameFromUri(final Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) return cursor.getString(nameIndex);
            }
        } catch (SecurityException e) {
            return getString(R.string.default_filename);
        }
        return "document.txt";
    }

    private void cycleFont() {
        currentFontIndex = (currentFontIndex + 1) % 5;
        applyFont(true);
    }

    private void applyFont(boolean showNotice) {
        final int fontRes;
        final String fontName;
        switch (currentFontIndex) {
            case 1: fontRes = R.font.roboto; fontName = "Roboto"; break;
            case 2: fontRes = R.font.spleen; fontName = "Spleen"; break;
            case 3: fontRes = R.font.px437; fontName = "Px437"; break;
            case 4: fontRes = R.font.unifont; fontName = "Unifont"; break;
            default: fontRes = R.font.jetbrains_mono; fontName = "JetBrains Mono"; break;
        }
        final android.graphics.Typeface tf = ResourcesCompat.getFont(this, fontRes);
        editor.setTypeface(tf);
        txtNotice.setTypeface(tf);
        if (showNotice) showNotice(fontName);
    }

    private void applyTheme() {
        mainLayout.setBackgroundColor(colorBackground);
        editor.setTextColor(colorForeground);
        editor.setHintTextColor(colorHint);
        txtFilePath.setTextColor(colorForeground);
        txtNotice.setTextColor(colorForeground);
        imgNoticeIcon.setColorFilter(colorForeground);

        for (final TextView btn : navButtons) btn.setTextColor(colorForeground);
        for (final View sep : separators) sep.setBackgroundColor(colorForeground);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                final Drawable cursorDrawable = ResourcesCompat.getDrawable(getResources(), R.drawable.block_cursor, getTheme());
                if (cursorDrawable != null) {
                    final Drawable mutatedCursor = cursorDrawable.mutate();
                    mutatedCursor.setTint(colorForeground);
                    editor.setTextCursorDrawable(null);
                    editor.setTextCursorDrawable(mutatedCursor);
                    editor.post(() -> {
                        editor.setCursorVisible(false);
                        editor.setCursorVisible(true);
                    });
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        final SharedPreferences.Editor prefsEditor = prefs.edit();
        prefsEditor.putInt("font_index", currentFontIndex);
        prefsEditor.putFloat("font_size", currentTextSize);
        prefsEditor.putString("unsaved_buffer", editor.getText().toString());
        prefsEditor.putBoolean("is_modified", isModified);
        if (currentFileUri != null) prefsEditor.putString("last_uri", currentFileUri.toString());
        else prefsEditor.remove("last_uri");
        prefsEditor.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        noticeHandler.removeCallbacksAndMessages(null);
        ioExecutor.shutdown();
    }

    private void restoreState() {
        currentFontIndex = prefs.getInt("font_index", 2);
        currentTextSize = prefs.getFloat("font_size", 18f);
        editor.setText(prefs.getString("unsaved_buffer", ""));
        editor.setTextSize(currentTextSize);
        isModified = prefs.getBoolean("is_modified", false);
        final String uriString = prefs.getString("last_uri", null);
        if (uriString != null) currentFileUri = Uri.parse(uriString);
        applyFont(false);
        updatePathDisplay();
        applyTheme();
    }

    private Uri lastResolvedUri = null;
    private void updatePathDisplay() {
        if (currentFileUri == null) {
            txtFilePath.setText(R.string.default_filename);
            lastResolvedUri = null;
            return;
        }

        // Avoid redundant processing if the URI hasn't changed
        if (java.util.Objects.equals(currentFileUri, lastResolvedUri) && !java.util.Objects.equals(txtFilePath.getText().toString(), getString(R.string.default_filename))) {
            return;
        }

        final Uri uriToProcess = currentFileUri;
        ioExecutor.execute(() -> {
            final String fileName = getFileNameFromUri(uriToProcess);
            String parentFolder = "";

            // 1. Try to get real path for local files
            try (Cursor cursor = getContentResolver().query(uriToProcess, new String[]{"_data"}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    final int index = cursor.getColumnIndex("_data");
                    if (index != -1) {
                        final String realPath = cursor.getString(index);
                        if (realPath != null && realPath.contains("/")) {
                            final String pathWithoutFile = realPath.substring(0, realPath.lastIndexOf('/'));
                            final int lastSlash = pathWithoutFile.lastIndexOf('/');
                            parentFolder = lastSlash >= 0 ? pathWithoutFile.substring(lastSlash + 1) : pathWithoutFile;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 2. Fallback for Storage Access Framework (Cloud/Virtual providers)
            if (parentFolder.isEmpty()) {
                try {
                    final String docId = DocumentsContract.getDocumentId(uriToProcess);
                    if (docId != null) {
                        final String decodedId = Uri.decode(docId);
                        if (decodedId.contains(":")) {
                            final String[] split = decodedId.split(":");
                            if (split.length > 1) {
                                final String[] segments = split[1].split("/");
                                if (segments.length >= 2) {
                                    parentFolder = segments[segments.length - 2];
                                }
                            }
                        } else if (decodedId.contains("/")) {
                            final String[] segments = decodedId.split("/");
                            if (segments.length >= 2) {
                                parentFolder = segments[segments.length - 2];
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            final String finalParent = parentFolder;
            final String displayText;
            
            // Validate parent folder name to avoid system internal strings
            if (!finalParent.isEmpty() && 
                !finalParent.equalsIgnoreCase("0") &&
                !finalParent.equalsIgnoreCase("primary") && 
                !finalParent.equalsIgnoreCase("document") &&
                !finalParent.equalsIgnoreCase("emulated") && 
                !finalParent.matches("\\d+")) {
                displayText = getString(R.string.path_template, finalParent, fileName);
            } else {
                displayText = fileName;
            }
            
            runOnUiThread(() -> {
                txtFilePath.setText(displayText);
                lastResolvedUri = uriToProcess;
            });
        });
    }
}