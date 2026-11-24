# Doomsday Alert

A one-screen Android app for friends that **rings and vibrates until you open it** when District opens Avengers: Doomsday tickets at a new theatre or on a new date in Chennai, Tamil included. One tap takes you straight to the Doomsday page on District.

```
 District (district.in)
        â”‚  every ~60 s
        â–¼
 watcher/  (Node, hosted on Railway)
        â”‚  new theatre / date / language  â†’ ALARM
        â”‚  extra shows at a known theatre â†’ normal notification
        â–¼
 Firebase Cloud Messaging  topic "doomsday-chennai"
        â–¼
 app/  (Android, Kotlin + Compose): Doom sigil home screen, red full-screen alarm
```

## For friends: install

1. Open **Releases â†’ latest â†’ DoomsdayAlert.apk** on your Android phone, or use the direct link https://github.com/zorosanjiluffy/doomsday-alert/releases/latest/download/DoomsdayAlert.apk.
2. Allow "Install unknown apps" if Android asks.
3. Open the app and fix every item in **Finish setup** (notifications, full-screen alerts, battery: unrestricted).
4. Tap **Test the alarm** and lock your phone. It should ring and buzz until you touch the screen.

Xiaomi, Redmi, POCO, Oppo, Vivo, Realme and iQOO phones also need: Settings â†’ Apps â†’ Doomsday Alert â†’ **Autostart ON**, and battery set to **No restrictions**.

iPhone: open https://zorosanjiluffy.github.io/doomsday-alert/ in Safari, tap Share → Add to Home Screen, open it from the Home Screen and tap Turn on alerts (iOS 16.4+). iPhone gets one alert, not a continuous alarm (Apple limitation).

## watcher/

`node watcher.js` runs it forever. `--once` does one check, and `--test` sends a test alarm to every phone.

| Env var | Purpose |
|---|---|
| `FIREBASE_SERVICE_ACCOUNT` | Firebase service-account JSON, either the whole JSON or a path to it. Enables app push |
| `STATE_DIR` | Where `state.json` lives. On Railway, mount a volume at `/data` and set this to `/data` |
| `ADMIN_KEY` | Secret for `POST /test?key=â€¦`, which sends a test alarm to every phone |
| `NTFY_TOPIC` | Optional ntfy topic for iPhone or backup alerts. Set it to an empty value to disable |
| `PORT` | Set automatically by Railway; serves `/status` for the app's home screen |

Filters such as theatres, formats and languages are set in `watcher/config.json`.

## app/

```
cd app
./gradlew assembleRelease
```

The build needs `app/app/google-services.json` from Firebase. Without it the app still builds, but push is off. CI writes it from secrets.

GitHub secrets used by `.github/workflows/build-apk.yml`:

| Secret / variable | Value |
|---|---|
| `GOOGLE_SERVICES_JSON` (secret) | Contents of `google-services.json` |
| `KEYSTORE_BASE64` (secret) | `base64` of `app/app/doomsday.jks` |
| `KEYSTORE_PASSWORD` (secret) | Contents of `app/app/keystore.password` |
| `STATUS_URL` (variable) | `https://<railway-domain>/status` |

Every push to `main` that touches `app/` builds a signed APK and publishes it as the latest release. Always sign with the same keystore, or friends can't install updates over the old version.

Fan-made, and not affiliated with Marvel, Disney or District.
