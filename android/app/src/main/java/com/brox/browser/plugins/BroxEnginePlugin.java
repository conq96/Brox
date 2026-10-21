package com.brox.browser.plugins;

import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.CookieManager;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.view.View;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "BroxEngine")
public class BroxEnginePlugin extends Plugin {
    private WebView nativeBrowser;

    @PluginMethod
    public void loadUrl(PluginCall call) {
        String url = call.getString("url");
        if (url == null) {
            call.reject("URL is required");
            return;
        }

        getActivity().runOnUiThread(() -> {
            if (nativeBrowser == null) {
                nativeBrowser = new WebView(getContext());
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
                
                // Çerezlere ve depolamaya tam izin ver
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setAcceptThirdPartyCookies(nativeBrowser, true);

                // WebView ibaresini silerek Google/YouTube engellerini bypass et
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
                        getBridge().getWebView().evaluateJavascript("if(window.updateUrlBar) window.updateUrlBar('" + url + "');", null);
                    }
                });
                nativeBrowser.setWebChromeClient(new WebChromeClient());

                // Üst araç çubuğu (56px) altından tam ekran başlat
                float density = getContext().getResources().getDisplayMetrics().density;
                int marginTop = (int)(56 * density);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                );
                params.topMargin = marginTop;
                
                ((ViewGroup) getBridge().getWebView().getParent()).addView(nativeBrowser, params);
            }
            nativeBrowser.setVisibility(View.VISIBLE);
            nativeBrowser.loadUrl(url);
            call.resolve();
        });
    }

    @PluginMethod
    public void hideWebView(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (nativeBrowser != null) {
                nativeBrowser.setVisibility(View.GONE);
                nativeBrowser.loadUrl("about:blank");
            }
            if(call != null) call.resolve();
        });
    }

    @PluginMethod
    public void reload(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (nativeBrowser != null && nativeBrowser.getVisibility() == View.VISIBLE) {
                nativeBrowser.reload();
            }
            if(call != null) call.resolve();
        });
    }
    
    public boolean canGoBack() {
        return nativeBrowser != null && nativeBrowser.getVisibility() == View.VISIBLE && nativeBrowser.canGoBack();
    }
    
    public void goBack() {
         if (nativeBrowser != null) {
             nativeBrowser.goBack();
         }
    }
    
    public boolean isVisible() {
         return nativeBrowser != null && nativeBrowser.getVisibility() == View.VISIBLE;
    }
}
