# MangaReaderApp — Native Android APK Roadmap (`android-app/`)

> **Geltungsbereich:** Alle Arbeiten dieser Roadmap beziehen sich **ausschließlich** auf das native Android-Projekt in `android-app/` (Kotlin + Jetpack Compose + Material 3) sowie zugehörige Android-Workflows/Dokumentation. Die Web-/Node-Dateien im Root bleiben strikt **Read-Only**.

---

## Meilenstein 1: Sauberes, kompilierbares Android-Projekt + funktionierende Start-APK (`app-debug.apk`) + GitHub Action

- **Ziel:** Reproduzierbar baubares Gradle-Projekt unter `android-app/`, saubere absturzfreie AMOLED-Start-App (`#000000`) mit Grundnavigation (**Bibliothek / Reader / Einstellungen**), verifizierte `app-debug.apk` lokal und automatisch als Download in GitHub Actions.
- **Konkrete Schritte:**
  1. `GEMINI.md` mit strikter Android-only-Regel aktualisieren (Web-Dateien im Root nicht anfassen).
  2. Gradle-Struktur in `android-app/` vervollständigen/reparieren (Kotlin, Jetpack Compose, Material 3, OkHttp, Coil, lokales gitignored `local.properties`, `android-app/.gitignore`, offizieller Gradle-8.9-Wrapper `gradlew` / `gradlew.bat`, saubere Integration der produktiven `MangaReaderApplication.kt` ohne loses Root-Duplikat).
  3. Absturzfreie Start-App mit echtem AMOLED-Hintergrund (`#000000`) und zuverlässiger Grundnavigation (**Bibliothek / Reader / Einstellungen**, inkl. WebView-/CookieManager-Absturzsicherung) sicherstellen.
  4. `.github/workflows/build-apk.yml` erstellen, damit GitHub Actions bei jedem Push automatisch `testDebugUnitTest` & `assembleDebug` ausführt und `app-debug.apk` als Artifact hochlädt.
  5. `android-app/README.md` auf den aktuellen Stand bringen.
- **Abnahmebedingung:**
  - `./gradlew testDebugUnitTest assembleDebug` (bzw. `gradlew.bat testDebugUnitTest assembleDebug` unter Windows) läuft mit Exit-Code `0` durch.
  - `android-app/app/build/outputs/apk/debug/app-debug.apk` existiert und lässt sich direkt auf einem Android-Gerät installieren und starten.

---

## Meilenstein 2: Nativer Bild- & Kapitel-Loader mit OkHttp + Coil (ohne Express-Server)

- **Ziel:** Zuverlässiges, 100 % natives Laden von Kapitelseiten und Manga-Bildern direkt auf Android — ohne externen Proxy-Server.
- **Konkrete Schritte:**
  1. **Client-Trennung:** `MangaReaderApplication` stellt zwei getrennte Clients bereit:
     - `imageHttpClient` (gehärtet für Bilder: 3-Stufen-Referer-Fallback, einheitlicher User-Agent, WebView-Cookie-Merge, `Accept: image/webp,image/png,image/jpeg,image/*;q=0.8`, 429/5xx-Exponential-Backoff `400ms → 800ms → 1600ms`, 404-Fast-Fail, HTML/XML-Challenge- & Magic-Byte-Prüfung, `GuardedBitmapDecoder`).
     - `chapterHttpClient` (für HTML-Kapitelseiten & Pre-Fetching: HTML-`Accept`, Cookie-Sync, 4-MB-Limit, ohne Bild-Challenge-Blockade).
  2. **Bugfix `prefetchNextChapter()`:** Auf `chapterHttpClient` umstellen, sodass Hintergrund-Pre-Fetching zuverlässig funktioniert.
  3. **Dediziertes `ChapterLoaderRepository`:** Direktes natives HTML-Laden + `UrlChapterAnalyzer` mit sauberem Fallback auf `WebView` + `ImageExtractorJs` bei JS-/Cloudflare-geschützten Seiten.
  4. **Sicherheits-Härtung `UrlSafety.kt`:** Hex-IPv4-mapped IPv6, Trailing-Dot-Normalisierung, NAT64/6to4/Multicast, Zone-IDs und Standard-Port-Allowlist (`80`/`443`).
  5. **Tests:** `MockWebServer`-Tests für Referer-Fallback, Challenge-Erkennung, 429-Backoff, 404-Fast-Fail und HTML-Chapter-Loading.
