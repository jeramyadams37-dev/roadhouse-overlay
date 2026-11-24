# RoadHouse Overlay v1

Standalone, voice-first driver assistant under the RoadHouse brand.
Package `com.atothei.roadhouse.overlay`, app name "RoadHouse Overlay".

It sits on top of the delivery apps (Walmart Spark, DoorDash, Instacart) and
helps hands-free: it reads the delivery app's screen, checks the offer in
dollars per mile, answers questions out loud, navigates, and logs deliveries.

**It never auto-accepts or auto-declines orders.** It advises; the driver
decides. It never touches driver accounts or credentials.

## What v1 does

- **Screen reading** — accessibility service walks the foreground app's node
  tree (same proven shape as the AI Overlay APK shell: `getScreenContext`,
  `extractText`). Read-only: it never clicks or types in any app.
- **Floating mic button** — drawn over any app (`SYSTEM_ALERT_WINDOW`).
  Tap to start listening, long-press for an instant offer check, drag to move.
- **Voice in / voice out** — Android SpeechRecognizer + TextToSpeech.
  Say "check this offer", "navigate", "log this delivery", or ask anything
  about the screen ("how much is this paying?", "what's the address?").
- **Offer check in code** — pay and miles are parsed from the screen text
  and the $/mile math runs in code against an adjustable floor (default
  $1.00/mile). The model never decides the verdict: "$2.66 a mile — take it"
  / "$0.60 a mile — skip it".
- **Navigate** — pulls the delivery address off the screen and opens Google
  Maps navigation.
- **Delivery log** — "log this delivery" saves date, platform, pay, miles,
  address to a local JSON log (`delivery_log.json` in the app's files dir),
  in a RoadHouse-compatible shape for mileage/tax tracking. Export it from
  Settings.
- **Gemini Q&A** — open questions go to Gemini `gemini-3.8-flash` (free
  tier) with a driver-aware prompt and the current screen text.
- **Settings** — Gemini API key (bring your own, stored in
  EncryptedSharedPreferences), $/mile floor, voice on/off, overlay button
  size, log export/clear, key test button.
- **First-run flow** — overlay permission → accessibility → microphone →
  Gemini key → floor → start. Restarts itself after reboot once setup is
  done.

## What's new in v1.4 — five features

- **Offer memory** — every offer checked is saved locally (pay, miles,
  $/mile, store, date, verdict). After a check the card asks "Took it" /
  "Skipped it" — tap one, or say "took it" / "skipped it". Next time the
  same store's offer appears, the card and the voice say so: "You skipped
  this offer last time — $12.50 for 8.2 miles." Same store, different
  numbers still warns: "You've seen this store before."
- **Earnings tally** — say "how much today" / "how much this week" /
  "how much this month". The math runs in code on the delivery log:
  total pay, total miles, deliveries, average $/mile. Nothing logged for
  the range says so plainly. Also an Earnings button on the card.
- **Pre-shift check** — say "pre-shift check" or tap the card button. It
  walks through three questions by voice: vehicle OK, fuel level, which
  apps you're running — then fetches the weather for your city (free
  Open-Meteo, no key, fail-soft) and speaks a short summary. The apps
  answer is what YOU say; the overlay cannot detect another app's online
  status and never pretends to. The check is saved locally. Say "cancel"
  to stop mid-check.
- **Store notes** — say "remember: this Walmart's pickup is at the back"
  to save a note for the store on screen, or "what do I know about this
  store" to hear it back. Notes come back automatically on the next offer
  check for that store. "Forget that note" deletes them. Notes live in
  local JSON; manage/export them in Settings.
- **Batch math** — Spark batches with several drops: the offer check now
  shows the batch total (pay, miles, $/mile, verdict on the whole batch)
  plus a per-stop breakdown (each stop's miles where the screen shows
  them). Single-stop offers work exactly as before.

All five are on-demand only: no polling, no timers, no background reads,
no background Gemini calls. Earnings and batch math are code math, never
Gemini. Offer memory, notes, and pre-shift records are local JSON files
in the app's private files dir — nothing leaves the phone.

## Privacy

- Screen text goes to Google's Gemini API **only**, and only as part of a
  question the driver asked. It is never stored and never sent anywhere else.
- No analytics, no backend, no account. The Gemini key lives encrypted on
  the device. No key, token, or secret is in the code or the APK.

## Push from Termux

Create the repo first (once):

```
gh repo create jeramyadams37-dev/roadhouse-overlay --public --description "RoadHouse Overlay — voice-first driver assistant"
```

