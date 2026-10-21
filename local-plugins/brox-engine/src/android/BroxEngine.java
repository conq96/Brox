package com.brox.engine;
import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.view.ViewGroup;
import android.view.View;
import android.widget.FrameLayout;
import android.graphics.Color;

public class BroxEngine extends CordovaPlugin {
    private WebView nativeWebView;
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if ("open".equals(action)) {
            final String url = args.getString(0);
            final int topMargin = args.getInt(1);
            cordova.getActivity().runOnUiThread(() -> {
                if (nativeWebView == null) initWebView(topMargin);
                nativeWebView.setVisibility(View.VISIBLE);
                nativeWebView.loadUrl(url);
                webView.getView().setBackgroundColor(Color.TRANSPARENT);
            });
            return true;
        } else if ("hide".equals(action)) {
            cordova.getActivity().runOnUiThread(() -> {
                if (nativeWebView != null) {
                    nativeWebView.setVisibility(View.GONE);
                    nativeWebView.loadUrl("about:blank");
                }
                webView.getView().setBackgroundColor(Color.parseColor("#000000"));
            });
            return true;
        } else if ("reload".equals(action)) {
            cordova.getActivity().runOnUiThread(() -> { if (nativeWebView != null) nativeWebView.reload(); });
            return true;
        }
        return false;
    }

    private void initWebView(int topMargin) {
        nativeWebView = new WebView(cordova.getActivity());
        WebSettings settings = nativeWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        nativeWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
        nativeWebView.setWebChromeClient(new WebChromeClient());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        );
        float density = cordova.getActivity().getResources().getDisplayMetrics().density;
        params.topMargin = (int)(topMargin * density);
        
        ViewGroup parent = (ViewGroup) webView.getView().getParent();
        parent.addView(nativeWebView, 0, params);
    }
}
