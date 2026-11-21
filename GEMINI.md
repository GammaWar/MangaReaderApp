# GEMINI.md — MangaReaderApp Projekt-Leitfaden & Architektur

Dieser Leitfaden dient als verbindliche Referenz für die Weiterentwicklung der **MangaReaderApp** (auch über neue Chat-Sitzungen hinweg). Er dokumentiert Architektur, API-Integration, Härtungsmaßnahmen gegen CDN-/Cloudflare-Fehler, UI-Richtlinien sowie alle manuell schaltbaren Quality-of-Life-Funktionen.

---

## A. Projekt-Überblick & Modulstruktur

Die Anwendung ist als Full-Stack-Web-App (React 19 + TypeScript + Tailwind CSS v4 + Express-Proxy-Server) im AMOLED-Dunkeldesign aufgebaut und bildet alle Kernfunktionen des mobilen Manga-Readers ab:

- **Express-Backend (`server.ts`)**:
  - Stellt in der Entwicklung Vite-Middlewares und in Produktion `dist/` bereit (Port `3000`).
  - Kapselt externe Netzwerkabrufe sicher über `/api/extract`, `/api/image-proxy` und `/api/prefetch` (inkl. SSRF-Schutz, Cookie-/Referer-Fallback, HTML-Challenge-Erkennung und sicherem Bild-Cache).
- **Reine Logikmodule (`src/utils/`)** — vollständig von React entkoppelt und via `node:test` getestet:
  - `urlChapterAnalyzer.ts`: Kapitelnummer-Erkennung mit strikten Wortgrenzen (`(?<=[/_?&=-]|^)...`), UUID-/MangaDex-Schutz, Folgekapitel-Synthese, kollisionssichere Download-IDs (`<Titel>_<hash>`), HTML-Bildextraktion.
  - `imageDiagnostics.ts`: Klassifiziert HTTP 404, 403, 429, 500–504, Timeouts sowie HTML-Challenges / Null-Bitmap-Dekodierungsfehler inkl. Exponential-Backoff-Regeln (`400ms`, `800ms`, `1600ms`).
  - `urlSafety.ts`: Blockiert lokale/private IP-Bereiche, Link-Local-Metadaten (`169.254.169.254`), `localhost` und unsichere URL-Schemata (`javascript:`, `data:`).
  - `storage.ts`: Entprellte (`400ms` Debounce) und serialisierte (Mutex-Warteschlange) Persistenz in `localStorage` für Lesezeichen, Verlauf, Offline-Downloads, Browser-Tabs und Einstellungen.
- **UI-Komponenten (`src/components/`)**:
  - `AppShell.tsx`: Dreiteilige Top-Bar auf Desktop + ergonomische untere Daumen-Tab-Leiste auf Mobilgeräten (≤ 15 % Viewport-Höhe).
  - `LibraryView.tsx`: Haupt-Tab **Bibliothek** mit den Reitern *Lesezeichen*, *Verlauf* und *Offline-Downloads*.
  - `BrowserView.tsx`: Multi-Tab-Browser mit breiten Tabs (`148px–220px`), isoliertem `24px` Schließen-Button, strikter `key={tab.id}`-Zuordnung und zuverlässig entsperrender Bildextraktion.
  - `ReaderView.tsx`, `ReaderPageItem.tsx`, `ImageErrorView.tsx`: AMOLED-schwarzer Reader (`#000000`) für Webtoon (Vertikal) & Manga (Blätter-Modus), stufenlosem Zoom (`1.0x–4.0x`), nicht-blockierenden Tap-Zonen, Diagnosekarten und `ChapterEndFooter`.
  - `SettingsDrawer.tsx`: Zentrales Einstellungs-Sheet zum manuellen Ein-/Ausschalten aller 4 QoL-Funktionen.

---

## B. Strukturierte Anleitung zur API-Integration

### 1. Extraktions-Endpunkt (`POST /api/extract`)
- **Eingabe**: `{ "url": "https://...", "cookieHeader"?: "..." }`
- **Ablauf**:
  1. Validiert `url` mit `validateExternalUrl()` + DNS-Auflösung gegen private/Loopback-IPs.
  2. Folgt maximal 3 Redirects manuell und prüft jedes Ziel erneut.
  3. Begrenzt HTML-Antworten auf max. `4 MB` mit `15s` Connect- / `20s` Read-Timeout.
  4. Extrahiert Kapitel-Seitenbilder aus `src`, `data-src`, `data-original`, `data-lazy-src` und filtert Deko-Icons/Tracker heraus.
  5. Ermittelt über `analyzeChapterUrl()` optional `nextChapterUrl` und `prevChapterUrl` (mit UUID-Schutz für MangaDex & Co.).
