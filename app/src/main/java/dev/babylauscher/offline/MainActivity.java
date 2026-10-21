                if (!trustedOrigin || !audioRequested) {
                    request.deny();
                    Toast.makeText(MainActivity.this,
                            "Mikrofonanfrage der App abgelehnt (WebView-Origin).",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    if (pendingAudioRequest != null) pendingAudioRequest.deny();
                    pendingAudioRequest = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
                }
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingAudioRequest == request) pendingAudioRequest = null;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileChoice != null) pendingFileChoice.onReceiveValue(null);
                pendingFileChoice = callback;
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("audio/*");
                pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivityForResult(pick, AUDIO_FILE_REQUEST);
                } catch (ActivityNotFoundException e) {
                    pendingFileChoice = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "Keine Dateiauswahl verfügbar", Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });

        webView.loadUrl(START_PAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != AUDIO_PERMISSION_REQUEST || pendingAudioRequest == null) return;
        PermissionRequest pending = pendingAudioRequest;
        pendingAudioRequest = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pending.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pending.deny();
            Toast.makeText(this, "Bitte Mikrofonberechtigung erlauben", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != AUDIO_FILE_REQUEST || pendingFileChoice == null) return;
        ValueCallback<Uri[]> callback = pendingFileChoice;
        pendingFileChoice = null;
        Uri item = resultCode == RESULT_OK && data != null ? data.getData() : null;
        callback.onReceiveValue(item == null ? null : new Uri[]{item});
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.evaluateJavascript("window.dispatchEvent(new Event('babylauscher:pause'));", null);
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    @Deprecated
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (pendingAudioRequest != null) {
            pendingAudioRequest.deny();
            pendingAudioRequest = null;
        }
        if (pendingFileChoice != null) {
            pendingFileChoice.onReceiveValue(null);
            pendingFileChoice = null;
        }
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
