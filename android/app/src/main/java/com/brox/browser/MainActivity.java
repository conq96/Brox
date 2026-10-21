package com.brox.browser;

import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
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
        
        // HTML ile Native Java arasındaki köprüyü kuruyoruz
        capacitorWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void loadSite(final String url) {
                runOnUiThread(() -> {
                    // Eğer native motor henüz oluşturulmadıysa oluştur
                    if (nativeBrowser == null) {
                        nativeBrowser = new WebView(MainActivity.this);
                        WebSettings settings = nativeBrowser.getSettings();
                        settings.setJavaScriptEnabled(true);
                        settings.setDomStorageEnabled(true);
                        settings.setDatabaseEnabled(true);
                        settings.setMediaPlaybackRequiresUserGesture(false);
                        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                        
                        // Mobil tarayıcı kimliği (Bütün kısıtlamaları bypass eder)
                        String ua = settings.getUserAgentString().replace("; wv", "");
                        settings.setUserAgentString(ua);

                        nativeBrowser.setWebViewClient(new WebViewClient() {
                            @Override
                            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                                view.loadUrl(url);
                                return true;
                            }
                            @Override
                            public void onPageFinished(WebView view, String url) {
                                // Sayfa yüklendiğinde HTML'deki üst arama çubuğunu güncelle
                                capacitorWebView.evaluateJavascript("if(window.updateUrlBar) window.updateUrlBar('" + url + "');", null);
                            }
                        });
                        nativeBrowser.setWebChromeClient(new WebChromeClient());

                        // HTML araç çubuğunun ve gölgesinin (69px) tam altından başlaması için boşluk bırakıyoruz
                        float density = getResources().getDisplayMetrics().density;
                        int marginTop = (int)(69 * density);
                        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        );
                        params.topMargin = marginTop;
                        
                        // WebView'i ekrana ekliyoruz
                        ((ViewGroup) capacitorWebView.getParent()).addView(nativeBrowser, params);
                    }
                    nativeBrowser.setVisibility(View.VISIBLE);
                    nativeBrowser.loadUrl(url);
                });
            }

            @JavascriptInterface
            public void hideSite() {
                runOnUiThread(() -> {
                    if (nativeBrowser != null) {
                        nativeBrowser.setVisibility(View.GONE);
                        nativeBrowser.loadUrl("about:blank");
                    }
                });
            }

            @JavascriptInterface
            public void reloadSite() {
                runOnUiThread(() -> {
                    if (nativeBrowser != null && nativeBrowser.getVisibility() == View.VISIBLE) {
                        nativeBrowser.reload();
                    }
                });
            }
        }, "BroxNative");
    }

    // Geri tuşuna basıldığında
    @Override
    public void onBackPressed() {
        if (nativeBrowser != null && nativeBrowser.getVisibility() == View.VISIBLE) {
            if (nativeBrowser.canGoBack()) {
                nativeBrowser.goBack(); // Sitede bir önceki sayfaya dön
            } else {
                nativeBrowser.setVisibility(View.GONE); // Siteyi kapat
                nativeBrowser.loadUrl("about:blank");
                // HTML arayüzüne Ana Sayfayı göster komutu gönder
                this.bridge.getWebView().evaluateJavascript("if(window.showHome) window.showHome();", null);
            }
        } else {
            super.onBackPressed();
        }
    }
}
