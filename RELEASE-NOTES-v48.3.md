# Quiet Remote v48.3 — release notes

## Download

**[Quiet-Remote-v48.3.apk](https://github.com/Tsonnn/quiet-remote/releases/download/v48.3/Quiet-Remote-v48.3.apk)** — tap to download, then open it to install.

---

Free, ad-free Android remote for Vizio SmartCast TVs. No ads, no
account, no login, no monthly fee, no cloud — your phone talks to
the TV directly over your home WiFi.

> Quiet Remote is an independent, open-source project. It is not
> affiliated with, endorsed by, or connected to VIZIO, Inc. VIZIO and
> SmartCast are trademarks of VIZIO, Inc.

## What's new since v48.2

- **Settings, organized.** Settings is six outlined boxes now —
  TVs on this WiFi, Pair with the TV, Per-TV settings, TV address,
  This TV, and App settings at the very bottom. The Per-TV box
  header names the TV its controls follow (for example "Per-TV
  settings — Bedroom Twelve"), so you always know which TV you are
  adjusting. The version line sits at the bottom of the App
  settings box. Same buttons, same behaviour — only the grouping
  moved.

Everything from v48.2 is still here: the touchpad remote, the
volume slider that jumps straight to the level you pick, the phone
keyboard, the power button that tells the truth, the Chromecast
strip, ads that skip themselves, the home-screen widget, Goodnight,
per-TV settings, the update check, and a fresh install that starts
with zero TVs.

## Install

1. Download `Quiet-Remote-v48.3.apk` above.
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
- Home WiFi only. Pairings live on your phone and are deleted if you
  uninstall the app.

## Verify the download

- File: `Quiet-Remote-v48.3.apk` (337,728 bytes)
- SHA-256:
  `3c5de5d25b3e0e4a15a445ccc3e0bf32e5fd69b884d2e2368a33ebcbd90c0fbc`
- Package: `com.tom.tvremote`, version 48.3 (versionCode 89)
