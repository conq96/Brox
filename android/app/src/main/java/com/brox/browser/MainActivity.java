package com.brox.browser;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import android.app.DownloadManager;
import android.database.Cursor;
import android.webkit.URLUtil;
import org.json.JSONArray;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.getcapacitor.BridgeActivity;

import org.json.JSONObject;

public class MainActivity extends BridgeActivity {

    private WebView siteView;
    private FrameLayout contentRoot;
    private WebChromeClient chromeClient;
    private boolean siteActive = false;
    private boolean overlayOpen = false;
    private boolean fullscreen = false;
    private boolean desktopMode = false;
    private boolean barOpen = false;
    private boolean forceZoom = false;
    private final java.util.LinkedHashMap<String, WebView> tabViews = new java.util.LinkedHashMap<>();
    private String activeTabKey = "";
    private boolean skipLoadOnce = false;
    private WebView utilView;
    private int currentTextZoom = 100;
    private boolean currentForceDark = false;
    private String lastPrivacyJson = null;
    private boolean backgroundPlayback = true;
    private boolean bgServiceRunning = false;
    private static final int MAX_TAB_VIEWS = 10;
    private android.speech.tts.TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeakLang;
    private String pendingSpeakText;
    private String mobileUserAgent;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Uygulama açılışında ekran görüntüsü engelini varsayılan olarak TEMİZLİYORUZ (Kapalı)
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);

        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (filePathCallback == null) return;
                Uri[] uris = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData());
                filePathCallback.onReceiveValue(uris);
                filePathCallback = null;
            });

        // Arayüzdeki JS'den (window.BroxNative) gelen istekleri dinlemek için köprü sınıfını ekliyoruz
        if (bridge != null && bridge.getWebView() != null) {
            WebView ui = bridge.getWebView();
            ui.addJavascriptInterface(new BroxNativeInterface(), "BroxNative");
            ui.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                if ((r - l) != (oldR - oldL) || (b - t) != (oldB - oldT)) relayoutSite();
            });
        }

        contentRoot = findViewById(android.R.id.content);
        BackgroundPlaybackService.stopMediaHook = () -> runOnUiThread(this::pauseAllMedia);
        String exitClear = getSharedPreferences("brox_prefs", MODE_PRIVATE).getString("exit_clear", "");
        if (!exitClear.isEmpty()) clearBrowsingData(exitClear);
        getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    // ---------- Site WebView (gerçek tarayıcı motoru) ----------

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createSiteWebView() {
        contentRoot = findViewById(android.R.id.content);
        // Her sekmenin kendi WebView'ı vardır (bu yerel değişken, sınıftaki siteView alanını gölgeler)
        final WebView siteView = new WebView(this);
        siteView.setBackgroundColor(Color.BLACK);
        siteView.setVisibility(View.GONE);

        WebSettings s = siteView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Sitelerin "gömülü tarayıcı" diye engellememesi için standart mobil tarayıcı kimliği
        String ua = s.getUserAgentString().replace("; wv", "").replaceAll("Version/\\d+\\.\\d+\\s", "");
        s.setUserAgentString(ua);
        mobileUserAgent = ua;
        s.setAllowFileAccess(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(siteView, true);

        siteView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String target = request.getUrl().toString();
                if (isBlockedText(target)) {
                    if (request.isForMainFrame()) loadBlockPage(view);
                    return true;
                }
                return handleUrl(target);
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    if (adMode > 0 && !request.isForMainFrame() && isBlockedAdHost(request.getUrl().getHost())) {
                        return new android.webkit.WebResourceResponse("text/plain", "UTF-8", new java.io.ByteArrayInputStream(new byte[0]));
                    }
                } catch (Exception ignored) {
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (view == MainActivity.this.siteView) {
                    notifyNav(url, null);
                    stopSpeaking();
                }
                if (dntEnabled) injectDnt(view);
                injectBackgroundScript(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view == MainActivity.this.siteView) notifyNav(url, view.getTitle());
                CookieManager.getInstance().flush();
                injectBackgroundScript(view);
                if (desktopMode) applyDesktopViewport(view);
                if (forceZoom) applyForceZoomScript(view);
            }
        });

        chromeClient = new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) injectBackgroundScript(view);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (view == MainActivity.this.siteView) notifyNav(view.getUrl(), title);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        String u = r.getUrl().toString();
                        if (isBlockedText(u)) loadBlockPage(siteView);
                        else if (!handleUrl(u)) siteView.loadUrl(u);
                        v.post(v::destroy);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    fileChooserLauncher.launch(params.createIntent());
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                view.setBackgroundColor(Color.BLACK);
                contentRoot.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                applyImmersive(true);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                contentRoot.removeView(customView);
                customView = null;
                WebChromeClient.CustomViewCallback cb = customViewCallback;
                customViewCallback = null;
                if (cb != null) cb.onCustomViewHidden();
                if (!fullscreen) applyImmersive(false);
            }
        };
        siteView.setWebChromeClient(chromeClient);
        siteView.setFindListener((active, total, done) -> {
            if (siteView == MainActivity.this.siteView) notifyFind(active, total);
        });
        siteView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
            startDownload(url, userAgent, contentDisposition, mimeType));

        contentRoot.addView(siteView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Yeni sekme görünümüne geçerli ayarlar uygulanır
        if (desktopMode) applyDesktopUa(siteView);
        s.setTextZoom(currentTextZoom);
        applyDarkTo(siteView, currentForceDark);
        if (lastPrivacyJson != null) applyPrivacyTo(siteView, lastPrivacyJson);
        return siteView;
    }

    // http/https dışındaki bağlantıları (tel:, mailto:, intent: ...) ilgili uygulamaya yönlendirir
    private boolean handleUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")
            || lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("javascript:")) {
            return false;
        }
        boolean isIntent = lower.startsWith("intent:");
        boolean simple = lower.startsWith("tel:") || lower.startsWith("mailto:") || lower.startsWith("sms:")
            || lower.startsWith("smsto:") || lower.startsWith("geo:");
        if (onlyBrowser || (blockAppPrompts && !simple)) {
            String fallback = null;
            if (isIntent) {
                try {
                    fallback = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url");
                } catch (Exception ignored) {
                }
            }
            if (fallback != null && (fallback.startsWith("http://") || fallback.startsWith("https://")) && siteView != null) {
                if (isBlockedText(fallback)) showBlockPageInternal();
                else siteView.loadUrl(fallback);
            } else {
                nativeToast("Uygulamaya yönlendirme engellendi");
            }
            return true;
        }
        try {
            Intent intent = lower.startsWith("intent:")
                ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setComponent(null);
            intent.setSelector(null);
            startActivity(intent);
        } catch (Exception ignored) {
        }
        return true;
    }

    // Adres çubuğunu ve sekme başlığını güncellemek için arayüze (JS) haber verir
    private void notifyNav(String url, String title) {
        if (url == null || bridge == null || bridge.getWebView() == null) return;
        String js = "window.onNativeNav && window.onNativeNav("
            + JSONObject.quote(url) + "," + JSONObject.quote(title == null ? "" : title) + ");";
        bridge.getWebView().evaluateJavascript(js, null);
    }

    // Site görünümünü araç çubuğunun hemen altına yerleştirir
    private void relayoutSite() {
        if (bridge == null || bridge.getWebView() == null || siteView == null) return;
        String js = "(function(){var t=document.getElementById('toolbar');if(!t)return 0;"
            + "var s=getComputedStyle(t);return s.display==='none'?0:t.getBoundingClientRect().bottom;})()";
        bridge.getWebView().evaluateJavascript(js, value -> {
            float cssTop = 52f;
            try {
                cssTop = Float.parseFloat(value.replace("\"", ""));
            } catch (Exception ignored) {
            }
            applySiteLayout(cssTop);
        });
    }

    private void applySiteLayout(float cssTop) {
        if (bridge == null || bridge.getWebView() == null || siteView == null || contentRoot == null) return;
        WebView ui = bridge.getWebView();
        float density = getResources().getDisplayMetrics().density;
        int topPx = Math.round(cssTop * density);
        int[] root = new int[2];
        int[] pos = new int[2];
        contentRoot.getLocationOnScreen(root);
        ui.getLocationOnScreen(pos);
        int w = ui.getWidth() > 0 ? ui.getWidth() : ViewGroup.LayoutParams.MATCH_PARENT;
        int h = ui.getHeight() > 0 ? Math.max(0, ui.getHeight() - topPx) : ViewGroup.LayoutParams.MATCH_PARENT;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = pos[0] - root[0];
        lp.topMargin = (pos[1] - root[1]) + topPx;
        siteView.setLayoutParams(lp);
    }

    private void applySiteVisibility() {
        for (WebView w : tabViews.values()) {
            if (siteActive && w == siteView) w.setVisibility(overlayOpen ? View.INVISIBLE : View.VISIBLE);
            else w.setVisibility(View.GONE);
        }
    }

    // Etkin sekmenin sayfasını kapatır (kısayol ekranına dönüş); diğer sekmeler çalışmaya devam eder
    private void goHome(boolean notifyUi) {
        WebView v = siteView;
        if (v != null) {
            tabViews.remove(activeTabKey);
            destroyTabView(v);
        }
        siteView = null;
        siteActive = false;
        applySiteVisibility();
        if (notifyUi && bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().evaluateJavascript("window.resetBrowser && window.resetBrowser();", null);
        }
    }

    // ---------- Sekme başına canlı sayfalar (arka planda oynatma için) ----------

    private void destroyTabView(WebView v) {
        try {
            v.stopLoading();
            v.loadUrl("about:blank");
            if (contentRoot != null) contentRoot.removeView(v);
            v.destroy();
        } catch (Exception ignored) {
        }
    }

    private WebView ensureActiveView() {
        if (activeTabKey == null || activeTabKey.isEmpty()) activeTabKey = "default";
        WebView v = tabViews.get(activeTabKey);
        if (v == null) {
            v = createSiteWebView();
        } else {
            tabViews.remove(activeTabKey);
        }
        tabViews.put(activeTabKey, v);
        siteView = v;
        siteActive = true;
        trimTabViews();
        return v;
    }

    private void trimTabViews() {
        while (tabViews.size() > MAX_TAB_VIEWS) {
            String victim = null;
            for (String k : tabViews.keySet()) {
                if (!k.equals(activeTabKey)) {
                    victim = k;
                    break;
                }
            }
            if (victim == null) break;
            destroyTabView(tabViews.remove(victim));
        }
    }

    private void selectTabInternal(String key) {
        String prev = activeTabKey;
        activeTabKey = key == null ? "" : key;
        WebView v = tabViews.get(activeTabKey);
        boolean adopted = false;
        if (v == null && "default".equals(prev) && !"default".equals(activeTabKey) && tabViews.containsKey("default")) {
            v = tabViews.remove("default");
            adopted = true;
        }
        if (v != null) {
            tabViews.remove(activeTabKey);
            tabViews.put(activeTabKey, v);
            siteView = v;
            siteActive = true;
            if (!adopted) {
                skipLoadOnce = true;
                if (contentRoot != null) contentRoot.postDelayed(() -> skipLoadOnce = false, 1500);
            }
            applySiteVisibility();
            relayoutSite();
            notifyNav(v.getUrl(), v.getTitle());
        } else {
            siteView = null;
            siteActive = false;
            skipLoadOnce = false;
            applySiteVisibility();
        }
    }

    private void syncTabViews(String idsCsv) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        if (idsCsv != null && !idsCsv.isEmpty()) keep.addAll(java.util.Arrays.asList(idsCsv.split(",")));
        java.util.List<String> drop = new java.util.ArrayList<>();
        for (String k : tabViews.keySet()) {
            if (!keep.contains(k)) drop.add(k);
        }
        for (String k : drop) {
            WebView v = tabViews.remove(k);
            if (v == siteView) {
                siteView = null;
                siteActive = false;
            }
            destroyTabView(v);
        }
        if (!drop.isEmpty()) applySiteVisibility();
    }

    // Etkin sayfa yokken bile tüm WebView'lara ortak işlemler (önbellek vb.) için bir görünüm döndürür
    private WebView anyView() {
        if (siteView != null) return siteView;
        for (WebView w : tabViews.values()) return w;
        if (utilView == null) utilView = new WebView(this);
        return utilView;
    }

    // ---------- Arka planda oynatma ----------

    private static final String BG_SCRIPT = "(function(){try{if(window.__broxBg)return;window.__broxBg=true;var d=document;"
        + "function def(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v;},configurable:true});}catch(e){}}"
        + "def(d,'hidden',false);def(d,'visibilityState','visible');def(d,'webkitHidden',false);def(d,'webkitVisibilityState','visible');"
        + "try{d.hasFocus=function(){return true;};}catch(e){}"
        + "var stop=function(e){e.stopImmediatePropagation();};"
        + "var stopWin=function(e){if(e.target===window||e.target===document)e.stopImmediatePropagation();};"
        + "d.addEventListener('visibilitychange',stop,true);d.addEventListener('webkitvisibilitychange',stop,true);"
        + "window.addEventListener('blur',stopWin,true);window.addEventListener('pagehide',stopWin,true);"
        + "}catch(e){}})();";

    private void injectBackgroundScript(WebView v) {
        if (backgroundPlayback && v != null) v.evaluateJavascript(BG_SCRIPT, null);
    }

    private void pauseAllMedia() {
        for (WebView w : tabViews.values()) {
            w.evaluateJavascript("(function(){var l=document.querySelectorAll('video,audio');"
                + "for(var i=0;i<l.length;i++){try{l[i].pause();}catch(e){}}})();", null);
        }
    }

    private void startBackgroundService() {
        if (!backgroundPlayback || bgServiceRunning) return;
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null || !am.isMusicActive()) return;
            Intent i = new Intent(this, BackgroundPlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            bgServiceRunning = true;
        } catch (Exception ignored) {
        }
    }

    private void stopBackgroundService() {
        try {
            stopService(new Intent(this, BackgroundPlaybackService.class));
        } catch (Exception ignored) {
        }
        bgServiceRunning = false;
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive(boolean enable) {
        View decorView = getWindow().getDecorView();
        if (enable) {
            // Sayfa boyutunu/yakınlığını bozmadan gerçek tam ekran (Immersive Sticky Mode)
            decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        } else {
            // Normal görünüme dön (Durum çubuğu ve navigasyon geri gelsin)
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    // ---------- Adım 4: bul, yakınlaştır, masaüstü, paylaş, yazdır, kaydet ----------

    private void nativeToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void notifyFind(int active, int total) {
        if (bridge == null || bridge.getWebView() == null) return;
        bridge.getWebView().evaluateJavascript(
            "window.onFindResult && window.onFindResult(" + active + "," + total + ");", null);
    }

    private void applyDesktopViewport(WebView view) {
        view.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]');"
            + "if(m){m.setAttribute('content','width=1024');}"
            + "else{m=document.createElement('meta');m.name='viewport';m.content='width=1024';document.head.appendChild(m);}})();", null);
    }

    private void applyDesktopUa(WebView v) {
        WebSettings s = v.getSettings();
        if (desktopMode) {
            String def = mobileUserAgent != null ? mobileUserAgent : s.getUserAgentString();
            String chrome = "Chrome/120.0.0.0";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/[\\d.]+").matcher(def);
            if (m.find()) chrome = m.group();
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " + chrome + " Safari/537.36");
        } else if (mobileUserAgent != null) {
            s.setUserAgentString(mobileUserAgent);
        }
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
    }

    private void setDesktopModeInternal(boolean enable) {
        desktopMode = enable;
        for (WebView w : tabViews.values()) applyDesktopUa(w);
        if (siteView != null && siteActive) siteView.reload();
    }

    private void shareCurrent() {
        String url = siteView == null ? null : siteView.getUrl();
        if (siteView == null || !siteActive || url == null || url.equals("about:blank")) {
            nativeToast("Paylaşılabilecek sayfa yok");
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        String title = siteView.getTitle();
        if (title != null && !title.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(send, "Paylaş"));
    }

    private void printCurrent() {
        if (siteView == null || !siteActive) {
            nativeToast("Yazdırılacak sayfa yok");
            return;
        }
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            String title = siteView.getTitle();
            String name = "Brox - " + (title == null || title.isEmpty() ? "Sayfa" : title);
            PrintDocumentAdapter adapter = siteView.createPrintDocumentAdapter(name);
            pm.print(name, adapter, new PrintAttributes.Builder().build());
        } catch (Exception e) {
            nativeToast("Yazdırma başlatılamadı");
        }
    }

    private String safeName(String title) {
        String t = (title == null || title.trim().isEmpty()) ? "sayfa" : title.trim();
        t = t.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_");
        if (t.length() > 60) t = t.substring(0, 60);
        return t;
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private boolean copyToDownloads(File src, String name) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Brox");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(uri);
                     InputStream in = new FileInputStream(src)) {
                    copyStream(in, out);
                }
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Brox");
                dir.mkdirs();
                try (OutputStream out = new FileOutputStream(new File(dir, name));
                     InputStream in = new FileInputStream(src)) {
                    copyStream(in, out);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyJs(String js) {
        if (bridge == null || bridge.getWebView() == null) return;
        bridge.getWebView().evaluateJavascript(js, null);
    }

    private String copyToPrivate(File src, String name) {
        try {
            File dir = new File(getFilesDir(), "saved");
            dir.mkdirs();
            File dest = new File(dir, System.currentTimeMillis() + "_" + name);
            try (OutputStream out = new FileOutputStream(dest);
                 InputStream in = new FileInputStream(src)) {
                copyStream(in, out);
            }
            return dest.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void saveCurrent() {
        if (siteView == null || !siteActive) {
            nativeToast("Kaydedilecek sayfa yok");
            return;
        }
        final String pageUrl = siteView.getUrl();
        final String name = safeName(siteView.getTitle()) + ".mht";
        final File tmp = new File(getCacheDir(), "kayit_" + System.currentTimeMillis() + ".mht");
        siteView.saveWebArchive(tmp.getAbsolutePath(), false, path -> {
            if (path == null) {
                nativeToast("Sayfa kaydedilemedi");
                return;
            }
            new Thread(() -> {
                boolean ok = copyToDownloads(tmp, name);
                String priv = copyToPrivate(tmp, name);
                tmp.delete();
                runOnUiThread(() -> {
                    nativeToast(ok || priv != null ? "Sayfa kaydedildi (İndirilenler listesinde)" : "Sayfa kaydedilemedi");
                    if (priv != null) {
                        notifyJs("window.onPageSaved && window.onPageSaved(" + JSONObject.quote(name) + ","
                            + JSONObject.quote(priv) + "," + JSONObject.quote(pageUrl == null ? "" : pageUrl) + ");");
                    }
                });
            }).start();
        });
    }

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            nativeToast("Bu indirme türü desteklenmiyor");
            return;
        }
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) req.setMimeType(mimeType);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Brox/" + fileName);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            long id = dm.enqueue(req);
            nativeToast("İndirme başladı: " + fileName);
            notifyJs("window.onDownloadStarted && window.onDownloadStarted(" + id + ","
                + JSONObject.quote(fileName) + "," + JSONObject.quote(url) + ");");
        } catch (Exception e) {
            nativeToast("İndirme başlatılamadı");
        }
    }

    private void openDownloadInternal(String idStr) {
        try {
            long id = Long.parseLong(idStr);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) {
                nativeToast("Dosya bulunamadı");
                return;
            }
            String mime = dm.getMimeTypeForDownloadedFile(id);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, mime == null ? "*/*" : mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        } catch (Exception e) {
            nativeToast("Bu dosyayı açabilecek uygulama bulunamadı");
        }
    }

    // ---------- Adım 5b: tarama verilerini silme ----------

    // Site verileri silinirken uygulamanın kendi arayüz verisi (localhost) korunur
    private void clearSiteStorage(final String host) {
        final android.webkit.WebStorage ws = android.webkit.WebStorage.getInstance();
        if (host == null) {
            ws.getOrigins(new ValueCallback<java.util.Map>() {
                @Override
                public void onReceiveValue(java.util.Map map) {
                    if (map == null) return;
                    for (Object key : map.keySet()) {
                        String origin = String.valueOf(key);
                        if (origin.contains("//localhost")) continue;
                        ws.deleteOrigin(origin);
                    }
                }
            });
        } else {
            for (String scheme : new String[]{"https://", "http://"}) {
                ws.deleteOrigin(scheme + host);
                ws.deleteOrigin(scheme + "www." + host);
            }
            ws.getOrigins(new ValueCallback<java.util.Map>() {
                @Override
                public void onReceiveValue(java.util.Map map) {
                    if (map == null) return;
                    for (Object key : map.keySet()) {
                        String origin = String.valueOf(key);
                        String h = origin.replaceFirst("^[a-z]+://", "").replaceFirst(":\\d+$", "");
                        if (h.equals(host) || h.endsWith("." + host)) ws.deleteOrigin(origin);
                    }
                }
            });
        }
    }

    private void clearBrowsingData(String typesCsv) {
        if (typesCsv == null || typesCsv.isEmpty()) return;
        java.util.List<String> types = java.util.Arrays.asList(typesCsv.split(","));
        if (types.contains("cookies")) {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        }
        WebView any = anyView();
        if (types.contains("cache")) any.clearCache(true);
        if (types.contains("form")) any.clearFormData();
        if (types.contains("history")) {
            for (WebView w : tabViews.values()) w.clearHistory();
        }
        if (types.contains("form")) {
            android.webkit.WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword();
        }
        if (types.contains("site")) clearSiteStorage(null);
    }

    private void clearHostInternal(String host) {
        if (host == null || !host.matches("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?")) return;
        CookieManager cm = CookieManager.getInstance();
        java.util.List<String> domains = new java.util.ArrayList<>();
        String[] labels = host.split("\\.");
        for (int i = 0; i < labels.length - 1; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < labels.length; j++) {
                if (j > i) sb.append('.');
                sb.append(labels[j]);
            }
            domains.add(sb.toString());
        }
        for (String scheme : new String[]{"https://", "http://"}) {
            String url = scheme + host + "/";
            String cookies = cm.getCookie(url);
            if (cookies == null) continue;
            for (String pair : cookies.split(";")) {
                String name = pair.split("=", 2)[0].trim();
                if (name.isEmpty()) continue;
                cm.setCookie(url, name + "=; Max-Age=0; Path=/");
                for (String d : domains) {
                    cm.setCookie(url, name + "=; Max-Age=0; Path=/; Domain=" + d);
                    cm.setCookie(url, name + "=; Max-Age=0; Path=/; Domain=." + d);
                }
            }
        }
        cm.flush();
        clearSiteStorage(host);
    }

    // ---------- Adım 6: görünüm ve seslendirme ----------

    private void applyForceZoomScript(WebView view) {
        view.evaluateJavascript("(function(){var m=document.querySelector('meta[name=viewport]');"
            + "if(!m)return;var c=m.getAttribute('content')||'';"
            + "c=c.replace(/,?\\s*(user-scalable|maximum-scale|minimum-scale)\\s*=\\s*[^,;]+/ig,'');"
            + "c=(c+',user-scalable=yes,maximum-scale=10').replace(/^\\s*,/,'');"
            + "m.setAttribute('content',c);})();", null);
    }

    @SuppressWarnings("deprecation")
    private void applyDarkTo(WebView v, boolean on) {
        WebSettings s = v.getSettings();
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(on);
        } else if (Build.VERSION.SDK_INT >= 29) {
            s.setForceDark(on ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
        }
    }

    private void applyForceDarkInternal(boolean on, boolean reload) {
        currentForceDark = on;
        for (WebView w : tabViews.values()) applyDarkTo(w, on);
        if (reload && siteView != null && siteActive) siteView.reload();
    }

    private void stopSpeaking() {
        pendingSpeakText = null;
        if (tts != null && ttsReady) tts.stop();
    }

    private void startSpeaking(String lang, String text) {
        pendingSpeakLang = lang;
        pendingSpeakText = text;
        if (tts == null) {
            tts = new android.speech.tts.TextToSpeech(this, status -> runOnUiThread(() -> {
                ttsReady = (status == android.speech.tts.TextToSpeech.SUCCESS);
                if (ttsReady) {
                    speakPending();
                } else {
                    if (tts != null) tts.shutdown();
                    tts = null;
                    nativeToast("Seslendirme bu cihazda kullanılamıyor");
                }
            }));
        } else if (ttsReady) {
            speakPending();
        }
    }

    private void speakPending() {
        if (pendingSpeakText == null || tts == null) return;
        java.util.Locale loc = (pendingSpeakLang == null || pendingSpeakLang.isEmpty())
            ? java.util.Locale.getDefault() : java.util.Locale.forLanguageTag(pendingSpeakLang);
        int r = tts.setLanguage(loc);
        if (r == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || r == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(java.util.Locale.getDefault());
        }
        tts.stop();
        String text = pendingSpeakText;
        pendingSpeakText = null;
        int max = Math.max(500, android.speech.tts.TextToSpeech.getMaxSpeechInputLength() - 100);
        int i = 0;
        int n = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + max);
            if (end < text.length()) {
                int cut = text.lastIndexOf(' ', end);
                if (cut > i + max / 2) end = cut;
            }
            tts.speak(text.substring(i, end), android.speech.tts.TextToSpeech.QUEUE_ADD, null, "brox" + (n++));
            i = end;
        }
        nativeToast("Sayfa seslendiriliyor (durdurmak için tekrar dokunun)");
    }

    // ---------- Adım 7: gizlilik ve güvenlik ----------

    private static final String[] AD_HOSTS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "amazon-adsystem.com", "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net",
        "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "adsrvr.org", "casalemedia.com",
        "smartadserver.com", "advertising.com", "adform.net", "teads.tv", "media.net", "yieldmo.com",
        "sharethrough.com", "triplelift.com", "indexww.com", "bidswitch.net", "contextweb.com",
        "sovrn.com", "adcolony.com", "applovin.com", "inmobi.com", "mopub.com", "moatads.com",
        "2mdn.net", "serving-sys.com", "zedo.com", "revcontent.com", "mgid.com", "adroll.com",
        "exoclick.com", "propellerads.com", "popads.net", "popcash.net", "adsterra.com",
        "juicyads.com", "trafficjunky.net", "adtech.com", "adbrite.com", "adblade.com"
    };

    private static final String[] TRACKER_HOSTS = {
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "analytics.google.com",
        "connect.facebook.net", "scorecardresearch.com", "quantserve.com", "quantcast.com", "hotjar.com",
        "mixpanel.com", "segment.io", "segment.com", "amplitude.com", "fullstory.com", "mouseflow.com",
        "crazyegg.com", "chartbeat.com", "optimizely.com", "bat.bing.com", "clarity.ms", "ads-twitter.com",
        "analytics.twitter.com", "px.ads.linkedin.com", "snap.licdn.com", "analytics.tiktok.com",
        "tr.snapchat.com", "appsflyer.com", "adjust.com", "kochava.com", "mc.yandex.ru", "mc.yandex.com",
        "demdex.net", "omtrdc.net", "everesttech.net", "bluekai.com", "krxd.net", "rlcdn.com",
        "addthis.com", "sharethis.com"
    };

    private volatile int adMode = 0;
    private volatile boolean dntEnabled = false;
    private volatile boolean onlyBrowser = false;
    private volatile boolean blockAppPrompts = false;

    private boolean hostIn(String host, String[] list) {
        for (String d : list) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    private boolean isBlockedAdHost(String host) {
        int mode = adMode;
        if (mode == 0 || host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return hostIn(h, AD_HOSTS) || (mode >= 2 && hostIn(h, TRACKER_HOSTS));
    }

    private java.util.List<String> blockList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            JSONArray arr = new JSONArray(getSharedPreferences("brox_block", MODE_PRIVATE).getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private boolean isBlockedText(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        String s;
        try {
            s = java.net.URLDecoder.decode(raw, "UTF-8");
        } catch (Exception e) {
            s = raw;
        }
        s = s.toLowerCase(java.util.Locale.ROOT);
        String host = "";
        try {
            String hh = Uri.parse(raw).getHost();
            if (hh != null) host = hh.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ignored) {
        }
        for (String e : blockList()) {
            if (e.length() < 3) continue;
            String val = e.substring(2);
            if (e.startsWith("h:")) {
                if (host.equals(val) || host.endsWith("." + val)) return true;
                if (host.isEmpty() && s.contains(val)) return true;
            } else if (e.startsWith("k:")) {
                if (s.contains(val)) return true;
            }
        }
        return false;
    }

    private boolean addBlockEntryInternal(String entryRaw) {
        if (entryRaw == null) return false;
        String entry = entryRaw.trim().toLowerCase(java.util.Locale.ROOT);
        boolean isHost = entry.startsWith("h:");
        boolean isKeyword = entry.startsWith("k:");
        if (!isHost && !isKeyword) return false;
        String val = entry.substring(2);
        if (val.length() < 3 || val.length() > 100) return false;
        if (isHost && !val.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z0-9-]{2,}")) return false;
        try {
            android.content.SharedPreferences sp = getSharedPreferences("brox_block", MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("list", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                if (entry.equals(arr.getString(i))) return true;
            }
            arr.put(entry);
            sp.edit().putString("list", arr.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadBlockPage(WebView v) {
        if (v == null) return;
        String html = "<!doctype html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>html,body{margin:0;height:100%;background:#000;color:#B0B0B0;font-family:sans-serif}"
            + "body{display:flex;align-items:center;justify-content:center;text-align:center;padding:24px;box-sizing:border-box}"
            + "h1{font-size:20px;font-weight:600;margin:0 0 10px}p{font-size:14px;color:#8A8A8A;margin:0}</style></head>"
            + "<body><div><h1>Girmemeniz gereken bir URL veya kelime yazdınız!</h1><p>Bu içerik engellendi.</p></div></body></html>";
        v.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void showBlockPageInternal() {
        WebView v = ensureActiveView();
        applySiteVisibility();
        relayoutSite();
        loadBlockPage(v);
    }

    private void loadWithHeaders(String url) {
        if (dntEnabled && (url.startsWith("http://") || url.startsWith("https://"))) {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("DNT", "1");
            headers.put("Sec-GPC", "1");
            siteView.loadUrl(url, headers);
        } else {
            siteView.loadUrl(url);
        }
    }

    private void injectDnt(WebView view) {
        view.evaluateJavascript("try{Object.defineProperty(navigator,'doNotTrack',{get:function(){return '1';}});"
            + "Object.defineProperty(navigator,'globalPrivacyControl',{get:function(){return true;}});}catch(e){}", null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applyPrivacyTo(WebView v, String json) {
        try {
            JSONObject o = new JSONObject(json);
            WebSettings s = v.getSettings();
            if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(o.optBoolean("safeBrowsing", true));
            CookieManager.getInstance().setAcceptThirdPartyCookies(v, o.optString("cookies", "allow").equals("allow"));
            boolean js = o.optBoolean("js", true);
            boolean jsChanged = s.getJavaScriptEnabled() != js;
            s.setJavaScriptEnabled(js);
            if (jsChanged && v == siteView && siteActive) v.reload();
        } catch (Exception ignored) {
        }
    }

    private void applyPrivacyInternal(String json) {
        if (json == null) return;
        lastPrivacyJson = json;
        try {
            JSONObject o = new JSONObject(json);
            CookieManager.getInstance().setAcceptCookie(!o.optString("cookies", "allow").equals("all"));
            String ads = o.optString("ads", "allow");
            adMode = ads.equals("strict") ? 2 : (ads.equals("standard") ? 1 : 0);
            dntEnabled = o.optBoolean("dnt", false);
            onlyBrowser = o.optBoolean("onlyBrowser", false);
            blockAppPrompts = o.optBoolean("blockApp", false);
        } catch (Exception ignored) {
        }
        for (WebView w : tabViews.values()) applyPrivacyTo(w, json);
    }

    // ---------- Donanım Geri tuşu ----------

    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (barOpen) {
                bridge.getWebView().evaluateJavascript("window.closeBars && window.closeBars();", null);
                return;
            }
            if (customView != null) {
                chromeClient.onHideCustomView();
                return;
            }
            if (fullscreen) {
                fullscreen = false;
                applyImmersive(false);
                bridge.getWebView().evaluateJavascript(
                    "window.exitFullscreen && window.exitFullscreen();", v -> relayoutSite());
                return;
            }
            if (overlayOpen) {
                bridge.getWebView().evaluateJavascript(
                    "['dropdown-menu','tabs-menu','tabs-modal','settings-modal','shortcut-menu-modal']"
                    + ".forEach(function(i){var e=document.getElementById(i);if(e)e.style.display='none';});"
                    + "window.closeAllOverlays && window.closeAllOverlays();", null);
                return;
            }
            if (siteActive) {
                if (siteView.canGoBack()) siteView.goBack();
                else goHome(true);
                return;
            }
            setEnabled(false);
            getOnBackPressedDispatcher().onBackPressed();
            setEnabled(true);
        }
    };

    @Override
    public void onPause() {
        super.onPause();
        startBackgroundService();
    }

    @Override
    public void onResume() {
        super.onResume();
        stopBackgroundService();
    }

    @Override
    public void onDestroy() {
        BackgroundPlaybackService.stopMediaHook = null;
        stopBackgroundService();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (isFinishing()) {
            String exitClear = getSharedPreferences("brox_prefs", MODE_PRIVATE).getString("exit_clear", "");
            if (!exitClear.isEmpty()) clearBrowsingData(exitClear);
        }
        for (WebView w : new java.util.ArrayList<>(tabViews.values())) destroyTabView(w);
        tabViews.clear();
        siteView = null;
        if (utilView != null) {
            utilView.destroy();
            utilView = null;
        }
        super.onDestroy();
    }

    // ---------- JS (window.BroxNative) köprüsü ----------

    public class BroxNativeInterface {

        @JavascriptInterface
        public void setScreenshotProtection(boolean enable) {
            runOnUiThread(() -> {
                if (enable) {
                    getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                }
            });
        }

        @JavascriptInterface
        public void setFullscreen(boolean enable) {
            runOnUiThread(() -> {
                fullscreen = enable;
                applyImmersive(enable);
                relayoutSite();
            });
        }

        @JavascriptInterface
        public void loadUrl(String url) {
            runOnUiThread(() -> {
                if (url == null) return;
                if (skipLoadOnce && siteView != null) {
                    skipLoadOnce = false;
                    applySiteVisibility();
                    relayoutSite();
                    return;
                }
                ensureActiveView();
                applySiteVisibility();
                relayoutSite();
                if (isBlockedText(url)) {
                    showBlockPageInternal();
                    return;
                }
                loadWithHeaders(url);
            });
        }

        @JavascriptInterface
        public void hideWebView() {
            runOnUiThread(() -> {
                if (siteView != null) goHome(false);
            });
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(() -> {
                if (siteView != null && siteActive) siteView.reload();
            });
        }

        @JavascriptInterface
        public void goBack() {
            runOnUiThread(() -> {
                if (siteView == null || !siteActive) return;
                if (siteView.canGoBack()) siteView.goBack();
                else goHome(true);
            });
        }

        @JavascriptInterface
        public void goForward() {
            runOnUiThread(() -> {
                if (siteView != null && siteActive && siteView.canGoForward()) siteView.goForward();
            });
        }

        @JavascriptInterface
        public void setOverlayOpen(boolean open) {
            runOnUiThread(() -> {
                overlayOpen = open;
                applySiteVisibility();
            });
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> nativeToast(msg));
        }

        @JavascriptInterface
        public void setBarOpen(boolean open) {
            runOnUiThread(() -> {
                barOpen = open;
            });
        }

        @JavascriptInterface
        public void findInPage(String query) {
            runOnUiThread(() -> {
                if (siteView == null) return;
                if (query == null || query.isEmpty()) {
                    siteView.clearMatches();
                    notifyFind(0, 0);
                } else {
                    siteView.findAllAsync(query);
                }
            });
        }

        @JavascriptInterface
        public void findNext(boolean forward) {
            runOnUiThread(() -> {
                if (siteView != null) siteView.findNext(forward);
            });
        }

        @JavascriptInterface
        public void clearFind() {
            runOnUiThread(() -> {
                if (siteView != null) siteView.clearMatches();
            });
        }

        @JavascriptInterface
        public void setTextZoom(int percent) {
            runOnUiThread(() -> {
                currentTextZoom = Math.max(25, Math.min(400, percent));
                for (WebView w : tabViews.values()) w.getSettings().setTextZoom(currentTextZoom);
            });
        }

        @JavascriptInterface
        public void setDesktopMode(boolean enable) {
            runOnUiThread(() -> setDesktopModeInternal(enable));
        }

        @JavascriptInterface
        public void sharePage() {
            runOnUiThread(() -> shareCurrent());
        }

        @JavascriptInterface
        public void printPage() {
            runOnUiThread(() -> printCurrent());
        }

        @JavascriptInterface
        public void savePage() {
            runOnUiThread(() -> saveCurrent());
        }

        @JavascriptInterface
        public void evalInPage(String js) {
            runOnUiThread(() -> {
                if (siteView == null || !siteActive || js == null) return;
                siteView.evaluateJavascript(js, value -> {
                    if (bridge == null || bridge.getWebView() == null) return;
                    bridge.getWebView().evaluateJavascript("window.onPageEval && window.onPageEval("
                        + JSONObject.quote(value == null ? "" : value) + ");", null);
                });
            });
        }

        @JavascriptInterface
        public String getDownloadStatus(String idsCsv) {
            JSONArray out = new JSONArray();
            try {
                if (idsCsv == null || idsCsv.isEmpty()) return "[]";
                String[] parts = idsCsv.split(",");
                long[] ids = new long[parts.length];
                for (int i = 0; i < parts.length; i++) ids[i] = Long.parseLong(parts[i].trim());
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                Cursor c = dm.query(new DownloadManager.Query().setFilterById(ids));
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            JSONObject o = new JSONObject();
                            o.put("id", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)));
                            o.put("status", c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)));
                            o.put("bytes", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                            o.put("total", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
                            out.put(o);
                        }
                    } finally {
                        c.close();
                    }
                }
            } catch (Exception e) {
                return "[]";
            }
            return out.toString();
        }

        @JavascriptInterface
        public void openDownload(String idStr) {
            runOnUiThread(() -> openDownloadInternal(idStr));
        }

        @JavascriptInterface
        public void deleteSavedFile(String path) {
            try {
                File f = new File(path);
                File base = new File(getFilesDir(), "saved");
                if (f.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) f.delete();
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void clearData(String typesCsv) {
            runOnUiThread(() -> clearBrowsingData(typesCsv));
        }

        @JavascriptInterface
        public void clearHostData(String host) {
            runOnUiThread(() -> clearHostInternal(host));
        }

        @JavascriptInterface
        public void setExitClear(String typesCsv) {
            getSharedPreferences("brox_prefs", android.content.Context.MODE_PRIVATE).edit()
                .putString("exit_clear", typesCsv == null ? "" : typesCsv).apply();
        }

        @JavascriptInterface
        public void setForceDark(boolean on, boolean reload) {
            runOnUiThread(() -> applyForceDarkInternal(on, reload));
        }

        @JavascriptInterface
        public void setForceZoom(boolean on) {
            runOnUiThread(() -> {
                forceZoom = on;
                if (on && siteView != null && siteActive) applyForceZoomScript(siteView);
            });
        }

        @JavascriptInterface
        public void speakPage(String js) {
            runOnUiThread(() -> {
                if (tts != null && ttsReady && tts.isSpeaking()) {
                    tts.stop();
                    nativeToast("Seslendirme durduruldu");
                    return;
                }
                if (siteView == null || !siteActive || js == null) return;
                siteView.evaluateJavascript(js, value -> {
                    try {
                        Object o = new org.json.JSONTokener(value).nextValue();
                        String s = o instanceof String ? (String) o : "";
                        int nl = s.indexOf('\n');
                        String lang = nl > 0 ? s.substring(0, nl).trim() : "";
                        String text = nl >= 0 ? s.substring(nl + 1).trim() : s.trim();
                        if (text.length() < 20) {
                            nativeToast("Seslendirilecek metin bulunamadı");
                            return;
                        }
                        startSpeaking(lang, text);
                    } catch (Exception e) {
                        nativeToast("Seslendirilecek metin bulunamadı");
                    }
                });
            });
        }

        @JavascriptInterface
        public void applyPrivacy(String json) {
            runOnUiThread(() -> applyPrivacyInternal(json));
        }

        @JavascriptInterface
        public boolean isBlocked(String text) {
            return isBlockedText(text);
        }

        @JavascriptInterface
        public boolean addBlockEntry(String entry) {
            return addBlockEntryInternal(entry);
        }

        @JavascriptInterface
        public String getBlocklist() {
            return new JSONArray(blockList()).toString();
        }

        @JavascriptInterface
        public void showBlockPage() {
            runOnUiThread(() -> showBlockPageInternal());
        }

        @JavascriptInterface
        public void selectTab(String id) {
            runOnUiThread(() -> selectTabInternal(id));
        }

        @JavascriptInterface
        public void syncTabs(String idsCsv) {
            runOnUiThread(() -> syncTabViews(idsCsv));
        }

        @JavascriptInterface
        public void setBackgroundPlayback(boolean on) {
            runOnUiThread(() -> {
                backgroundPlayback = on;
                if (!on) stopBackgroundService();
            });
        }
    }
}
