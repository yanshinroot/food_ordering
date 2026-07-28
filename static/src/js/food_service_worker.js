// Food Ordering PWA service worker.
//
// Strategy:
// - Navigation requests (the catalog page, order tracking pages): network
//   first, falling back to the last cached copy when offline, and to a
//   minimal offline page if nothing is cached yet.
// - Static assets (CSS/JS bundles, images): cache-first with a background
//   network refresh (stale-while-revalidate) so the app shell loads
//   instantly on repeat visits.
// - Anything state-changing (POST — cart updates, order submission,
//   payments) is never cached and always goes straight to the network, so
//   an order can never be silently queued or duplicated while offline.
const CACHE_NAME = "food-ordering-v2";
const OFFLINE_FALLBACK_URL = "/food/offline";

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll([OFFLINE_FALLBACK_URL]).catch(() => {}))
  );
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)))
    ).then(() => self.clients.claim())
  );
});

function isStateChanging(request) {
  return request.method !== "GET";
}

function isNavigation(request) {
  return request.mode === "navigate";
}

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (isStateChanging(request)) return; // never intercept POST/PUT/DELETE

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  if (isNavigation(request)) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
          return response;
        })
        .catch(() =>
          caches.match(request).then((cached) => cached || caches.match(OFFLINE_FALLBACK_URL))
        )
    );
    return;
  }

  if (/\/(web\/assets|food_ordering\/static|web\/image)\//.test(url.pathname)) {
    event.respondWith(
      caches.open(CACHE_NAME).then((cache) =>
        cache.match(request).then((cached) => {
          const network = fetch(request)
            .then((response) => { cache.put(request, response.clone()); return response; })
            .catch(() => cached);
          return cached || network;
        })
      )
    );
    return;
  }

  if (url.pathname === "/api/food/v1/catalog") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          caches.open(CACHE_NAME).then((cache) => cache.put(request, response.clone()));
          return response;
        })
        .catch(() => caches.match(request))
    );
  }
});
