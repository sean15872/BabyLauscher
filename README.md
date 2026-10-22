# BabyLauscher 0.5.2 – HuBERT-ONNX-Export reparieren

## Ursache

GitHub Actions erreichte bereits die Modellumwandlung, konnte das exportierte
ONNX aber wegen `Node (/model/Where_3) ... [ShapeInferenceError] Incompatible dimensions`
nicht mit ONNX Runtime laden. Es ist **kein Fehler der Mikrofonaufnahme**.

## Änderung

- `tools/export_distilhubert.py` ersetzt problematische maskierte
  PyTorch-Indizierung durch maskierte Multiplikation und vergleichsbasierte
  Feature-Maske. Die trainierten Gewichte bleiben unverändert.
- Das Skript vergleicht die neue PyTorch-Ausgabe mit der unveränderten
  Modell-Ausgabe und die ONNX-Ausgabe mit dem Original, für zwei unterschiedliche
  Aufnahmelängen. Bei Fehlern wird der Build abgebrochen.
- `app/build.gradle` setzt Versionsname auf `0.5.2` und VersionCode auf `7`.
  Der bereits versionierte Workflow erzeugt dann `BabyLauscher_v0.5.2.apk`.

## In GitHub ersetzen (nur diese zwei Dateien)

1. https://github.com/sean15872/BabyLauscher/edit/main/tools/export_distilhubert.py
   - Ganze alte Datei markieren und den vollständigen Inhalt der gleichnamigen
     Datei aus diesem ZIP einfügen, dann **Commit changes**.
2. https://github.com/sean15872/BabyLauscher/edit/main/app/build.gradle
   - Ebenso vollständig ersetzen und **Commit changes**.
3. https://github.com/sean15872/BabyLauscher/actions
   - Neueste Ausführung öffnen. Entscheidend ist die **letzte** Ausführung nach
     beiden Commits.
   - Bei Erfolg: unter **Artifacts** `BabyLauscher_v0.5.2_APK` herunterladen,
     ZIP entpacken und die `BabyLauscher_v0.5.2.apk` installieren.
   - Wenn der Modell-Export wieder rot wird, die neue Fehlermeldung senden.

## Prüfstatus und Vorsicht

Die Python-Syntax und die mathematische Gleichheit des Maskenaufbaus auf
Testmasken wurden lokal geprüft. Die vollständige ONNX-Konvertierung und der
Android-APK-Build waren lokal nicht ausführbar. Ein grüner GitHub-Build mit
bestandenem ONNX-vs-PyTorch-Vergleich ist noch erforderlich.

Bisherige Mikrofonfunktion, Web-Oberfläche und Lernmodus bleiben unberührt.
Bei Installation mit einem anderen Debug-Signaturschlüssel kann eine
Neuinstallation nötig werden, die lokale Lernbeispiele löscht.

Diese Forschungs-KI kann die Ursache eines Babygeschreis nicht zuverlässig
diagnostizieren, auch wenn ein Modellscores hoch ist.
