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
import java.io.Reader;
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
    private volatile Pty pty;
    private int escState = 0;
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

        Button ctrlCBtn = new Button(this);
        ctrlCBtn.setText("^C");
        ctrlCBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ctrlCBtn.setOnClickListener(v -> sendBytes(new byte[]{3}));

        Button sendBtn = new Button(this);
        sendBtn.setText("Send");
        sendBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sendBtn.setOnClickListener(v -> {
            sendBytes((stdinField.getText().toString() + "\n").getBytes(StandardCharsets.UTF_8));
            stdinField.setText("");
        });

        inputRow.addView(stdinField);
        inputRow.addView(ctrlCBtn);
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
                File runtimeDir = new File(getFilesDir(), "runtime");
                java.util.Map<String, String> env = new java.util.HashMap<>();
                env.put("PYTHONHOME", runtimeDir.getAbsolutePath());
                File stdlib = findStdlib(runtimeDir);
                if (stdlib != null) {
                    env.put("PYTHONPATH", stdlib.getAbsolutePath());
                }
                env.put("PYTHONDONTWRITEBYTECODE", "1");
                env.put("PYTHONUNBUFFERED", "1");
                env.put("PYTHONIOENCODING", "utf-8");
                env.put("TMPDIR", getCacheDir().getAbsolutePath());
                env.put("HOME", getFilesDir().getAbsolutePath());
                env.put("LD_LIBRARY_PATH", runtimeDir.getAbsolutePath() + "/lib");
                env.put("PATH", ai.nativeLibraryDir);
                env.put("TERM", "dumb");
                env.put("LANG", "C.UTF-8");
                env.put("LC_ALL", "C.UTF-8");
                String[] envArr = new String[env.size()];
                int e = 0;
                for (java.util.Map.Entry<String, String> entry : env.entrySet()) {
                    envArr[e++] = entry.getKey() + "=" + entry.getValue();
                }

                Pty started = Pty.start(argv.toArray(new String[0]),
                        getFilesDir().getAbsolutePath(), envArr, 40, 100);
                if (started == null) {
                    append("PyRunner: could not start pty");
                    return;
                }
                pty = started;
                readPty(started);
                int exitCode = started.waitFor();
                pty = null;
                mainHandler.post(() -> console.append("[exit " + exitCode + "]\n"));
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

    private void sendBytes(byte[] bytes) {
        Pty p = pty;
        if (p == null) {
            return;
        }
        try {
            p.out.write(bytes);
            p.out.flush();
        } catch (IOException e) {
            append("input write error: " + e.getMessage());
        }
    }

    private void readPty(Pty p) throws IOException {
        Reader reader = new InputStreamReader(p.in, StandardCharsets.UTF_8);
        char[] buf = new char[4096];
        int n;
        while ((n = reader.read(buf)) > 0) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < n; i++) {
                char c = buf[i];
                if (escState == 0) {
                    if (c == 0x1b) {
                        escState = 1;
                    } else if (c == '\n') {
                        out.append('\n');
                    } else if (c != '\r' && c != '\b' && c != 0x07) {
                        out.append(c);
                    }
                } else if (escState == 1) {
                    escState = (c == '[') ? 2 : 0;
                } else if (c >= 0x40 && c <= 0x7e) {
                    escState = 0;
                }
            }
            if (out.length() > 0) {
                String text = out.toString();
                mainHandler.post(() -> {
                    console.append(text);
                    scrollView.fullScroll(View.FOCUS_DOWN);
                });
            }
        }
    }

    private File findStdlib(File runtimeDir) {
        File[] kids = new File(runtimeDir, "lib").listFiles();
        if (kids == null) {
            return null;
        }
        for (File k : kids) {
            if (k.isDirectory() && k.getName().startsWith("python3.")) {
                return k;
            }
        }
        return null;
    }

    private void ensureRuntime() {
        if (runtimeReady.get()) return;
        File dir = new File(getFilesDir(), "runtime");
        if (findStdlib(dir) != null) {
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
