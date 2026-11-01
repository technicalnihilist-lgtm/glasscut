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
            if (resultCode == RESULT_OK) {
                writeToGallery(pendingData, pendingName, pendingMime);
            } else {
                jsToast("Save nahi hua");
            }
            pendingData = pendingName = pendingMime = null;
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

    private String writeToGallery(String dataUrl, String name, String mime) {
        try {
            if (dataUrl == null) return "err:empty";
            if (mime == null || mime.length() == 0) mime = "image/png";
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
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
                        os.write(bytes);
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
            fos.write(bytes);
            fos.flush();
            fos.close();
            android.media.MediaScannerConnection.scanFile(this,
                    new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
            return "ok:" + Uri.fromFile(f);
        } catch (Exception e) {
            return "err:" + e.getMessage();
        }
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
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                writeToGallery(pendingData, pendingName, pendingMime);
            } else {
                jsToast("Save nahi hua");
            }
            pendingData = pendingName = pendingMime = null;
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
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