- **Abnahme:** Alle Netzwerk-, Diagnostics- und `UrlSafety`-Tests grün; Bilder und Folgekapitel laden nativ über OkHttp + Coil ohne Express.

---

## Meilenstein 3: AMOLED-Reader-Screen (`#000000`), flüssiger Webtoon-/Seitenmodus, Zoom, Helligkeit/Nachtfilter & Keep-Screen-On

- **Ziel:** Modernisierter, flackerfreier, wartbarer Vollbild-AMOLED-Reader.
- **Konkrete Schritte:**
  1. **Modularisierung:** `ReaderScreen.kt` sauber in fokussierte Composables zerlegen (`ReaderHudBars.kt`, `WebtoonReaderList.kt`, `PagedReaderView.kt`, `AutoScrollControls.kt`, `ReaderFilterOverlays.kt`).
  2. **Echtes AMOLED-Vollbild (`#000000`):** Synchrones Ein-/Ausblenden der Android System-Bars (Status-/Navigationsleiste via `WindowInsetsControllerCompat`) passend zum HUD.
  3. **Gesten & Modi:** `ZoomableBox` (1x–4x Pinch-to-Zoom, Panning, konfliktfreies Scrollen im Webtoon-Modus und Blättern im RTL/LTR-`HorizontalPager` bei `1.0x`), flackerfreies Coil-Rendering.
  4. **Komfortfunktionen:** Software-Dimmer (bis 80 %) + optionaler Sepia-Nachtfilter, stufenloser Autoscroll mit Floating-Pill, zuverlässiges `keepScreenOn`, transparente `ImageErrorView` mit 1-Klick-Einzelbild-Cache-Bypass.
- **Abnahme:** Ruckelfreier Wechsel zwischen Webtoon und Manga (RTL/LTR), sauberes Zoomen/Pannen, funktionierender Dimmer/Nachtfilter und aktives `keepScreenOn` auf Gerät/Emulator.

---

## Meilenstein 4: Lokale Bibliothek (Room + DataStore), Lesefortschritt & Cache-Limit-Verwaltung

- **Ziel:** Moderne, asynchrone Persistenz ohne Main-Thread-Blockaden und volle Kontrolle über den Bild-Cache.
- **Konkrete Schritte:**
  1. **Dependencies ergänzen:** `androidx.room:room-runtime`, `room-ktx`, `room-compiler` (via KSP) sowie `androidx.datastore:datastore-preferences` in `gradle/libs.versions.toml`.
  2. **Room-Datenbank (`MangaDatabase`):** Entities & DAOs für `BookmarkEntity` (Lesezeichen & Verlauf), `ReadingProgressEntity` (exakte Seite, Gesamtseiten, Zeitstempel), `DownloadedChapterEntity` und `WebTabEntity` — reaktiv per `Flow`.
  3. **Jetpack DataStore (`SettingsDataStore`):** Typisierte Speicherung aller Reader-Einstellungen (Modus, Richtung, Dimmer, Nachtfilter, Autoscroll, Prefetch, KeepScreenOn) und des konfigurierten Cache-Limits (512 MB–32 GB, Standard 4 GB).
  4. **Nahtlose Einmal-Migration:** Beim ersten Start werden vorhandene JSON-Dateien (`library_bookmarks.json`, `reader_preferences.json`, `tabs_session.json`) & `SharedPreferences` automatisch in Room/DataStore übernommen — keine synchrone Disk-I/O mehr im `ViewModel`-Init.
  5. **Cache-Limit-Verwaltung:** `ChapterCacheManager` (keine 31-Tage-Auto-Löschung, 90-%-LRU-Hysterese, Live-Anzeige, Limit-Slider & manuelles Leeren) sauber an DataStore anbinden.
- **Abnahme:** App-Neustart stellt Bibliothek, Verlauf, exakte Seite und Cache-Limit reaktiv ohne Main-Thread-I/O wieder her; Room- & Cache-Tests grün.

---

## Nachgelagerte Abschlussphase: Konsolidierung (erst nach Abschluss aller Meilensteine)

Erst nach erfolgreicher Abnahme von Meilenstein 1–4 wird optional ein eigenständiges, sauberes Android-Zielrepository ohne Web-/Node-Altlasten zusammengeführt (inkl. lokalem Release-Keystore, `app/`, `gradle/`, `.github/workflows/build-apk.yml`, `GEMINI.md` und `docs/APK_ROADMAP.md`).
