# Quiet Remote v47.2 — release notes

Free, ad-free Android remote for Vizio SmartCast TVs. No ads, no
account, no login, no monthly fee, no cloud — your phone talks to
the TV directly over your home WiFi.

> Quiet Remote is an independent, open-source project. It is not
> affiliated with, endorsed by, or connected to VIZIO, Inc. VIZIO and
> SmartCast are trademarks of VIZIO, Inc.

## What's in this release

- **Power button that tells the truth.** Green when the TV is on,
  red when it is off, grey while the remote cannot tell yet. It
  flips the moment you tap it, and the app opens fast by showing
  the last-known state at once.
- **Phone keyboard, finished.** The keyboard pops up by itself
  when you open it. Send types the text, and in YouTube the search
  runs by itself. Search presses Enter, DEL deletes a character,
  and Select picks the highlighted key on the TV.
- **Double-tap to select.** Double-tap anywhere on the touchpad to
  press OK — a single tap does nothing, so a tap while swiping will
  not select by accident.
- **Apple-style remote:** touchpad swipe + tap, volume slider that
  jumps straight to the level you pick, speaker icon to mute, power,
  home, menu, back, and playback (rewind / play-pause / fast-forward).
- **YouTube and Netflix buttons** that open the apps on the TV.
- **Per-TV settings:** sleep timer, picture mode, closed captions.
- **Setup:** WiFi-first first-time setup — check WiFi, find your TVs,
  pair with the 4-digit PIN from the TV screen.
- **Reliable names:** the scan reads each TV's identity from the TV
  itself, even in Quick Start standby, and the list updates in place
  when a name arrives.
- **Remove a TV** in Settings with REMOVE THIS TV. A TV the app
  cannot find stays listed, dimmed — the app never removes one by
  itself.
- **Every button visibly presses** (brief darken + shrink, springs back).
- **Settings shows the real version** at the bottom.
- **The Q icon:** a black touchpad disc as a Q on silver, drawn by hand.
- **A fresh install starts with zero TVs** — nothing about any TV is
  baked into the app.

This is the build tested through the v43–v47.2 keyboard and power
fixes: the keyboard popping up by itself, Send running a YouTube
search by itself, and the power button flipping the moment it is
tapped.

## Install

1. Download `Quiet-Remote-v47.2.apk` below.
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
- **Tested on two SmartCast models so far.** It should work on other
  SmartCast models; please report yours — working or not — in an
  issue.
- **Android only.** There is no iPhone version.
- Home WiFi only. Pairings live on your phone and are deleted if you
  uninstall the app.

## Verify the download

- File: `Quiet-Remote-v47.2.apk` (304,349 bytes)
- SHA-256:
  `9e8dcb2a6e27d668d5aaa2d0941712a09c756079430232228b762472debbf651`
- Package: `com.tom.tvremote`, version 47.2
