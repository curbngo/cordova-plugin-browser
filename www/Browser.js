var exec = require('cordova/exec');

var Browser = {
    open: function(url, config, successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Browser', 'open', [url, config]);
    },

    close: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Browser', 'close', []);
    },

    back: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Browser', 'back', []);
    },

    hide: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Browser', 'hide', []);
    },

    show: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'Browser', 'show', []);
    }
};

module.exports = Browser;
