/**
 * Debounced (400ms) and queue-serialized persistence utility for localStorage.
 * Prevents parallel disk write storms on rapid page turns and avoids partial/corrupt state writes.
 */
class SerializedStorage {
  private timers = new Map<string, ReturnType<typeof setTimeout>>();
  private writeQueue: Promise<void> = Promise.resolve();

  load<T>(key: string, fallback: T): T {
    if (typeof window === 'undefined') return fallback;
    try {
      const raw = window.localStorage.getItem(key);
      if (!raw) return fallback;
      const parsed = JSON.parse(raw) as T;
      return parsed ?? fallback;
    } catch {
      return fallback;
    }
  }

  saveImmediate<T>(key: string, value: T): Promise<void> {
    const pending = this.timers.get(key);
    if (pending) {
      clearTimeout(pending);
      this.timers.delete(key);
    }
    return this.enqueueWrite(key, value);
  }

  saveDebounced<T>(key: string, value: T, delayMs = 400): void {
    const existing = this.timers.get(key);
    if (existing) clearTimeout(existing);

    const timer = setTimeout(() => {
      this.timers.delete(key);
      void this.enqueueWrite(key, value);
    }, delayMs);

    this.timers.set(key, timer);
  }

  private enqueueWrite<T>(key: string, value: T): Promise<void> {
    this.writeQueue = this.writeQueue
      .then(() => {
        if (typeof window === 'undefined') return;
        const serialized = JSON.stringify(value);
        window.localStorage.setItem(key, serialized);
      })
      .catch(() => {
        // Ignore quota errors gracefully without crashing reader
      });
    return this.writeQueue;
  }
}

export const serializedStorage = new SerializedStorage();

export const STORAGE_KEYS = {
  bookmarks: 'mangareader_v1_bookmarks',
  history: 'mangareader_v1_history',
  downloads: 'mangareader_v1_downloads',
  settings: 'mangareader_v1_settings',
  tabs: 'mangareader_v1_browser_tabs',
  chatHistory: 'mangareader_v1_chat_history',
} as const;