- **Ausgabe**: `{ title, chapterUrl, coverUrl, pages: [{ index, url, proxiedUrl }], nextChapterUrl, prevChapterUrl }`

### 2. Gehärteter Bild-Proxy (`GET /api/image-proxy`)
- **Parameter**: `url` (erforderlich), `referer` (optional), `bypassCache=1` (optional bei Retry / Sitzungserneuerung), `simError` (nur für das eingebaute Diagnose-Labor).
- **3-Stufen-Referer- & Header-Kette** bei HTTP 403 oder HTML-Challenge:
  1. Stufe 1: Kapitel-Origin als `Referer` + dynamisches `Sec-Fetch-Site` (`same-origin` / `same-site`).
  2. Stufe 2: Bildhost-Origin als `Referer`.
  3. Stufe 3: Abruf ohne `Referer`.
- **Sichere Standard-Header**:
  - **Kein künstlicher `Origin`-Header** (vermeidet Ablehnungen strenger Bild-CDNs).
  - **`Accept: image/webp,image/png,image/jpeg,image/*;q=0.8`** (kein erzwungenes serverseitiges AVIF-Transcoding, verhindert Dekodierungsfehler).
- **HTML-Challenge- & Null-Bitmap-Schutz**:
  - Jede Antwort wird vor dem Weiterleiten bzw. Cachen geprüft: `Content-Type` muss mit `image/` beginnen UND ein Prefix-Check (`peekBody` auf erste 256 Bytes) stellt sicher, dass kein `<!doctype` oder `<html` enthalten ist.
  - Liefert ein CDN trotz HTTP 200 eine Cloudflare-/Bot-HTML-Seite, gibt der Proxy gezielt **HTTP 403** mit `X-Manga-Diagnostic: HTML_CHALLENGE` zurück.

### 3. Hintergrund-Pre-Fetching (`POST /api/prefetch`)
- Lädt das HTML des erkannten Folgekapitels im Hintergrund vor, sobald der Leser die letzten 2 Seiten erreicht (`currentPage >= totalPages - 2`) und der Schalter **Automatisches Pre-Fetching** aktiv ist.
- Hält die extrahierten Seiten-URLs im Arbeitsspeicher bereit und lädt die ersten Vorschaubilder vor, sodass **„Nächstes Kapitel“** ohne spürbare Wartezeit startet.

### 4. Multi-Turn Manga-Assistent (`POST /api/chat`)
- Serverseitiger SSE-Streaming-Endpunkt über `@google/genai` (`ai.chats.create` + `chat.sendMessageStream`) mit strikter Modell-Allowlist (`gemini-4-hydrogen` für Standard, `gemini-3.1-flash-lite` für schnelle Antworten) und serverseitig kuratierten Rollen-Systeminstruktionen (`Manga-Berater`, `Reader- & CDN-Hilfe`, `Kapitel-Analyst`).
- Persistiert den Gesprächsverlauf lokal über `serializedStorage` (`STORAGE_KEYS.chatHistory`) und übergibt optional aktuellen Kapitel- und Cache-Kontext.

---

## C. Härtung, Caching & Fehlerdiagnose (`ImageErrorView`)

### Gestaffelte Timeouts & Exponential Backoff
- **Timeouts**: `15s` Verbindungsaufbau / Header, `20s` Gesamt-Lesezeit — verhindert endlose Ladekreise bei blockierten CDN-Nodes.
- **Auto-Retry**: Bei **HTTP 429 (Rate-Limit)**, **HTTP 500–504** oder **Timeout** wiederholt der Client automatisch bis zu **3-mal** mit **400 ms → 800 ms → 1600 ms** Backoff und informiert per Snackbar.
- **Kein sinnloser Retry**: **HTTP 404 (Datei existiert nicht)** und **HTTP 403 / HTML-Challenge** werden sofort ohne Endlosschleife als Diagnosekarte angezeigt.

