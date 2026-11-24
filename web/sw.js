// Service worker: receives push alerts while the app is closed.
// Messages carry a `webpush.notification` block, which the Firebase SDK displays,
// and tapping it opens the District link (fcmOptions.link).
importScripts('config.js');

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

const cfg = self.DOOM_CONFIG;
if (cfg && cfg.firebase && cfg.firebase.appId) {
  importScripts(
    'https://www.gstatic.com/firebasejs/11.0.2/firebase-app-compat.js',
    'https://www.gstatic.com/firebasejs/11.0.2/firebase-messaging-compat.js',
  );
  firebase.initializeApp(cfg.firebase);
  firebase.messaging();
}

// Fallback for a click on a notification the SDK didn't handle.
self.addEventListener('notificationclick', (event) => {
  const link = event.notification?.data?.FCM_MSG?.fcmOptions?.link || cfg.districtUrl;
  event.notification.close();
  event.waitUntil(self.clients.openWindow(link));
});
