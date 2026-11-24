// Public settings for the web app. These values are safe to publish:
// Firebase web config and the VAPID public key are designed to be public.
// Loaded by both the page and the service worker.
self.DOOM_CONFIG = {
  firebase: {
    apiKey: 'AIzaSyAxbLDbjDWLphys947LcPTe97kfZVhSk5Y',
    authDomain: 'doomsday-6fbb2.firebaseapp.com',
    projectId: 'doomsday-6fbb2',
    storageBucket: 'doomsday-6fbb2.firebasestorage.app',
    messagingSenderId: '645877001718',
    appId: '1:645877001718:web:9a3e77286e5a09ce8bebd7',
  },
  // Firebase console → Project settings → Cloud Messaging → Web Push certificates → Key pair
  vapidKey: 'BLJWu389nbaDnAbUHlO_tMXgtqFLt_KEbzZN1pbHA-K838S1Q41l6c0pMMn8K8BTLTChOdVZVMMxB_WP5J9zoQM',
  // The hosted watcher (Railway), without a trailing slash, e.g. https://doomsday-watcher.up.railway.app
  watcherUrl: '',
  apkUrl: 'https://github.com/zorosanjiluffy/doomsday-alert/releases/latest/download/DoomsdayAlert.apk',
  districtUrl: 'https://www.district.in/movies/avengers-doomsday-movie-tickets-in-chennai-MV176735',
  firstShow: '2026-12-17T06:00:00+05:30',
};
