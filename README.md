# MangaReaderApp

> **AMOLED Manga- & Webtoon-Reader** — Hauptprojekt ist die native **Android-App (`android-app/`)** in Kotlin & Jetpack Compose. Die eigenständige **Web-Version (`web-app/`)** liegt sauber getrennt im eigenen Unterordner.

---

## Projektstruktur

| Ordner | Zweck | Status |
| :--- | :--- | :--- |
| [`android-app/`](./android-app) | **Native Android-App (APK)** — Kotlin 2.0, Jetpack Compose, Material 3, AMOLED `#000000`, OkHttp + Coil, Room + DataStore, WorkManager | **Hauptprojekt (aktiv)** |
| [`web-app/`](./web-app) | **Web-Version** — React 19, TypeScript, Tailwind CSS v4, Express Proxy | Sauber getrennt (Archiv / optional) |
| [`docs/`](./docs) | Architektur-Dokumente (`APK_ROADMAP.md`, `AUDIT.md`, `REENGINEERING_PLAN.md`) | Dokumentation |

---

## Schnellstart — Android APK (`android-app/`)

### Voraussetzungen
- **JDK 17** (oder JDK 21 mit `jvmTarget = 17`)
- **Android SDK Platform 34** (`ANDROID_HOME` gesetzt oder `android-app/local.properties` mit `sdk.dir=...`)

### Tests & Debug-APK bauen

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest assembleDebug
```

Fertige Debug-APK: `android-app/app/build/outputs/apk/debug/app-debug.apk`

### Signierte Release-APK bauen (lokal)
Wenn `android-app/keystore/release.keystore` vorhanden ist und `KEYSTORE_PASSWORD`, `KEY_ALIAS` sowie `KEY_PASSWORD` über Umgebungsvariablen oder gitignored `android-app/local.properties` gesetzt sind:

```powershell
cd android-app
.\gradlew.bat assembleRelease
```

### GitHub Actions CI
- **Android:** [`.github/workflows/build-apk.yml`](./.github/workflows/build-apk.yml) baut und testet `android-app/` automatisch und stellt `app-debug.apk` als Download-Artifact bereit.
- **Web:** [`.github/workflows/web-ci.yml`](./.github/workflows/web-ci.yml) läuft ausschließlich bei Änderungen in `web-app/`.

---

## Web-Version (`web-app/`)

Die Web-Anwendung ist vollständig eigenständig in [`web-app/`](./web-app) gekapselt (Details siehe [`web-app/README.md`](./web-app/README.md)):

```bash
cd web-app
npm install
npm run dev
```

---

## Lizenz

Veröffentlicht unter der **Apache License 2.0**.
