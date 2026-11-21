import { useEffect, useState } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  ExternalLink,
  Globe,
  ImageDown,
  Loader2,
  Plus,
  RotateCw,
  ShieldCheck,
  X,
} from 'lucide-react';
import type { BrowserTab, MangaChapter } from '../types/manga';
import { CATALOG_CHAPTERS, findCatalogChapterByUrl } from '../data/catalog';
import { analyzeChapterUrl } from '../utils/urlChapterAnalyzer';

interface BrowserViewProps {
  tabs: BrowserTab[];
  activeTabId: string;
  isExtracting: boolean;
  onSelectTab: (tabId: string) => void;
  onCreateTab: (initialUrl?: string, initialTitle?: string) => void;
  onCloseTab: (tabId: string) => void;
  onNavigateTab: (tabId: string, url: string, title?: string) => void;
  onGoBackTab: (tabId: string) => void;
  onGoForwardTab: (tabId: string) => void;
  onExtractImages: (url: string) => Promise<void>;
  onOpenCatalogChapter: (chapter: MangaChapter, targetPage?: number) => void;
}

export function BrowserView({
  tabs,
  activeTabId,
  isExtracting,
  onSelectTab,
  onCreateTab,
  onCloseTab,
  onNavigateTab,
  onGoBackTab,
  onGoForwardTab,
  onExtractImages,
  onOpenCatalogChapter,
}: BrowserViewProps) {
  const activeTab = tabs.find((t) => t.id === activeTabId) || tabs[0];
  const [urlInput, setUrlInput] = useState(activeTab?.url || '');
  const [reloadNonce, setReloadNonce] = useState(0);

  useEffect(() => {
    if (activeTab) {
      setUrlInput(activeTab.url);
    }
  }, [activeTab?.id, activeTab?.url]);

  if (!activeTab) return null;

  const matchedCatalog = findCatalogChapterByUrl(activeTab.url);
  const analysis = analyzeChapterUrl(activeTab.url);
  const canGoBack = activeTab.historyIndex > 0;
  const canGoForward = activeTab.historyIndex < activeTab.history.length - 1;

  const handleSubmitUrl = (e: React.FormEvent) => {
    e.preventDefault();
    const cleaned = urlInput.trim();
    if (!cleaned) return;
    const normalized =
      cleaned.startsWith('http://') || cleaned.startsWith('https://')
        ? cleaned
        : `https://${cleaned}`;
    const found = findCatalogChapterByUrl(normalized);
    onNavigateTab(
      activeTab.id,
      normalized,
      found ? `${found.seriesTitle.split(':')[0]} — Kap. ${found.chapterNumber ?? ''}` : normalized
    );
  };

  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col px-4 py-4 sm:px-6 lg:px-8">
      {/* Multi-Tab Bar: generous 148px–220px tab width, 90% focus target, isolated 24px circular Close button */}
      <div className="flex items-center gap-2 overflow-x-auto pb-2" role="tablist" aria-label="Browser-Tabs">
        {tabs.map((tab) => {
          const isActive = tab.id === activeTab.id;
          return (
            <div
              key={tab.id}
              data-testid={`browser-tab-${tab.id}`}
              className={`group flex h-11 min-w-[152px] max-w-[220px] shrink-0 items-center rounded-xl border transition-colors ${
                isActive
                  ? 'border-[#BB86FC] bg-[#1C1635] text-white'
                  : 'border-[#25203B] bg-[#120F20] text-[#A29BBE] hover:border-[#3A325C] hover:text-white'
              }`}
            >
              {/* ~90% click area strictly switches focus to this tab */}
              <button
                type="button"
                role="tab"
                aria-selected={isActive}
                onClick={() => onSelectTab(tab.id)}
                className="flex h-full min-w-0 flex-1 items-center gap-2 pl-3 pr-1 text-left text-xs font-medium focus-visible:outline-2 focus-visible:outline-[#BB86FC] rounded-l-xl"
              >
                <Globe
                  className={`h-3.5 w-3.5 shrink-0 ${
                    isActive ? 'text-[#BB86FC]' : 'text-[#777096]'
                  }`}
                  aria-hidden="true"
                />
                <span className="truncate">{tab.title}</span>
              </button>

              {/* Isolated 24px circular close button on far right prevents accidental closing */}
              <div className="flex shrink-0 items-center pr-2 pl-1">
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    onCloseTab(tab.id);
                  }}
                  aria-label={`Tab '${tab.title}' schließen`}
                  className="flex h-6 w-6 items-center justify-center rounded-full bg-white/5 text-[#9C95BA] transition-colors hover:bg-rose-500/30 hover:text-white focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
                >
                  <X className="h-3.5 w-3.5" aria-hidden="true" />
                </button>
              </div>
            </div>
          );
        })}

        <button
          type="button"
          onClick={() => onCreateTab()}
          aria-label="Neuen Browser-Tab öffnen"
          className="inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border border-[#25203B] bg-[#120F20] text-[#C8C2E0] transition-colors hover:border-[#493E78] hover:bg-[#1C1635] hover:text-white focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
        >
          <Plus className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>

      {/* Browser Toolbar: Back / Forward / Reload + URL Form + Primary Extract CTA */}
      <div className="mt-2 flex flex-col gap-2.5 rounded-2xl border border-[#25203C] bg-[#131022] p-3 lg:flex-row lg:items-center">
        <div className="flex items-center gap-1.5">
          <button
            type="button"
            disabled={!canGoBack}
            onClick={() => onGoBackTab(activeTab.id)}
            aria-label="Im Tab zurück"
            className="flex h-11 w-11 items-center justify-center rounded-xl border border-[#26213E] bg-[#0D0B18] text-[#D2CCE8] transition-colors hover:bg-[#1B1633] disabled:opacity-40"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          </button>
          <button
            type="button"
            disabled={!canGoForward}
            onClick={() => onGoForwardTab(activeTab.id)}
            aria-label="Im Tab vorwärts"
            className="flex h-11 w-11 items-center justify-center rounded-xl border border-[#26213E] bg-[#0D0B18] text-[#D2CCE8] transition-colors hover:bg-[#1B1633] disabled:opacity-40"
          >
            <ArrowRight className="h-4 w-4" aria-hidden="true" />
          </button>
          <button
            type="button"
            onClick={() => setReloadNonce((n) => n + 1)}
            aria-label="Seite neu laden"
            className="flex h-11 w-11 items-center justify-center rounded-xl border border-[#26213E] bg-[#0D0B18] text-[#D2CCE8] transition-colors hover:bg-[#1B1633]"
          >
            <RotateCw className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>

        <form onSubmit={handleSubmitUrl} className="flex min-w-0 flex-1 items-center gap-2">
          <label htmlFor="browser-url-input" className="sr-only">
            Kapitel-URL eingeben
          </label>
          <input
            id="browser-url-input"
            type="url"
            value={urlInput}
            onChange={(e) => setUrlInput(e.target.value)}
            placeholder="https://... Manga-Kapitel-URL eingeben"
            className="h-11 min-w-0 flex-1 rounded-xl border border-[#2A2444] bg-[#0B0914] px-3.5 font-mono-tabular text-xs text-white placeholder:text-[#6E678C] focus:border-[#BB86FC] focus:outline-none sm:text-sm"
          />
          <button
            type="submit"
            className="inline-flex h-11 items-center justify-center rounded-xl border border-[#342C57] bg-[#1B1633] px-4 text-sm font-medium text-[#ECE9F8] whitespace-nowrap shrink-0 transition-colors hover:bg-[#261F47]"
          >
            Laden
          </button>
        </form>

        <button
          type="button"
          disabled={isExtracting}
          onClick={() => void onExtractImages(activeTab.url)}
          className="inline-flex h-11 items-center justify-center gap-2 rounded-xl bg-[#7C4DFF] px-5 text-sm font-semibold text-white whitespace-nowrap shrink-0 transition-colors hover:bg-[#6E3FF2] disabled:opacity-60 focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
        >
          {isExtracting ? (
            <>
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
              Extrahiere…
            </>
          ) : (
            <>
              <ImageDown className="h-4 w-4" aria-hidden="true" />
              Bilder extrahieren
            </>
          )}
        </button>
      </div>

      {/* Per-tab Isolated Content Area (strictly keyed by tab.id!) */}
      <div
        key={`${activeTab.id}-${reloadNonce}`}
        className="mt-4 grid grid-cols-1 gap-6 lg:grid-cols-12"
      >
        {/* Main Active Tab Viewport */}
        <section className="rounded-2xl border border-[#25203C] bg-[#110E1E] p-5 lg:col-span-8">
          {matchedCatalog ? (
            <div className="space-y-5">
              <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
                <img
                  src={matchedCatalog.coverUrl}
                  alt={matchedCatalog.seriesTitle}
                  referrerPolicy="no-referrer"
                  className="h-44 w-32 shrink-0 rounded-xl border border-[#2D264A] object-cover"
                />
                <div className="min-w-0 flex-1 space-y-2">
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-[#9E97BC]">
                    <span>{matchedCatalog.sourceName}</span>
                    <span aria-hidden="true">·</span>
                    <span className="font-mono-tabular">
                      {matchedCatalog.pages.length} Seiten erkannt
                    </span>
                    {matchedCatalog.nextChapterUrl && (
                      <>
                        <span aria-hidden="true">·</span>
                        <span>Folgekapitel verfügbar</span>
                      </>
                    )}
                  </div>
                  <h2 className="font-display text-xl font-bold text-white">
                    {matchedCatalog.seriesTitle}
                  </h2>
                  <p className="text-sm text-[#CFCBE2]">{matchedCatalog.chapterTitle}</p>
                  <p className="truncate font-mono-tabular text-xs text-[#7D769C]">
                    {matchedCatalog.chapterUrl}
                  </p>

                  <div className="flex flex-wrap items-center gap-2.5 pt-2">
                    <button
                      type="button"
                      onClick={() => void onExtractImages(matchedCatalog.chapterUrl)}
                      className="inline-flex min-h-[42px] items-center gap-2 rounded-xl bg-[#7C4DFF] px-4 py-2 text-sm font-medium text-white whitespace-nowrap transition-colors hover:bg-[#6E3FF2]"
                    >
                      <ImageDown className="h-4 w-4" aria-hidden="true" />
                      Im Reader öffnen
                    </button>
                    {matchedCatalog.nextChapterUrl && (
                      <button
                        type="button"
                        onClick={() => {
                          const next = findCatalogChapterByUrl(matchedCatalog.nextChapterUrl!);
                          if (next) {
                            onNavigateTab(
                              activeTab.id,
                              next.chapterUrl,
                              `${next.seriesTitle.split(':')[0]} — Kap. ${next.chapterNumber ?? ''}`
                            );
                          }
                        }}
                        className="inline-flex min-h-[42px] items-center gap-1.5 rounded-xl border border-[#322B54] bg-[#19142E] px-4 py-2 text-sm font-medium text-[#E4DEFA] whitespace-nowrap transition-colors hover:bg-[#231D3E]"
                      >
                        Nächstes Kapitel im Tab
                      </button>
                    )}
                  </div>
                </div>
              </div>

              {/* Thumbnail strip of pages in this browser tab */}
              <div className="border-t border-[#221D38] pt-4">
                <h3 className="text-xs font-semibold text-[#A59EC2]">
                  Seiten-Vorschau im Dokument
                </h3>
                <div className="mt-3 grid grid-cols-3 gap-3 sm:grid-cols-6">
                  {matchedCatalog.pages.map((p) => (
                    <button
                      key={p.index}
                      type="button"
                      onClick={() => onOpenCatalogChapter(matchedCatalog, p.index)}
                      className="group overflow-hidden rounded-xl border border-[#272140] bg-black text-left transition-colors hover:border-[#BB86FC]"
                    >
                      <div className="aspect-[3/4] w-full overflow-hidden bg-[#0A0812]">
                        <img
                          src={p.fallbackAssetUrl || p.url}
                          alt={p.label || `Seite ${p.index + 1}`}
                          referrerPolicy="no-referrer"
                          className="h-full w-full object-cover opacity-90 transition-transform duration-150 group-hover:scale-105"
                        />
                      </div>
                      <div className="px-2 py-1.5 font-mono-tabular text-[11px] text-[#B8B2D4]">
                        Seite {p.index + 1}
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <div className="space-y-4">
              <div className="flex items-start gap-3">
                <ShieldCheck className="mt-0.5 h-5 w-5 shrink-0 text-[#BB86FC]" aria-hidden="true" />
                <div className="space-y-1">
                  <h2 className="font-display text-lg font-semibold text-white">
                    Externe Kapitel-URL bereit zur Extraktion
                  </h2>
                  <p className="text-sm leading-relaxed text-[#ACA6C9]">
                    Klicke auf <strong className="text-white">„Bilder extrahieren“</strong>, um die
                    Seitenbilder sicher über den gehärteten Proxy (Referer-Fallback,
                    HTML-Challenge-Schutz &amp; 15s/20s-Timeouts) in den AMOLED-Reader zu laden.
                  </p>
                </div>
              </div>

              <div className="rounded-xl border border-[#231E3A] bg-[#0C0A16] p-4 font-mono-tabular text-xs text-[#B5AFD2] space-y-1.5">
                <div className="truncate">Ziel-URL: {activeTab.url}</div>
                <div>
                  Kapitel-Analyse:{' '}
                  {analysis.isUuidPath
                    ? 'UUID-Pfad erkannt (kein blindes Inkrementieren — MangaDex-Schutz aktiv)'
                    : analysis.chapterNumber !== null
                    ? `Kapitel ${analysis.chapterNumber} erkannt · Nächstes Kapitel: ${analysis.nextChapterUrl}`
                    : 'Keine numerische Kapitel-Struktur erkannt'}
                </div>
              </div>

              <div className="flex flex-wrap items-center gap-3 pt-1">
                <button
                  type="button"
                  disabled={isExtracting}
                  onClick={() => void onExtractImages(activeTab.url)}
                  className="inline-flex min-h-[44px] items-center gap-2 rounded-xl bg-[#7C4DFF] px-5 py-2.5 text-sm font-semibold text-white whitespace-nowrap transition-colors hover:bg-[#6E3FF2] disabled:opacity-60"
                >
                  <ImageDown className="h-4 w-4" aria-hidden="true" />
                  Jetzt Bilder extrahieren
                </button>
              </div>
            </div>
          )}
        </section>

        {/* Quick Catalog / Bookmark Sources Sidebar */}
        <aside className="rounded-2xl border border-[#25203C] bg-[#110E1E] p-5 lg:col-span-4">
          <h3 className="font-display text-sm font-semibold text-white">
            Schnellzugriff &amp; Testquellen
          </h3>
          <p className="mt-1 text-xs text-[#9C95BA]">
            Lade ein Kapitel direkt in den aktiven Tab oder öffne es in einem neuen Tab.
          </p>

          <div className="mt-4 divide-y divide-[#221D38]">
            {CATALOG_CHAPTERS.map((ch) => (
              <div key={ch.id} className="flex items-center justify-between gap-3 py-3 first:pt-0 last:pb-0">
                <button
                  type="button"
                  onClick={() =>
                    onNavigateTab(
                      activeTab.id,
                      ch.chapterUrl,
                      `${ch.seriesTitle.split(':')[0]} — Kap. ${ch.chapterNumber ?? ''}`
                    )
                  }
                  className="min-w-0 flex-1 text-left focus-visible:outline-2 focus-visible:outline-[#BB86FC] rounded-lg"
                >
                  <div className="truncate text-sm font-medium text-[#EFEBF9] hover:text-[#BB86FC]">
                    {ch.seriesTitle}
                  </div>
                  <div className="truncate text-xs text-[#958EB5]">{ch.chapterTitle}</div>
                </button>

                <button
                  type="button"
                  onClick={() =>
                    onCreateTab(
                      ch.chapterUrl,
                      `${ch.seriesTitle.split(':')[0]} — Kap. ${ch.chapterNumber ?? ''}`
                    )
                  }
                  aria-label={`'${ch.seriesTitle}' in neuem Tab öffnen`}
                  className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-[#2A2444] text-[#9E97BC] transition-colors hover:bg-[#1D1836] hover:text-white"
                >
                  <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                </button>
              </div>
            ))}
          </div>
        </aside>
      </div>
    </div>
  );
}