Then push this project (run from the folder above `roadhouse-overlay/`):

```
rm -rf roadhouse-overlay
unzip ~/storage/downloads/roadhouse-overlay-v1.zip
cd roadhouse-overlay
git init
git add .
git commit -m "RoadHouse Overlay v1"
git branch -M main
git remote add origin https://github.com/jeramyadams37-dev/roadhouse-overlay.git
git push -u origin main
```

GitHub Actions (`.github/workflows/build-apk.yml`) builds the debug APK on
every push to `main`. No keystore is needed for v1 — it is a debug build.

## Download + install the APK

After the Actions run goes green:

```
gh run download -n RoadHouse-Overlay-debug-apk --repo jeramyadams37-dev/roadhouse-overlay
```

That drops `app-debug.apk` in the current folder. Install it:

```
termux-open app-debug.apk
```

If nothing pops up, tap the APK in the Files app instead. (Recurring quirk:
`termux-open` sometimes does not surface the install prompt on the first try.)

## First-run setup on the phone

1. Open **RoadHouse Overlay**.
2. Tap **1. Allow overlay permission** → allow "draw over other apps".
3. Tap **2. Turn on screen reading** → find **RoadHouse Overlay** in the
   Accessibility list and turn it on. (This is how it reads the delivery
   app's screen. It only reads — it never taps or types.)
4. Tap **3. Allow microphone**.
5. Tap **4. Settings** → paste your Gemini API key (free tier works), set
   your $/mile floor (default 1.00), tap **Save settings**. Tap **Test
   Gemini key** — it should say the key works.
6. Tap **5. Start overlay**. The amber mic button appears. Switch to Spark,
   DoorDash, or Instacart and tap it.

## What to test first

1. Open Spark (or DoorDash / Instacart) to an offer screen.
2. Tap the mic button and say **"check this offer"** — it should speak the
   pay, miles, $/mile, and take-it-or-skip-it verdict, and show the same on
   the card. Long-press the button does the same check silently.
3. Say **"navigate"** — Google Maps should open with the delivery address.
4. Say **"log this delivery"** — Settings should show "Deliveries logged: 1".
5. Say **"what's the address?"** or any question about the screen — Gemini
   answers out loud and on the card.
6. Drag the mic button — it should move and stay where you put it.
7. Offer memory: check an offer, tap **Took it** or **Skipped it**, then
   check the same offer again — the card should say you took/skipped it
   last time.
8. Say **"how much today"** — it should read back the logged deliveries,
   pay, miles, and average $/mile (log one first, or it says nothing is
   logged yet).
9. Say **"pre-shift check"** — answer the three questions by voice; it
   should finish with a summary including the weather.
10. Say **"remember: pickup is at the back"** on a store screen, then
    **"what do I know about this store"** — it should read the note back.
11. On a multi-stop Spark batch, **"check this offer"** should show the
    batch total plus a per-stop breakdown.

## Troubleshooting

- **No mic button:** overlay permission off, or the service was killed.
  Reopen the app and tap Start overlay. Lock the app in the recents screen
  so Android does not kill it for memory (same low-memory behaviour seen
  with StoreForge — reopen from the app icon, not recents).
- **"I can't see a pay amount or miles":** the offer screen was not the
  foreground screen, or the app shows the offer as an image. Open the offer
  details first, then check again.
- **Speech recognition unavailable:** the Google app must be installed and
  updated; SpeechRecognizer depends on it.
- **Gemini errors:** check the key with the Test button in Settings. A 400
  "API key not valid" means a bad or mistyped key. Free-tier rate limits
  show as 429 — wait a minute and retry.
- **Card buttons don't respond:** the card is focusable only while shown.
  Hide it and tap the mic button again.

## Build details

- Native Android, Kotlin, AGP 8.10.1, Gradle 8.13, JDK 17.
- minSdk 26, targetSdk 36, compileSdk 36.
- Dependencies: androidx core-ktx, appcompat, material,
  security-crypto (EncryptedSharedPreferences). Gemini is plain
  HttpURLConnection — no extra networking dependency.
- The GitHub Actions build is the compile check: this project was written
  and verified on a machine with no Android SDK, so the first green Actions
  run is the first real compile.

## Known v1 limits

- Voice is tap-to-talk, not always-on listening (no hotword engine).
- The $/mile parser reads text the accessibility service exposes; offers
  rendered as images with no text nodes cannot be parsed.
- The delivery log is local JSON; RoadHouse import is a later step.
- Foreground service type is `specialUse`; if the app ever goes to the Play
  Store, that needs a Play Console declaration.
