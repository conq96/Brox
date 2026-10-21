package com.brox.browser;

import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.getcapacitor.BridgeActivity;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

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
            
            // Standart Masaüstü/Mobil Chrome Kimliği
            settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

            // Iframe engellerini (X-Frame-Options & CSP) süzen Native Filtre
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    if ((url.startsWith("http://") || url.startsWith("https://")) && !request.isForMainFrame()) {
                        try {
                            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                            conn.setRequestMethod(request.getMethod());
                            for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                                conn.setRequestProperty(header.getKey(), header.getValue());
                            }
                            conn.connect();

                            String mimeType = conn.getContentType();
                            if (mimeType != null && mimeType.contains(";")) {
                                mimeType = mimeType.split(";")[0];
                            }
                            String encoding = conn.getContentEncoding() != null ? conn.getContentEncoding() : "UTF-8";

                            Map<String, String> responseHeaders = new HashMap<>();
                            for (Map.Entry<String, java.util.List<String>> entry : conn.getHeaderFields().entrySet()) {
                                if (entry.getKey() != null) {
                                    String key = entry.getKey().toLowerCase();
                                    if (!key.equals("x-frame-options") && !key.equals("content-security-policy")) {
                                        responseHeaders.put(entry.getKey(), entry.getValue().get(0));
                                    }
                                }
                            }

                            InputStream stream = conn.getInputStream();
                            return new WebResourceResponse(mimeType, encoding, conn.getResponseCode(), conn.getResponseMessage(), responseHeaders, stream);
                        } catch (Exception e) {
                            return super.shouldInterceptRequest(view, request);
                        }
                    }
                    return super.shouldInterceptRequest(view, request);
                }
            });
        }
    }
}
