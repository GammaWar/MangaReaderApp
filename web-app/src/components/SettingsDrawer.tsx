import { useEffect, useState } from 'react';
import {
  BookOpen,
  DownloadCloud,
  Eye,
  FastForward,
  HardDrive,
  History,
  Moon,
  SlidersHorizontal,
  SunDim,
  Trash2,
  X,
} from 'lucide-react';
import type { AutoscrollSpeed, ReaderSettings } from '../types/manga';
import {
  CACHE_LIMIT_PRESETS_MB,
  CacheManager,
  clampCacheLimitMb,
  formatBytesDe,
  formatCacheLimitLabel,
  MAX_CONFIGURABLE_CACHE_LIMIT_MB,
  mbToBytes,
  MIN_CACHE_LIMIT_MB,
  type CacheManagerStats,
} from '../utils/cacheManager';

interface SettingsDrawerProps {
  open: boolean;
  settings: ReaderSettings;
  isAutoscrolling: boolean;
  cacheRefreshTick?: number;
  onClose: () => void;
  onUpdateSettings: (patch: Partial<ReaderSettings>) => void;
  onToggleAutoscrollNow: () => void;
  onCacheChanged?: () => void;
  onNotify?: (message: string, tone?: 'info' | 'success' | 'warning' | 'error') => void;
}

const SPEED_OPTIONS: AutoscrollSpeed[] = [0.5, 1.0, 1.5, 2.0, 3.0];

interface ToggleSwitchProps {
  id: string;
  checked: boolean;
  label: string;
  onChange: (checked: boolean) => void;
}

function ToggleSwitch({ id, checked, label, onChange }: ToggleSwitchProps) {
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className={`relative inline-flex h-7 w-12 shrink-0 items-center rounded-full border transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#BB86FC] ${
        checked
          ? 'border-[#956BFF] bg-[#7C4DFF]'
          : 'border-[#332D4F] bg-[#1D1930]'
      }`}
    >
      <span
        className={`inline-block h-5 w-5 rounded-full bg-white transition-transform duration-150 ${
          checked ? 'translate-x-6' : 'translate-x-1'
        }`}
      />
    </button>
  );
}

