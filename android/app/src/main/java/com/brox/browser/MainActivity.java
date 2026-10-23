package com.brox.browser;

import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Uygulama açılışında ekran görüntüsü engelini varsayılan olarak TEMİZLİYORUZ (Kapalı)
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        
        // Arayüzdeki JS'den (window.BroxNative) gelen istekleri dinlemek için köprü sınıfını ekliyoruz
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().addJavascriptInterface(new BroxNativeInterface(), "BroxNative");
        }
    }

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
                if (enable) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
            });
        }
    }
}
