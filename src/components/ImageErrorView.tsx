import {
  AlertTriangle,
  CloudOff,
  Copy,
  Globe,
  Hourglass,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  XCircle,
} from 'lucide-react';
import type { ImageDiagnostic } from '../types/manga';

interface ImageErrorViewProps {
  pageIndex: number;
  diagnostic: ImageDiagnostic;
  imageUrl: string;
  retryAttempt: number;
  isAutoRetrying: boolean;
  onRetry: () => void;
  onRefreshSession: () => void;
  onOpenBrowser: () => void;
  onCopyUrl: (url: string) => void;
}

const SEVERITY_SURFACE: Record<ImageDiagnostic['severity'], string> = {
  crimson: 'border-rose-900/80 bg-[#1B0D12]',
  amber: 'border-amber-800/80 bg-[#1C140B]',
  yellow: 'border-yellow-800/75 bg-[#1B180B]',
  slate: 'border-[#2E2A45] bg-[#141222]',
};

const SEVERITY_ICON_COLOR: Record<ImageDiagnostic['severity'], string> = {
  crimson: 'text-rose-400',
  amber: 'text-amber-400',
  yellow: 'text-yellow-300',
  slate: 'text-slate-300',
};

function DiagnosticIcon({ kind, className }: { kind: ImageDiagnostic['kind']; className: string }) {
  switch (kind) {
    case 'HTTP_404':
      return <XCircle className={className} aria-hidden="true" />;
    case 'HTTP_403':
    case 'HTML_CHALLENGE':
      return <ShieldAlert className={className} aria-hidden="true" />;
    case 'HTTP_429':
      return <Hourglass className={className} aria-hidden="true" />;
    case 'HTTP_5XX':
    case 'TIMEOUT':
      return <CloudOff className={className} aria-hidden="true" />;
    default:
      return <AlertTriangle className={className} aria-hidden="true" />;
  }
}

export function ImageErrorView({
  pageIndex,
  diagnostic,
  imageUrl,
  retryAttempt,
  isAutoRetrying,
  onRetry,
  onRefreshSession,
  onOpenBrowser,
  onCopyUrl,
}: ImageErrorViewProps) {
  return (
    <div
      data-testid={`image-error-card-${pageIndex}`}
      className={`mx-auto my-3 w-full max-w-2xl rounded-2xl border p-5 text-left ${SEVERITY_SURFACE[diagnostic.severity]}`}
    >
      <div className="flex items-start gap-3.5">
        <div className={`mt-0.5 shrink-0 ${SEVERITY_ICON_COLOR[diagnostic.severity]}`}>
          <DiagnosticIcon kind={diagnostic.kind} className="h-6 w-6" />
        </div>
        <div className="min-w-0 flex-1 space-y-1.5">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-[#A7A1C2]">
            <span className="font-mono-tabular">Seite {pageIndex + 1}</span>
            <span aria-hidden="true">·</span>
            <span className="font-mono-tabular">
              {diagnostic.httpStatus ? `Status ${diagnostic.httpStatus}` : 'Diagnose'}
            </span>
            {retryAttempt > 0 && (
              <>
                <span aria-hidden="true">·</span>
                <span className="font-mono-tabular">
                  {isAutoRetrying
                    ? `Auto-Retry ${retryAttempt}/3 läuft…`
                    : `${retryAttempt} Wiederholungsversuche`}
                </span>
              </>
            )}
          </div>

          <h4 className="font-display text-base font-semibold text-[#F5F3FF]">
            {diagnostic.title}
          </h4>

          <p className="text-sm leading-relaxed text-[#CFCBE2]">{diagnostic.description}</p>
          <p className="text-xs leading-relaxed text-[#9C95BA]">{diagnostic.hint}</p>

          <p
            className="truncate pt-1 font-mono-tabular text-xs text-[#80799E]"
            title={imageUrl}
          >
            {imageUrl}
          </p>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-2.5 border-t border-white/10 pt-4">
        <button
          type="button"
          onClick={onRetry}
          className="inline-flex min-h-[44px] items-center gap-2 rounded-xl bg-[#7C4DFF] px-4 py-2 text-sm font-medium text-white whitespace-nowrap shrink-0 transition-colors hover:bg-[#6E3FF2] focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
        >
          <RotateCcw className="h-4 w-4" />
          Erneut versuchen
        </button>

        {diagnostic.suggestSessionRefresh && (
          <button
            type="button"
            onClick={onRefreshSession}
            className="inline-flex min-h-[44px] items-center gap-2 rounded-xl border border-amber-500/40 bg-amber-500/15 px-4 py-2 text-sm font-medium text-amber-100 whitespace-nowrap shrink-0 transition-colors hover:bg-amber-500/25 focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
          >
            <RefreshCw className="h-4 w-4" />
            Sitzung erneuern
          </button>
        )}

        {diagnostic.suggestOpenBrowser && (
          <button
            type="button"
            onClick={onOpenBrowser}
            className="inline-flex min-h-[44px] items-center gap-2 rounded-xl border border-white/15 bg-white/5 px-4 py-2 text-sm font-medium text-[#ECE9F8] whitespace-nowrap shrink-0 transition-colors hover:bg-white/10 focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
          >
            <Globe className="h-4 w-4" />
            Browser
          </button>
        )}

        <button
          type="button"
          onClick={() => onCopyUrl(imageUrl)}
          className="inline-flex min-h-[44px] items-center gap-2 rounded-xl border border-white/15 bg-white/5 px-3.5 py-2 text-sm font-medium text-[#CFCBE2] whitespace-nowrap shrink-0 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
        >
          <Copy className="h-4 w-4" />
          URL kopieren
        </button>
      </div>
    </div>
  );
}
