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
    private String barcodeScanURL; // Track the barcode scan URL
    private String[] whitelist; // Track whitelisted domains
    private java.util.Timer domainCheckTimer; // Timer for domain checking

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

                case "executeScript":
                    LOG.d(TAG, "Executing script in WebView");
                    executeScript(args, callbackContext);
                    return true;

                case "navigate":
                    LOG.d(TAG, "Navigating to URL in WebView");
                    navigate(args, callbackContext);
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

        // Extract optional barcodeScanURL from args
        try {
            JSONObject options = args.optJSONObject(1);
            if (options != null && options.has("barcodeScanURL")) {
                barcodeScanURL = options.getString("barcodeScanURL");
                LOG.d(TAG, "Barcode scan URL set to: " + barcodeScanURL);
            } else {
                barcodeScanURL = null;
            }
            
            // Extract optional whitelist from args and ensure it includes the initial domain
            if (options != null && options.has("whitelist")) {
                JSONArray whitelistArray = options.getJSONArray("whitelist");
                whitelist = new String[whitelistArray.length() + 1]; // +1 for the initial domain
                
                // Add the initial domain first
                try {
                    java.net.URL urlObj = new java.net.URL(url);
                    String initialDomain = urlObj.getHost();
                    whitelist[0] = initialDomain;
                    LOG.d(TAG, "Added initial domain to whitelist: " + initialDomain);
                    
                    // Add the provided whitelist domains
                    for (int i = 0; i < whitelistArray.length(); i++) {
                        String domain = whitelistArray.getString(i);
                        whitelist[i + 1] = domain;
                        if (domain.startsWith("*.")) {
                            LOG.d(TAG, "Added wildcard domain to whitelist: " + domain);
                        } else {
                            LOG.d(TAG, "Added domain to whitelist: " + domain);
                        }
                    }
                } catch (Exception e) {
                    LOG.e(TAG, "Error parsing initial URL domain: " + e.getMessage());
                    // Fallback: just use the provided whitelist
                    whitelist = new String[whitelistArray.length()];
                    for (int i = 0; i < whitelistArray.length(); i++) {
                        whitelist[i] = whitelistArray.getString(i);
                    }
                }
                LOG.d(TAG, "Whitelist set with " + whitelist.length + " domains (including initial domain)");
            } else {
                // No whitelist provided, create one with just the initial domain
                try {
                    java.net.URL urlObj = new java.net.URL(url);
                    String initialDomain = urlObj.getHost();
                    whitelist = new String[]{initialDomain};
                    LOG.d(TAG, "Created whitelist with initial domain: " + initialDomain);
                } catch (Exception e) {
                    LOG.e(TAG, "Error parsing initial URL domain: " + e.getMessage());
                    whitelist = null;
                }
            }
        } catch (JSONException e) {
            LOG.e(TAG, "Error parsing options: " + e.getMessage());
            barcodeScanURL = null;
            whitelist = null;
        }

        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {

                // Check if we can reuse the existing WebView
                if (webView != null && layout != null && layout.getParent() != null) {
                    LOG.d(TAG, "Reusing existing WebView for navigation to: " + url);
                    LOG.d(TAG, "Current WebView state - canGoBack: " + webView.canGoBack() + ", current URL: " + webView.getUrl());
                    
                    // Update the whitelist and barcode scan URL for the new navigation
                    // (these were already set above)
                    
                    // Navigate to the new URL
                    webView.setVisibility(View.VISIBLE);
                    webView.bringToFront();
                    webView.loadUrl(url);
                    
                    PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, "WebView navigated");
                    pluginResult.setKeepCallback(true);
                    callbackContext.sendPluginResult(pluginResult);
                    return;
                }

                // Create new WebView only if one doesn't exist or is not properly set up
                LOG.d(TAG, "Creating new WebView - existing WebView: " + (webView != null) + ", layout: " + (layout != null) + ", layout parent: " + (layout != null && layout.getParent() != null));
                if (layout == null) {
                    layout = new FrameLayout(cordova.getContext());
                } else {
                    // Remove any views from layout
                    layout.removeAllViews();
                }
                
                // Destroy the existing WebView if it exists
                if (webView != null) {
                    LOG.d(TAG, "Destroying existing WebView before creating a new one");
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

                // Reset barcode scan URL
                barcodeScanURL = null;

                // Reset whitelist
                whitelist = null;

                // Stop domain checking timer
                stopDomainCheckingTimer();

                callbackContext.success("WebView closed and data cleared");
            }
        });
    }

    private void back(final CallbackContext callbackContext) {
        LOG.d(TAG, "back method called");
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    LOG.d(TAG, "WebView exists, canGoBack: " + webView.canGoBack());
                    LOG.d(TAG, "Current URL: " + webView.getUrl());
                    if (webView.canGoBack()) {
                        LOG.d(TAG, "Going back in WebView");
                        webView.goBack();
                        LOG.d(TAG, "Navigated back in WebView");
                        callbackContext.success("Navigated back");
                    } else {
                        LOG.e(TAG, "Cannot go back. WebView exists but no back history.");
                        callbackContext.error("Cannot go back. WebView exists but no back history.");
                    }
                } else {
                    LOG.e(TAG, "Cannot go back. No WebView available.");
                    callbackContext.error("Cannot go back. No WebView available.");
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

    private void executeScript(final JSONArray args, final CallbackContext callbackContext) {
        LOG.d(TAG, "executeScript method called");
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    try {
                        JSONObject config = args.optJSONObject(0);
                        if (config != null && config.has("code")) {
                            String scriptCode = config.getString("code");
                            LOG.d(TAG, "Executing script: " + scriptCode);
                            
                            webView.evaluateJavascript(scriptCode, new android.webkit.ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    LOG.d(TAG, "Script execution result: " + value);
                                    callbackContext.success(value);
                                }
                            });
                        } else {
                            LOG.e(TAG, "Invalid config object or missing 'code' property");
                            callbackContext.error("Invalid config object or missing 'code' property");
                        }
                    } catch (JSONException e) {
                        LOG.e(TAG, "Error parsing config: " + e.getMessage());
                        callbackContext.error("Error parsing config: " + e.getMessage());
                    }
                } else {
                    LOG.e(TAG, "No WebView available to execute script");
                    callbackContext.error("No WebView available to execute script");
                }
            }
        });
    }

    private void navigate(final JSONArray args, final CallbackContext callbackContext) {
        LOG.d(TAG, "navigate method called");
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    try {
                        String url = args.optString(0, null);
                        if (url != null && !url.isEmpty()) {
                            LOG.d(TAG, "Navigating to URL: " + url);
                            webView.loadUrl(url);
                            callbackContext.success("Navigation started");
                        } else {
                            LOG.e(TAG, "Invalid URL provided for navigation");
                            callbackContext.error("Invalid URL provided for navigation");
                        }
                    } catch (Exception e) {
                        LOG.e(TAG, "Error during navigation: " + e.getMessage());
                        callbackContext.error("Error during navigation: " + e.getMessage());
                    }
                } else {
                    LOG.e(TAG, "No WebView available for navigation");
                    callbackContext.error("No WebView available for navigation");
                }
            }
        });
    }

    private boolean isDomainWhitelisted(String url) {
        if (whitelist == null || whitelist.length == 0) {
            LOG.w(TAG, "No whitelist configured, allowing all domains");
            return true; // No whitelist means all domains are allowed
        }
        
        try {
            java.net.URL urlObj = new java.net.URL(url);
            String domain = urlObj.getHost();
            
            for (String whitelistedDomain : whitelist) {
                // Handle wildcard domains (e.g., *.shopify.com)
                if (whitelistedDomain.startsWith("*.")) {
                    String baseDomain = whitelistedDomain.substring(2); // Remove "*. "
                    if (domain.equals(baseDomain) || domain.endsWith("." + baseDomain)) {
                        LOG.d(TAG, "Domain " + domain + " is whitelisted (matches wildcard " + whitelistedDomain + ")");
                        return true;
                    }
                }
                // Handle exact domain matching and subdomain matching
                else if (domain.equals(whitelistedDomain) || domain.endsWith("." + whitelistedDomain)) {
                    LOG.d(TAG, "Domain " + domain + " is whitelisted (matches " + whitelistedDomain + ")");
                    return true;
                }
            }
            LOG.d(TAG, "Domain " + domain + " is not whitelisted. Whitelist: " + java.util.Arrays.toString(whitelist));
            return false;
        } catch (Exception e) {
            LOG.e(TAG, "Error parsing URL for whitelist check: " + e.getMessage());
            return false;
        }
    }

    private void startDomainCheckingTimer() {
        // Stop any existing timer
        stopDomainCheckingTimer();
        
        if (whitelist == null || whitelist.length == 0) {
            LOG.d(TAG, "No whitelist configured, skipping domain checking timer");
            return;
        }
        
        LOG.d(TAG, "Starting domain checking timer with whitelist: " + java.util.Arrays.toString(whitelist));
        domainCheckTimer = new java.util.Timer();
        domainCheckTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                if (webView != null) {
                    cordova.getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String currentUrl = webView.getUrl();
                            if (currentUrl != null && !isDomainWhitelisted(currentUrl)) {
                                LOG.d(TAG, "Current domain is not whitelisted, navigating back: " + currentUrl);
                                if (webView.canGoBack()) {
                                    webView.goBack();
                                } else {
                                    // If we can't go back, load a blank page
                                    webView.loadUrl("about:blank");
                                }
                            }
                        }
                    });
                }
            }
        }, 1000, 1000); // Start after 1 second, repeat every 1 second
    }

    private void stopDomainCheckingTimer() {
        if (domainCheckTimer != null) {
            LOG.d(TAG, "Stopping domain checking timer");
            domainCheckTimer.cancel();
            domainCheckTimer = null;
        }
    }

    private void injectBarcodeScanningScript(WebView view) {
        if (barcodeScanURL == null || barcodeScanURL.isEmpty()) {
            LOG.d(TAG, "No barcode scan URL configured, skipping barcode script injection");
            return;
        }
        
        LOG.d(TAG, "Injecting barcode scanning script for URL: " + barcodeScanURL);
        
        String barcodeScript = 
            "var cngPageInitialized = false;" +
            "function cngPageInit(){" +
            "if(cngPageInitialized)" +
            "return;" +
            "cngPageInitialized = true;" +
            "initBarcodeListener = function () {" +
            "barcodeScannerInitialized = Boolean(true);" +
            "barcode_timeoutHandler = 0;" +
            "barcode_inputString = '';" +
            "barcode_onKeypress = function (ev) {" +
            "if (ev.target.tagName === 'INPUT' || ev.target.tagName === 'TEXTAREA') return;" +
            "if (barcode_timeoutHandler) clearTimeout(barcode_timeoutHandler);" +
            "if (ev.key == 'Enter') {" +
            "window.location = window.location.origin + '" + barcodeScanURL + "' + barcode_inputString;" +
            "barcode_inputString = '';" +
            "return;" +
            "}" +
            "barcode_inputString += ev.key;" +
            "barcode_timeoutHandler = setTimeout(function () {" +
            "if (barcode_inputString.length <= 3) {" +
            "barcode_inputString = '';" +
            "return;" +
            "}" +
            "barcode_inputString = '';" +
            "}, 200);" +
            "};" +
            "document.addEventListener('keypress', barcode_onKeypress, { passive: true });" +
            "};" +
            "initBarcodeListener();" +
            "var vp = document.querySelector('meta[name=viewport]');" +
            "if (typeof vp !== 'undefined' && vp) vp.setAttribute('content', 'width=device-width');" +
            "else {" +
            "var meta = document.createElement('meta');" +
            "meta.name = 'viewport';" +
            "meta.content = 'width=device-width';" +
            "document.getElementsByTagName('head')[0].appendChild(meta);" +
            "}" +
            "var cEventList = ['keyup', 'touchstart'/*, 'click', 'touchend', 'mousedown', 'mouseup'*/];" +
            "cEventList.forEach(function (eventName) {" +
            "window.addEventListener(eventName, function (e) {" +
            "window['webkit'].messageHandlers['cordova_iab'].postMessage(JSON.stringify({" +
            "active: true," +
            "type: e.type" +
            "}));" +
            "}, { passive: true });" +
            "});" +
            "if(typeof ShopifyAnalytics.meta !== 'undefined' && typeof ShopifyAnalytics.meta.page !== 'undefined' && typeof ShopifyAnalytics.meta.page.customerId !== 'undefined')" +
            "window['webkit'].messageHandlers['cordova_iab'].postMessage(JSON.stringify({" +
            "logged_in: true" +
            "}));" +
            "}" +
            "cngPageInit();";
        
        view.evaluateJavascript(barcodeScript, new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                LOG.d(TAG, "Barcode scanning script injected successfully");
            }
        });
    }

    private void setupWebViewClient() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!isDomainWhitelisted(url)) {
                    LOG.d(TAG, "Blocking navigation to non-whitelisted domain: " + url);
                    return true; // Block the navigation
                }
                return false; // Allow the navigation
            }
            
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                LOG.e(TAG, "onPageFinished");
                
                // Start domain checking timer if whitelist is configured
                startDomainCheckingTimer();
                
                // Inject barcode scanning script if configured
                injectBarcodeScanningScript(view);
                
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
    
    @Override
    public void onDestroy() {
        stopDomainCheckingTimer();
        super.onDestroy();
    }
}
