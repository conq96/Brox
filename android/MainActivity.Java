package com.conq96.brox;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import android.webkit.WebView;
import android.webkit.WebSettings;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        WebView webView = getBridge().getWebView();
        if (webView != null) {
            WebSettings settings = webView.getSettings();
            if (settings != null) {
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setAllowFileAccess(true);
                settings.setAllowContentAccess(true);
                settings.setLoadWithOverviewMode(true);
                settings.setUseWideViewPort(true);
                settings.setSupportMultipleWindows(true);
                settings.setJavaScriptCanOpenWindowsAutomatically(true);
            }
        }
    }
}

