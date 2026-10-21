package com.brox.browser;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import comgetcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = this.bridge.getWebView();
        if (webView != null) {
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(true);
            settings.setDatabaseEnabled(true);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            
            // Sitelerin mobil tarayıcı olarak algılaması ve engelleri tamamen kaldırması için standart Chrome User-Agent
            String userAgent = settings.getUserAgentString();
            userAgent = userAgent.replace("; wv", "");
            settings.setUserAgentString(userAgent);
        }
    }
}
