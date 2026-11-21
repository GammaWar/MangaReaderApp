import type { ReactNode } from 'react';
import { BookMarked, BookOpen, Globe, MessageSquare, SlidersHorizontal } from 'lucide-react';
import type { MainTab } from '../types/manga';
import { APP_ICON_URL } from '../data/catalog';

interface AppShellProps {
  activeTab: MainTab;
  onSelectTab: (tab: MainTab) => void;
  onOpenSettings: () => void;
  hideChrome?: boolean;
  hideMobileNav?: boolean;
  children: ReactNode;
}

const NAV_ITEMS: Array<{ id: MainTab; label: string; icon: typeof BookMarked }> = [
  { id: 'library', label: 'Bibliothek', icon: BookMarked },
  { id: 'browser', label: 'Browser', icon: Globe },
  { id: 'reader', label: 'Reader', icon: BookOpen },
  { id: 'chat', label: 'Assistent', icon: MessageSquare },
];

export function AppShell({
  activeTab,
  onSelectTab,
  onOpenSettings,
  hideChrome = false,
  hideMobileNav = false,
  children,
}: AppShellProps) {
  return (
    <div className="flex min-h-screen flex-col bg-[#0B0A12] text-[#F1EFFA]">
      {/* Desktop & Mobile Top Navigation Bar (strict 3-zone contract) */}
      {!hideChrome && (
        <header
          className={`sticky top-0 z-30 border-b border-[#221E36] bg-[#0B0A12]/95 backdrop-blur-md ${
            hideMobileNav ? 'hidden md:block' : ''
          }`}
        >
          <div className="mx-auto flex h-14 max-w-7xl items-center justify-between gap-8 px-4 sm:px-6 lg:px-8">
            {/* Zone 1: Brand mark (single line, no descriptor/subtitle) */}
            <button
              type="button"
              onClick={() => onSelectTab('library')}
              className="flex items-center gap-2.5 whitespace-nowrap shrink-0 text-left focus-visible:outline-2 focus-visible:outline-[#BB86FC] rounded-lg"
            >
              <img
                src={APP_ICON_URL}
                alt="MangaReaderApp Icon"
                referrerPolicy="no-referrer"
                className="h-8 w-8 rounded-lg border border-[#2C2647] object-cover"
              />
              <span className="font-display text-base font-bold tracking-tight text-white">
                MangaReaderApp
              </span>
            </button>

            {/* Zone 2: Clean single-line text navigation links */}
            <nav
              aria-label="Hauptnavigation"
              className="hidden items-center gap-7 md:flex"
            >
              {NAV_ITEMS.map((item) => {
                const isActive = activeTab === item.id;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => onSelectTab(item.id)}
                    aria-current={isActive ? 'page' : undefined}
                    className={`relative flex h-14 items-center text-sm font-medium whitespace-nowrap shrink-0 transition-colors focus-visible:outline-2 focus-visible:outline-[#BB86FC] ${
                      isActive ? 'text-white' : 'text-[#A099BC] hover:text-white'
                    }`}
                  >
                    {item.label}
                    {isActive && (
                      <span
                        aria-hidden="true"
                        className="absolute right-0 bottom-0 left-0 h-0.5 bg-[#BB86FC]"
                      />
                    )}
                  </button>
                );
              })}
            </nav>

            {/* Zone 3: Single primary contextual action */}
            <div className="flex items-center shrink-0">
              <button
                type="button"
                onClick={onOpenSettings}
                className="inline-flex min-h-[40px] items-center gap-2 rounded-xl border border-[#2B2545] bg-[#141124] px-4 py-2 text-sm font-medium text-[#ECE9F8] whitespace-nowrap shrink-0 transition-colors hover:border-[#413869] hover:bg-[#1B1730] focus-visible:outline-2 focus-visible:outline-[#BB86FC]"
              >
                <SlidersHorizontal className="h-4 w-4 text-[#BB86FC]" aria-hidden="true" />
                <span>Einstellungen</span>
              </button>
            </div>
          </div>
        </header>
      )}

      {/* Main Content */}
      <main className={`flex-1 ${!hideChrome && !hideMobileNav ? 'pb-16 md:pb-0' : ''}`}>
        {children}
      </main>

      {/* Mobile Bottom Tab Bar (ergonomic thumb zone, strictly <= 15% mobile sticky cap) */}
      {!hideChrome && !hideMobileNav && (
        <nav
          aria-label="Mobile Navigation"
          className="fixed right-0 bottom-0 left-0 z-40 border-t border-[#221E36] bg-[#0B0A12]/95 backdrop-blur-md md:hidden"
        >
          <div className="grid h-14 grid-cols-4 items-center">
            {NAV_ITEMS.map((item) => {
              const Icon = item.icon;
              const isActive = activeTab === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => onSelectTab(item.id)}
                  aria-current={isActive ? 'page' : undefined}
                  className={`flex h-full flex-col items-center justify-center gap-0.5 transition-colors focus-visible:outline-2 focus-visible:outline-[#BB86FC] ${
                    isActive ? 'text-[#BB86FC]' : 'text-[#8F88AB] hover:text-white'
                  }`}
                >
                  <Icon className="h-5 w-5" aria-hidden="true" />
                  <span className="text-[11px] font-medium whitespace-nowrap">{item.label}</span>
                </button>
              );
            })}
          </div>
        </nav>
      )}
    </div>
  );
}
