import test from 'node:test';
import assert from 'node:assert/strict';
import { serializedStorage, STORAGE_KEYS } from './storage.ts';

function withMockLocalStorage<T>(
  fn: (store: Map<string, string>, setShouldThrow: (shouldThrow: boolean) => void) => Promise<T>
): Promise<T> {
  const store = new Map<string, string>();
  let shouldThrow = false;
  const previousWindow = (globalThis as Record<string, unknown>).window;

  (globalThis as Record<string, unknown>).window = {
    localStorage: {
      getItem: (key: string) => (store.has(key) ? store.get(key)! : null),
      setItem: (key: string, value: string) => {
        if (shouldThrow) {
          throw new Error('QuotaExceededError');
        }
        store.set(key, value);
      },
      removeItem: (key: string) => {
        store.delete(key);
      },
    },
  };

  return fn(store, (flag) => {
    shouldThrow = flag;
  }).finally(() => {
    if (previousWindow === undefined) {
      delete (globalThis as Record<string, unknown>).window;
    } else {
      (globalThis as Record<string, unknown>).window = previousWindow;
    }
  });
}

test('serializedStorage returns fallback when window is unavailable or JSON is corrupt', async () => {
  assert.deepEqual(serializedStorage.load('missing', { ok: true }), { ok: true });

  await withMockLocalStorage(async (store) => {
    store.set('corrupt', '{not-valid-json');
    assert.deepEqual(serializedStorage.load('corrupt', ['fallback']), ['fallback']);
  });
});

test('serializedStorage persists values via saveImmediate and saveDebounced and ignores quota errors', async () => {
  await withMockLocalStorage(async (store, setShouldThrow) => {
    await serializedStorage.saveImmediate(STORAGE_KEYS.settings, { dark: true });
    assert.deepEqual(serializedStorage.load(STORAGE_KEYS.settings, { dark: false }), { dark: true });

    // Rapid debounced writes coalesce to the latest value
    serializedStorage.saveDebounced(STORAGE_KEYS.tabs, ['tab-1'], 25);
    serializedStorage.saveDebounced(STORAGE_KEYS.tabs, ['tab-1', 'tab-2'], 25);
    await new Promise((resolve) => setTimeout(resolve, 60));
    assert.deepEqual(serializedStorage.load(STORAGE_KEYS.tabs, []), ['tab-1', 'tab-2']);

    // saveImmediate cancels any pending debounced write for the same key
    serializedStorage.saveDebounced(STORAGE_KEYS.history, ['stale'], 40);
    await serializedStorage.saveImmediate(STORAGE_KEYS.history, ['fresh']);
    await new Promise((resolve) => setTimeout(resolve, 60));
    assert.deepEqual(serializedStorage.load(STORAGE_KEYS.history, []), ['fresh']);

    // Quota error must be swallowed without rejecting or poisoning subsequent writes
    setShouldThrow(true);
    await assert.doesNotReject(() =>
      serializedStorage.saveImmediate(STORAGE_KEYS.bookmarks, ['cannot-save'])
    );
    setShouldThrow(false);
    await serializedStorage.saveImmediate(STORAGE_KEYS.bookmarks, ['saved-after-error']);
    assert.deepEqual(serializedStorage.load(STORAGE_KEYS.bookmarks, []), ['saved-after-error']);
    assert.ok(store.has(STORAGE_KEYS.bookmarks));
  });
});
