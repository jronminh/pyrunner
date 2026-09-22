package local.pyrunner;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final String TAG = "PyRunner";
    private static final AtomicBoolean runtimeReady = new AtomicBoolean(false);

    private ExecutorService executor;
    private Handler mainHandler;
    private ScrollView scrollView;
    private TextView console;
    private EditText stdinField;
    private volatile Process process;
    private float textSizeSp = 14f;
    private ScaleGestureDetector scaleDetector;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("pyrunner", MODE_PRIVATE);
        textSizeSp = prefs.getFloat("text_size", 14f);
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                textSizeSp = Math.max(8f, Math.min(40f, textSizeSp * detector.getScaleFactor()));
                console.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
                return true;
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f));

        console = new TextView(this);
        console.setTypeface(android.graphics.Typeface.MONOSPACE);
        console.setTextIsSelectable(true);
        console.setHorizontallyScrolling(true);
        console.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        console.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        hScroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        hScroll.addView(console);
        scrollView.addView(hScroll);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        stdinField = new EditText(this);
        stdinField.setHint("stdin...");
        stdinField.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f));

        Button sendBtn = new Button(this);
        sendBtn.setText("Send");
        sendBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sendBtn.setOnClickListener(v -> {
            String text = stdinField.getText().toString();
            if (text.length() > 0 && process != null && process.isAlive()) {
                try {
                    OutputStream os = process.getOutputStream();
                    Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                    w.write(text + "\n");
                    w.flush();
                } catch (IOException e) {
                    append("stdin write error: " + e.getMessage());
                }
            }
            stdinField.setText("");
        });

        inputRow.addView(stdinField);
        inputRow.addView(sendBtn);

        root.addView(scrollView);
        root.addView(inputRow);
        setContentView(root);

        append("PyRunner: ready");
        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            runScript(intent.getData());
        } else {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("text/*");
            startActivityForResult(picker, 42);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            runScript(intent.getData());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 42 && resultCode == RESULT_OK && data != null) {
            runScript(data.getData());
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (scaleDetector != null) {
            scaleDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (prefs != null) {
            prefs.edit().putFloat("text_size", textSizeSp).apply();
        }
    }

    private void runScript(Uri uri) {
        if (uri == null) return;
        String scheme = uri.getScheme();
        if (Build.VERSION.SDK_INT >= 23 && "file".equals(scheme)) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
        executor.execute(() -> {
            try {
                ensureRuntime();
                File scriptFile = new File(getFilesDir(), "script.py");
                copyScript(uri, scriptFile);
                append("PyRunner: running " + scriptFile.getAbsolutePath());
                ApplicationInfo ai = getApplicationInfo();
                String py = new File(ai.nativeLibraryDir, "libpython3.so").getAbsolutePath();
                List<String> argv = new ArrayList<>();
                argv.add(py);
                argv.add("-u");
                argv.add(scriptFile.getAbsolutePath());
                ProcessBuilder pb = new ProcessBuilder(argv);
                pb.directory(getFilesDir());
                pb.redirectErrorStream(true);
                File runtimeDir = new File(getFilesDir(), "runtime");
                java.util.Map<String, String> env = pb.environment();
                env.put("PYTHONHOME", runtimeDir.getAbsolutePath());
                env.put("PYTHONPATH", runtimeDir.getAbsolutePath() + "/lib/python3.14");
                env.put("PYTHONDONTWRITEBYTECODE", "1");
                env.put("PYTHONUNBUFFERED", "1");
                env.put("PYTHONIOENCODING", "utf-8");
                env.put("TMPDIR", getCacheDir().getAbsolutePath());
                env.put("HOME", getFilesDir().getAbsolutePath());
                env.put("LD_LIBRARY_PATH", runtimeDir.getAbsolutePath() + "/lib");
                env.put("LANG", "C.UTF-8");
                env.put("LC_ALL", "C.UTF-8");
                process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String l = line;
                    mainHandler.post(() -> {
                        console.append(l + "\n");
                        scrollView.fullScroll(View.FOCUS_DOWN);
                    });
                }
                int exitCode = process.waitFor();
                mainHandler.post(() -> console.append("[exit " + exitCode + "]"));
            } catch (Throwable t) {
                StringBuilder sb = new StringBuilder();
                for (StackTraceElement e : t.getStackTrace()) {
                    sb.append(e.toString()).append("\n");
                }
                String err = sb.toString();
                mainHandler.post(() -> console.append(err));
            }
        });
    }

    private void ensureRuntime() {
        if (runtimeReady.get()) return;
        File dir = new File(getFilesDir(), "runtime");
        if (new File(dir, "lib/python3.14").exists()) {
            runtimeReady.set(true);
            return;
        }
        try {
            dir.mkdirs();
            ZipInputStream zis = new ZipInputStream(getAssets().open("pyruntime.zip"));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(dir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(outFile);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) >= 0) {
                        fos.write(buf, 0, n);
                    }
                    fos.close();
                    outFile.setExecutable(true);
                    outFile.setReadable(true);
                    outFile.setWritable(true);
                }
                zis.closeEntry();
            }
            zis.close();
            runtimeReady.set(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void copyScript(Uri uri, File dest) throws IOException {
        try (OutputStream os = new FileOutputStream(dest)) {
            if ("content".equals(uri.getScheme())) {
                ContentResolver cr = getContentResolver();
                try (InputStream is = cr.openInputStream(uri)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) >= 0) {
                        os.write(buf, 0, n);
                    }
                }
            } else {
                try (FileInputStream fis = new FileInputStream(uri.getPath())) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) >= 0) {
                        os.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private void append(final String text) {
        mainHandler.post(() -> {
            console.append(text + "\n");
            scrollView.fullScroll(View.FOCUS_DOWN);
        });
    }
}
