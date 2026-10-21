package dev.babylauscher.offline;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.Gravity;
import android.webkit.PermissionRequest;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.widget.FrameLayout;
import android.widget.Button;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Arrays;

/**
 * Offline-WebView-Huelle fuer BabyLauscher.
 * Kein INTERNET-Permission, kein Remote-API, keine Audio-Uebertragung.
 * Nur die explizit angeforderte Mikrofonberechtigung wird freigegeben.
 */
public class MainActivity extends Activity {
    private static final int AUDIO_PERMISSION_REQUEST = 41;
    private static final int AUDIO_FILE_REQUEST = 42;
    private static final int DEBUG_EXPORT_REQUEST = 43;
    private static final String DEBUG_FILE_NAME = "babylauscher_debug.log";
    private static final long LOG_CAP_BYTES = 100_000;
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String START_PAGE = ORIGIN + "/assets/index.html";

    private WebView webView;
    private PermissionRequest pendingAudioRequest;
    private ValueCallback<Uri[]> pendingFileChoice;

    // Diagnose: nur technische Statusmeldungen, niemals Audio oder Samples.
    private final Object logLock = new Object();

    private void trace(String message) {
        synchronized (logLock) {
            String value = message == null ? "(null)" : message.replace('\n', ' ').replace('\r', ' ');
            if (value.length() > 650) value = value.substring(0, 650) + "...";
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.GERMANY).format(new Date());
            String row = timestamp + " | " + value + "\n";
            File file = new File(getFilesDir(), DEBUG_FILE_NAME);
            try {
                if (file.exists() && file.length() > LOG_CAP_BYTES) {
                    // Vor einem neuen Diagnoseversuch die aelteren Meldungen entfernen.
                    if (!file.delete()) return;
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    out.write(row.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // Diagnose darf die normale App nicht beeintraechtigen.
            }
        }
    }

    private void exportLog() {
        trace("Benutzer: Diagnoseprotokoll speichern");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("text/plain");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE,
                "BabyLauscher_Debug_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt");
        try {
            startActivityForResult(intent, DEBUG_EXPORT_REQUEST);
        } catch (ActivityNotFoundException e) {
            trace("Android-Dateiauswahl nicht verfuegbar: " + e.getClass().getSimpleName());
            Toast.makeText(this, "Speichern ist auf diesem Geraet nicht verfuegbar", Toast.LENGTH_LONG).show();
        }
    }

