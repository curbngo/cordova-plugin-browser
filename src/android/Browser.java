package com.curbngo.browser;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.curbngo.browser.WebAppInterface;

public class Browser extends CordovaPlugin {

    private static final String TAG = "BrowserPlugin";

    private WebView webView;
    private FrameLayout layout;
    private CallbackContext eventCallbackContext;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        LOG.d(TAG, "execute called with action: " + action);
        try {
            switch (action) {
                case "open":
                    LOG.d(TAG, "Opening URL with args: " + args.toString());
                    eventCallbackContext = callbackContext;
                    String url = args.optString(0, null);
                    open(url, args, callbackContext);
                    return true;

                case "close":
                    LOG.d(TAG, "Closing WebView");
                    close(callbackContext);
                    return true;

                case "back":
                    LOG.d(TAG, "Navigating back in WebView");
                    back(callbackContext);
                    return true;

                case "hide":
                    LOG.d(TAG, "Hiding WebView");
                    hide(callbackContext);
                    return true;

                case "show":
                    LOG.d(TAG, "Showing WebView");
                    show(callbackContext);
                    return true;
            }
        } catch (Exception e) {
            LOG.e(TAG, "Error processing action: " + e.getMessage());
            callbackContext.error("Error processing action: " + e.getMessage());
            return false;
        }
        LOG.e(TAG, "Invalid action: " + action);
        return false;
    }

    private void open(final String url, final JSONArray args, final CallbackContext callbackContext) {
        LOG.d(TAG, "open method called with URL: " + url);
        if (url == null || url.isEmpty()) {
            callbackContext.error("URL is required.");
            return;
        }

        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {

                if (layout == null) {
                    layout = new FrameLayout(cordova.getContext());
                } else {
                    // Remove any views from layout
                    layout.removeAllViews();
                }
                // Destroy the existing WebView if it exists
                if (webView != null) {
                    LOG.d(TAG, "Destroying existing WebView before opening a new one");
                    webView.destroy();
                    webView = null;
                }

                // Create a new WebView
                LOG.d(TAG, "Creating new WebView");
                webView = new WebView(cordova.getContext());
                setupWebViewClient();
                webView.getSettings().setJavaScriptEnabled(true);
                
                // Enable DOM storage
                webView.getSettings().setDomStorageEnabled(true); // Enable localStorage

                // Set a custom User-Agent
                String customUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";
                webView.getSettings().setUserAgentString(customUserAgent);

                // Add JavaScript interface
                webView.addJavascriptInterface(new WebAppInterface(eventCallbackContext), "Android");

                // Enable vertical scrolling
                webView.setVerticalScrollBarEnabled(true);
                webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
                webView.setOverScrollMode(WebView.OVER_SCROLL_ALWAYS); // Allow over-scrolling

                // Create a layout to hold the WebView
                layout = new FrameLayout(cordova.getContext());
                layout.addView(webView);

                // Add the layout to the Cordova activity's view with adjusted height
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT // Use full height
                );
                int offsetTop = args.optJSONObject(1) != null ? args.optJSONObject(1).optInt("offsetTop", 0) : 0; // Default to 0 if not provided
                params.topMargin = offsetTop; // Set the top margin
                if (layout.getParent() == null) {
                    cordova.getActivity().addContentView(layout, params);
                }

                LOG.d(TAG, "Opening URL: " + url);
                webView.setVisibility(View.VISIBLE);
                webView.bringToFront();
                webView.loadUrl(url);

                PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, "WebView opened");
                pluginResult.setKeepCallback(true);
                callbackContext.sendPluginResult(pluginResult);
            }
        });
    }

    private void close(final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    // Clear cookies, localStorage, etc.
                    CookieManager.getInstance().removeAllCookies(null);
                    webView.evaluateJavascript("localStorage.clear(); sessionStorage.clear();", null);

                    webView.setVisibility(View.GONE);
                    webView.loadUrl("about:blank");
                    webView.destroy();
                    webView = null;
                }
                
                // Also remove the layout from the parent
                if (layout != null) {
                    ViewGroup parentView = (ViewGroup) layout.getParent();
                    if (parentView != null) {
                        parentView.removeView(layout);
                    }
                    layout.removeAllViews();
                    layout = null;
                }

                callbackContext.success("WebView closed and data cleared");
            }
        });
    }

    private void back(final CallbackContext callbackContext) {
        LOG.d(TAG, "back method called");
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null && webView.canGoBack()) {
                    LOG.d(TAG, "Going back in WebView");
                    webView.goBack();
                    LOG.d(TAG, "Navigated back in WebView");
                    callbackContext.success("Navigated back");
                } else {
                    LOG.e(TAG, "Cannot go back. Either no WebView or no back history.");
                    callbackContext.error("Cannot go back. Either no WebView or no back history.");
                }
            }
        });
    }

    private void hide(final CallbackContext callbackContext) {
        LOG.d(TAG, "hide method called");
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.setVisibility(View.GONE);
                    LOG.d(TAG, "WebView hidden");
                    callbackContext.success("WebView hidden");
                } else {
                    LOG.e(TAG, "No WebView to hide.");
                    callbackContext.error("No WebView to hide.");
                }
            }
        });
    }

    private void show(final CallbackContext callbackContext) {
        LOG.d(TAG, "show method called");
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.setVisibility(View.VISIBLE);
                    LOG.d(TAG, "WebView shown");
                    callbackContext.success("WebView shown");
                } else {
                    LOG.e(TAG, "No WebView to show.");
                    callbackContext.error("No WebView to show.");
                }
            }
        });
    }

    private void setupWebViewClient() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                LOG.e(TAG, "onPageFinished");
                
                // Ensure this is called after the page is fully loaded
                view.evaluateJavascript(
                    "try {" +
                    "document.addEventListener('touchstart', function(event) { " +
                    "console.log('Touchstart event fired');" + // Test log
                    "Android.eventTriggered('touchstart');" +
                    "});" +
                    "document.addEventListener('keyup', function(event) { " +
                    "console.log('Keyup event fired');" + // Test log
                    "Android.eventTriggered('keyup');" +
                    "});" +
                    "} catch (e) { console.error('Error adding event listeners:', e); }", 
                    null
                );
            }
        });
    }
}
