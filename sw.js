// Sentinel Alertes — service worker : notifications appli fermée + fonctionnement hors ligne
const SHELL = "sentinel-shell-v3";
const FILES = ["./", "index.html", "app.js", "tasks.js", "parser.js", "manifest.webmanifest", "icon-192.png", "icon-512.png", "badge-96.png"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(SHELL).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k.startsWith("sentinel-shell-") && k !== SHELL).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const u = new URL(e.request.url);
  if (e.request.method !== "GET" || u.origin !== location.origin) return;
  e.respondWith(fetch(e.request).then(r => { const copy = r.clone(); caches.open(SHELL).then(c => c.put(e.request, copy)); return r; }).catch(() => caches.match(e.request).then(r => r || caches.match("index.html"))));
});

async function config() {
  try { const r = await (await caches.open("sentinel-cfg")).match("/cfg"); return r ? r.json() : null; } catch (e) { return null; }
}

self.addEventListener("push", e => {
  e.waitUntil((async () => {
    let msg = { title: "⚠ Débuts de service à confirmer", body: "Ouvre Sentinel pour voir les agents à appeler.", tag: "sentinel" };
    const cfg = await config();
    if (cfg && cfg.url && cfg.key) {
      try {
        const r = await fetch(cfg.url + "/pending", { headers: { "x-key": cfg.key }, cache: "no-store" });
        if (r.ok) msg = await r.json();
      } catch (err) {}
    }
    await self.registration.showNotification(msg.title, {
      body: msg.body, tag: msg.tag, renotify: true, requireInteraction: true,
      icon: "icon-192.png", badge: "badge-96.png", vibrate: [400, 150, 400, 150, 400],
      data: { url: "./" }
    });
  })());
});

self.addEventListener("notificationclick", e => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then(list => {
    for (const c of list) { if ("focus" in c) return c.focus(); }
    return self.clients.openWindow("./");
  }));
});