### Aggressives, aber streng sicheres Caching & Konfigurierbarer Cache-Manager (Standard: 4 GB, manuelles Löschen)
- Nur verifizierte `image/*`-Antworten (ohne HTML-Signatur) werden server- und clientseitig im Bild-Cache abgelegt.
- Bei **„Erneut versuchen“** oder **„Sitzung erneuern“** wird `bypassCache=1` gesendet, um fehlerhaft oder veraltet gespeicherte Einträge sofort zu überschreiben.
- **Konfigurierbarer `ChapterCacheManager` / `CacheManager`** (implementiert in `MangaReaderApplication.kt` für Android/Coil/OkHttp sowie in `src/utils/cacheManager.ts` + `server.ts` über `/api/cache/status` und `/api/cache/cleanup`):
  - **Keine automatische 31-Tage-Löschung**: Zwischengespeicherte Seiten bleiben dauerhaft erhalten, bis sie manuell gelöscht werden oder das konfigurierte Speicherlimit erreicht wird.
  - **Konfigurierbares Speicherlimit (Standard: 4 GB, Bereich 512 MB – 32 GB)**: Lässt sich im Einstellungs-Drawer (`SettingsDrawer.tsx`) per Preset (`1 GB`, `2 GB`, `4 GB`, `8 GB`, `16 GB`, `32 GB`), Direkteingabe oder Slider flexibel anpassen; bei Überschreitung werden älteste Einträge bereinigt.
  - **Visuelle Speicheranzeige im `LibraryView`-Header**: Kompakte Fortschrittsleiste mit Live-Belegung, Prozentwert, Schnellzugriff auf *„Limit ändern“* und manuellem *„Leeren“*-Button.
  - **Manuelle Bereinigung im Einstellungs-Drawer**: Explizite Aktionen *„Auf Limit bereinigen“* und *„Cache leeren“*.

### Transparente Diagnosekarte (`ImageErrorView`)
Jede fehlgeschlagene Seite zeigt eine klar gekennzeichnete Karte mit Farb- **und** Text-/Icon-Kodierung:
- **HTTP 404 (Dunkelrot)**: Datei existiert nicht auf dem Server.
- **HTTP 403 / HTML-Sicherheitsabfrage (Amber/Orange)**: Zugriff verweigert oder Bot-Prüfung — bietet **„Sitzung erneuern“** (1-Klick-Cache-Bypass + Neuabruf) und **„Browser“**.
- **HTTP 429 (Warmgelb)**: Temporäres Rate-Limit durch parallele Seitenabrufe.
- **HTTP 500–504 / Timeout (Schiefer/Amber)**: Server vorübergehend nicht erreichbar.
- **Aktionen auf jeder Karte**: *„Erneut versuchen“* und *„URL kopieren“* (kopiert die direkte Bild-URL in die Zwischenablage).

### Behobene Architektur-Fallstricke (Referenz-Checkliste)
1. **Tab-Zuordnung**: Browser-Tabs immer mit `key={tab.id}` rendern; Schließen eines mittleren Tabs darf niemals Nachbar-States vertauschen.
2. **Entsperrung der Extraktion**: `isExtracting` immer im `try / catch / finally` zurücksetzen.
3. **Weiterlesen-Zielseite**: `pendingTargetPage` beim Öffnen aus Bibliothek/Verlauf respektieren, nicht blind auf Seite 1 (`0`) überschreiben.
4. **Offline-Isolation**: Das Lesen von Offline-Kapiteln (`isOffline: true`) aktualisiert den Verlauf, aber niemals den aktiven Web-Tab.
5. **Flackerfreies Rendering**: Kein 200ms-Crossfade-Aufblitzen auf Manga-Seiten; AMOLED-schwarze Platzhalter (`#000000`) mit dezentem Spinner und reservierter Mindesthöhe.
6. **Nicht-blockierende Gesten**: Im Blätter-Modus (Paged) dürfen Tap-Zonen (22 % / 56 % / 22 %) weder Swipes noch Zoom blockieren; Einzeltipp reagiert sofort ohne 300ms-Verzögerung.

---

## D. UI-Gestaltung & AMOLED Design-Leitlinien

