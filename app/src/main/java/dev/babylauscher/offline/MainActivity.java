package dev.babylauscher.offline;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

/**
 * Offline-WebView-Huelle fuer BabyLauscher.
 * Kein INTERNET-Permission, kein Remote-API, keine Audio-Uebertragung.
 * Nur die explizit angeforderte Mikrofonberechtigung wird freigegeben.
 */
public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION_REQUEST = 41;
    private static final int AUDIO_FILE_REQUEST = 42;
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String START_PAGE = ORIGIN + "/assets/index.html";

    private WebView webView;
    private PermissionRequest pendingAudioRequest;
    private ValueCallback<Uri[]> pendingFileChoice;

    private static WebResourceResponse empty404() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found",
            Collections.singletonMap("Cache-Control", "no-store"),
            new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeFor(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".mjs") || path.endsWith(".js")) return "text/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private WebResourceResponse fromLocalAssets(Uri uri) {
        // Nur unsere eigene lokale HTTPS-Origin bedienen; kein Netzwerk-Fallback.
        if (!"https".equals(uri.getScheme())
                || !"appassets.androidplatform.net".equals(uri.getHost())
                || uri.getPort() != -1) return empty404();
        String path = uri.getPath();
        if (path == null || !path.startsWith("/assets/")) return empty404();
        String relative = path.substring("/assets/".length());
        if (relative.isEmpty() || relative.contains("..") || relative.contains("\\")
                || relative.startsWith("/") || relative.indexOf('\0') >= 0) return empty404();
        try {
            InputStream content = getAssets().open("web/" + relative);
            return new WebResourceResponse(mimeFor(relative),
                    relative.endsWith(".png") ? null : "UTF-8", content);
        } catch (IOException ignored) {
            return empty404();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(21, 31, 49));
        getWindow().setNavigationBarColor(Color.rgb(21, 31, 49));
        getWindow().getDecorView().setSystemUiVisibility(0);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(21, 31, 49));
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Nur System-Dateiauswahl (Audioimport), kein file://-Webzugriff.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return fromLocalAssets(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Keine Internetnavigation. Auch Versuche, andere Seiten zu laden, blockieren.
                return !START_PAGE.equals(request.getUrl().toString());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Nicht pauschal alle Ressourcen freigeben: nur AUDIO_CAPTURE und nur unsere App-Origin.
                // Android WebView kann die Origin mit einem abschliessenden / liefern.
                // Daher Scheme und Host vergleichen, statt den URI-Text 1:1 abzugleichen.
                Uri requestingOrigin = request.getOrigin();
                boolean trustedOrigin = requestingOrigin != null
                        && "https".equalsIgnoreCase(requestingOrigin.getScheme())
                        && "appassets.androidplatform.net".equalsIgnoreCase(requestingOrigin.getHost())
                        && (requestingOrigin.getPort() == -1 || requestingOrigin.getPort() == 443);
                boolean audioRequested = Arrays.asList(request.getResources())
                        .contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
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