export function SettingsDrawer({
  open,
  settings,
  isAutoscrolling,
  cacheRefreshTick = 0,
  onClose,
  onUpdateSettings,
  onToggleAutoscrollNow,
  onCacheChanged,
  onNotify,
}: SettingsDrawerProps) {
  const [cacheStats, setCacheStats] = useState<CacheManagerStats | null>(null);
  const [isPruningCache, setIsPruningCache] = useState(false);

  const safeLimitMb = clampCacheLimitMb(settings.cacheLimitMb);
  const limitBytes = mbToBytes(safeLimitMb);
  const [customGbDraft, setCustomGbDraft] = useState<string>(() =>
    String(Number((safeLimitMb / 1024).toFixed(1)))
  );

  useEffect(() => {
    setCustomGbDraft(String(Number((safeLimitMb / 1024).toFixed(1))));
  }, [safeLimitMb]);

  const commitCustomGb = (raw: string) => {
    const normalized = raw.replace(',', '.').trim();
    const parsedGb = Number(normalized);
    if (!Number.isFinite(parsedGb)) {
      setCustomGbDraft(String(Number((safeLimitMb / 1024).toFixed(1))));
      return;
    }
    const clampedMb = clampCacheLimitMb(Math.round(parsedGb * 1024));
    onUpdateSettings({ cacheLimitMb: clampedMb });
    setCustomGbDraft(String(Number((clampedMb / 1024).toFixed(1))));
  };

  const handleCustomGbChange = (raw: string) => {
    setCustomGbDraft(raw);
    const normalized = raw.replace(',', '.').trim();
    if (!normalized || normalized.endsWith('.')) return;
    const parsedGb = Number(normalized);
    if (!Number.isFinite(parsedGb)) return;
    // Immediately apply valid in-range values (or clamp if exceeding 32 GB max) so App.tsx validates storage right away
    if (parsedGb >= 0.5) {
      const clampedMb = clampCacheLimitMb(Math.round(parsedGb * 1024));
      onUpdateSettings({ cacheLimitMb: clampedMb });
      if (parsedGb > 32) {
        setCustomGbDraft('32');
      }
    }
  };

  const loadCombinedStats = async (targetLimitBytes: number): Promise<CacheManagerStats> => {
    const clientUsage = CacheManager.calculateStorageUsage(targetLimitBytes);
    let serverStats: CacheManagerStats | null = null;
    try {
      const res = await fetch(
        `/api/cache/status?maxBytes=${encodeURIComponent(String(targetLimitBytes))}`
      );
      if (res.ok) serverStats = (await res.json()) as CacheManagerStats;
    } catch {
      // Fallback to client usage only
    }
    const totalBytes = clientUsage.totalBytes + (serverStats?.totalBytes ?? 0);
    return {
      entryCount: clientUsage.entryCount + (serverStats?.entryCount ?? 0),
      chapterCount: Math.max(clientUsage.chapterCount, serverStats?.chapterCount ?? 0),
      totalBytes,
      maxBytes: targetLimitBytes,
      maxAgeDays: null,
      usagePercent:
        targetLimitBytes > 0
          ? Math.min(100, Number(((totalBytes / targetLimitBytes) * 100).toFixed(2)))
          : 0,
      lastPruneAt: serverStats?.lastPruneAt ?? null,
      totalExpiredEvicted: serverStats?.totalExpiredEvicted ?? 0,
      totalSizeEvicted: serverStats?.totalSizeEvicted ?? 0,
      totalFreedBytes: serverStats?.totalFreedBytes ?? 0,
    };
  };

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    void loadCombinedStats(limitBytes).then((combined) => {
      if (!cancelled) setCacheStats(combined);
    });
    return () => {
      cancelled = true;
    };
  }, [cacheRefreshTick, limitBytes, open]);

  const handleRunCleanup = async (clearAll: boolean) => {
    if (isPruningCache) return;
    setIsPruningCache(true);
    try {
      const clientReport = clearAll
        ? await CacheManager.clearAll()
        : await CacheManager.clearOldCache(null, limitBytes);

      let serverRemoved = 0;
      let serverFreed = 0;
      try {
        const res = await fetch('/api/cache/cleanup', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(
            clearAll
              ? { clearAll: true }
              : { clearAll: false, maxSizeBytes: limitBytes }
          ),
        });
        if (res.ok) {
          const payload = (await res.json()) as {
            report: {
              removedExpiredCount: number;
              removedSizeEvictedCount: number;
              freedBytes: number;
            };
          };
          serverRemoved =
            payload.report.removedExpiredCount + payload.report.removedSizeEvictedCount;
          serverFreed = payload.report.freedBytes;
        }
      } catch {
        // Server optional
      }

      const combined = await loadCombinedStats(limitBytes);
      setCacheStats(combined);
      onCacheChanged?.();

      const removedTotal = clientReport.removedCount + serverRemoved;
      const freedTotal = clientReport.freedBytes + serverFreed;
      const fileLabel = removedTotal === 1 ? 'Datei' : 'Dateien';
      const entryLabel = removedTotal === 1 ? 'ältester Eintrag' : 'älteste Einträge';
      onNotify?.(
        clearAll
          ? removedTotal > 0
            ? `Bild-Cache manuell geleert: ${removedTotal} ${fileLabel} entfernt (${formatBytesDe(freedTotal)} frei).`
            : 'Bild-Cache ist bereits leer.'
          : removedTotal > 0
          ? `Limit angewendet: ${removedTotal} ${entryLabel} entfernt (${formatBytesDe(freedTotal)} frei).`
          : `Cache innerhalb des ${formatCacheLimitLabel(safeLimitMb)}-Limits — keine Bereinigung nötig.`,
        removedTotal > 0 ? 'success' : 'info'
      );
    } catch {
      onNotify?.('Cache-Bereinigung konnte nicht ausgeführt werden.', 'error');
    } finally {
      setIsPruningCache(false);
    }
  };

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-end bg-black/70 backdrop-blur-xs md:items-stretch"
      role="dialog"
      aria-modal="true"
      aria-label="Reader- & QoL-Einstellungen"
    >
      <button
        type="button"
        aria-label="Einstellungen schließen"
        onClick={onClose}
        className="flex-1 cursor-default"
      />

      <div className="flex max-h-[88vh] w-full flex-col rounded-t-3xl border-t border-[#2A2442] bg-[#120F20] text-[#F1EFFA] shadow-2xl md:h-full md:max-h-none md:w-[440px] md:rounded-none md:border-t-0 md:border-l">
        {/* Mobile grab affordance */}
        <div className="mx-auto mt-3 h-1 w-10 rounded-full bg-[#312B4C] md:hidden" />

        <div className="flex items-center justify-between gap-4 border-b border-[#25203A] px-6 py-4">
          <div className="flex items-center gap-2.5">
            <SlidersHorizontal className="h-5 w-5 text-[#BB86FC]" aria-hidden="true" />
            <h2 className="font-display text-lg font-semibold text-white">
              Reader- &amp; QoL-Einstellungen
            </h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Einstellungen schließen"
            className="flex h-11 w-11 items-center justify-center rounded-xl text-[#B4AED0] transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="flex-1 space-y-6 overflow-y-auto px-6 py-5">
          {/* Lese-Modus */}
          <section className="space-y-3">
            <div className="flex items-center gap-2 text-sm font-semibold text-[#D8D3EE]">
              <BookOpen className="h-4 w-4 text-[#BB86FC]" aria-hidden="true" />
              <span>Lesemodus</span>
            </div>
            <div className="grid grid-cols-2 gap-1.5 rounded-xl bg-[#0B0914] p-1 border border-[#231E38]">
              <button
                type="button"
                onClick={() => onUpdateSettings({ readingMode: 'webtoon' })}
                className={`min-h-[42px] rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors ${
                  settings.readingMode === 'webtoon'
                    ? 'bg-[#7C4DFF] text-white'
                    : 'text-[#A9A2C7] hover:text-white'
                }`}
              >
                Webtoon (Vertikal)
              </button>
              <button
                type="button"
                onClick={() => onUpdateSettings({ readingMode: 'paged' })}
                className={`min-h-[42px] rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors ${
                  settings.readingMode === 'paged'
                    ? 'bg-[#7C4DFF] text-white'
                    : 'text-[#A9A2C7] hover:text-white'
                }`}
              >
                Manga (Blättern)
              </button>
            </div>

            {settings.readingMode === 'paged' && (
              <div className="flex items-center justify-between pt-1">
                <span className="text-sm text-[#B8B2D4]">Blätterrichtung</span>
                <div className="flex gap-1 rounded-lg bg-[#0B0914] p-1 border border-[#231E38]">
                  <button
                    type="button"
                    onClick={() => onUpdateSettings({ readingDirection: 'rtl' })}
                    className={`rounded-md px-3 py-1.5 text-xs font-medium whitespace-nowrap transition-colors ${
                      settings.readingDirection === 'rtl'
                        ? 'bg-[#262040] text-white'
                        : 'text-[#9A93B8] hover:text-white'
                    }`}
                  >
                    Rechts → Links
                  </button>
                  <button
                    type="button"
                    onClick={() => onUpdateSettings({ readingDirection: 'ltr' })}
                    className={`rounded-md px-3 py-1.5 text-xs font-medium whitespace-nowrap transition-colors ${
                      settings.readingDirection === 'ltr'
                        ? 'bg-[#262040] text-white'
                        : 'text-[#9A93B8] hover:text-white'
                    }`}
                  >
                    Links → Rechts
                  </button>
                </div>
              </div>
            )}
          </section>

          <hr className="border-[#231E38]" />

          {/* QoL 2: Bildschirm abdunkeln & Blaulichtfilter / Sepia */}
          <section className="space-y-4">
            <div className="flex items-center gap-2 text-sm font-semibold text-[#D8D3EE]">
              <Moon className="h-4 w-4 text-[#BB86FC]" aria-hidden="true" />
              <span>Helligkeits- &amp; Nachtfilter</span>
            </div>

            <div className="space-y-3">
              <div className="flex items-center justify-between gap-4">
                <div>
                  <label htmlFor="toggle-dimmer" className="block text-sm font-medium text-white">
                    Bildschirm abdunkeln (Software-Dimmer)
                  </label>
                  <p className="text-xs text-[#9C95BA]">
                    Dunkelt das Display unter das Hardware-Minimum ab.
                  </p>
                </div>
                <ToggleSwitch
                  id="toggle-dimmer"
                  checked={settings.dimmerEnabled}
                  label="Software-Dimmer umschalten"
                  onChange={(dimmerEnabled) => onUpdateSettings({ dimmerEnabled })}
                />
              </div>

              {settings.dimmerEnabled && (
                <div className="space-y-1.5 pl-0.5">
                  <div className="flex items-center justify-between text-xs text-[#B8B2D4]">
                    <span className="inline-flex items-center gap-1.5">
                      <SunDim className="h-3.5 w-3.5" aria-hidden="true" />
                      Abdunkelung
                    </span>
                    <span className="font-mono-tabular text-white">{settings.dimmerStrength} %</span>
                  </div>
                  <input
                    type="range"
                    min={0}
                    max={80}
                    step={2}
                    value={settings.dimmerStrength}
                    aria-label="Abdunkelungsstärke"
                    onChange={(e) =>
                      onUpdateSettings({ dimmerStrength: Number(e.target.value) })
                    }
                    className="h-2 w-full cursor-pointer accent-[#BB86FC]"
                  />
                </div>
              )}
            </div>

            <div className="space-y-3 pt-1">
              <div className="flex items-center justify-between gap-4">
                <div>
                  <label htmlFor="toggle-sepia" className="block text-sm font-medium text-white">
                    Blaulichtfilter / Sepia
                  </label>
                  <p className="text-xs text-[#9C95BA]">
                    Warmtonfilter für augenschonendes Lesen bei Nacht.
                  </p>
                </div>
                <ToggleSwitch
                  id="toggle-sepia"
                  checked={settings.blueLightFilterEnabled}
                  label="Blaulichtfilter / Sepia umschalten"
                  onChange={(blueLightFilterEnabled) =>
                    onUpdateSettings({ blueLightFilterEnabled })
                  }
                />
              </div>

              {settings.blueLightFilterEnabled && (
                <div className="space-y-1.5 pl-0.5">
                  <div className="flex items-center justify-between text-xs text-[#B8B2D4]">
                    <span className="inline-flex items-center gap-1.5">
                      <Eye className="h-3.5 w-3.5" aria-hidden="true" />
                      Filterstärke
                    </span>
                    <span className="font-mono-tabular text-amber-200">
                      {settings.blueLightStrength} %
                    </span>
                  </div>
                  <input
                    type="range"
                    min={10}
                    max={80}
                    step={5}
                    value={settings.blueLightStrength}
                    aria-label="Sepia-Filterstärke"
                    onChange={(e) =>
                      onUpdateSettings({ blueLightStrength: Number(e.target.value) })
                    }
                    className="h-2 w-full cursor-pointer accent-amber-400"
                  />
                </div>
              )}
            </div>
          </section>

          <hr className="border-[#231E38]" />

          {/* QoL 3: Autoscroll */}
          <section className="space-y-3.5">
            <div className="flex items-center justify-between gap-4">
              <div>
                <label
                  htmlFor="toggle-autoscroll-feature"
                  className="flex items-center gap-2 text-sm font-semibold text-[#D8D3EE]"
                >
                  <FastForward className="h-4 w-4 text-[#BB86FC]" aria-hidden="true" />
                  Autoscroll / Freihändiges Lesen
                </label>
                <p className="mt-0.5 text-xs text-[#9C95BA]">
                  Zeigt Play/Pause im Reader sowie die schwebende Geschwindigkeits-Steuerung.
                </p>
              </div>
              <ToggleSwitch
                id="toggle-autoscroll-feature"
                checked={settings.autoscrollFeatureEnabled}
                label="Autoscroll-Funktion aktivieren"
                onChange={(autoscrollFeatureEnabled) =>
                  onUpdateSettings({ autoscrollFeatureEnabled })
                }
              />
            </div>

            {settings.autoscrollFeatureEnabled && (
              <div className="space-y-3">
                <div className="flex flex-wrap items-center gap-1.5">
                  {SPEED_OPTIONS.map((spd) => (
                    <button
                      key={spd}
                      type="button"
                      onClick={() => onUpdateSettings({ autoscrollSpeed: spd })}
                      className={`min-h-[38px] rounded-lg px-3 py-1.5 font-mono-tabular text-xs font-medium whitespace-nowrap transition-colors ${
                        settings.autoscrollSpeed === spd
                          ? 'bg-[#7C4DFF] text-white'
                          : 'border border-[#2A2444] bg-[#0B0914] text-[#B4AED0] hover:text-white'
                      }`}
                    >
                      {spd.toFixed(1)}x
                    </button>
                  ))}
                </div>
                <button
                  type="button"
                  onClick={() => {
                    onToggleAutoscrollNow();
                    onClose();
                  }}
                  className="min-h-[44px] w-full rounded-xl border border-[#3B3166] bg-[#1D1736] px-4 py-2.5 text-sm font-medium text-[#E5DCFF] transition-colors hover:bg-[#271F48]"
                >
                  {isAutoscrolling ? 'Autoscroll jetzt pausieren' : 'Autoscroll im Reader starten'}
                </button>
              </div>
            )}
          </section>

          <hr className="border-[#231E38]" />

          {/* QoL 4: Automatisches Pre-Fetching */}
          <section className="flex items-center justify-between gap-4">
            <div>
              <label
                htmlFor="toggle-prefetch"
                className="flex items-center gap-2 text-sm font-semibold text-[#D8D3EE]"
              >
                <DownloadCloud className="h-4 w-4 text-[#BB86FC]" aria-hidden="true" />
                Automatisches Pre-Fetching
              </label>
              <p className="mt-0.5 text-xs text-[#9C95BA]">
                Lädt das nächste Kapitel im Hintergrund vor, sobald du die letzten 2 Seiten erreichst.
              </p>
            </div>
            <ToggleSwitch
              id="toggle-prefetch"
              checked={settings.prefetchEnabled}
              label="Automatisches Pre-Fetching umschalten"
              onChange={(prefetchEnabled) => onUpdateSettings({ prefetchEnabled })}
            />
          </section>

          <hr className="border-[#231E38]" />

          {/* QoL 1: Automatischer Verlauf */}
          <section className="flex items-center justify-between gap-4">
            <div>
              <label
                htmlFor="toggle-history"
                className="flex items-center gap-2 text-sm font-semibold text-[#D8D3EE]"
              >
                <History className="h-4 w-4 text-[#BB86FC]" aria-hidden="true" />
                Automatischer Leseverlauf
              </label>
              <p className="mt-0.5 text-xs text-[#9C95BA]">
                Speichert gelesene Kapitel und die exakte Seitenzahl automatisch in der Bibliothek.
              </p>
            </div>
            <ToggleSwitch
              id="toggle-history"
              checked={settings.historyTrackingEnabled}
              label="Automatischen Verlauf umschalten"
              onChange={(historyTrackingEnabled) =>
                onUpdateSettings({ historyTrackingEnabled })
              }
            />
          </section>

          <hr className="border-[#231E38]" />

          {/* Bild-Cache & Konfigurierbares Speicherlimit (Standard: 4 GB, manuelles Löschen) */}
          <section data-testid="cache-manager-section" className="space-y-3.5">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2 text-sm font-semibold text-[#D8D3EE]">
                <HardDrive className="h-4 w-4 text-[#BB86FC]" aria-hidden="true" />
                <span>Bild-Cache &amp; Speicherlimit</span>
              </div>
              <span className="font-mono-tabular text-xs text-[#9C95BA]">
                Limit: {formatCacheLimitLabel(safeLimitMb)} · Manuell
              </span>
            </div>

            <p className="text-xs leading-relaxed text-[#9C95BA]">
              Zwischengespeicherte Seiten verfallen nicht mehr automatisch nach 31 Tagen. Sie
              bleiben erhalten, bis du sie manuell löschst oder dein eingestelltes Speicherlimit
              erreicht wird.
            </p>

            {/* Aktuelle Auslastung */}
            <div className="space-y-1.5">
              <div className="flex items-center justify-between text-xs text-[#CFCBE2]">
                <span className="font-mono-tabular">
                  {cacheStats
                    ? `${formatBytesDe(cacheStats.totalBytes)} / ${formatBytesDe(limitBytes)}`
                    : `0 B / ${formatBytesDe(limitBytes)}`}
                </span>
                <span className="font-mono-tabular text-[#9C95BA]">
                  {cacheStats
                    ? `${cacheStats.entryCount} ${cacheStats.entryCount === 1 ? 'Datei' : 'Dateien'} · ${cacheStats.chapterCount} Kapitel`
                    : '0 Dateien · 0 Kapitel'}
                </span>
              </div>
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-[#241F3A]">
                <div
                  className={`h-full transition-all ${
                    (cacheStats?.usagePercent ?? 0) >= 90
                      ? 'bg-rose-500'
                      : (cacheStats?.usagePercent ?? 0) >= 75
                      ? 'bg-amber-400'
                      : 'bg-[#BB86FC]'
                  }`}
                  style={{ width: `${cacheStats ? Math.max(0, cacheStats.usagePercent) : 0}%` }}
                />
              </div>
            </div>

            {/* Speicherlimit flexibel anpassen (Standard: 4 GB, Max.: 32 GB) */}
            <div className="space-y-2.5 pt-1">
              <div className="flex items-center justify-between gap-3 text-xs text-[#B8B2D4]">
                <label htmlFor="cache-limit-gb-input">Maximales Cache-Limit</label>
                <div className="flex items-center gap-1.5">
                  <input
                    id="cache-limit-gb-input"
                    data-testid="cache-limit-gb-input"
                    type="number"
                    min={0.5}
                    max={32}
                    step={0.5}
                    value={customGbDraft}
                    aria-label="Maximales Cache-Limit in GB"
                    onChange={(e) => handleCustomGbChange(e.target.value)}
                    onBlur={(e) => commitCustomGb(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        commitCustomGb(customGbDraft);
                      }
                    }}
                    className="w-20 rounded-lg border border-[#312A4E] bg-[#0B0914] px-2.5 py-1 text-right font-mono-tabular text-xs font-medium text-white focus:border-[#BB86FC] focus:outline-none"
                  />
                  <span className="font-mono-tabular text-xs text-[#A9A2C7]">GB</span>
                </div>
              </div>

              <div className="grid grid-cols-3 gap-1.5 sm:grid-cols-6">
                {CACHE_LIMIT_PRESETS_MB.map((presetMb) => (
                  <button
                    key={presetMb}
                    type="button"
                    data-testid={`cache-limit-preset-${presetMb}`}
                    onClick={() => onUpdateSettings({ cacheLimitMb: presetMb })}
                    className={`min-h-[36px] rounded-lg px-2 py-1.5 font-mono-tabular text-xs font-medium whitespace-nowrap transition-colors ${
                      safeLimitMb === presetMb
                        ? 'bg-[#7C4DFF] text-white'
                        : 'border border-[#2A2444] bg-[#0B0914] text-[#B4AED0] hover:text-white'
                    }`}
                  >
                    {formatCacheLimitLabel(presetMb)}
                  </button>
                ))}
              </div>

              <div className="space-y-1">
                <input
                  type="range"
                  data-testid="cache-limit-slider"
                  min={MIN_CACHE_LIMIT_MB}
                  max={MAX_CONFIGURABLE_CACHE_LIMIT_MB}
                  step={512}
                  value={safeLimitMb}
                  aria-label="Cache-Speicherlimit"
                  onChange={(e) => onUpdateSettings({ cacheLimitMb: Number(e.target.value) })}
                  className="h-2 w-full cursor-pointer accent-[#BB86FC]"
                />
                <div className="flex items-center justify-between font-mono-tabular text-[11px] text-[#8D86A8]">
                  <span>512 MB</span>
                  <span>Standard: 4 GB</span>
                  <span>Max. 32 GB</span>
                </div>
              </div>
            </div>

            {/* Manuelle Lösch- und Limit-Aktionen */}
            <div className="flex items-center gap-2 pt-1">
              <button
                type="button"
                data-testid="cache-apply-limit-btn"
                disabled={isPruningCache}
                onClick={() => void handleRunCleanup(false)}
                className="inline-flex min-h-[40px] flex-1 items-center justify-center gap-1.5 rounded-xl border border-[#3B3166] bg-[#1D1736] px-3 py-2 text-xs font-medium text-[#E5DCFF] whitespace-nowrap transition-colors hover:bg-[#271F48] disabled:opacity-50"
              >
                Auf Limit bereinigen
              </button>
              <button
                type="button"
                data-testid="cache-clear-all-btn"
                disabled={isPruningCache}
                onClick={() => void handleRunCleanup(true)}
                className="inline-flex min-h-[40px] items-center justify-center gap-1.5 rounded-xl border border-rose-900/60 bg-rose-950/30 px-3.5 py-2 text-xs font-medium text-rose-200 whitespace-nowrap transition-colors hover:bg-rose-950/60 disabled:opacity-50"
              >
                <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                Cache leeren
              </button>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
