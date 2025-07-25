package com.curbngo.browser;

import android.content.Context;
import android.webkit.JavascriptInterface;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONObject;
import org.json.JSONException;

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

    @JavascriptInterface
    public void postMessage(String jsonMessage) {
        if (callbackContext != null) {
            try {
                JSONObject messageObj = new JSONObject(jsonMessage);
                PluginResult r = new PluginResult(PluginResult.Status.OK, messageObj);
                r.setKeepCallback(true);
                callbackContext.sendPluginResult(r);
            } catch (JSONException e) {
                LOG.e(TAG, "Error parsing JSON message: " + e.getMessage());
                // Fall back to sending as string
                PluginResult r = new PluginResult(PluginResult.Status.OK, jsonMessage);
                r.setKeepCallback(true);
                callbackContext.sendPluginResult(r);
            }
        }
    }
} 