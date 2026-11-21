# MangaReaderApp

> **AMOLED Manga- & Webtoon-Reader** — Full-Stack Web-App (React 19, TypeScript, Tailwind CSS v4, Express) mit gehärtetem CDN-/Bild-Proxy, konfigurierbarem Cache-Manager (bis 32 GB), manuell schaltbaren Quality-of-Life-Funktionen und Multi-Turn-Manga-Assistent.

<p align="left">
  <a href="#deutsch"><strong>🇩🇪 Deutsch</strong></a> ·
  <a href="#english"><strong>🇬🇧 English</strong></a>
</p>

![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-Strict-3178C6?logo=typescript&logoColor=white)
![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS-v4-06B6D4?logo=tailwindcss&logoColor=white)
![Express](https://img.shields.io/badge/Express-Backend-111111?logo=express&logoColor=white)
![License](https://img.shields.io/badge/License-Apache_2.0-7C4DFF)

---

<a id="deutsch"></a>

## 🇩🇪 Deutsch

### Überblick

**MangaReaderApp** ist ein moderner Manga- und Webtoon-Reader im echten AMOLED-Dunkeldesign (`#000000`), entwickelt für ablenkungsfreies Lesen auf Desktop und Mobilgeräten. Ein integrierter Express-Proxy schützt externe Seiten- und Bildabrufe vor SSRF, umgeht häufige CDN-/Referer-Probleme automatisch und erkennt Cloudflare-/HTML-Challenges zuverlässig, bevor fehlerhafte Daten im Cache landen.

### Funktionsumfang

- **AMOLED-Reader (`Webtoon` & `Manga`)**
  - Vertikaler Webtoon-Modus und klassischer Blätter-Modus (`Rechts → Links` sowie `Links → Rechts`) mit stufenlosem Zoom (`1.0x–4.0x`) und nicht-blockierenden Tap-Zonen.
  - Flackerfreies Nachladen auf tiefschwarzer Fläche (`#000000`) sowie `ChapterEndFooter` für direkten Kapitelwechsel.
- **Manuell schaltbare Quality-of-Life-Features (im Einstellungs-Drawer)**
  1. **Lesezeichen & Leseverlauf (`Bibliothek`)**: Lesezeichen mit Cover, Titel und exakter Seitenzahl sowie optionaler chronologischer Verlauf und Offline-Kapitel.
  2. **Helligkeits- & Nachtfilter**: Software-Dimmer (`0 %–80 %`) und Blaulicht-/Sepia-Filter (`10 %–80 %`) für augenschonendes Lesen bei Nacht.
  3. **Autoscroll / Freihändiges Lesen**: Schwebende Steuerung im Webtoon-Modus (`0.5x` bis `3.0x`), inkl. Screen Wake Lock (`navigator.wakeLock`).
  4. **Automatisches Pre-Fetching**: Lädt das erkannte Folgekapitel im Hintergrund vor, sobald die letzten 2 Seiten erreicht werden.
- **Konfigurierbarer Bild-Cache-Manager (Standard `4 GB`, Max. `32 GB`)**
  - Keine automatische 31-Tage-Löschung — zwischengespeicherte Seiten bleiben bis zum manuellen Löschen oder Erreichen des Limits erhalten.
  - Flexible Anpassung im Einstellungs-Drawer per **Slider**, **Direkteingabe in GB** (`0,5 GB`–`32 GB`) oder **Schnellwahl-Presets** (`1 GB` bis `32 GB`) mit sofortiger Speichervalidierung.
  - Live-Fortschrittsleiste im Header der **Bibliothek** mit Schnellzugriff auf *Limit ändern* und *Leeren*.
  - Enthält zusätzlich die Android-/Kotlin-Referenzimplementierung (`MangaReaderApplication.kt`) für Coil/OkHttp.
- **Ergonomischer Multi-Tab-Browser**
  - Breite, fingerfreundliche Tabs (`148px–220px`) mit isoliertem Schließen-Button (`✕`) gegen versehentliches Schließen und robuster Bildextraktion.
- **CDN-Härtung & Transparente Fehlerdiagnose (`ImageErrorView`)**
  - 3-Stufen-Referer-Fallback, Schutz gegen HTML-Challenges/Null-Bitmaps, gestaffelte Timeouts (`15s` / `20s`) und Exponential Backoff (`400ms → 800ms → 1600ms`) bei HTTP `429` / `500–504`.
  - Eindeutige Diagnosekarten für `404`, `403 / HTML-Challenge` (mit *Sitzung erneuern* & Cache-Bypass), `429` und `5xx` inkl. *Erneut versuchen* und *URL kopieren*.
- **Multi-Turn-Manga-Assistent (`Assistent`)**
  - Server-seitiger Streaming-Chat (SSE) mit persistentem Gesprächsverlauf, drei wählbaren Rollen (*Manga-Berater*, *Reader- & CDN-Hilfe*, *Kapitel-Analyst*) sowie *Standard*- und *Schnell*-Modus.

### Schnellstart

#### Voraussetzungen
- **Node.js** `>= 20`
- **npm** (oder kompatibler Paketmanager)

#### Installation & Start

```bash
# 1. Repository klonen
git clone <REPOSITORY-URL>
cd MangaReaderApp

# 2. Abhängigkeiten installieren
npm install

# 3. Umgebungsvariablen anlegen (optional für den Manga-Assistenten)
cp .env.example .env
# GEMINI_API_KEY in .env eintragen

# 4. Entwicklungsserver starten (Port 3000)
npm run dev
```

Die Anwendung ist anschließend unter `http://localhost:3000` erreichbar.

#### Weitere Skripte & Tests

```bash
# TypeScript-Prüfung
npm run lint

# Produktions-Build erstellen
npm run build

# Produktionsserver starten
npm start

# Unit-Tests für reine Logikmodule ausführen (node:test)
npx --yes tsx --test src/utils/*.test.ts
```

### Umgebungsvariablen

| Variable | Pflicht | Beschreibung |
| :--- | :---: | :--- |
| `GEMINI_API_KEY` | Optional\* | API-Schlüssel für den serverseitigen Multi-Turn-Assistenten (`/api/chat`). \*Reader, Browser, Bibliothek und Bild-Proxy funktionieren vollständig auch ohne API-Schlüssel. |
| `APP_URL` | Optional | Basis-URL der gehosteten Anwendung. |

### API-Endpunkte (`server.ts`)

| Methode | Pfad | Zweck |
| :--- | :--- | :--- |
| `POST` | `/api/extract` | Lädt Kapitel-HTML (SSRF-geprüft, max. 3 Redirects, max. 4 MB), extrahiert Seitenbilder und erkennt Folge-/Vorkapitel. |
| `GET` | `/api/image-proxy` | Gehärteter Bild-Proxy mit 3-Stufen-Referer-Fallback, HTML-Challenge-Erkennung (`X-Manga-Diagnostic`) und Cache-Bypass. |
| `POST` | `/api/prefetch` | Lädt das HTML des Folgekapitels für verzögerungsfreie Übergänge im Hintergrund vor. |
| `GET` | `/api/cache/status` | Liefert Belegung, Dateianzahl, Kapitelanzahl und Auslastung des serverseitigen Bild-Caches. |
| `POST` | `/api/cache/cleanup` | Wendet ein neues Speicherlimit an oder leert den Cache manuell (`clearAll: true`). |
| `POST` | `/api/chat` | Serverseitiger SSE-Streaming-Endpunkt für den Multi-Turn-Manga-Assistenten. |

### Projektstruktur

```text
.
├── server.ts                      # Express-Backend, Proxy-Endpunkte, Cache-API & SSE-Chat
├── MangaReaderApplication.kt      # Android-/Kotlin-Referenz (Coil/OkHttp Cache-Manager)
├── GEMINI.md                      # Architektur-Leitfaden, CDN-Härtung & UI-Richtlinien
├── .env.example                   # Vorlage für Umgebungsvariablen
└── src/
    ├── App.tsx                    # Zentrale App-Logik, sofortige Cache-Validierung & State
    ├── components/
    │   ├── AppShell.tsx           # Desktop Top-Bar & mobile 4-Tab-Navigation
    │   ├── LibraryView.tsx        # Lesezeichen, Verlauf, Offline-Kapitel & Cache-Fortschrittsbalken
    │   ├── BrowserView.tsx        # Multi-Tab-Browser & Kapitel-Extraktion
    │   ├── ReaderView.tsx         # AMOLED-Reader (Webtoon/Paged, Zoom, Autoscroll, Nachtfilter)
    │   ├── ReaderPageItem.tsx     # Seiten-Renderer mit Retry & Backoff
    │   ├── ImageErrorView.tsx     # Fehlerdiagnosekarten (404/403/429/5xx)
    │   ├── ChatView.tsx           # Multi-Turn-Assistent mit Rollen- & Moduswahl
    │   ├── SettingsDrawer.tsx     # QoL-Schalter & konfigurierbares Cache-Limit (512 MB–32 GB)
    │   └── ToastStack.tsx         # Benachrichtigungen / Snackbars
    ├── utils/
    │   ├── cacheManager.ts        # Client- & Server-Cache-Manager + Formatierung (+ Tests)
    │   ├── chatHelpers.ts         # Modell-Allowlist, Rollen-Prompts & History-Sanitizing (+ Tests)
    │   ├── imageDiagnostics.ts    # HTTP-/HTML-Challenge-Klassifizierung & Backoff (+ Tests)
    │   ├── urlChapterAnalyzer.ts  # Kapitelnummern-Analyse, UUID-Schutz & HTML-Extraktion (+ Tests)
    │   ├── urlSafety.ts           # SSRF-Schutz & URL-Validierung (+ Tests)
    │   └── storage.ts             # Entprellte & serialisierte localStorage-Persistenz
    ├── data/                      # Beispielkapitel & Diagnose-Labor
    └── types/                     # TypeScript-Domänentypen
```

> Weiterführende Architekturdetails, CDN-Härtungsregeln und UI-Richtlinien sind in [`GEMINI.md`](./GEMINI.md) dokumentiert.

---

<a id="english"></a>

## 🇬🇧 English

### Overview

**MangaReaderApp** is a true-black AMOLED (`#000000`) manga and webtoon reader built as a full-stack React 19, TypeScript, Tailwind CSS v4, and Express application. Its hardened Express backend protects external fetches against SSRF, resolves common CDN/Referer blocks automatically, detects disguised Cloudflare/HTML challenge pages before caching, and powers a built-in multi-turn assistant.

### Key Features

- **AMOLED Reader (`Webtoon` & `Manga` Modes)**
  - Vertical Webtoon scroll mode and classic Paged mode (`RTL` & `LTR`) with smooth zoom (`1.0x–4.0x`) and non-blocking tap zones.
  - Flicker-free pure-black page placeholders (`#000000`) and a `ChapterEndFooter` for seamless chapter transitions.
- **4 Manually Toggleable Quality-of-Life Features (via Settings Drawer)**
  1. **Bookmarks & Reading History (`Library`)**: One-click bookmarks storing cover, title, and exact page number, alongside optional chronological reading history and offline chapters.
  2. **Brightness & Night Filter**: Software screen dimmer (`0%–80%`) and warm blue-light / sepia filter (`10%–80%`) for comfortable night reading.
  3. **Autoscroll / Hands-Free Reading**: Floating pill controller in Webtoon mode (`0.5x`–`3.0x`) with Screen Wake Lock (`navigator.wakeLock`).
  4. **Automatic Pre-Fetching**: Preloads the next chapter in the background once you reach the last 2 pages.
- **Configurable Image Cache Manager (Default `4 GB`, Max `32 GB`)**
  - No automatic 31-day expiration — cached pages stay available until manually cleared or the configured storage threshold is reached.
  - Flexible adjustment in the Settings Drawer via **slider**, **direct GB numeric input** (`0.5 GB`–`32 GB`), or **quick presets** (`1 GB`–`32 GB`) with immediate storage validation and oldest-entry eviction.
  - Live storage progress bar in the **Library** header with quick *Change Limit* and *Clear* actions.
  - Includes the companion Android/Kotlin reference implementation (`MangaReaderApplication.kt`) for Coil/OkHttp.
- **Ergonomic Multi-Tab Browser**
  - Wide touch-friendly tabs (`148px–220px`) with an isolated close button (`✕`) to prevent accidental tab closures, plus reliable page extraction.
- **CDN Hardening & Transparent Error Diagnostics (`ImageErrorView`)**
  - 3-tier Referer fallback chain, HTML-challenge & null-bitmap protection, tiered timeouts (`15s` / `20s`), and exponential backoff (`400ms → 800ms → 1600ms`) for HTTP `429` / `500–504`.
  - Clear diagnostic cards for `404`, `403 / HTML Challenge` (with 1-click *Renew Session* cache bypass), `429`, and `5xx`, including *Retry* and *Copy URL*.
- **Multi-Turn Manga Assistant (`Assistent`)**
  - Server-side SSE streaming chat with persistent conversation history, three selectable personas (*Manga Guide*, *Reader & CDN Help*, *Chapter Analyst*), and *Standard* / *Fast* modes.

### Quick Start

#### Prerequisites
- **Node.js** `>= 20`
- **npm** (or a compatible package manager)

#### Setup & Run

```bash
# 1. Clone the repository
git clone <REPOSITORY-URL>
cd MangaReaderApp

# 2. Install dependencies
npm install

# 3. Configure environment variables (optional, for the Manga Assistant)
cp .env.example .env
# Add your GEMINI_API_KEY in .env

# 4. Start the development server (port 3000)
npm run dev
```

Open `http://localhost:3000` in your browser.

#### Scripts & Unit Tests

```bash
# TypeScript type-check
npm run lint

# Build for production
npm run build

# Start production server
npm start

# Run pure-logic unit tests (node:test)
npx --yes tsx --test src/utils/*.test.ts
```

### Environment Variables

| Variable | Required | Description |
| :--- | :---: | :--- |
| `GEMINI_API_KEY` | Optional\* | Server-side API key used by `/api/chat`. \*The reader, browser, library, cache manager, and image proxy work fully without an API key. |
| `APP_URL` | Optional | Public base URL of the hosted application. |

### API Endpoints (`server.ts`)

| Method | Endpoint | Purpose |
| :--- | :--- | :--- |
| `POST` | `/api/extract` | Fetches chapter HTML (SSRF-validated, max 3 redirects, max 4 MB), extracts page image URLs, and detects next/previous chapters. |
| `GET` | `/api/image-proxy` | Hardened image proxy with 3-tier Referer fallback, HTML-challenge detection (`X-Manga-Diagnostic`), and cache bypass. |
| `POST` | `/api/prefetch` | Preloads the upcoming chapter in the background for instant transitions. |
| `GET` | `/api/cache/status` | Returns server-side cache byte usage, file count, chapter count, and utilization percentage. |
| `POST` | `/api/cache/cleanup` | Enforces a configured size limit or manually clears the cache (`clearAll: true`). |
| `POST` | `/api/chat` | Server-side SSE streaming endpoint for the multi-turn Manga Assistant. |

### Security & Reliability Highlights

- **SSRF Protection (`src/utils/urlSafety.ts`)**: Blocks `localhost`, loopback, private RFC 1918/ULA ranges, link-local metadata endpoints (`169.254.169.254`), and unsafe schemes (`javascript:`, `data:`).
- **Safe Caching**: Only verified `image/*` payloads without HTML signatures enter the cache; error/challenge pages are rejected with `HTTP 403`.
- **Server-Side Secrets**: API keys stay strictly on the Express backend and are never exposed to the browser bundle.

### License

Licensed under the **Apache License 2.0**.
