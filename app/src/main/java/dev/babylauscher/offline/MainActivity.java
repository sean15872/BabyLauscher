package dev.babylauscher.offline;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * BabyLauscher native Aufnahme v0.4.
 * Die HTML-App liegt ausschliesslich in den APK-Assets, ohne Internet-Berechtigung.
 * Android AudioRecord ersetzt die auf manchen Telefonen defekte WebView-getUserMedia-Aufnahme.
 * Nur das 8-Sekunden-WAV wird im Arbeitsspeicher an die bestehende lokale Analyse uebergeben.
 */
public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 41;
    private static final int FILE_REQUEST = 42;
    private static final int LOG_SAVE_REQUEST = 43;
    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST;
    private static final String PAGE = ORIGIN + "/assets/index.html";
    private static final int MAX_SECONDS = 8;

    private WebView webView;
    private ValueCallback<Uri[]> pendingFileChoice;
    private PermissionRequest pendingWebPermission;
    private volatile boolean nativeRecording = false;
    private volatile boolean stopNative = false;
    private final Object logLock = new Object();
    private File logFile;

    private static final String NATIVE_JAVASCRIPT = """
        (() => {
          if (window.__babyNativeInstalled) return;
          window.__babyNativeInstalled = true;
          let active = false;
          let started = 0;
          let timer = null;
          let wavChunks = [];
          const byId = id => document.getElementById(id);
          function message(title, detail) {
            if (byId('recordHeadline')) byId('recordHeadline').textContent = title;
            if (byId('recordDescription')) byId('recordDescription').textContent = detail || '';
          }
          function stopTimer() { if (timer) clearInterval(timer); timer = null; }
          function updateClock() {
            const elapsed = Math.min(8, (performance.now() - started) / 1000);
            if (byId('timer')) byId('timer').innerHTML =
              elapsed.toFixed(1).replace('.', ',') + ' <span>/ 8,0 Sekunden</span>';
          }
          window.__babyNativeState = (kind, details) => {
            if (kind === 'recording') {
              active = true; started = performance.now(); stopTimer();
              timer = setInterval(updateClock, 100);
              document.body.classList.add('is-recording');
              if (byId('recordActionText')) byId('recordActionText').textContent = 'Aufnahme stoppen';
              if (byId('recordActionIcon')) byId('recordActionIcon').textContent = '■';
              if (byId('recState')) byId('recState').textContent = 'Android-Mikrofon aktiv';
              message('Ich lausche …', 'Native Android-Aufnahme läuft, maximal 8 Sekunden.');
            } else {
              active = false; stopTimer();
              document.body.classList.remove('is-recording');
              if (byId('recordActionText')) byId('recordActionText').textContent = 'Aufnahme starten';
              if (byId('recordActionIcon')) byId('recordActionIcon').textContent = '◉';
              if (byId('recState')) byId('recState').textContent = 'Bereit';
              if (kind === 'error') message('Android-Mikrofon fehlgeschlagen', details || 'Bitte Debug-Log speichern.');
              if (kind === 'processing') message('Geräusche werden ausgewertet …', 'Alles bleibt auf diesem Gerät.');
            }
          };
          window.__babyNativeChunk = chunk => { wavChunks.push(chunk); };
          window.__babyNativeFinish = () => {
            try {
              const base64 = wavChunks.join(''); wavChunks = [];
              const raw = atob(base64);
              const bytes = new Uint8Array(raw.length);
              for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
              const wav = new File([bytes], 'babylauscher-android.wav', {type: 'audio/wav'});
              const dt = new DataTransfer(); dt.items.add(wav);
              const input = byId('fileInput');
              if (!input) throw new Error('HTML fileInput fehlt');
              input.files = dt.files;
              input.dispatchEvent(new Event('change', {bubbles: true}));
            } catch (e) {
              console.error('[BabyNative] Uebergabe fehlgeschlagen: ' + e.name + ': ' + e.message);
              message('Audio-Uebergabe fehlgeschlagen', e.message);
            }
          };
          function nativePress(event) {
            event.preventDefault();
            event.stopImmediatePropagation();
            if (active) window.BabyNative.stopAudio();
            else window.BabyNative.startAudio();
          }
          ['recordButton', 'recordAction'].forEach(id => {
            const el = byId(id);
            if (el) el.addEventListener('click', nativePress, true);
          });
          console.info('[BabyNative] Android AudioRecord integriert (v0.4)');
        })();
        """;

    private void trace(String line) {
        synchronized (logLock) {
            try {
                if (logFile == null) logFile = new File(getFilesDir(), "babylauscher_debug.log");
                if (logFile.exists() && logFile.length() > 120000) {
                    // Begrenztes Protokoll, keine Audioinhalte.
                    new FileOutputStream(logFile, false).close();
                }
                String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                try (FileOutputStream out = new FileOutputStream(logFile, true)) {
                    out.write((stamp + " | " + line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) { /* Debug darf die App nicht blockieren */ }
        }
    }

    private void saveLog() {
        trace("Benutzer: Diagnoseprotokoll speichern");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "BabyLauscher_Debug_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt");
        try { startActivityForResult(intent, LOG_SAVE_REQUEST); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "Speichern nicht verfuegbar", Toast.LENGTH_LONG).show(); }
    }

    private void writeLog(Uri uri) {
        if (uri == null) return;
        synchronized (logLock) {
            try (InputStream in = new FileInputStream(new File(getFilesDir(), "babylauscher_debug.log"));
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Kein Schreibzugriff");
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                Toast.makeText(this, "Debug-Log gespeichert", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Speichern fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found",
                Collections.singletonMap("Cache-Control", "no-store"),
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "text/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private WebResourceResponse serveLocalAsset(Uri uri) {
        if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || uri.getPort() != -1)
            return notFound();
        String path = uri.getPath();
        if (path == null || !path.startsWith("/assets/")) return notFound();
        String rel = path.substring("/assets/".length());
        if (rel.length() == 0 || rel.contains("..") || rel.contains("\\") || rel.startsWith("/") || rel.indexOf('\0') >= 0)
            return notFound();
        try {
            InputStream input = getAssets().open("web/" + rel);
            return new WebResourceResponse(mime(rel), rel.endsWith(".png") ? null : "UTF-8", input);
        } catch (IOException e) { return notFound(); }
    }

    private static int dp(Activity activity, int n) {
        return Math.round(n * activity.getResources().getDisplayMetrics().density);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(21, 31, 49));
        getWindow().setNavigationBarColor(Color.rgb(21, 31, 49));
        trace("BabyLauscher v0.4 native mic gestartet");
        trace("Android API=" + Build.VERSION.SDK_INT + " Hersteller=" + Build.MANUFACTURER + " Modell=" + Build.MODEL);
        trace("RECORD_AUDIO=" + (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"));
        if (Build.VERSION.SDK_INT >= 26 && WebView.getCurrentWebViewPackage() != null)
            trace("WebView=" + WebView.getCurrentWebViewPackage().packageName + " " + WebView.getCurrentWebViewPackage().versionName);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(21, 31, 49));
        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        Button debug = new Button(this);
        debug.setText("Debug-Log speichern");
        debug.setAllCaps(false);
        debug.setTextSize(11);
        debug.setTextColor(Color.WHITE);
        debug.setBackgroundColor(Color.rgb(54, 74, 99));
        debug.setOnClickListener(v -> saveLog());
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(this, 165), dp(this, 48), Gravity.END | Gravity.BOTTOM);
        p.setMargins(dp(this, 8), dp(this, 8), dp(this, 8), dp(this, 10));
        root.addView(debug, p);
        setContentView(root);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setSupportMultipleWindows(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        // Die JS-Schnittstelle ist nur fuer die lokale App gedacht, ohne Internetberechtigung.
        webView.addJavascriptInterface(new NativeBridge(), "BabyNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serveLocalAsset(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !PAGE.equals(request.getUrl().toString());
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                trace("WebView geladen: " + (PAGE.equals(url) ? "lokal" : "fremd/anders"));
                if (PAGE.equals(url)) view.evaluateJavascript(NATIVE_JAVASCRIPT, null);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) trace("WebView Fehler " + error.getErrorCode() + ": " + error.getDescription());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                if (m != null) trace("WebConsole " + m.messageLevel() + ": " + m.message());
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                Uri uri = request.getOrigin();
                boolean trusted = uri != null && "https".equalsIgnoreCase(uri.getScheme())
                        && HOST.equalsIgnoreCase(uri.getHost()) && (uri.getPort() == -1 || uri.getPort() == 443);
                boolean onlyAudio = request.getResources().length == 1
                        && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(request.getResources()[0]);
                if (!trusted || !onlyAudio) { trace("WebView Permission abgelehnt"); request.deny(); return; }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    trace("WebView AUDIO_CAPTURE freigegeben");
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingWebPermission = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
                }
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest r) {
                if (pendingWebPermission == r) pendingWebPermission = null;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFileChoice != null) pendingFileChoice.onReceiveValue(null);
                pendingFileChoice = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("audio/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivityForResult(i, FILE_REQUEST); }
                catch (ActivityNotFoundException e) {
                    pendingFileChoice = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "Keine Audio-Dateiauswahl verfuegbar", Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
        webView.loadUrl(PAGE);
    }

    public class NativeBridge {
        @JavascriptInterface public void startAudio() {
            runOnUiThread(() -> startNativeAudio());
        }
        @JavascriptInterface public void stopAudio() {
            stopNative = true;
            trace("Benutzer: Android AudioRecord vorzeitig stoppen");
        }
    }

    private void sendState(String state, String details) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("window.__babyNativeState && window.__babyNativeState("
                        + JSONObject.quote(state) + "," + JSONObject.quote(details == null ? "" : details) + ");", null);
            }
        });
    }

    private void startNativeAudio() {
        if (nativeRecording) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            trace("Native Start: RECORD_AUDIO nicht freigegeben, fordere Berechtigung an");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
            return;
        }
        nativeRecording = true;
        stopNative = false;
        trace("Native AudioRecord START");
        Thread worker = new Thread(this::recordAudioWorker, "BabyLauscher-AudioRecord");
        worker.start();
    }

    private void recordAudioWorker() {
        AudioRecord recorder = null;
        int sampleRate = 0;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(300000);
        try {
            for (int rate : new int[]{16000, 44100, 48000}) {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                trace("AudioRecord minBuffer rate=" + rate + " result=" + min);
                if (min <= 0) continue;
                try {
                    recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(4096, min * 2));
                    if (recorder.getState() == AudioRecord.STATE_INITIALIZED) { sampleRate = rate; break; }
                    recorder.release(); recorder = null;
                    trace("AudioRecord init fehlgeschlagen bei " + rate);
                } catch (IllegalArgumentException | UnsupportedOperationException ex) {
                    trace("AudioRecord init Ausnahme: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    if (recorder != null) { recorder.release(); recorder = null; }
                }
            }
            if (recorder == null || sampleRate == 0) throw new IllegalStateException("Kein AudioRecord mit unterstützter Abtastrate initialisierbar");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("AudioRecord startet nicht: state=" + recorder.getRecordingState());
            trace("AudioRecord Aufnahme aktiv, rate=" + sampleRate);
            sendState("recording", "");
            byte[] buffer = new byte[4096];
            int max = MAX_SECONDS * sampleRate * 2;
            while (!stopNative && pcm.size() < max) {
                int requested = Math.min(buffer.length, max - pcm.size());
                int n = recorder.read(buffer, 0, requested, AudioRecord.READ_BLOCKING);
                if (n > 0) pcm.write(buffer, 0, n);
                else if (n < 0) throw new IllegalStateException("AudioRecord.read Fehlercode=" + n);
            }
            if (pcm.size() < sampleRate / 3) throw new IllegalStateException("Aufnahme zu kurz (" + pcm.size() + " Bytes)");
            trace("AudioRecord erfolgreich: " + pcm.size() + " PCM-Bytes, rate=" + sampleRate);
            sendState("processing", "");
            byte[] wav = encodeWav(pcm.toByteArray(), sampleRate);
            sendWav(wav);
        } catch (Exception ex) {
            trace("Native AudioRecord FEHLER " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            sendState("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (recorder != null) {
                try { if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop(); }
                catch (Exception ignored) { }
                recorder.release();
            }
            nativeRecording = false;
            trace("Native AudioRecord Ende");
        }
    }

    private static void intLE(ByteArrayOutputStream out, int value) {
        for (int i = 0; i < 4; i++) out.write((value >>> (i * 8)) & 255);
    }
    private static void shortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 255); out.write((value >>> 8) & 255);
    }
    private static void ascii(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }
    private static byte[] encodeWav(byte[] pcm, int sampleRate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        ascii(out, "RIFF"); intLE(out, 36 + pcm.length); ascii(out, "WAVE");
        ascii(out, "fmt "); intLE(out, 16); shortLE(out, 1); shortLE(out, 1);
        intLE(out, sampleRate); intLE(out, sampleRate * 2); shortLE(out, 2); shortLE(out, 16);
        ascii(out, "data"); intLE(out, pcm.length);
        out.write(pcm, 0, pcm.length);
        return out.toByteArray();
    }

    private void sendWav(byte[] wav) {
        // In kleinen Teilen senden, damit keine einzelnen sehr grossen WebView-Aufrufe entstehen.
        String encoded = Base64.encodeToString(wav, Base64.NO_WRAP);
        runOnUiThread(() -> {
            if (webView == null) return;
            trace("WAV an lokalen JavaScript-Audioimport: " + wav.length + " Byte (Audio nicht protokolliert)");
            final int block = 16000;
            for (int offset = 0; offset < encoded.length(); offset += block) {
                String part = encoded.substring(offset, Math.min(encoded.length(), offset + block));
                webView.evaluateJavascript("window.__babyNativeChunk && window.__babyNativeChunk(" + JSONObject.quote(part) + ");", null);
            }
            webView.evaluateJavascript("window.__babyNativeFinish && window.__babyNativeFinish();", null);
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != PERMISSION_REQUEST) return;
        boolean allowed = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        trace("Android Mikrofonberechtigung: " + (allowed ? "GRANTED" : "DENIED"));
        if (pendingWebPermission != null) {
            PermissionRequest request = pendingWebPermission;
            pendingWebPermission = null;
            if (allowed) request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else request.deny();
        } else if (allowed) startNativeAudio();
        else sendState("error", "Android-Mikrofonberechtigung wurde verweigert");
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == LOG_SAVE_REQUEST) {
            if (result == RESULT_OK && data != null) writeLog(data.getData());
        } else if (code == FILE_REQUEST && pendingFileChoice != null) {
            ValueCallback<Uri[]> callback = pendingFileChoice; pendingFileChoice = null;
            Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
            callback.onReceiveValue(uri == null ? null : new Uri[]{uri});
        }
    }

    @Override protected void onPause() {
        stopNative = true;
        if (webView != null) {
            webView.evaluateJavascript("window.dispatchEvent(new Event('babylauscher:pause'));", null);
            webView.onPause();
        }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); if (webView != null) webView.onResume();
    }
    @Override @Deprecated public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        stopNative = true;
        if (pendingWebPermission != null) { pendingWebPermission.deny(); pendingWebPermission = null; }
        if (pendingFileChoice != null) { pendingFileChoice.onReceiveValue(null); pendingFileChoice = null; }
        if (webView != null) {
            webView.loadUrl("about:blank"); webView.removeJavascriptInterface("BabyNative");
            webView.destroy(); webView = null;
        }
        super.onDestroy();
    }
}
