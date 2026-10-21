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

                        CookieManager cookieManager = CookieManager.getInstance();
                        cookieManager.setAcceptCookie(true);
                        cookieManager.setAcceptThirdPartyCookies(nativeBrowser, true);

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
                                capacitorWebView.evaluateJavascript("if(window.updateAddressBar) window.updateAddressBar('" + url + "');", null);
                            }
                        });
                        nativeBrowser.setWebChromeClient(new WebChromeClient());

                        float density = getResources().getDisplayMetrics().density;
                        int marginTop = (int)(56 * density);
                        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        );
                        params.topMargin = marginTop;

                        ((ViewGroup) capacitorWebView.getParent()).addView(nativeBrowser, params);
                    }
                    nativeBrowser.setVisibility(View.VISIBLE);
                    nativeBrowser.loadUrl(url);
                });
            }

            @JavascriptInterface
            public void hideWebView() {
                runOnUiThread(() -> {
                    if (nativeBrowser != null) {
                        nativeBrowser.setVisibility(View.GONE);
                        nativeBrowser.loadUrl("about:blank");
                    }
                });
            }

            @JavascriptInterface
            public void goBack() {
                runOnUiThread(() -> {
                    if (nativeBrowser != null && nativeBrowser.canGoBack()) {
                        nativeBrowser.goBack();
                    } else {
                        hideWebView();
                        bridge.getWebView().evaluateJavascript("if(window.resetBrowser) window.resetBrowser();", null);
                    }
                });
            }
        }, "BroxNative");
    }

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
