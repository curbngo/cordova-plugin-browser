package com.curbngo.browser;

import android.content.Context;
import android.webkit.JavascriptInterface;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;

public class WebAppInterface {

    private static final String TAG = "WebAppInterface";

    private CallbackContext callbackContext;

    public WebAppInterface(CallbackContext callbackContext) {
        LOG.d(TAG, "instantiating");
        this.callbackContext = callbackContext;
    }

    @JavascriptInterface
    public void eventTriggered(String eventType) {
        if (callbackContext != null) {
            PluginResult r = new PluginResult(PluginResult.Status.OK, eventType);
            r.setKeepCallback(true);
            callbackContext.sendPluginResult(r);
        }
    }
} 