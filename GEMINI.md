# GEMINI.md — MangaReaderApp (Verbindlicher Projekt-Leitfaden)

> **PROJEKTREGELN (OBERSTE PRIORITÄT FÜR ALLE KI-AGENTEN):**
> 1. **Hauptprojekt ist die native Android-App (`.apk`)** in **Kotlin + Jetpack Compose + Material 3** im Ordner **`android-app/`**.
> 2. **Saubere Trennung zur Web-Version (`web-app/`):** Die vollständige React-/Express-Web-Version liegt sauber isoliert in **`web-app/`** und ist **standardmäßig schreibgeschützt (Read-Only)**. Sie darf **nur** bearbeitet werden, wenn der Nutzer dies explizit für `web-app/` anfordert — bei allen regulären Aufgaben wird ausschließlich in `android-app/` gearbeitet.
> 3. **Kein Express-/Node-Backend für Android:** Alle Netzwerk-, Referer-Fallback-, Extraktions-, Cache- und Persistenzfunktionen der Android-App laufen **100 % nativ auf Android** (OkHttp, Coil, WebView, Room, DataStore, WorkManager).
> 4. **Modernes geht vor:** Bevorzuge moderne Android-Standards (Kotlin 2.0, Compose Material 3, Coroutines/StateFlow, Room + KSP, Jetpack DataStore, Edge-to-Edge AMOLED `#000000`).
> 5. **Aktive Arbeitsbereiche (Standard):** `android-app/`, `docs/APK_ROADMAP.md`, `.github/workflows/build-apk.yml` und diese `GEMINI.md`.

---

## 1. Projektstruktur

```text
MangaReaderApp/
├── android-app/                      # HAUPTPROJEKT — Native Android-App (Kotlin + Compose, Gradle 8.9)
│   ├── build.gradle.kts              # Root-Gradle-Konfiguration (AGP 8.5.2, Kotlin 2.0.20, KSP)
│   ├── settings.gradle.kts           # Modul-Einbindung (:app)
│   ├── gradle.properties             # JVM-, AndroidX- & Kotlin-Build-Flags
│   ├── gradle/                       # Version Catalog (libs.versions.toml) & Wrapper 8.9
│   ├── gradlew / gradlew.bat         # Gradle Wrapper Skripte
│   ├── keystore/                     # Lokaler Release-Keystore (gitignored!)
│   ├── local.properties              # Lokaler SDK- & Signing-Pfad (gitignored!)
│   ├── assets/                       # Original App-Icon Artwork
│   ├── tools/                        # Icon-Generator-Skripte
│   └── app/
│       ├── build.gradle.kts          # compileSdk 34, minSdk 26, targetSdk 34, JVM 17
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml
│           │   ├── java/com/mangareader/app/
│           │   │   ├── MangaReaderApplication.kt  # Getrennte OkHttp-Clients, Coil, GuardedBitmapDecoder
│           │   │   ├── MainActivity.kt            # Edge-to-Edge AMOLED Compose-Einstieg & Navigation
│           │   │   ├── data/                      # cache/, chat/, download/, extractor/, library/, local/ (Room), model/, network/, session/, settings/ (DataStore)
│           │   │   ├── ui/                        # theme/, components/, screens/ (inkl. reader/), viewmodel/
│           │   │   └── util/                      # AppConstants.kt, UrlSafety.kt
│           │   └── res/                           # AMOLED Icons, Adaptive Icons, Fonts, Themes (#000000)
│           └── test/java/com/mangareader/app/     # JUnit-, MockWebServer- & Robolectric-Tests
├── web-app/                          # GETRENNTE WEB-VERSION (React 19 + Express, Read-Only / Archiv)
│   ├── src/, server/, server.ts, package.json, vite.config.ts, tsconfig.json, README.md
├── docs/                             # Projekt-Dokumentation (APK_ROADMAP.md, AUDIT.md, REENGINEERING_PLAN.md)
├── .github/workflows/
│   ├── build-apk.yml                 # Automatischer Android-Test- & APK-Build (android-app/**)
│   └── web-ci.yml                    # Isolierte Web-CI (läuft nur bei web-app/**)
├── GEMINI.md                         # Dieser Leitfaden
└── README.md                         # Projektübersicht
```

---

## 2. Architektur- & Design-Leitlinien (Android)

- **UI-Framework:** Jetpack Compose + Material 3 im **AMOLED-Dunkeldesign (`#000000`)**:
  - Primärer Hintergrund & Reader-Canvas: `#000000` (echtes AMOLED-Schwarz)
  - Strukturelle Oberflächen: `#120F20` / `#141124` / `#1D1933`, Hairline-Rahmen `#221E36`–`#2B2545`
  - Akzente: `#7C4DFF` / `#BB86FC` (primär), `#22D3EE` (sparsam)
- **Netzwerk & Bild-Härtung (100 % nativ):**
  - Strikt getrennte OkHttp-Clients in `MangaNetworkClients.kt`: `imageHttpClient` (3-Stufen-Referer-Fallback, einheitlicher User-Agent, WebView-Cookie-Merge, `Accept: image/webp,image/png,image/jpeg,image/*;q=0.8`, 429/5xx-Exponential-Backoff, 404-Fast-Fail, HTML/XML-Challenge- & Magic-Byte-Prüfung, `GuardedBitmapDecoder`) und `chapterHttpClient` (HTML-Kapitelseiten & Pre-Fetching).
  - Gehärtete SSRF-/URL-Prüfung in `UrlSafety.kt`.
- **Persistenz & Cache:**
  - **Room** (`data/local/`) für Bibliothek, Verlauf, exakten Lesefortschritt, Downloads & Tabs + **Jetpack DataStore** (`data/settings/`) für Reader-Einstellungen und Cache-Limit — ohne synchrone Main-Thread-I/O.
  - `ChapterCacheManager`: Standardlimit 4 GB (512 MB–32 GB), **keine** 31-Tage-Auto-Löschung, 90-%-LRU-Hysterese, manuelles Leeren.

---

## 3. Pflichtprüfungen & Build-Befehle (Android)

Nach jeder Änderung an `android-app/` müssen folgende Befehle erfolgreich durchlaufen:

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest assembleDebug
```

Unter Linux/macOS:

```bash
cd android-app
./gradlew testDebugUnitTest assembleDebug
```

- **Ausgabe-APK (Debug):** `android-app/app/build/outputs/apk/debug/app-debug.apk`
- **Ausgabe-APK (Release, lokal signiert mit `keystore/release.keystore`):** `.\gradlew.bat assembleRelease` → `android-app/app/build/outputs/apk/release/app-release.apk`
- **Voraussetzung lokal:** `ANDROID_HOME` gesetzt oder gitignored `android-app/local.properties` vorhanden; JDK 17 (oder kompatibles JDK 21 mit `jvmTarget = 17`), Android SDK Platform 34.
- **CI:** `.github/workflows/build-apk.yml` führt bei Änderungen in `android-app/**` automatisch `testDebugUnitTest` und `assembleDebug` aus und lädt `app-debug.apk` als Artifact hoch.
- **Kein ungewolltes Web-Tooling:** Keine `npm`-Befehle für `web-app/` ausführen, sofern der Nutzer nicht ausdrücklich Änderungen an der Web-Version anfordert.

---

## 4. Roadmap & Dokumentation

- Android-Roadmap: [`docs/APK_ROADMAP.md`](docs/APK_ROADMAP.md)
- Android-Details: [`android-app/README.md`](android-app/README.md)
- Web-Details (getrennt): [`web-app/README.md`](web-app/README.md)
