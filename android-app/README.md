# MangaReaderApp — Native Android App (v2.1)

Eigenständiges natives **Android-Studio-Projekt (Kotlin + Jetpack Compose + Material 3)** im **1:1 AMOLED-Dunkeldesign (`#000000` / `#0B0A12`)**, entwickelt ohne Veränderung der Web-App im Hauptverzeichnis.

---

## Enthaltene Features (v2.1)

- **AMOLED 1:1 Layout & 4-Tab-Navigation (`Bibliothek · Browser · Reader · Assistent`)**:
  - Startet sauber in der **Bibliothek** mit globaler Top-Bar, neuem Neon-App-Icon und gebündelten Schriften (*Plus Jakarta Sans*, *DM Sans*, *JetBrains Mono*).
  - Echte **Multi-Tab-WebView** mit persistentem Tab-/Session-Speicher (`SessionManager`), Cookie-Synchronisierung für Cloudflare/WAF und **JavaScript-DOM-Extraktion** (`ImageExtractorJs`) inkl. 2-Stufen-Kapitelerkennung (`UrlChapterAnalyzer`).
- **Vollbild-Reader (`Webtoon` & `Manga RTL/LTR`)**:
  - Stufenloser Pinch-to-Zoom & Panning (`ZoomableBox`), synchronisiert mit der Zoom-Steuerung (`- | 100% | +`), Auto-Hide-HUD, Seitenscrubber, Autoscroll-Floating-Pill, Software-Dimmer & Sepia-Filter, `keepScreenOn` und automatisches Kapitel-Pre-Fetching.
- **Offline-Downloads (`WorkManager`)**:
  - Hintergrund-Download via `MangaDownloadWorker` in `filesDir/manga_downloads/` mit Cover-Vorschau und Offline-Status in Reader & Bibliothek.
- **Konfigurierbarer Bild-Cache & CDN-Härtung (`ChapterCacheManager`, `GuardedBitmapDecoder`)**:
  - Standardlimit **4 GB** (anpassbar von **512 MB bis 32 GB**, manuelles Bereinigen/Leeren ohne 31-Tage-Auto-Ablauf).
  - 3-Stufen-Referer-Fallback, Exponential Backoff (`400ms → 800ms → 1600ms`), `text/html`- & `application/xml`-Challenge-Erkennung vor `BitmapFactory` und Einzelbild-Cache-Bypass (`ImageErrorView`).
- **Multi-Turn Manga-Assistent (`AssistantScreen`)**:
  - Direkter Gemini-Streaming-Client mit Persona-Wahl, Modell-Switch (*Standard* / *Schnell*) und optionalem lokal gespeicherten API-Key.

---

## APK erstellen & testen

### 1. Android Studio
1. Unterordner **`android-app/`** direkt in Android Studio öffnen (**JDK 17**, **Android SDK Platform 34**).
2. Gradle Sync abwarten (Gradle Wrapper **8.9** ist enthalten).
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

### 2. Kommandozeile (Gradle Wrapper)
```bash
cd android-app
./gradlew testDebugUnitTest assembleDebug
```
Fertige Debug-APK: `android-app/app/build/outputs/apk/debug/app-debug.apk`.

#### Optional: Signierte Release-APK
Lege deinen Keystore lokal unter `android-app/keystore/release.keystore` ab (wird via `.gitignore` ignoriert) und übergib die Zugangsdaten per Umgebungsvariable oder Gradle-Property:
```bash
KEYSTORE_PASSWORD="..." KEY_ALIAS="mangareader" KEY_PASSWORD="..." ./gradlew assembleRelease
```

### 3. GitHub Actions CI
Die vorbereitete Workflow-Datei `android-app/ci/build-android-apk.yml` nach `.github/workflows/build-android-apk.yml` kopieren — führt automatisch Unit-Tests + `assembleDebug` aus und lädt die APK als Workflow-Artifact hoch.
