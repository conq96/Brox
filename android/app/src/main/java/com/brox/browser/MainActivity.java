package com.brox.browser;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.brox.browser.plugins.BroxEnginePlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BroxEnginePlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onBackPressed() {
        BroxEnginePlugin broxEngine = (BroxEnginePlugin) this.bridge.getPlugin("BroxEngine").getInstance();
        if (broxEngine != null && broxEngine.isVisible()) {
            if (broxEngine.canGoBack()) {
                broxEngine.goBack();
            } else {
                broxEngine.hideWebView(null);
                this.bridge.getWebView().evaluateJavascript("if(window.showHome) window.showHome();", null);
            }
        } else {
            super.onBackPressed();
        }
    }
}
