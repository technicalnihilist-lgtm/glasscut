package com.glasscut.app;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
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
    /* volatile: page inhe JavaBridge ke apne thread se padhta hai (insets()), jabki
       ye UI thread par likhe jate hain - warna page ko purani value mil sakti hai. */
    private volatile int lastTop = -1, lastBottom = -1;
    /* volatile: ye teen saveImage() me likhe jate hain, jo WebView ke JavaBridge
       thread par chalta hai, aur padhe jate hain UI thread par
       (onRequestPermissionsResult / onActivityResult). Bina volatile ke UI thread
       ko purani (null) value dikh sakti hai - us haalat me finishPendingSave(null)
       chalta hai aur page ko "Save nahi hua" milta hai halanki payload taiyar tha,
       jabki ek asli save me payload kai MB ka hota hai. lastTop/lastBottom par pehle
       se yahi tark likha hai. */
    private volatile String pendingData;   // image waiting for a storage permission
    private volatile String pendingName;
    private volatile String pendingMime;

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
        setContentView(root);

        /* WebView ka poora setup ek jagah: onCreate ke saath-saath renderer crash ke
           baad bhi yahi chalta hai (onRenderProcessGone), is liye dono raaste ek hi
           settings/client/url se guzarte hain - dobara banaya gaya WebView purane
           jaisa hi hota hai. */
        buildWebView();
    }

    /** Creates the WebView, wires it up and loads the page. Safe to call twice. */
    private void buildWebView() {
        web = new WebView(this);
        web.setBackgroundColor(0xFF1A1550);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
                /* Renderer crash ke baad (onRenderProcessGone) naya WebView banta hai
                   aur lastTop/lastBottom -1 ho jate hain, is liye upar wali line
                   kuch push hi nahi karti. Insets listener root par hai aur root
                   window se juda hi rehta hai, yaani onAttachedToWindow dobara nahi
                   chalta aur naye page ko --sat/--sab kabhi nahi milte jab tak koi
                   insets badalne wali cheez (rotation, keyboard) na ho - yaani
                   header status bar ke neeche aur dock nav bar ke neeche chipka
                   rehta hai. requestApplyInsets() wahi delivery dobara maangta hai;
                   listener ka guard (top == lastTop) repeat call ko no-op bana deta
                   hai, is liye har page load par iska koi extra kaam nahi. */
                try { root.requestApplyInsets(); } catch (Exception ignored) { }
            }

            /**
             * The WebView renderer is a separate process, and a 1400x1050 canvas plus
             * its typed arrays is exactly the kind of load that gets it killed on a
             * low-RAM device. Without this override Android treats that as a fatal
             * error and takes the whole app down - the user sees the app vanish, with
             * no message and nothing to recover. Returning true says "handled": the
             * dead WebView is dropped and a fresh one is built and reloaded, so the
             * shell survives and the user can pick the photo again.
             */
            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                if (v != null) {
                    try { root.removeView(v); } catch (Exception ignored) { }
                    try { v.destroy(); } catch (Exception ignored) { }
                }
                if (web == v) web = null;
                /* a callback that will never be answered would pin the old WebView */
                if (pendingFiles != null) {
                    try { pendingFiles.onReceiveValue(null); } catch (Exception ignored) { }
                    pendingFiles = null;
                }
                pendingData = pendingName = pendingMime = null;
                lastTop = -1;
                lastBottom = -1;
                buildWebView();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                /* Chauthe call site par bhi wahi guard. Ye line pehle bina try/catch
                   thi, jabki onActivityResult, onRenderProcessGone aur onDestroy
                   teeno ise guard karte hain. Renderer mar chuka ho
                   (onRenderProcessGone) ya WebView destroy ho raha ho to
                   onReceiveValue IllegalStateException phenkta hai - aur ye WebView
                   ka hi callback hai, yaani exception UI thread se seedha activity
                   tak jati hai aur app band. Theek wahi lamha jab ye chalta hai wo
                   doosri baar "photo chuno" dabana hai jab pehla picker abhi band
                   hua ho. Ab dead callback ko chhod diya jata hai. */
                if (pendingFiles != null) {
                    try { pendingFiles.onReceiveValue(null); } catch (Exception ignored) { }
                }
                pendingFiles = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Photo chuno"), REQ_FILE);
                } catch (Exception e) {
                    /* Pehle yahan pendingFiles ko null kar ke false lauta diya jata
                       tha. false ka matlab WebView ke liye "maine handle nahi
                       kiya", par humne callback pehle hi le liya tha - yaani
                       <input type=file> ka ValueCallback hamesha ke liye
                       unanswered reh jata tha: page ka onchange kabhi nahi
                       chalta, e.target.value='' kabhi nahi chalta, aur us
                       activity me photo chunne ka rasta bilkul band ho jata
                       tha (koi message bhi nahi). Ab callback ka jawab null
                       se de diya jata hai aur true lautaya jata hai - WebView
                       ko intezaar nahi karna padta aur input apni normal
                       "kuch nahi chuna" haalat me chala jata hai. */
                    try { cb.onReceiveValue(null); } catch (Exception ignored) { }
                    pendingFiles = null;
                    return true;
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
                    /* Android ye callback keyboard/rotation animation ke dauraan
                       har frame par bulata hai, aur har call ek naya JS string
                       banati thi jo WebView ke thread par evaluate hoti thi -
                       wahi do value baar-baar. Value badli na ho to page ko
                       dobara batane ki zaroorat nahi (CSS variable ki value
                       wahi rehti hai). onPageFinished alag se pushInsets bulata
                       hai, is liye page reload par variable phir bhi set hote
                       hain - guard sirf isi listener me hai, pushInsets me nahi. */
                    if (top == lastTop && bottom == lastBottom) return insets;
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
                /* Ye runnable baad me chalta hai, aur tab tak activity destroy ho
                   sakti hai (insets ka callback aakhri frame par aata hai, aur
                   onDestroy web ko null kar deta hai) - us haalat me seedha
                   web.evaluateJavascript NPE deta tha, yaani app band hone ke
                   waqt ek crash. notifyPage() aur trimPage() pehle se isi tarah
                   guard karte hain; ab insets bhi. */
                WebView w = web;
                if (w == null) return;
                try {
                    w.evaluateJavascript(js, null);
                } catch (Exception ignored) { }
            }
        });
    }

    /* ---------------- file picker result ---------------- */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        /* super pehle: pehle ye call sabse aakhir me thi, aur dono early returns
           (REQ_FILE par pendingFiles null, aur REQ_WRITE ka poora block) use chhod
           dete the - yaani file-chooser ka result aane par aur permission ke result
           aane par superclass ko kabhi khabar hi nahi jati thi (ActivityResultRegistry
           / fragment dispatch wahi se chalta hai). ab har raaste par theek ek baar. */
        super.onActivityResult(requestCode, resultCode, data);
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
            /* Callback pehle hi field se hata do aur uski call ko guard karo:
               onReceiveValue WebView ke andar post hota hai aur destroy ho chuke
               WebView par IllegalStateException de sakta hai. Us haalat me pehle
               pendingFiles kabhi null nahi hota tha (line uske baad thi) aur ek
               dead callback field me padi rehti thi, aur exception UI thread se
               bahar nikal kar activity ko gira deti thi. Baaki saare call sites
               (onShowFileChooser, onRenderProcessGone, onDestroy) pehle se isi
               tarah guarded hain. */
            ValueCallback<Uri[]> cb = pendingFiles;
            pendingFiles = null;
            try { cb.onReceiveValue(out); } catch (Exception ignored) { }
        }
        if (requestCode == REQ_WRITE) {
            final String d = pendingData, n = pendingName, m = pendingMime;
            pendingData = pendingName = pendingMime = null;
            if (resultCode == RESULT_OK) finishPendingSave(d, n, m);
            else failPage();
        }
    }

    /* ---------------- save to gallery ---------------- */

    public class Bridge {
        /**
         * Safe-area insets ka synchronous read, page ke pehle frame ke liye.
         *
         * Pehle insets sirf onPageFinished par push hote the (CSS variable ek JS
         * call se). Us waqt tak page apna pehla frame --sat/--sab ke bina bana
         * chuka hota tha, yaani padding 0 se shuru ho kar baad me ek jhatke me
         * badalti thi. Ye getter wahi value deta hai jo pushInsets bhejta hai
         * ("top|bottom", dp me), taaki page script chalte hi use laga le.
         * Insets abhi pata na hon to khali string - page tab purane push raaste
         * par chalta hai, is liye kuch tootta nahi.
         */
        @JavascriptInterface
        public String insets() {
            int t = lastTop, b = lastBottom;
            if (t < 0) return "";
            float d = getResources().getDisplayMetrics().density;
            return (t / d) + "|" + (b / d);
        }

        /** Called from the page: base64 data URL + file name + mime type. */
        @JavascriptInterface
        public String saveImage(String dataUrl, String name, String mime) {
            try {
                if (dataUrl == null || dataUrl.length() < 32) return "err:empty";
                if (dataUrl.indexOf(',') < 0) return "err:format";
                /* yahan pehle `String b64 = dataUrl.substring(comma+1)` tha - ek
                   poora multi-MB copy jo kabhi use hi nahi hota tha (naam bhi
                   kahin nahi jata). har save par base64 payload ka dohra copy
                   sirf ban kar garbage ban jata tha. */
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
                            /* requestPermissions aakhri call site hai jispar guard nahi
                               tha - pushInsets, notifyPage, trimPage, failPage,
                               pause/resume/back aur onPause ke saath ab ye bhi. Ye
                               runnable baad me chalta hai, aur tab tak activity
                               finish ho chuki ho sakti hai (user ne save ke turant
                               baad back dabaya). Us haalat me requestPermissions
                               IllegalStateException deta hai UI thread par, yaani
                               app crash. Aur us se bura: pendingData set reh jata
                               hai - page ko pehle hi "ok:pending" mil chuka hai,
                               is liye wo chup baitha hai aur GlassCut.saved()/
                               failed() ka intezaar karta rehta hai. Natija: na file,
                               na message, aur agla koi bhi REQ_WRITE result purane
                               payload ko likh deta. ab exception par payload saaf
                               hota hai aur page ko wahi fail raasta milta hai jo
                               baaki har failure par milta hai (message + long-press
                               overlay; blob page ke paas pehle se hai). */
                            try {
                                requestPermissions(
                                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
                            } catch (Exception e) {
                                pendingData = pendingName = pendingMime = null;
                                failPage();
                            }
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
    private static void writeBase64(String b64, int from, OutputStream os) throws IOException {
        final int n = b64.length();
        int i = from;
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
            /* `dataUrl.substring(comma+1)` poore base64 payload ka ek dohra String
               banata tha - 1400x1200 PNG par ~6 MB ka payload yaani save ke waqt
               6 MB extra Java heap, sirf is liye ki payload ko "kaatna" pada. ab
               payload jahan hai wahin se, ek offset se padha jata hai; har 16 KB
               slice hi kat kar decode hoti hai (jaisa pehle se tha). */
            final int start = dataUrl.indexOf(',') >= 0 ? dataUrl.indexOf(',') + 1 : 0;
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
                        writeBase64(dataUrl, start, os);
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
            // API 29+ never leaves a half-written file behind: the MediaStore row
            // is IS_PENDING, so a failed write is deleted and stays invisible.
            // This path has no such flag - a write that dies (disk full, card
            // pulled, app killed) used to leave a truncated image in Pictures/
            // GlassCut forever, and the media scanner would happily index it.
            // Now the partial file is removed before the error goes back to the page.
            try {
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    writeBase64(dataUrl, start, fos);
                    fos.flush();
                } finally {
                    try { fos.close(); } catch (Exception ignored) { }
                }
            } catch (Exception e) {
                try { if (f.exists()) f.delete(); } catch (Exception ignored) { }
                throw e;
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
            failPage();
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String r = writeToGallery(d, n, m);
                if (r == null || r.startsWith("err")) failPage();
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
    /**
     * A save that the shell was going to do has failed (write error, permission
     * denied, picker cancelled). The page already knows how to hand the picture
     * over anyway - it keeps the encoded blob for exactly this case and shows it
     * in its own "long-press to save" overlay - and it can say the message in the
     * language the user picked. Before this, the shell showed its own hardcoded
     * Hinglish Toast, so an English-mode user got a Hindi message and, on the
     * API<29 permission path, got no picture at all: the page had already returned
     * after "ok:pending", so the blob was gone and there was nothing left to save.
     */
    private void failPage() {
        if (web == null) {
            jsToast("Save nahi hua");
            return;
        }
        web.post(new Runnable() {
            @Override
            public void run() {
                WebView w = web;
                if (w == null) return;
                try {
                    w.evaluateJavascript(
                            "window.GlassCut&&GlassCut.failed&&GlassCut.failed();", null);
                } catch (Exception e) {
                    jsToast("Save nahi hua");
                }
            }
        });
    }

    private void notifyPage() {
        if (web == null) return;
        web.post(new Runnable() {
            @Override
            public void run() {
                /* Ye ek hi evaluateJavascript site hai jispar try/catch nahi tha.
                   Runnables yahan baad me chalte hain, aur tab tak renderer mar
                   chuka ho (onRenderProcessGone) ya WebView destroy ho raha ho to
                   evaluateJavascript IllegalStateException phenkta hai - aur wo
                   UI thread par hota hai, yaani poora app band. Theek wahi lamha
                   jab ye chalta hai wo save ka aakhri step hai (thread se notify),
                   is liye crash us waqt hoti jab user ko "Save ho gaya" milna tha.
                   pushInsets/trimPage/failPage/pause/resume/back pehle se guarded
                   hain; ab saaton call sites ek jaise hain (6/7 -> 7/7). */
                WebView w = web;
                if (w == null) return;
                try {
                    w.evaluateJavascript("window.GlassCut&&GlassCut.saved&&GlassCut.saved();", null);
                } catch (Exception ignored) { }
            }
        });
    }

    /**
     * The page keeps a handful of caches that only exist to make the next frame
     * cheaper: the feather-blurred mask, the box-blur tables, the Grow/Shrink
     * copy buffer, the undo buffer pool, the JPEG flatten canvas and the island
     * scratch arrays. On a 1400x1200 photo that is ~27 MB of typed arrays held
     * for the whole session, even while the user is in another app. Android tells
     * us when memory is tight; the page then drops all of it (GlassCut.trim) and
     * rebuilds whatever it needs on the next render, so the picture on screen
     * does not change.
     */
    private void trimPage() {
        if (web == null) return;
        web.post(new Runnable() {
            @Override
            public void run() {
                WebView w = web;
                if (w == null) return;
                try {
                    w.evaluateJavascript(
                            "window.GlassCut&&GlassCut.trim&&GlassCut.trim();", null);
                } catch (Exception ignored) { }
            }
        });
    }

    /**
     * The page's GlassCut.trim() only drops the JS side (typed arrays, the feather
     * blur buffer, the undo pool, offscreen canvases). The WebView keeps its own
     * copy of the same picture - decoded image resources and its renderer caches -
     * and nothing ever asked it to let go. On a canvas-heavy page that second copy
     * is the bigger half, so memory pressure was answered with half a release.
     * freeMemory() is the documented hint for exactly this (it drops everything the
     * WebView can rebuild), and the page is a local asset with no network cache to
     * lose. Both calls are no-ops when there is nothing cached, so nothing on
     * screen changes - the next render rebuilds whatever it needs.
     */
    private void freeWebMemory(boolean hard) {
        WebView w = web;
        if (w == null) return;
        try { w.freeMemory(); } catch (Exception ignored) { }
        if (hard) {
            try { w.clearCache(false); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            trimPage();
            freeWebMemory(level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        trimPage();
        freeWebMemory(true);
    }

    private void jsToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // A save runs on its own thread and reports back when it is done.
                // If the user has already left the screen by then the activity is
                // gone, and Toast.makeText on a destroyed activity throws
                // WindowManager$BadTokenException - a crash caused purely by the
                // save finishing late. Say nothing instead.
                if (isFinishing() || isDestroyed()) return;
                try {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) { }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_WRITE) {
            final String d = pendingData, n = pendingName, m = pendingMime;
            pendingData = pendingName = pendingMime = null;
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) finishPendingSave(d, n, m);
            else failPage();
        }
    }

    /**
     * WebView apne aap background me JS timers nahi rokta: page ka 4D Motion loop
     * 60 ms par ek full-canvas render karta rehta tha jab tak app foreground me hai,
     * chahe user kisi doosri app me ho. onPause par page ko bata kar us loop ko rok
     * dete hain (aur WebView ko bhi pause kar dete hain), onResume par wapas chalu.
     */
    @Override
    protected void onPause() {
        /* Ye do call pehle bina guard ke thi. WebView ka renderer alag process hai;
           wo mar chuka ho (onRenderProcessGone se theek pehle) ya WebView destroy ho
           raha ho to evaluateJavascript / onPause IllegalStateException phenkte hain -
           yaani app theek us waqt crash karti jab user app se bahar nikalta (ya photo
           picker khulta) hai. notifyPage(), trimPage(), pushInsets() aur failPage()
           pehle se isi tarah guarded hain; ab pause/resume bhi. */
        WebView w = web;
        if (w != null) {
            try {
                w.evaluateJavascript(
                        "window.GlassCut&&GlassCut.pause&&GlassCut.pause();", null);
            } catch (Exception ignored) { }
            try { w.onPause(); } catch (Exception ignored) { }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WebView w = web;
        if (w != null) {
            try { w.onResume(); } catch (Exception ignored) { }
            try {
                w.evaluateJavascript(
                        "window.GlassCut&&GlassCut.resume&&GlassCut.resume();", null);
            } catch (Exception ignored) { }
        }
    }

    /**
     * Back used to finish the activity straight away, even when the page had an
     * open sheet (options / background) or the "saved" overlay on screen - the
     * thing the user wanted to dismiss stayed open and the whole app went away.
     * The page already closes a sheet on Escape; back now asks it first and only
     * leaves the app when the page says it has nothing to close.
     */
    private int backToken;
    private boolean backAnswered;

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        final int token = ++backToken;
        backAnswered = false;
        try {
            web.evaluateJavascript(
                    "(function(){try{return (window.GlassCut&&GlassCut.back&&GlassCut.back())?'1':'0'}"
                            + "catch(e){return '0'}})();",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String v) {
                            if (token != backToken || backAnswered) return;
                            backAnswered = true;
                            if (v == null || v.indexOf('1') < 0) finishFromBack();
                        }
                    });
        } catch (Exception e) {
            finishFromBack();
            return;
        }
        // A page that is busy (mid-save) must not be able to swallow the back press.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token != backToken || backAnswered) return;
                backAnswered = true;
                finishFromBack();
            }
        }, 600);
    }

    private void finishFromBack() {
        if (isFinishing() || isDestroyed()) return;
        super.onBackPressed();
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
