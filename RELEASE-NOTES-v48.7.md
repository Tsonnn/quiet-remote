# Quiet Remote v48.7 — release notes

## Download

**[Quiet-Remote-v48.7.apk](https://github.com/Tsonnn/quiet-remote/releases/download/v48.7/Quiet-Remote-v48.7.apk)** — tap to download, then open it to install.

---

Free, ad-free Android remote for Vizio SmartCast TVs. No ads, no
account, no login, no monthly fee, no cloud — your phone talks to
the TV directly over your home WiFi.

> Quiet Remote is an independent, open-source project. It is not
> affiliated with, endorsed by, or connected to VIZIO, Inc. VIZIO and
> SmartCast are trademarks of VIZIO, Inc.

## What's new since v48.6

- **"Next input" works now.** The button sent the input change by
  name in v48.6, and the TV refused the switch. Now it sends the
  TV remote's own INPUT key instead — the TV owns the cycle.
  Tap once and the input list opens on the TV with the current
  input highlighted; tap again to move down one, and keep tapping
  until the input you want is highlighted. Then press Select to
  switch at once, or just wait 5 seconds and it switches by
  itself. The cycle follows the TV's own list.

Everything from v48.6 is still here: the touchpad remote, the
volume slider that jumps straight to the level you pick, the phone
keyboard, the power button that tells the truth, the Chromecast
strip, ads that skip themselves, the home-screen widget, Goodnight,
per-TV settings, the update check, Settings in six boxes, and a
fresh install that starts with zero TVs.

## Install

1. Download `Quiet-Remote-v48.7.apk` above.
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

- File: `Quiet-Remote-v48.7.apk` (341,824 bytes)
- SHA-256:
  `6cdd2c9fd0965f67f857cd86609cdc51f3ac4adb4a0de69f96e190704b1e07ef`
- Package: `com.tom.tvremote`, version 48.7 (versionCode 93)
