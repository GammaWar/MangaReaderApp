import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  MAX_CONFIGURABLE_CACHE_LIMIT_MB,
  MIN_CACHE_LIMIT_MB,
} from '../src/utils/cacheManager.ts';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export const PROJECT_ROOT = path.resolve(__dirname, '..');

export const DEFAULT_PORT = Number(process.env.PORT) || 3000;
export const DEFAULT_HOST = process.env.HOST?.trim() || '0.0.0.0';

export const CONNECT_TIMEOUT_MS = 15_000;
export const READ_TIMEOUT_MS = 20_000;
export const MAX_HTML_BYTES = 4 * 1024 * 1024;
export const MAX_IMAGE_BYTES = 15 * 1024 * 1024;

export const CACHE_PRUNE_INTERVAL_MS = 6 * 60 * 60 * 1000; // Alle 6 Stunden Speicherlimit prüfen
export const MIN_SERVER_CACHE_BYTES = MIN_CACHE_LIMIT_MB * 1024 * 1024;
export const MAX_SERVER_CACHE_BYTES = MAX_CONFIGURABLE_CACHE_LIMIT_MB * 1024 * 1024;
export const DEFAULT_DISK_CACHE_DIR =
  process.env.MANGA_CACHE_DIR?.trim() || path.resolve(PROJECT_ROOT, '.cache', 'images');

export const CHAT_FIRST_TOKEN_TIMEOUT_MS = 20_000;
export const CHAT_IDLE_CHUNK_TIMEOUT_MS = 30_000;
export const CHAT_HEARTBEAT_INTERVAL_MS = 15_000;

export const SAFE_USER_AGENT =
  'Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36';

export const DEMO_ASSETS: Record<string, string> = {
  manga1: 'src/assets/images/manga_panel_01_1795260245604.jpg',
  manga2: 'src/assets/images/manga_panel_02_1795260245956.jpg',
  webtoon1: 'src/assets/images/webtoon_panel_01_1795260245598.jpg',
  cover1: 'src/assets/images/cover_kurogane_1795260244125.jpg',
  cover2: 'src/assets/images/cover_stellar_drift_1795260245712.jpg',
};
