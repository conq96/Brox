package com.brox.browser;

import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.CookieManager;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.View;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private WebView nativeBrowser;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView capacitorWebView = this.bridge.getWebView();

        capacitorWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void loadUrl(final String url) {
                runOnUiThread(() -> {
                    if (nativeBrowser == null) {
                        nativeBrowser = new WebView(MainActivity.this);
                        WebSettings settings = nativeBrowser.getSettings();
                        
                        settings.setJavaScriptEnabled(true);
                        settings.setDomStorageEnabled(true);
                        settings.setDatabaseEnabled(true);
                        settings.setAllowFileAccess(true);
                        settings.setAllowContentAccess(true);
                        settings.setMediaPlaybackRequiresUserGesture(false);
                        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                        settings.setJavaScriptCanOpenWindowsAutomatically(true);
                        settings.setSupportMultipleWindows(true);

                        // Çerez yönetimi (Oturum açma ve hesaplar için)
                        CookieManager cookieManager = CookieManager.getInstance();
                        cookieManager.setAcceptCookie(true);
                        cookieManager.setAcceptThirdPartyCookies(nativeBrowser, true);

                        // WebView engellerini kaldıran User-Agent ayarı
                        String ua = settings.getUserAgentString().replace("; wv", "");
                        settings.setUserAgentString(ua);

                        nativeBrowser.setWebViewClient(new WebViewClient() {
                            @Override
                            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                                view.loadUrl(url);
                                return true;
                            }
                        });
                        nativeBrowser.setWebChromeClient(new WebChromeClient());

                        // Tam ekran tarayıcı motoru
                        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        );
                        
                        ((ViewGroup) capacitorWebView.getParent()).addView(nativeBrowser, params);
                    }
                    nativeBrowser.setVisibility(View.VISIBLE);
                    nativeBrowser.loadUrl(url);
                });
            }
        }, "BroxNative");
    }

    // Telefonda geri tuşuna basıldığında sitede geri git, ana sayfadaysa uygulamadan çık
    @Override
    public void onBackPressed() {
        if (nativeBrowser != null && nativeBrowser.getVisibility() == View.VISIBLE) {
            if (nativeBrowser.canGoBack()) {
                nativeBrowser.goBack();
            } else {
                nativeBrowser.setVisibility(View.GONE);
                nativeBrowser.loadUrl("about:blank");
                this.bridge.getWebView().evaluateJavascript("if(window.resetBrowser) window.resetBrowser();", null);
            }
        } else {
            super.onBackPressed();
        }
    }
}
