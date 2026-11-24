# Quiet Remote v48.0 — release notes

## Download

**[Quiet-Remote-v48.0.apk](https://github.com/Tsonnn/quiet-remote/releases/download/v48.0/Quiet-Remote-v48.0.apk)** — tap to download, then open it to install.

---

Free, ad-free Android remote for Vizio SmartCast TVs. No ads, no
account, no login, no monthly fee, no cloud — your phone talks to
the TV directly over your home WiFi.

> Quiet Remote is an independent, open-source project. It is not
> affiliated with, endorsed by, or connected to VIZIO, Inc. VIZIO and
> SmartCast are trademarks of VIZIO, Inc.

## What's new since v47.2

- **Chromecast strip.** When something is casting to the selected TV, a mini-player strip appears under the TV row: what is playing, Pause, Stop, and Skip Ad — plus a countdown while an ad plays. The TV's pill shows a Cast icon while it is casting.
- **Ads skip themselves.** While the cast strip is open, a skippable ad is skipped by itself — no tap needed. It is on by default; turn it off in Settings. The Skip Ad button and double-tap still work by hand.
- **Home-screen widget.** Power (labelled with the TV's name), volume − / +, and a big Skip Ad button, all without opening the app. Tap the magnifying glass to open the remote.
- **Goodnight.** One quiet tap turns off every TV that is on.
- **Phone volume keys drive the TV volume** while the app is open.
- **Closed captions and sleep timer on the main screen** — two quiet controls that follow the selected TV.
- **It picks the TV that is on.** On launch, when more than one TV is paired, the app selects the TV that is on. Your own tap always wins.
- **Double-tap to skip.** Double-tap the touchpad while casting to skip an ad.

Everything from v47.2 is still here: the touchpad remote, the
volume slider that jumps straight to the level you pick, the phone
keyboard, the power button that tells the truth, per-TV settings,
and a fresh install that starts with zero TVs.

## Install

1. Download `Quiet-Remote-v48.0.apk` above.
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
- Home WiFi only. Pairings live on your phone and are deleted if you
  uninstall the app.

## Verify the download

- File: `Quiet-Remote-v48.0.apk` (333,632 bytes)
- SHA-256:
  `02b002a6fe1da0c136488b422a04eabc58c62b58da251e5dc048afdac21815fa`
- Package: `com.tom.tvremote`, version 48.0 (versionCode 86)
