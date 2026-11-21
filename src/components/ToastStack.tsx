import { X } from 'lucide-react';
import type { ToastMessage } from '../types/manga';

interface ToastStackProps {
  toasts: ToastMessage[];
  onDismiss: (id: string) => void;
}

const TONE_CLASSES: Record<ToastMessage['tone'], string> = {
  info: 'border-[#332C54] bg-[#161326] text-[#ECE9F8]',
  success: 'border-emerald-800/70 bg-[#101F1A] text-emerald-100',
  warning: 'border-amber-700/70 bg-[#221A10] text-amber-100',
  error: 'border-rose-800/70 bg-[#251218] text-rose-100',
};

export function ToastStack({ toasts, onDismiss }: ToastStackProps) {
  if (toasts.length === 0) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed bottom-20 left-1/2 z-50 flex w-[min(92vw,440px)] -translate-x-1/2 flex-col gap-2 md:bottom-6"
    >
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`flex items-center justify-between gap-3 rounded-xl border px-4 py-3 text-sm shadow-xl ${TONE_CLASSES[toast.tone]}`}
        >
          <p className="leading-snug">{toast.message}</p>
          <button
            type="button"
            onClick={() => onDismiss(toast.id)}
            aria-label="Hinweis schließen"
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-current/70 transition-colors hover:bg-white/10 hover:text-current focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      ))}
    </div>
  );
}
