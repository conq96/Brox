package com.brox.browser;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.CookieManager;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = this.bridge.getWebView();
        if (webView != null) {
            WebSettings settings = webView.getSettings();
            
            // Tüm güvenlik engellerini kaldıran ve tam profesyonel tarayıcı yeteneği veren ayarlar
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            settings.setSupportMultipleWindows(true);
            
            // Üçüncü taraf çerezlere izin ver (Google/YouTube girişleri için şarttır)
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            cookieManager.setAcceptThirdPartyCookies(webView, true);

            // X-Frame-Options kısıtlamalarını bypass etmek için standart masaüstü/mobil Chrome kimliği
            String userAgent = settings.getUserAgentString();
            userAgent = userAgent.replace("; wv", ""); // WebView ibaresini sil
            settings.setUserAgentString(userAgent);
        }
    }
}
