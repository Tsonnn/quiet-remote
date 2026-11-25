# Quiet Remote v48.2 — release notes

## Download

**[Quiet-Remote-v48.2.apk](https://github.com/Tsonnn/quiet-remote/releases/download/v48.2/Quiet-Remote-v48.2.apk)** — tap to download, then open it to install.

---

Free, ad-free Android remote for Vizio SmartCast TVs. No ads, no
account, no login, no monthly fee, no cloud — your phone talks to
the TV directly over your home WiFi.

> Quiet Remote is an independent, open-source project. It is not
> affiliated with, endorsed by, or connected to VIZIO, Inc. VIZIO and
> SmartCast are trademarks of VIZIO, Inc.

## What's new since v48.0

- **Silver widget.** The widget's buttons are now the silver tone
  from the app icon — the white pills are gone, replaced by a silver
  gradient with a thin edge so they still read as buttons. Power
  keeps its red/green state colour.
- **Update check.** A "Check for updates" switch in Settings (on by
  default). When the app starts, it quietly asks GitHub for the
  latest release, and when a newer version exists it shows a notice
  with a Download button — one tap downloads the new version and
  opens the installer. No notice when you are already up to date.

Everything from v48.0 is still here: the touchpad remote, the
volume slider that jumps straight to the level you pick, the phone
keyboard, the power button that tells the truth, the Chromecast
strip, ads that skip themselves, the home-screen widget, Goodnight,
per-TV settings, and a fresh install that starts with zero TVs.

## Install

1. Download `Quiet-Remote-v48.2.apk` above.
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

- File: `Quiet-Remote-v48.2.apk` (337,728 bytes)
- SHA-256:
  `85d55013cc10b561e6c71bc3dcf38fdc7e6900e04b361e1c46ef08bb17c2f880`
- Package: `com.tom.tvremote`, version 48.2 (versionCode 88)