    private void writeDiagnosticFile(Uri outputUri) {
        if (outputUri == null) return;
        trace("Benutzer: Speichern bestaetigt");
        synchronized (logLock) {
            try (FileInputStream input = new FileInputStream(new File(getFilesDir(), DEBUG_FILE_NAME));
                 OutputStream output = getContentResolver().openOutputStream(outputUri, "wt")) {
                if (output == null) throw new IOException("Ausgabestream nicht verfuegbar");
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                Toast.makeText(this, "Debug-Protokoll gespeichert – jetzt hier im Chat hochladen", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                trace("Export fehlgeschlagen: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Toast.makeText(this, "Fehler beim Speichern des Protokolls", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static int dip(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    // Wird nach jedem Laden der internen Offline-Webseite eingespielt.
    // Protokolliert Audio-API-Fehler mit e.name/e.message – keine Audioinhalte.
    private static final String JS_MICROPHONE_DIAGNOSTICS =
            "(function(){" +
            "if(window.__babyMicDebugInstalled)return;window.__babyMicDebugInstalled=true;" +
            "function out(s){console.info('[BabyDebug] '+s);}" +
            "out('isSecureContext='+window.isSecureContext+" +
            "' mediaDevices='+!!navigator.mediaDevices+" +
            "' getUserMedia='+typeof(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia)+" +
            "' AudioContext='+typeof(window.AudioContext||window.webkitAudioContext));" +
            "window.addEventListener('error',function(e){out('JS-Error: '+(e.message||'unbekannt'));});" +
            "window.addEventListener('unhandledrejection',function(e){" +
            "var r=e.reason||{};out('Unhandled-Rejection: '+(r.name||'Error')+': '+(r.message||String(r)));});" +
            "if(!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia){out('getUserMedia FEHLT');return;}" +
            "var original=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);" +
            "navigator.mediaDevices.getUserMedia=async function(c){" +
            "out('getUserMedia START, audio='+(!!(c&&c.audio)));" +
            "try{var s=await original(c);out('getUserMedia OK, audioTracks='+s.getAudioTracks().length);return s;}" +
            "catch(e){out('getUserMedia FEHLER name='+(e&&e.name)+', message='+(e&&e.message)+" +
            "', constraint='+(e&&e.constraint));throw e;}};" +
            "})();";

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

        trace("BabyLauscher Debug v0.3.3 startet");
        trace("Android API=" + Build.VERSION.SDK_INT + " Hersteller=" + Build.MANUFACTURER + " Modell=" + Build.MODEL);
        trace("RECORD_AUDIO Android Permission=" +
                (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
        if (Build.VERSION.SDK_INT >= 26 && WebView.getCurrentWebViewPackage() != null) {
            trace("WebView Paket=" + WebView.getCurrentWebViewPackage().packageName +
                    " Version=" + WebView.getCurrentWebViewPackage().versionName);
        }
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(21, 31, 49));
        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        Button debugButton = new Button(this);
        debugButton.setText("Debug-Log speichern");
        debugButton.setAllCaps(false);
        debugButton.setTextSize(11);
        debugButton.setTextColor(Color.WHITE);
        debugButton.setBackgroundColor(Color.rgb(54, 74, 99));
        debugButton.setAlpha(0.96f);
        debugButton.setContentDescription("Diagnoseprotokoll als Textdatei speichern");
        debugButton.setOnClickListener(v -> exportLog());
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                dip(this, 160), dip(this, 48), Gravity.END | Gravity.BOTTOM);
        buttonParams.setMargins(dip(this, 8), dip(this, 8), dip(this, 8), dip(this, 10));
        root.addView(debugButton, buttonParams);
        setContentView(root);
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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                trace("WebView onPageFinished: " + (START_PAGE.equals(url) ? "Offline-Startseite" : "andere URL"));
                if (START_PAGE.equals(url)) view.evaluateJavascript(JS_MICROPHONE_DIAGNOSTICS, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, android.webkit.WebResourceError error) {
                if (req.isForMainFrame()) {
                    trace("WebView Seitenladefehler Code=" + error.getErrorCode() + " Beschreibung=" + error.getDescription());
                }
            }

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
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null) {
                    trace("WebConsole " + message.messageLevel() + " [" + message.lineNumber() + "]: " + message.message());
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                trace("WebView onPermissionRequest origin=" + request.getOrigin()
                        + " resources=" + Arrays.toString(request.getResources())
                        + " AndroidPermission=" + (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
                // Android WebView kann die Origin mit abschliessendem / liefern.
                // Deshalb Schema, Host und Port pruefen statt URI-Text direkt zu vergleichen.
                Uri requestingOrigin = request.getOrigin();
                boolean trustedOrigin = requestingOrigin != null
                        && "https".equalsIgnoreCase(requestingOrigin.getScheme())
                        && "appassets.androidplatform.net".equalsIgnoreCase(requestingOrigin.getHost())
                        && (requestingOrigin.getPort() == -1 || requestingOrigin.getPort() == 443);
                boolean audioRequested = request.getResources().length == 1
                        && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(request.getResources()[0]);
                if (!trustedOrigin || !audioRequested) {
                    trace("WebView Permission DENIED trustedOrigin=" + trustedOrigin
                            + " audioRequested=" + audioRequested);
                    request.deny();
                    Toast.makeText(MainActivity.this,
                            "Mikrofonanfrage abgelehnt: unbekannte WebView-Herkunft oder Berechtigung.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    trace("WebView Permission AUDIO_CAPTURE GRANTED");
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    trace("Android RECORD_AUDIO Runtime-Permission wird angefordert");
                    if (pendingAudioRequest != null) pendingAudioRequest.deny();
                    pendingAudioRequest = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
                }
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                trace("WebView Mikrofonanfrage abgebrochen");
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
        trace("Android Runtime-Permission Ergebnis=" + Arrays.toString(results));
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
        if (requestCode == DEBUG_EXPORT_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                writeDiagnosticFile(data.getData());
            else trace("Benutzer: Speichern abgebrochen");
            return;
        }
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
