// Public settings for the web app. These values are safe to publish:
// Firebase web config and the VAPID public key are designed to be public.
// Loaded by both the page and the service worker.
self.DOOM_CONFIG = {
  firebase: {
    apiKey: '',
    authDomain: '',
    projectId: '',
    messagingSenderId: '',
    appId: '',
  },
  // Firebase console → Project settings → Cloud Messaging → Web Push certificates → Key pair
  vapidKey: '',
  // The hosted watcher (Railway), without a trailing slash, e.g. https://doomsday-watcher.up.railway.app
  watcherUrl: '',
  apkUrl: 'https://github.com/zorosanjiluffy/doomsday-alert/releases/latest/download/DoomsdayAlert.apk',
  districtUrl: 'https://www.district.in/movies/avengers-doomsday-movie-tickets-in-chennai-MV176735',
  firstShow: '2026-12-17T06:00:00+05:30',
};
