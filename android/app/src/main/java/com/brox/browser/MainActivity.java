package com.brox.browser;

import android.annotation.SuppressLint;
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

        setupSiteView();
        getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    // ---------- Site WebView (gerçek tarayıcı motoru) ----------

    @SuppressLint("SetJavaScriptEnabled")
    private void setupSiteView() {
        contentRoot = findViewById(android.R.id.content);
        siteView = new WebView(this);
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

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(siteView, true);

        siteView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                notifyNav(url, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                notifyNav(url, view.getTitle());
                CookieManager.getInstance().flush();
            }
        });

        chromeClient = new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                notifyNav(view.getUrl(), title);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        String u = r.getUrl().toString();
                        if (!handleUrl(u)) siteView.loadUrl(u);
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
                setImmersive(true);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                contentRoot.removeView(customView);
                customView = null;
                WebChromeClient.CustomViewCallback cb = customViewCallback;
                customViewCallback = null;
                if (cb != null) cb.onCustomViewHidden();
                if (!fullscreen) setImmersive(false);
            }
        };
        siteView.setWebChromeClient(chromeClient);

        contentRoot.addView(siteView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // http/https dışındaki bağlantıları (tel:, mailto:, intent: ...) ilgili uygulamaya yönlendirir
    private boolean handleUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")
            || lower.startsWith("data:") || lower.startsWith("blob:") || lower.startsWith("javascript:")) {
            return false;
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
        if (siteView == null) return;
        if (!siteActive) siteView.setVisibility(View.GONE);
        else siteView.setVisibility(overlayOpen ? View.INVISIBLE : View.VISIBLE);
    }

    private void goHome(boolean notifyUi) {
        siteActive = false;
        siteView.stopLoading();
        siteView.loadUrl("about:blank");
        siteView.clearHistory();
        applySiteVisibility();
        if (notifyUi && bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().evaluateJavascript("window.resetBrowser && window.resetBrowser();", null);
        }
    }

    @SuppressWarnings("deprecation")
    private void setImmersive(boolean enable) {
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

    // ---------- Donanım Geri tuşu ----------

    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            if (customView != null) {
                chromeClient.onHideCustomView();
                return;
            }
            if (fullscreen) {
                fullscreen = false;
                setImmersive(false);
                bridge.getWebView().evaluateJavascript(
                    "window.exitFullscreen && window.exitFullscreen();", v -> relayoutSite());
                return;
            }
            if (overlayOpen) {
                bridge.getWebView().evaluateJavascript(
                    "['dropdown-menu','tabs-menu','tabs-modal','settings-modal','shortcut-menu-modal']"
                    + ".forEach(function(i){var e=document.getElementById(i);if(e)e.style.display='none';});", null);
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
    protected void onDestroy() {
        if (siteView != null) {
            if (contentRoot != null) contentRoot.removeView(siteView);
            siteView.destroy();
            siteView = null;
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
                setImmersive(enable);
                relayoutSite();
            });
        }

        @JavascriptInterface
        public void loadUrl(String url) {
            runOnUiThread(() -> {
                if (siteView == null || url == null) return;
                siteActive = true;
                applySiteVisibility();
                relayoutSite();
                siteView.loadUrl(url);
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
    }
}
