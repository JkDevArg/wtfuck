// El service worker de la versión web. Hace UNA cosa: mostrar el aviso cuando
// llega un Web Push con el navegador cerrado (W4c), y abrir la web al tocarlo.
//
// ## Lo que no hace, a propósito
//
// No tiene `fetch`: no intercepta peticiones ni guarda nada en caché. Así la
// página sigue viniendo SIEMPRE del servidor (ver "modelo de confianza" en
// docs/12-VERSION-WEB.md): un service worker con caché sería una copia del
// código que sobrevive a una actualización del servidor, y lo que haya en esa
// copia es lo que maneja las claves.
//
// No abre la bóveda ni descifra nada. El aviso llega VACÍO (ver WebPush.kt):
// el servicio de push de Google, Mozilla, Apple o Microsoft no sabe quién
// escribió ni dónde, y por eso este archivo tampoco. Muestra un texto fijo y la
// página, al abrirse, baja y descifra lo pendiente. Es la misma regla que en la
// app: el aviso nunca lleva el texto.

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('push', (e) => {
  e.waitUntil((async () => {
    // Con la web a la vista no hace falta: la página tiene el socket abierto y
    // avisa ella, con más detalle. Chrome no exige el aviso en ese caso.
    const ventanas = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (ventanas.some((v) => v.visibilityState === 'visible' && v.focused)) return;
    await self.registration.showNotification('wtfuck', {
      body: 'Tienes algo nuevo',
      // Uno solo aunque lleguen varios: el segundo no dice nada que el primero no.
      tag: 'wtfuck',
      renotify: false,
    });
  })());
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil((async () => {
    const base = self.registration.scope;
    const ventanas = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const abierta = ventanas.find((v) => v.url.startsWith(base));
    if (abierta) return abierta.focus();
    return self.clients.openWindow(base);
  })());
});
