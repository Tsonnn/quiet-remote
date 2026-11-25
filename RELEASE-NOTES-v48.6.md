# Quiet Remote v48.6 — release notes

## Download

**[Quiet-Remote-v48.6.apk](https://github.com/Tsonnn/quiet-remote/releases/download/v48.6/Quiet-Remote-v48.6.apk)** — tap to download, then open it to install.

---

Free, ad-free Android remote for Vizio SmartCast TVs. No ads, no
account, no login, no monthly fee, no cloud — your phone talks to
the TV directly over your home WiFi.

> Quiet Remote is an independent, open-source project. It is not
> affiliated with, endorsed by, or connected to VIZIO, Inc. VIZIO and
> SmartCast are trademarks of VIZIO, Inc.

## What's new since v48.3

- **"Next input" button.** A third quiet control under the volume
  row, next to CC and Sleep. It reads "Next input" — tap it to step
  to the next input, and keep tapping until the input you want
  appears. It cycles every input, including ones with nothing
  plugged in, and wraps back to the start.
- **Check for updates, on demand.** Settings has a CHECK FOR
  UPDATES NOW button: it checks GitHub right now, even with the
  "Check for updates" switch off, and brings back an update you
  dismissed. The startup check is unchanged.
- **Volume slider, tidied.** The thumb is smaller and shows just
  the number, with no percent sign. The track is thicker, and the
  CC / Sleep / Next input row has more room above it.
- **Settings, tidied.** RUN FIRST-TIME SETUP AGAIN moved from the
  This TV box to the bottom of the App settings box, after the
  update check. The version line is still last.

Everything from v48.3 is still here: the touchpad remote, the
volume slider that jumps straight to the level you pick, the phone
keyboard, the power button that tells the truth, the Chromecast
strip, ads that skip themselves, the home-screen widget, Goodnight,
per-TV settings, the update check, Settings in six boxes, and a
fresh install that starts with zero TVs.

## Install

1. Download `Quiet-Remote-v48.6.apk` above.
2. On your phone, allow installing from unknown sources, then open
   the APK to install it.
3. Open the app and follow the three setup steps: WiFi first, FIND MY
   TVs, then tap a TV, tap PAIR, and type the 4-digit PIN from the TV
   screen. Turn the TV on with its regular remote first — you can't
   see the PIN while the TV is off.

Tip: for power-on over WiFi, put the TV in Quick Start mode
(Settings > System > Power Mode > Quick Start). In Eco Mode the TV
is off the network when it is off.

## Honest notes

- **Netflix has not been confirmed working yet.** The button uses
  the same launch mechanism as YouTube, but no one has confirmed it
  opens Netflix on a real TV yet. If it does nothing on your TV,
  please open an issue with your TV's model and the exact message
  the app shows.
- **Ad skip depends on the TV's receiver.** Skip Ad sends the
  standard skip command to the TV's built-in Cast receiver, and
  the receiver ignores it when no skippable ad is playing. Some
  receivers ignore it even during a skippable ad — that is the
  receiver, not the app.
- **Tested on two SmartCast models so far.** It should work on other
  SmartCast models; please report yours — working or not — in an
  issue.
- **Android only.** There is no iPhone version.
- **Google Play Protect may warn that the app is harmful.** The app
  is sideloaded, not from the Play Store, and it can install its own
  updates — Play Protect flags that combination. It is the same app
  from this repo; tap "Install anyway".
- **The update check contacts GitHub.** On startup, and when you tap
  CHECK FOR UPDATES NOW, the app asks GitHub for the latest release
  — the only time anything leaves your phone. No account, no
  tracking.
- Home WiFi only. Pairings live on your phone and are deleted if you
  uninstall the app.

## Verify the download

- File: `Quiet-Remote-v48.6.apk` (341,824 bytes)
- SHA-256:
  `8fb621f2211649aebd815ec79cee3a557811c65b95d215666e0cd8b1b7907ef6`
- Package: `com.tom.tvremote`, version 48.6 (versionCode 92)
