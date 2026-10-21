// Service worker: keeps the app shell available offline.
// Strategy: precache the shell on install; serve same-origin GETs from cache and refresh
// them in the background (stale-while-revalidate). /api/* and other origins are never cached.
// Bump VERSION when the shell list changes. test/core.test.js checks the list is complete.

const VERSION = 'rs-v3';

const SHELL = [
  './',
  'index.html',
  'manifest.webmanifest',
  'css/app.css',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/icon.svg',
  'js/app.js',
  'js/core/calibration.js',
  'js/core/gpt-kit.js',
  'js/core/json.js',
  'js/core/llm-request.js',
  'js/core/models.js',
  'js/core/notion-schema.js',
  'js/core/parse-local.js',
  'js/core/prompts.js',
  'js/core/scoring.js',
  'js/data/demo.js',
  'js/data/seed-cases.js',
  'js/services/api.js',
  'js/services/claude-host.js',
  'js/services/notion-sync.js',
  'js/services/settings.js',
  'js/store/db.js',
  'js/ui/dom.js',
  'js/views/cases.js',
  'js/views/dashboard.js',
  'js/views/import-box.js',
  'js/views/log.js',
  'js/views/people.js',
  'js/views/render-result.js',
  'js/views/roleplay.js',
  'js/views/sandbox.js',
  'js/views/settings.js',
  'js/views/world.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== self.location.origin) return;
  if (url.pathname.includes('/api/')) return;

  event.respondWith(
    caches.open(VERSION).then(async (cache) => {
      const cached = await cache.match(req, { ignoreSearch: true });
      const network = fetch(req)
        .then((res) => {
          if (res.ok && res.type === 'basic') cache.put(req, res.clone());
          return res;
        })
        .catch(() => null);
      if (cached) {
        event.waitUntil(network);
        return cached;
      }
      const res = await network;
      if (res) return res;
      // Offline navigation to an uncached URL: fall back to the app shell.
      if (req.mode === 'navigate') return (await cache.match('index.html')) || Response.error();
      return Response.error();
    }),
  );
});
