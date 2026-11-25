// Repository-owned service-worker kill-switch.
// It replaces Flutter's generated cache worker in production packaging so stale
// application shells and authenticated/trading responses are never served offline.
self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.map((key) => caches.delete(key)));
    await self.registration.unregister();
    await self.clients.claim();
    const clients = await self.clients.matchAll({type: 'window'});
    await Promise.all(clients.map((client) => client.navigate(client.url)));
  })());
});

// Intentionally no fetch handler: every request remains network/browser-policy controlled.