- **60-30-10 Farbsystem**:
  - **60 % Neutral-Canvas**: `#000000` (Reader-Fläche, echtes AMOLED-Schwarz), `#0B0A12` (App-Hintergrund).
  - **30 % Strukturelle Oberflächen**: `#141124` (Karten, Top-Bar, Drawer), `#1D1933` (aktive Felder), Hairline-Rahmen `#27223D`.
  - **10 % Akzentbudget**: `#BB86FC` / `#7C4DFF` (primäre Aktionen, aktiver Tab-Indikator), `#22D3EE` nur sparsam im App-Icon und fokussierten Fortschrittslinien.
- **Typografie**:
  - Display / Überschriften: `Plus Jakarta Sans` (600–700)
  - Fließtext / Listen: `DM Sans` (400–500)
  - Zähler, HTTP-Codes & Seitennummern: `JetBrains Mono` mit `tabular-nums`
- **Zero-Pill-Disziplin**: Statische Metadaten (Kapitelnummer, Seitenzahl, Zeitstempel) als ruhiger, ungerahmter Fließtext mit `·`-Trennern statt bunter Pill-Badges.
- **Multi-Tab-Ergonomie**: Browser-Tabs besitzen eine Mindestbreite von `148px` (max. `220px`); ~90 % der Tab-Fläche fokussiert den Tab, der Schließen-Button (`✕`) liegt isoliert rechts in einer `24×24px`-Kreisfläche.
- **Barrierefreiheit & Touch-Ziele**: Mindestens `44×44px` Trefferfläche für interaktive Controls, `focus-visible`-Ringe, Semantik über native `<button>`/`<nav>`/`<main>`-Elemente.

---

## E. Manuell schaltbare Quality-of-Life-Features (1–4)

Alle vier Funktionen lassen sich im Einstellungs-Drawer (`SettingsDrawer.tsx`) manuell ein- und ausschalten:

1. **Lesezeichen & Verlauf (`Bibliothek`)**:
   - Lesezeichen per Klick in der Reader-Leiste (speichert Cover, Titel, URL, genaue Seitenzahl; synchronisiert `lastReadPage` beim Weiterlesen).
   - Automatischer chronologischer Verlauf (per Schalter aktivierbar/deaktivierbar) + Einzellöschung & „Verlauf leeren“.
2. **Helligkeits- & Nachtfilter (`Software-Dimmer` & `Sepia / Blaulichtfilter`)**:
   - Software-Dimmer per Switch + Slider (`0 %–80 %` Abdunkelung).
   - Blaulichtfilter / Sepia per Switch + Stärkeregler (`10 %–80 %`).
3. **Autoscroll / Freihändiges Lesen (`Webtoon-Modus`)**:
   - Ein-/ausschaltbar über Einstellungen sowie direkt per Play/Pause in der Reader-Leiste.
   - Schwebende Floating-Pill-Steuerung unten: **Pause/Weiter**, **Geschwindigkeit (`0.5x`, `1.0x`, `1.5x`, `2.0x`, `3.0x`)**, **Beenden (`✕`)**.
   - Nutzt `navigator.wakeLock` (Screen Wake Lock / `keepScreenOn`), solange Autoscroll oder der Reader aktiv ist.
4. **Automatisches Pre-Fetching (`Hintergrund-Vorladen`)**:
   - Manuell per Schalter im Einstellungs-Sheet steuerbar.
   - Erkennt die letzten 2 Seiten eines Kapitels, lädt das Folgekapitel vor und signalisiert sofortige Verfügbarkeit im `ChapterEndFooter`.

---

## F. Leitlinien für neue Chats & Weiterentwicklung

1. **Erst testen, dann UI ändern**: Reine Logikänderungen in `src/utils/` stets durch `npx --yes tsx --test src/utils/*.test.ts` absichern.
2. **Keine externen Platzhalter-Bilder**: Lokale Assets unter `/src/assets/images/` verwenden und bei externen Manga-Bildern immer über `/api/image-proxy` gehen.
3. **Sicherheit wahren**: Keine Änderungen an `urlSafety.ts`, die private IPs oder `javascript:`-URLs freigeben; HTML aus fremden Quellen niemals per `dangerouslySetInnerHTML` rendern.
4. **GEMINI.md aktuell halten**: Neue Endpunkte, Reader-Modi oder Fehlerbehebungen kurz in Abschnitt B oder C ergänzen.
