package com.curbngo.browser;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.webkit.WebResourceRequest;

import java.util.HashSet;
import java.util.Set;
import com.curbngo.browser.WebAppInterface;

public class Browser extends CordovaPlugin {

    private static final String TAG = "BrowserPlugin";

    private WebView webView;
    private FrameLayout layout;
    private CallbackContext eventCallbackContext;
    private String barcodeScanURL; // Track the barcode scan URL
    private Set<String> whitelistDomains; // Use HashSet for faster lookup
    private boolean shopifyHelpersEnabled = false; // Inject the Shopify product-page variant-race fix

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        try {
            switch (action) {
                case "open":
                    eventCallbackContext = callbackContext;
                    String url = args.optString(0, null);
                    open(url, args, callbackContext);
                    return true;

                case "close":
                    close(callbackContext);
                    return true;

                case "back":
                    back(callbackContext);
                    return true;

                case "hide":
                    hide(callbackContext);
                    return true;

                case "show":
                    show(callbackContext);
                    return true;

                case "executeScript":
                    executeScript(args, callbackContext);
                    return true;

                case "navigate":
                    navigate(args, callbackContext);
                    return true;
            }
        } catch (Exception e) {
            callbackContext.error("Error processing action: " + e.getMessage());
            return false;
        }
        return false;
    }

    private void open(final String url, final JSONArray args, final CallbackContext callbackContext) {
        if (url == null || url.isEmpty()) {
            callbackContext.error("URL is required.");
            return;
        }

        // Extract optional barcodeScanURL from args
        try {
            JSONObject options = args.optJSONObject(1);
            if (options != null && options.has("barcodeScanURL")) {
                barcodeScanURL = options.getString("barcodeScanURL");
            } else {
                barcodeScanURL = null;
            }

            shopifyHelpersEnabled = options != null && options.optBoolean("shopifyHelpers", false);

            // Extract optional whitelist from args and ensure it includes the initial domain
            if (options != null && options.has("whitelist")) {
                JSONArray whitelistArray = options.getJSONArray("whitelist");
                whitelistDomains = new HashSet<>();
                
                // Add the initial domain first
                try {
                    java.net.URL urlObj = new java.net.URL(url);
                    String initialDomain = urlObj.getHost();
                    if (initialDomain != null) {
                        whitelistDomains.add(initialDomain);
                    }
                    
                    // Add the provided whitelist domains
                    for (int i = 0; i < whitelistArray.length(); i++) {
                        String domain = whitelistArray.getString(i);
                        whitelistDomains.add(domain);
                    }
                } catch (Exception e) {
                    LOG.e(TAG, "Error parsing initial URL domain: " + e.getMessage());
                    // Fallback: just use the provided whitelist
                    whitelistDomains = new HashSet<>();
                    for (int i = 0; i < whitelistArray.length(); i++) {
                        whitelistDomains.add(whitelistArray.getString(i));
                    }
                }
            } else {
                // No whitelist provided, create one with just the initial domain
                try {
                    java.net.URL urlObj = new java.net.URL(url);
                    String initialDomain = urlObj.getHost();
                    if (initialDomain != null) {
                        whitelistDomains = new HashSet<>();
                        whitelistDomains.add(initialDomain);
                    } else {
                        whitelistDomains = null;
                    }
                } catch (Exception e) {
                    LOG.e(TAG, "Error parsing initial URL domain: " + e.getMessage());
                    whitelistDomains = null;
                }
            }
        } catch (JSONException e) {
            LOG.e(TAG, "Error parsing options: " + e.getMessage());
            barcodeScanURL = null;
            whitelistDomains = null;
        }

        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {

                // Clear session data before every open so each session starts clean
                CookieManager.getInstance().removeAllCookies(null);
                if (webView != null) {
                    webView.evaluateJavascript(
                        "localStorage.clear(); sessionStorage.clear();" +
                        "if('caches' in window) caches.keys().then(k=>k.forEach(n=>caches.delete(n)));" +
                        "if('indexedDB' in window) { try { indexedDB.databases().then(dbs=>dbs.forEach(db=>indexedDB.deleteDatabase(db.name))); } catch(e) {} }",
                        null
                    );
                }

                // Check if we can reuse the existing WebView
                if (webView != null && layout != null && layout.getParent() != null) {
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
                if (layout == null) {
                    layout = new FrameLayout(cordova.getContext());
                } else {
                    // Remove any views from layout
                    layout.removeAllViews();
                }
                
                // Destroy the existing WebView if it exists
                if (webView != null) {
                    webView.destroy();
                    webView = null;
                }

                // Create a new WebView
                webView = new WebView(cordova.getContext());
                setupWebViewClient();
                
                // Configure WebView settings to mimic real browser
                android.webkit.WebSettings settings = webView.getSettings();
                settings.setJavaScriptEnabled(true);
                
                // Enable DOM storage
                settings.setDomStorageEnabled(true);
                settings.setDatabaseEnabled(true);
                
                // Enable file access and content URLs
                settings.setAllowFileAccess(true);
                settings.setAllowContentAccess(true);
                settings.setAllowFileAccessFromFileURLs(true);
                settings.setAllowUniversalAccessFromFileURLs(true);
                
                // Cache and loading settings
                settings.setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
                settings.setSafeBrowsingEnabled(false);
                
                // Media and content settings
                settings.setMediaPlaybackRequiresUserGesture(false);
                settings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                
                // Display and interaction settings
                settings.setSupportZoom(true);
                settings.setBuiltInZoomControls(true);
                settings.setDisplayZoomControls(false);
                settings.setLoadWithOverviewMode(true);
                settings.setUseWideViewPort(true);
                
                // Additional browser-like settings
                settings.setGeolocationEnabled(true);
                settings.setJavaScriptCanOpenWindowsAutomatically(true);
                settings.setSupportMultipleWindows(true);
                
                // Use system default User-Agent (more authentic than hardcoded)
                // settings.setUserAgentString() - commented out to use system default

                // Enable cookies
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setAcceptThirdPartyCookies(webView, true);

                // Add JavaScript interface
                webView.addJavascriptInterface(new WebAppInterface(eventCallbackContext), "Android");

                // Hardware acceleration and renderer priority
                webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
                }

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
                    
                    // Clear storage and unregister service workers
                    String cleanupScript = 
                        "try {" +
                        "localStorage.clear();" +
                        "sessionStorage.clear();" +
                        "if ('serviceWorker' in navigator) {" +
                        "navigator.serviceWorker.getRegistrations().then(function(registrations) {" +
                        "for(let registration of registrations) {" +
                        "registration.unregister();" +
                        "}" +
                        "});" +
                        "}" +
                        "if ('caches' in window) {" +
                        "caches.keys().then(function(cacheNames) {" +
                        "return Promise.all(" +
                        "cacheNames.map(function(cacheName) {" +
                        "return caches.delete(cacheName);" +
                        "})" +
                        ");" +
                        "});" +
                        "}" +
                        "if ('indexedDB' in window) {" +
                        "try {" +
                        "indexedDB.databases().then(function(databases) {" +
                        "databases.forEach(function(db) {" +
                        "indexedDB.deleteDatabase(db.name);" +
                        "});" +
                        "});" +
                        "} catch(e) {}" +
                        "}" +
                        "} catch(e) { console.log('Cleanup error:', e); }";
                    
                    webView.evaluateJavascript(cleanupScript, null);

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
                whitelistDomains = null;

                // Reset Shopify helpers flag
                shopifyHelpersEnabled = false;

                callbackContext.success("WebView closed and data cleared");
            }
        });
    }

    private void back(final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    if (webView.canGoBack()) {
                        webView.goBack();
                        callbackContext.success("Navigated back");
                    } else {
                        callbackContext.error("Cannot go back. WebView exists but no back history.");
                    }
                } else {
                    callbackContext.error("Cannot go back. No WebView available.");
                }
            }
        });
    }

    private void hide(final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.setVisibility(View.GONE);
                    callbackContext.success("WebView hidden");
                } else {
                    callbackContext.error("No WebView to hide.");
                }
            }
        });
    }

    private void show(final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.setVisibility(View.VISIBLE);
                    callbackContext.success("WebView shown");
                } else {
                    callbackContext.error("No WebView to show.");
                }
            }
        });
    }

    private void executeScript(final JSONArray args, final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    try {
                        JSONObject config = args.optJSONObject(0);
                        if (config != null && config.has("code")) {
                            String scriptCode = config.getString("code");
                            
                            webView.evaluateJavascript(scriptCode, new android.webkit.ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String value) {
                                    callbackContext.success(value);
                                }
                            });
                        } else {
                            callbackContext.error("Invalid config object or missing 'code' property");
                        }
                    } catch (JSONException e) {
                        callbackContext.error("Error parsing config: " + e.getMessage());
                    }
                } else {
                    callbackContext.error("No WebView available to execute script");
                }
            }
        });
    }

    private void navigate(final JSONArray args, final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    try {
                        String url = args.optString(0, null);
                        if (url != null && !url.isEmpty()) {
                            webView.loadUrl(url);
                            callbackContext.success("Navigation started");
                        } else {
                            callbackContext.error("Invalid URL provided for navigation");
                        }
                    } catch (Exception e) {
                        callbackContext.error("Error during navigation: " + e.getMessage());
                    }
                } else {
                    callbackContext.error("No WebView available for navigation");
                }
            }
        });
    }

    private boolean isDomainWhitelisted(String url) {
        if (whitelistDomains == null || whitelistDomains.isEmpty()) {
            return true; // No whitelist means all domains are allowed
        }
        
        try {
            java.net.URL urlObj = new java.net.URL(url);
            String domain = urlObj.getHost();
            
            // Direct lookup - O(1) performance
            if (whitelistDomains.contains(domain)) {
                return true;
            }
            
            // Check for wildcard domain matches
            for (String whitelistedDomain : whitelistDomains) {
                if (whitelistedDomain.startsWith("*.")) {
                    String baseDomain = whitelistedDomain.substring(2); // Remove "*."
                    if (domain.equals(baseDomain) || domain.endsWith("." + baseDomain)) {
                        return true;
                    }
                }
                // Check for subdomain matching
                else if (domain.endsWith("." + whitelistedDomain)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            LOG.e(TAG, "Error parsing URL for whitelist check: " + e.getMessage());
            return false;
        }
    }

    private void injectBarcodeScanningScript(WebView view) {
        if (barcodeScanURL == null || barcodeScanURL.isEmpty()) {
            return; // Skip injection if not needed
        }
        
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
            "var cEventList = ['keyup', 'touchstart'];" +
            "cEventList.forEach(function (eventName) {" +
            "window.addEventListener(eventName, function (e) {" +
            "Android.postMessage(JSON.stringify({" +
            "active: true," +
            "type: e.type" +
            "}));" +
            "}, { passive: true });" +
            "});" +
            "if(typeof ShopifyAnalytics !== 'undefined' && typeof ShopifyAnalytics.meta !== 'undefined' && typeof ShopifyAnalytics.meta.page !== 'undefined' && typeof ShopifyAnalytics.meta.page.customerId !== 'undefined')" +
            "Android.postMessage(JSON.stringify({" +
            "logged_in: true" +
            "}));" +
            "}" +
            "cngPageInit();";
        
        view.evaluateJavascript(barcodeScript, null);
    }

    /**
     * Injected on Shopify web shells. Fixes the variant-race on slow kiosks:
     *  - shows a brief touch-blocking spinner overlay on /products/ pages until
     *    the theme's <variant-radios>/<variant-selects>/<product-form> custom
     *    elements are defined (i.e. the theme JS is hydrated), so fast taps can't
     *    land before the page is wired up; and
     *  - keeps the product form's hidden `id` input in sync with the currently
     *    selected option values by reading the variant JSON embedded in the
     *    picker element, so add-to-cart always submits the right variant even if
     *    the theme JS hasn't run yet (Dawn-lineage themes).
     * Idempotent per document via the window.__cngShopifyHelpers guard.
     */
    private void injectShopifyHelpers(WebView view) {
        if (view == null) {
            return;
        }
        String js =
            "(function(){" +
            "if(window.__cngShopifyHelpers)return;" +
            "window.__cngShopifyHelpers=true;" +
            "var OV='__cngShopifyOverlay',MAXMS=8000,dismissed=false;" +
            "function isProductPage(){return /\\/products\\//.test(location.pathname);}" +
            "function pickers(){return document.querySelectorAll('variant-radios,variant-selects');}" +
            "function showOverlay(){try{if(document.getElementById(OV))return;" +
            "var d=document.createElement('div');d.id=OV;" +
            "d.style.cssText='position:fixed;top:0;left:0;right:0;bottom:0;z-index:2147483647;background:rgba(255,255,255,0.92);display:flex;align-items:center;justify-content:center;';" +
            "var s=document.createElement('div');" +
            "s.style.cssText='width:46px;height:46px;border:5px solid rgba(0,0,0,0.15);border-top-color:rgba(0,0,0,0.55);border-radius:50%;animation:__cngspin 0.8s linear infinite;';" +
            "var st=document.createElement('style');st.textContent='@keyframes __cngspin{to{transform:rotate(360deg)}}';" +
            "d.appendChild(st);d.appendChild(s);(document.body||document.documentElement).appendChild(d);}catch(e){}}" +
            "function hideOverlay(){try{var d=document.getElementById(OV);if(d&&d.parentNode)d.parentNode.removeChild(d);}catch(e){}}" +
            "function pickerForm(p){" +
            "try{var i=p.querySelector('[form]');if(i){var f=document.getElementById(i.getAttribute('form'));if(f)return f;}}catch(e){}" +
            "try{if(p.closest){var f2=p.closest('form');if(f2)return f2;var pf=p.closest('product-form');if(pf){var f3=pf.querySelector('form');if(f3)return f3;}}}catch(e){}" +
            "return document.querySelector('form[action*=\"/cart/add\"]');}" +
            "function optsOf(v){return v.options||[v.option1,v.option2,v.option3].filter(function(x){return x!=null;});}" +
            "function matchVariant(vs,picked){var i,k,o;" +
            "for(i=0;i<vs.length;i++){o=optsOf(vs[i]);if(o.length!==picked.length)continue;var ok=true;for(k=0;k<o.length;k++){if(String(o[k])!==String(picked[k])){ok=false;break;}}if(ok)return vs[i];}" +
            "for(i=0;i<vs.length;i++){o=optsOf(vs[i]);if(o.length!==picked.length)continue;var all=true;for(k=0;k<o.length;k++){if(picked.indexOf(String(o[k]))===-1){all=false;break;}}if(all)return vs[i];}" +
            "return null;}" +
            "function syncPicker(p){try{" +
            "var j=p.querySelector('script[type=\"application/json\"]');if(!j)return;" +
            "var vs=JSON.parse(j.textContent);if(!Array.isArray(vs)||!vs.length)return;" +
            "var picked=[],i;var rs=p.querySelectorAll('input[type=\"radio\"]:checked');" +
            "if(rs.length){for(i=0;i<rs.length;i++)picked.push(rs[i].value);}else{var ss=p.querySelectorAll('select');for(i=0;i<ss.length;i++)picked.push(ss[i].value);}" +
            "if(!picked.length)return;var v=matchVariant(vs,picked);if(!v)return;" +
            "var f=pickerForm(p);if(!f)return;" +
            "var inp=f.querySelector('input[name=\"id\"]');" +
            "if(!inp){inp=document.createElement('input');inp.type='hidden';inp.name='id';f.appendChild(inp);}" +
            "if(String(inp.value)!==String(v.id))inp.value=String(v.id);}catch(e){}}" +
            "function syncAll(){try{var ps=pickers();for(var i=0;i<ps.length;i++)syncPicker(ps[i]);}catch(e){}}" +
            "function isVariantControl(t){try{return !!(t&&t.closest&&t.closest('variant-radios,variant-selects'));}catch(e){return false;}}" +
            "document.addEventListener('change',function(e){if(isVariantControl(e.target))syncAll();},true);" +
            "document.addEventListener('submit',function(e){var f=e.target;if(f&&f.tagName==='FORM'&&/\\/cart\\/add/.test(f.getAttribute('action')||''))syncAll();},true);" +
            "document.addEventListener('click',function(e){try{var b=e.target&&e.target.closest?e.target.closest('[name=\"add\"]'):null;if(b)syncAll();}catch(err){}},true);" +
            "function dismiss(){if(dismissed)return;dismissed=true;syncAll();hideOverlay();}" +
            "function onReady(){var waits=[];try{if(window.customElements){['variant-radios','variant-selects','product-form','product-info'].forEach(function(t){if(document.querySelector(t))waits.push(customElements.whenDefined(t).catch(function(){}));});}}catch(e){}" +
            "if(waits.length){Promise.all(waits).then(function(){setTimeout(dismiss,50);});}else{dismiss();}}" +
            "if(isProductPage())showOverlay();" +
            "setTimeout(dismiss,MAXMS);" +
            "if(document.readyState==='complete'){setTimeout(dismiss,0);}else{window.addEventListener('load',function(){setTimeout(dismiss,0);},{once:true});}" +
            "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',onReady,{once:true});}else{onReady();}" +
            "})();";
        view.evaluateJavascript(js, null);
    }

    private void setupWebViewClient() {
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!isDomainWhitelisted(url)) {
                    return true; // Block the navigation
                }
                return false; // Allow the navigation
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                // Inject as early as possible so the variant-id backstop and loading
                // overlay are in place before the user can interact with a slow page.
                if (shopifyHelpersEnabled) {
                    injectShopifyHelpers(view);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                
                // Inject browser APIs and characteristics that Cloudflare checks
                String browserEnhancementScript = 
                    "try {" +
                    // Add missing navigator properties
                    "if (!navigator.webdriver) Object.defineProperty(navigator, 'webdriver', {get: () => undefined});" +
                    "if (!navigator.plugins) Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3,4,5]});" +
                    "if (!navigator.languages) Object.defineProperty(navigator, 'languages', {get: () => ['en-US', 'en']});" +
                    "if (!navigator.hardwareConcurrency) Object.defineProperty(navigator, 'hardwareConcurrency', {get: () => 4});" +
                    "if (!navigator.deviceMemory) Object.defineProperty(navigator, 'deviceMemory', {get: () => 8});" +
                    "if (!navigator.maxTouchPoints) Object.defineProperty(navigator, 'maxTouchPoints', {get: () => 5});" +
                    // Add chrome object
                    "if (!window.chrome) window.chrome = {runtime: {}};" +
                    // Add performance object
                    "if (!window.performance.memory) {" +
                    "Object.defineProperty(window.performance, 'memory', {" +
                    "get: () => ({usedJSHeapSize: 10000000, totalJSHeapSize: 20000000, jsHeapSizeLimit: 40000000})" +
                    "});" +
                    "}" +
                    // Add screen properties
                    "Object.defineProperty(screen, 'availTop', {get: () => 0});" +
                    "Object.defineProperty(screen, 'availLeft', {get: () => 0});" +
                    // Add missing Permission API
                    "if (!navigator.permissions) {" +
                    "navigator.permissions = {" +
                    "query: function() { return Promise.resolve({state: 'granted'}); }" +
                    "};" +
                    "}" +
                    "} catch(e) { console.log('Browser enhancement error:', e); }";
                
                view.evaluateJavascript(browserEnhancementScript, null);
                
                // Only inject barcode scanning script if configured
                if (barcodeScanURL != null && !barcodeScanURL.isEmpty()) {
                    injectBarcodeScanningScript(view);
                }

                // Re-run the Shopify helpers as a fallback (idempotent via a window guard)
                if (shopifyHelpersEnabled) {
                    injectShopifyHelpers(view);
                }
                
                // Only add event listeners if we have a callback context
                if (eventCallbackContext != null) {
                    view.evaluateJavascript(
                        "try {" +
                        "document.addEventListener('touchstart', function(event) { " +
                        "Android.eventTriggered('touchstart');" +
                        "});" +
                        "document.addEventListener('keyup', function(event) { " +
                        "Android.eventTriggered('keyup');" +
                        "});" +
                        "} catch (e) { console.error('Error adding event listeners:', e); }", 
                        null
                    );
                }
            }
        });
    }
}
