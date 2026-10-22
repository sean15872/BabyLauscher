# BabyLauscher v0.5 – Offline-KI-Prototyp für Android

**Stand: 21.10.2026 – Implementierung vorbereitet, GitHub-APK-Build und echtes Android-Ergebnis noch NICHT verifiziert.**

Diese Entwicklung baut auf der funktionierenden AudioRecord-Version 0.4 auf. Die native Android-Aufnahme bleibt bestehen; hinzu kommt die lokal ausgeführte Klassifikation mit `AmeerHesham/distilhubert-finetuned-baby_cry` (5 Kategorien). Die App funktioniert nach erfolgreichem Bau ohne Internet und enthält weiterhin die ältere Geräuschmerkmalsanalyse und den persönlichen Mustervergleich.

## Wissenschaftliche Grenzen

Das Modell wurde nur mit dem kleinen *Donate_a_cry*-Datensatz trainiert (457 veröffentlichte Beispiele). Dessen Klassifikation erlaubt **keine verlässliche Ursache- oder Schmerzdiagnose**. Die Ausgabe zeigt lediglich die relative Verteilung der fünf erzwungenen Modellkategorien. Hohe Modellscores bedeuten **nicht**, dass z.B. Hunger tatsächlich mit entsprechender Wahrscheinlichkeit vorliegt. Das Ergebnis `Nicht eindeutig` muss möglich bleiben. Bei anhaltend ungewöhnlichem Schreien oder anderen Warnzeichen ärztlich abklären. Die App ersetzt keine Betreuung/Beobachtung.

Quelle Modell (Apache-2.0): https://huggingface.co/AmeerHesham/distilhubert-finetuned-baby_cry
Trainingsdaten: https://huggingface.co/datasets/Nooon/Donate_a_cry
ONNX Runtime: https://onnxruntime.ai/docs/install/

## Was GitHub Actions baut

1. Python 3.11 / PyTorch 2.5.1 / Transformers 4.41.2 installieren.
2. Offizielles trainiertes `model.safetensors` von Hugging Face beziehen (Revision festgelegt im Skript `tools/export_distilhubert.py`).
3. Festes ONNX-Modell exportieren, mit Python ONNX Runtime gegen PyTorch prüfen. **Bei Modellfehlern den APK-Build abbrechen, keine Dummy-KI ausliefern.**
4. Modell in `app/src/main/assets/model/babycry.onnx` ablegen, Android-App mit Gradle bauen.
5. `BabyLauscher-v0.5-KI.apk` als GitHub Actions Artifact bereitstellen.

**Hinweis:** Der Build kann bei einem neuen Python-Paket, inkompatibler Export-Operation oder fehlender Serververbindung scheitern; Konversion und Laufzeit auf dem Realme waren in dieser Umgebung nicht testbar. Bitte GitHub-Log schicken, falls es zu einem Fehler kommt.

## Projekt im vorhandenen GitHub-Repository aktualisieren

Bestehendes Repository: `https://github.com/sean15872/BabyLauscher`

Am einfachsten das komplette ZIP entpacken, in GitHub `Code > Add file > Upload files` waehlen, den **Inhalt** des entpackten Verzeichnisses (inklusive `.github`, `app`, `tests` und `tools`) per Drag & Drop auf die Upload-Seite ziehen, Pfade beibehalten und `Commit changes` anklicken. Achtung: Nicht die ZIP selbst und nicht den uebergeordneten Verzeichnisnamen hochladen. Dateien mit bestehenden Pfaden werden ersetzt. Anschliessend unter `Actions` den letzten Build oeffnen.

Einzelne geaenderte Dateien (falls Drag & Drop nicht klappt):

- `.github/workflows/android-apk.yml`
- `app/build.gradle`
- `app/src/main/java/dev/babylauscher/offline/MainActivity.java`
- `app/src/main/java/dev/babylauscher/offline/CryClassifier.java` (neu)
- `app/src/main/assets/web/index.html`
- `app/src/main/assets/web/style.css`
- `app/src/main/assets/web/app.mjs`
- `tools/export_distilhubert.py` (neu)
- `tools/requirements-model.txt` (neu)

Im Repository alle geaenderten Dateien auf derselben Ebene wie bisher lassen. Den alten `.gitignore`, `tests`, die Icons usw. unveraendert beibehalten.

**Keine Modell-Datei selbst hochladen**: Der neue Workflow exportiert sie direkt beim APK-Build. Groessenordnung Modell ~95 MB, apk entsprechend groesser als die bisherige.

## Android-Installation und Tests

1. Auf GitHub `Actions > BabyLauscher v0.5 Offline-KI APK > letzter gruener Lauf > Artifacts` waehlen.
2. APK-ZIP herunterladen und entpacken. `BabyLauscher-v0.5-KI.apk` installieren.
3. Acht Sekunden Babylaute aufnehmen (ohne Hintergrund-TV oder Erwachsenengespräche). Nach dem bestehenden Akustikteil sollte die **KI-Box mit fünf Modellscores** erscheinen. Bei technischen Fehlern `Debug-Log speichern` antippen und die Textdatei senden.
4. Die Version zeigt **keine KI-Auswertung für den manuellen Dateiimport**: dieser bleibt auf den persönlichen Merkmalsvergleich begrenzt. KI gilt zunaechst nur fuer die native Mikrofonaufnahme.

**Update-Signatur:** GitHub Actions baut eine Debug-APK mit einem möglicherweise jedes Mal neu erzeugten Debug-Zertifikat. Wenn das Überschreiben fehlschlägt, kann eine Deinstallation notwendig sein. **Dadurch gehen die bislang nur lokal gespeicherten Lernbeispiele verloren**. Zukünftig sollte ein stabiler privater Signaturschlüssel als verschlüsseltes GitHub-Secret eingerichtet werden.

## Vorverarbeitung und Lizenz

- Android AudioRecord: Mono-PCM16 mit 16 kHz (bzw. Android-Fallback 44,1 oder 48 kHz).
- Resampling auf 16 kHz, Mittelwert und Standardabweichung nur der tatsächlichen Samples, danach 10 Sekunden Tensor mit Nullauffuellung.
- Zweiter Tensor: Attention-Mask mit 1 fuer echte Samples und 0 fuer Padding.
- Ergebnis: Softmax-Verteilung ueber die fünf Modellklassen. Alle Berechnungen in der APK, ohne Netzwerkberechtigung.
- Modell: Apache-2.0, Copyright der jeweiligen Modellautor:innen; Modellcode und Lizenz siehe verlinkte Modellseite. ONNX Runtime: MIT-Lizenz. Vor einer oeffentlichen Weitergabe bitte saemtliche Drittlizenzen vollstaendig beilegen.

## Groesse / Performance

Nicht auf einem Realme RMX3370 gemessen. Modell belegt unkomprimiert grob 95 MB und wird beim ersten Start einer KI-Auswertung in einen privaten App-Ordner kopiert. Erste Auswertung kann laenger dauern. Die Java-Ausfuehrung verwendet ONNX Runtime CPU auf ARM64.
