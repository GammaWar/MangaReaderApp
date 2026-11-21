# MangaReaderApp — Native Android App (APK-Projekt)

Dieses Verzeichnis (`android-app/`) enthält ein eigenständiges, natives **Android-Studio-Projekt (Kotlin + Jetpack Compose + Material 3 + OkHttp/Coil + WebView)**, ohne die bestehende Web-App im Hauptverzeichnis zu verändern.

---

## Enthaltene Features

- **AMOLED-Dunkeldesign (`#000000` / `#0B0A12`)**: Optimiert für OLED-Displays, flackerfreies Laden ohne Crossfade-Blitzen.
- **Konfigurierbarer Bild-Cache (`ChapterCacheManager`)**: Standardlimit **4 GB** (anpassbar von **512 MB bis 32 GB**), visuelle Fortschrittsanzeige in der Bibliothek, manuelles Bereinigen & Leeren (keine automatische 31-Tage-Löschung).
- **Gehärteter Bild-Loader & Frühe Null-Bitmap-Erkennung (`BitmapDecodeGuard`)**:
  - 3-Stufen-Referer-Fallback, WebView-Cookie-Synchronisierung (`CookieManager`), 15s/20s Timeouts und Exponential Backoff (`400 ms → 800 ms → 1600 ms`).
  - Erkennt `text/html`- und `application/xml`-CDN-Challenges frühzeitig vor/nach `BitmapFactory.decodeByteArray` und zeigt sofort die Fehlerkarte mit der primären Aktion **„Im Browser öffnen“**.
  - Bietet bei beschädigten Bilddaten (`Null Bitmap`) die explizite Aktion **„Cache-Bypass für dieses Bild“**.
- **Multi-Tab-WebView-Browser**: Breite Tabs mit isoliertem Schließen-Button, gemeinsamer Cookie-Speicher für Cloudflare-/WAF-Prüfungen und 1-Klick-Kapitelextraktion (`Jsoup`).
- **Alle 4 manuell schaltbaren QoL-Funktionen**: Lesezeichen & Verlauf, Software-Dimmer & Sepia-/Blaulichtfilter, Webtoon-Autoscroll (inkl. `keepScreenOn`), automatisches Pre-Fetching des Folgekapitels.

---

## APK erstellen — 3 Wege

### Weg 1: Mit Android Studio (empfohlen)
1. Lade das Projekt herunter und öffne in **Android Studio (Ladybug / Meerkat oder neuer)** direkt den Unterordner **`android-app`**.
2. Warte, bis **Gradle Sync** abgeschlossen ist (Android Studio lädt automatisch Gradle `8.11.1` und die Abhängigkeiten).
3. Klicke im Menü auf **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Die fertige APK findest du unter:
   ```text
   android-app/app/build/outputs/apk/debug/app-debug.apk
   ```
5. Kopiere die `.apk` auf dein Android-Gerät und installiere sie (oder verbinde dein Handy per USB-Debugging und drücke in Android Studio auf **Run ▶**).

### Weg 2: Über die Kommandozeile (mit installiertem JDK 17 + Android SDK + Gradle)
```bash
cd android-app
gradle assembleDebug
# Release-APK (unsigniert):
gradle assembleRelease
```

### Weg 3: Automatisch über GitHub Actions (ohne Android Studio auf dem PC)
1. Kopiere die vorbereitete Workflow-Datei `android-app/ci/build-android-apk.yml` in deinem GitHub-Repository nach `.github/workflows/build-android-apk.yml`.
2. Pushe auf GitHub oder starte den Workflow manuell im Tab **Actions**.
3. Lade nach ~2 Minuten unter **Artifacts** die fertige **`MangaReaderApp-debug-apk`** herunter und installiere sie direkt auf deinem Android-Handy.

---

## English Quick Guide — Building the APK

1. **Android Studio**: Open the **`android-app/`** folder directly in Android Studio, wait for Gradle Sync, then choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**. Output path: `android-app/app/build/outputs/apk/debug/app-debug.apk`.
2. **CLI**: Inside `android-app/`, run `gradle assembleDebug` (requires JDK 17, Android SDK 35, and Gradle 8.11+).
3. **Cloud Build (No local Android Studio required)**: Copy `android-app/ci/build-android-apk.yml` to `.github/workflows/build-android-apk.yml` in your GitHub repo and download the built APK from the workflow artifacts.
