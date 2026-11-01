package com.glasscut.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * GlassCut - single-file WebView shell.
 *
 * The whole UI lives in assets/index.html. This class only provides the two
 * things a browser tab cannot do reliably on Android:
 *   1. a real file chooser for <input type="file">  (onShowFileChooser)
 *   2. a real "save to gallery" bridge              (GlassCutAndroid.saveImage)
 * plus edge-to-edge safe-area insets handed to the page as CSS variables.
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final int REQ_WRITE = 1002;

    private WebView web;
    private FrameLayout root;
    private ValueCallback<Uri[]> pendingFiles;
    private int lastTop = -1, lastBottom = -1;
    private String pendingData;   // image waiting for a storage permission
    private String pendingName;
    private String pendingMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1A1550);

        web = new WebView(this);
        web.setBackgroundColor(0xFF1A1550);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 26) {
            s.setSafeBrowsingEnabled(false);
        }

        web.addJavascriptInterface(new Bridge(), "GlassCutAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return true; // single page app: never navigate away
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (lastTop >= 0) pushInsets(lastTop, lastBottom);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (pendingFiles != null) {
                    pendingFiles.onReceiveValue(null);
                }
                pendingFiles = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Photo chuno"), REQ_FILE);
                } catch (Exception e) {
                    pendingFiles = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    /* ---------------- safe area insets -> CSS variables ---------------- */

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT >= 20) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    int top, bottom;
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets b = insets.getInsets(
                                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                        top = b.top;
                        bottom = b.bottom;
                    } else {
                        top = insets.getSystemWindowInsetTop();
                        bottom = insets.getSystemWindowInsetBottom();
                    }
                    pushInsets(top, bottom);
                    return insets;
                }
            });
        }
    }

    private void pushInsets(int top, int bottom) {
        if (web == null) return;
        lastTop = top;
        lastBottom = bottom;
        float d = getResources().getDisplayMetrics().density;
        final String js = "(function(){var r=document.documentElement.style;"
                + "r.setProperty('--sat','" + (top / d) + "px');"
                + "r.setProperty('--sab','" + (bottom / d) + "px');})();";
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    /* ---------------- file picker result ---------------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (pendingFiles == null) return;
            Uri[] out = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                } else if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = data.getClipData().getItemAt(i).getUri();
                    }
                }
            }
            pendingFiles.onReceiveValue(out);
            pendingFiles = null;
            return;
        }
        if (requestCode == REQ_WRITE) {
            final String d = pendingData, n = pendingName, m = pendingMime;
            pendingData = pendingName = pendingMime = null;
            if (resultCode == RESULT_OK) finishPendingSave(d, n, m);
            else jsToast("Save nahi hua");
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* ---------------- save to gallery ---------------- */

    public class Bridge {
        /** Called from the page: base64 data URL + file name + mime type. */
        @JavascriptInterface
        public String saveImage(String dataUrl, String name, String mime) {
            try {
                if (dataUrl == null || dataUrl.length() < 32) return "err:empty";
                int comma = dataUrl.indexOf(',');
                if (comma < 0) return "err:format";
                String b64 = dataUrl.substring(comma + 1);
                if (mime == null || mime.length() == 0) mime = "image/png";
                if (Build.VERSION.SDK_INT < 29
                        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    pendingData = dataUrl;
                    pendingName = name;
                    pendingMime = mime;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            requestPermissions(
                                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
                        }
                    });
                    return "ok:pending";
                }
                return writeToGallery(dataUrl, name, mime);
            } catch (Exception e) {
                return "err:" + e.getMessage();
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            jsToast(msg);
        }
    }

    /** base64 slice size: a multiple of 4 so every slice decodes on its own. */
    private static final int B64_CHUNK = 16384;

    /**
     * Decodes a base64 payload straight into the output stream, 16 KB at a time.
     *
     * The old code called Base64.decode() on the whole payload first, so saving a
     * 1400x1200 PNG held three copies of it at once: the base64 String, the
     * multi-megabyte byte[] it decoded to, and the stream buffer - on top of the
     * WebView heap that is still holding the canvas. Slicing the decode means the
     * save only ever needs one 16 KB window (16 KB chars in, 12 KB out), which is
     * what keeps a big save from turning into an OOM on a low-RAM device.
     */
    private static void writeBase64(String b64, OutputStream os) throws IOException {
        final int n = b64.length();
        int i = 0;
        while (i < n) {
            int end = Math.min(n, i + B64_CHUNK);
            if (end < n) {
                // A slice must hold a whole number of 4-char groups. Counting the
                // real characters (not the bytes) keeps that true even if the
                // payload ever carries line breaks, which would otherwise put a
                // group boundary in the wrong place and make the slice undecodable.
                int data = 0;
                for (int k = i; k < end; k++) if (!ws(b64.charAt(k))) data++;
                int rem = data & 3;
                while (rem != 0 && end > i) {
                    if (!ws(b64.charAt(--end))) rem = (rem + 3) & 3;
                }
                if (end <= i) end = Math.min(n, i + B64_CHUNK);
            }
            os.write(Base64.decode(b64.substring(i, end), Base64.DEFAULT));
            i = end;
        }
    }

    private static boolean ws(char c) {
        return c == '\n' || c == '\r' || c == ' ' || c == '\t';
    }

    private String writeToGallery(String dataUrl, String name, String mime) {
        try {
            if (dataUrl == null) return "err:empty";
            if (mime == null || mime.length() == 0) mime = "image/png";
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            if (name == null || name.length() == 0) name = "glasscut.png";

            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, mime);
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/GlassCut");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "err:insert";
                // IS_PENDING rows are invisible to the gallery. If the write throws
                // we must delete the row, otherwise every failed save leaves an
                // orphan entry that the user can never see or clean up.
                try {
                    OutputStream os = cr.openOutputStream(uri);
                    if (os == null) throw new IOException("stream");
                    try {
                        writeBase64(b64, os);
                        os.flush();
                    } finally {
                        try { os.close(); } catch (Exception ignored) { }
                    }
                    cv.clear();
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                    cr.update(uri, cv, null, null);
                    return "ok:" + uri;
                } catch (Exception e) {
                    try { cr.delete(uri, null, null); } catch (Exception ignored) { }
                    return "err:" + e.getMessage();
                }
            }

            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "GlassCut");
            if (!dir.exists() && !dir.mkdirs()) return "err:mkdir";
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                writeBase64(b64, fos);
                fos.flush();
            } finally {
                try { fos.close(); } catch (Exception ignored) { }
            }
            android.media.MediaScannerConnection.scanFile(this,
                    new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
            return "ok:" + Uri.fromFile(f);
        } catch (Exception e) {
            return "err:" + e.getMessage();
        }
    }

    /**
     * A pending save is finished here, never on the caller's thread.
     * The base64 payload is several megabytes for a 1400x1200 PNG; decoding it and
     * writing it to MediaStore took long enough that doing it from
     * onActivityResult / onRequestPermissionsResult (both on the UI thread) risked an
     * ANR on a slow device. The bridge call itself already runs off the UI thread,
     * so the permission path now matches it.
     */
    private void finishPendingSave(String data, String name, String mime) {
        final String d = data, n = name, m = mime;
        if (d == null) {
            jsToast("Save nahi hua");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String r = writeToGallery(d, n, m);
                if (r == null || r.startsWith("err")) jsToast("Save nahi hua");
                else notifyPage();
            }
        }, "glasscut-save");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /**
     * The page keeps quiet while a save is waiting for a permission (the bridge
     * answers "ok:pending"), because until the bytes actually land there is nothing
     * true to say. This is the other half of that deal: tell the page the write
     * really finished so it shows its own "Save ho gaya" exactly once.
     */
    private void notifyPage() {
        if (web == null) return;
        web.post(new Runnable() {
            @Override
            public void run() {
                WebView w = web;
                if (w == null) return;
                w.evaluateJavascript("window.GlassCut&&GlassCut.saved&&GlassCut.saved();", null);
            }
        });
    }

    private void jsToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_WRITE) {
            final String d = pendingData, n = pendingName, m = pendingMime;
            pendingData = pendingName = pendingMime = null;
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) finishPendingSave(d, n, m);
            else jsToast("Save nahi hua");
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        // an unanswered ValueCallback keeps the WebView's file input pending forever,
        // and a pending base64 payload pins megabytes of memory in a dying activity
        if (pendingFiles != null) {
            try { pendingFiles.onReceiveValue(null); } catch (Exception ignored) { }
            pendingFiles = null;
        }
        pendingData = pendingName = pendingMime = null;
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
