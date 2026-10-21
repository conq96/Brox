var exec = require('cordova/exec');
module.exports = {
    open: function(url, topMargin) { exec(null, null, 'BroxEngine', 'open', [url, topMargin]); },
    hide: function() { exec(null, null, 'BroxEngine', 'hide', []); },
    reload: function() { exec(null, null, 'BroxEngine', 'reload', []); }
};
