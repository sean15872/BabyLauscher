# BabyLauscher – APK mit GitHub Actions bauen (ohne Android Studio)

Version **v0.3**, für Babys von **0 bis 13 Wochen**. Das **komplette Programm** steckt später in der Android-APK und ist **offline** nutzbar. Diese ZIP-Datei ist das Quellprojekt, noch **keine installierbare APK**.

## Einmalig einrichten (nur im Webbrowser)

1. GitHub-Konto öffnen oder erstellen: <https://github.com/>.
2. Auf <https://github.com/new> gehen. Als **Repository name** zum Beispiel `BabyLauscher` eintragen, vorzugsweise **Private** auswählen. Ein leeres Repository ohne README, `.gitignore` oder Lizenz erstellen.
3. Dieses ZIP **auf dem Computer entpacken**. Danach den **Inhalt** des Ordners `BabyLauscher_GitHub_APK_v0.3` auswählen, **nicht das ZIP selbst**. Der versteckte Unterordner `.github/workflows` muss mit hochgeladen werden.
4. Auf der neuen Repository-Seite **uploading an existing file** bzw. **Add file → Upload files** wählen. Dateien **und Ordner** aus dem entpackten Projekt per Drag-and-drop hineinziehen.
5. Darauf achten, dass im Upload `app/build.gradle` und `.github/workflows/android-apk.yml` landen. Unten **Commit changes** klicken.
6. Zum Reiter **Actions** gehen. Falls GitHub auffordert, Actions erst zu aktivieren, aktivieren. Der Build startet beim ersten Commit auf `main` automatisch. Falls nicht: links **BabyLauscher APK bauen** auswählen → **Run workflow** → grünen Knopf **Run workflow**.
7. Nach einem **grünen Häkchen** den Workflow-Lauf anklicken. Unten bei **Artifacts** auf `BabyLauscher-v0.3-APK` klicken. Das heruntergeladene ZIP entpacken; darin liegt **`BabyLauscher-v0.3.apk`**.
8. APK aufs Android-Handy kopieren und über den Dateimanager installieren (gegebenenfalls „Unbekannte Apps installieren“ für den Dateimanager erlauben). Beim ersten Start **Mikrofonberechtigung** erlauben.

## Für weitere Versionen

Bei Quellcode-Updates und erneutem Commit auf `main` erstellt GitHub automatisch eine neue APK. Für einen manuellen Build kannst du jederzeit **Actions → BabyLauscher APK bauen → Run workflow** benutzen. Ein kostenloses GitHub-Konto genügt üblicherweise für gelegentliche Builds; GitHub kann für private Repositories Nutzungsgrenzen haben.

**Wichtig wegen Updates:** Dies ist ausdrücklich eine **Debug-APK**. GitHub erstellt auf neuen Runnern möglicherweise bei jedem Build einen **anderen Debug-Signaturschlüssel**. Daher lässt sich eine spätere APK gegebenenfalls nicht über die installierte Version installieren. Ein Deinstallieren würde auch die lokal gespeicherten Lernbeispiele löschen. Bevor du über lange Zeit eigene Lernbeispiele sammelst, sollten wir eine dauerhafte Signierung mit GitHub Secrets sowie Import/Export für die Beispiele ergänzen. Den Signaturschlüssel niemals in ein öffentliches Repository hochladen.

## Sicherheit & technische Details

- Die App verwendet `RECORD_AUDIO`, **kein `INTERNET`** in AndroidManifest.xml. Die gesamten HTML-/JavaScript-/CSS-Dateien sind in der App eingebettet; auf Android ist keine Webadresse und kein Hosting nötig.
- Nur lokale achtsekündige Aufnahmen, ohne automatisches Abhören im Hintergrund. Es werden akustische Merkmale, keine Sprachaufnahmen gespeichert.
- Die fünf Dunstan-Laute sind **nur eine nicht gesicherte Orientierung**. Es gibt hier **kein** medizinisch geprüftes Erkennungsmodell und keine zuverlässige automatische Diagnose von Hunger, Bauchweh oder Müdigkeit. Die App lernt nur den Vergleich bestätigter Beispiele.
- Bei auffälliger Atmung, Teilnahmslosigkeit, Blaufärbung oder Fieber (38 °C oder höher bei Säuglingen unter 3 Monaten) sofort medizinische Hilfe suchen und nicht auf die App vertrauen.
- Ein **JavaScript-Signaltest** ist in `.github/workflows/android-apk.yml` vorgesehen. Die Android-Kompilierung und ein echter Gerätetest konnten bei Erstellung dieses ZIPs **nicht durchgeführt** werden.
- `gradle/actions/setup-gradle` stellt **Gradle 8.9** bereit. Der bisherige nicht-offizielle Wrapper wurde absichtlich entfernt. Die App nutzt Android Gradle Plugin **8.7.3**, API 35, Java 17, minSdk 26.

### Falls der Build fehlschlägt

Unter **Actions** den roten Build-Lauf öffnen und bei `android-apk` auf den fehlgeschlagenen Schritt klicken. Die **Fehlermeldung oder einen Screenshot** schicken, dann kann ich den Workflow anpassen.
