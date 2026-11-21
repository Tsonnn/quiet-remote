# Quiet Remote — User Manual

**Draft — for the launch version (v29).** Quiet Remote is a free Android remote for Vizio SmartCast TVs. No ads, no account, no login, no cloud, no monthly fee. Your phone talks to your TV directly over your home WiFi.

## To check on the live app

These are in the manual but have not been confirmed on a real TV yet:

- The Netflix button opening Netflix (new — first live tap is the real test).
- The YouTube button opening YouTube (same — first live tap is the real test).
- The name under the app icon on the home screen reading "Quiet Remote".
- The exact words Android shows when you install the APK (the allow-install prompt).
- The TV row scrolling sideways with 4 or 5 TVs, and dimmed pills for TVs not on this WiFi (in the v29 code, not yet tried live).
- A TV that shows only its model number (for example "E50-E1") picking up its friendly name ("Living Room") after the next scan.

## What you need

1. An Android phone — Android 8 or newer.
2. A Vizio SmartCast TV.
3. Your phone and your TV on the **same home WiFi**.
4. Your TV set to **Quick Start** mode: on the TV, go to **Settings > System > Power Mode > Quick Start**. In Eco Mode the TV is off the network when it is off, so the app cannot find it or turn it on.

There is no iPhone version yet — Android only for now.

## Installing it

1. On your phone, open the Quiet Remote page on GitHub: `https://github.com/Tsonnn/quiet-remote`
2. Download the APK file from the latest release.
3. Open the downloaded file to install it.
4. Your phone may ask you to allow installing apps from your browser or files app. Allow it just for this install.

Quiet Remote is not in the Play Store. There is nothing else to install, and no account to create.

## First-time setup

The first time you open the app, it walks you through three steps. There is no account, no login, and nothing to sign up for.

### Step 1 — Check WiFi first

The app checks that your phone is on WiFi. If it says **Not on WiFi yet**, connect your phone to your home WiFi first, then tap **CHECK AGAIN**. The **FIND MY TVs** button stays off until WiFi is confirmed.

### Step 2 — Find your TVs

1. Tap **FIND MY TVs**. The app looks for your TVs on this WiFi. It also scans by itself when it opens, and if a TV is missing it tries once more by itself after a couple of seconds.
2. Your TVs appear as buttons in a list. Tap yours.
3. A TV shows its friendly name when it has one (for example "Living Room"), its model when only the model is known (for example "E50-E1"), or "Name unknown" with its address when it reports neither. If it says "Name unknown", tap it, then tap **PAIR** — the PIN will appear on that TV's screen, and that tells you which TV you picked.

A fresh install starts with zero TVs in it. The app only finds TVs on your own WiFi — nothing about anyone else's TVs is baked in.

### Step 3 — Pair with the TV

1. **Turn the TV on with its regular remote first.** The 4-digit PIN shows on the TV screen, and you can't see it while the TV is off. The app cannot turn the TV on before it is paired.
2. Tap **PAIR**.
3. A 4-digit PIN appears on the TV screen. Type it into the **PIN from the TV screen (4 digits)** box.
4. Tap **FINISH PAIRING**.
5. Tap **DONE — OPEN THE REMOTE**.

Each TV pairs separately — pairing one TV does not pair the others. Your TV stays paired after that, even if its WiFi address changes.

## Adding another TV

1. In **Settings** (the gear icon), tap **Refresh — find TVs again**. The TVs it finds appear in a list under Refresh — tap the other TV to add it, then pair it below in **Pair with the TV**.
2. Or in setup, tap **ADD ANOTHER TV**, tap the other TV in the list, and pair it the same way: **PAIR**, type the PIN, **FINISH PAIRING**.

A TV only joins the remote when you tap it — a TV that showed up in setup but was never tapped is not saved, and Refresh only counts the TVs already saved until you tap the new one in the list.

To run setup again later, open **Settings** (the gear icon) and tap **RUN FIRST-TIME SETUP AGAIN**. This never unpairs anything.

## Removing a TV

A TV the app cannot find stays on the top pills, dimmed — the app never removes a TV by itself. To take one off:

1. Tap its pill at the top of the remote to select it.
2. Open **Settings** (the gear icon) and tap **REMOVE THIS TV FROM THE REMOTE**.
3. Its pill is gone from the top list, along with its pairing and saved settings. The next TV is selected.

A removed TV is not gone for good — tap **Refresh — find TVs again** and it shows up in the found list, ready to be tapped and paired like new. **FORGET PAIRING (UNPAIR THIS TV)** is different: it only clears the pairing and leaves the TV on the pills.

## Using the remote

### Switching TVs

Your TVs are the pills across the top of the remote. Tap one to switch to it — the black pill is the TV you are controlling right now.

- If you have more TVs than fit across the top, swipe the row sideways to see the rest. The settings gear and the power button stay put at the right end. The power button's colour tells you the TV's state: green when the TV is on, red when it is off, grey while the remote cannot tell yet.
- A **dimmed** pill means that TV was not found on this WiFi in the latest scan. You can still tap it, and the app will tell you it is not on this WiFi. Nothing is dimmed before the first scan runs.
- There is no limit on how many TVs the app can remember.

### The touchpad

The big black circle is the touchpad. It sits just above the buttons, at the bottom of the screen, so your thumb can reach it one-handed without stretching to the top.

- **Swipe** to move around the TV's menus. The whole area above the buttons is the swipe surface — not just the black circle. Swipe or double-tap anywhere up there, on the ring or beside it, without looking at the remote or aiming. The buttons below still work as buttons.
- **Double-tap anywhere above the buttons** to select — it is the OK button. You do not need to look at the remote: swipe and double-tap while watching the TV. A single tap does nothing, so a tap while swiping will not select by accident. The centre dips briefly when a double-tap selects.
- The centre button is raised and shaded like a real button, and its colour matches the TV too: green when the TV is on, red when it is off, silver while the remote cannot tell yet.
- The buttons sit at the bottom of the screen, leaving open room around the touchpad for swipes.
- The hint in the middle says "Swipe to move / Double-tap to select".

### The buttons

- **Menu**, **Home**, **Back** — the same as the buttons on your regular remote.
- **Keyboard icon** (the wide keyboard picture, next to Back) — opens the phone keyboard (see below).
- **REW**, **Play/Pause**, **FF** — rewind, play or pause, fast-forward. The Play button changes to Pause after you tap it.
- **YouTube** (red play-button logo) — opens the YouTube app on the selected TV.
- **Netflix** (black circle with the red N) — opens the Netflix app on the selected TV. Netflix is new: if it does not open on your TV, open Netflix from the TV's home screen instead and let us know.
- **Power** (red button, top right) — turns the selected TV on or off. Turning on over WiFi only works if the TV is in Quick Start mode.
- **Settings** (gear icon, top right) — opens Settings.

### Volume

- Drag the **slider** to the level you want. The number sits in the thumb (for example "50%"). The app sets the volume directly — no repeated tapping.
- Tap the **speaker icon** to mute, and tap it again to unmute.

## Typing with the phone keyboard

Typing on a TV with the regular remote is slow. Use your phone instead.

1. On the TV, tap the search box first so it is ready for typing (for example the YouTube search box).
2. On the remote, tap the **keyboard icon**. A small box opens on top of the remote and your phone's keyboard pops up by itself — just start typing.
3. Type, then tap **Send**. The text goes to the TV and the box stays open, so you can type more or tap **Select** next. In YouTube, the search runs by itself as soon as you tap Send — the results just show up.
4. **Search** presses Enter. It does nothing in YouTube — YouTube ignores Enter — but other apps may use it. If a search doesn't run on your TV, highlight **SEARCH** on the TV keyboard and tap **Select** instead. **DEL** deletes one character on the TV. **Select** presses whatever key is highlighted on the TV — on YouTube's keyboard that types the highlighted letter. **Close** closes the keyboard.

Only plain letters, numbers and symbols are sent. Accents and emoji are left out.

## Settings (the gear icon)

- **Sleep Timer** — Off, 30m, 60m, 90m, or 120m. The option that is on is black with a checkmark.
- **Picture Mode** — pick the picture mode for the selected TV.
- **Closed Captions** — On or Off for the selected TV.
- **SHOW INPUTS** — fetches the list of inputs from the TV, then tap one to switch to it.
- **Refresh — find TVs again** — scans your WiFi again for TVs, and lists the ones it found under Refresh — tap one to add it, then pair it below. The status also names any TV it found that is not added yet.
- **PAIR WITH THIS TV** — pair the selected TV (same as setup Step 3).
- **FORGET PAIRING (UNPAIR THIS TV)** — removes the pairing for the selected TV. Tap **PAIR** to pair it again. The TV stays on the top pills.
- **REMOVE THIS TV FROM THE REMOTE** — takes the selected TV off the remote entirely: its pill leaves the top list, along with its pairing, IP and saved settings. Tap **Refresh** to find it again if you want it back.
- **RUN FIRST-TIME SETUP AGAIN** — opens the first-time setup. This never unpairs anything.

Sleep timer, picture mode, and closed captions are per TV — switching TVs shows that TV's own settings.

## Your privacy

- A fresh install starts with zero TVs. Nothing about any TV is baked into the app.
- Your pairings and settings live only on your phone.
- No account, no tracking, no data about you is collected or sent anywhere. The only thing that leaves your phone is the remote commands to your own TV, over your own WiFi.

## If something goes wrong

**The app cannot find a TV.**

- Check your phone and your TV are on the same home WiFi.
- Check the TV is on, and set to Quick Start mode (**Settings > System > Power Mode > Quick Start**).
- Tap **FIND MY TVs** again. The app retries once by itself, but a second manual tap never hurts.

**One TV is missing after opening the app.**

- The app retries once by itself after a couple of seconds — watch the status line ("Found 1 of 2 TVs — looking again in a moment…").
- If it is still missing, tap **FIND MY TVs** for another look.

**No PIN shows when you tap PAIR.**

- The TV must be **on** — turn it on with its regular remote first. The app cannot turn it on before pairing, and the PIN only shows on the TV screen.

**PAIR is greyed out.**

- Tap one of the TVs in the list first, then tap **PAIR**.
- If it stays greyed out, close the app fully and open it again, then tap the TV and **PAIR**.

**The TV will not turn on from the app.**

- The TV must be in Quick Start mode. In Eco Mode the TV is off the network when it is off, so it cannot be woken over WiFi. Change it on the TV: **Settings > System > Power Mode > Quick Start**.

**The volume slider moves but the TV volume does not.**

- The app tries the direct volume first, then steps the volume up or down. Wait a second and try again.

**A TV that used to work now shows dimmed.**

- That TV is not on this WiFi right now. Check the TV is on and on the same WiFi as your phone — or check whether your phone is on a different WiFi than the TV (for example a guest network, or a second home). When you are back on the same WiFi, the TV works again with no re-pairing.

**Something else is wrong.**

- Tell us on GitHub — open an issue on the Quiet Remote page (`https://github.com/Tsonnn/quiet-remote`) and say which TV model you have and what happened. That is the fastest way to reach us.

## Disclaimer

Quiet Remote is an independent, open-source project. It is not affiliated with, endorsed by, or connected to VIZIO, Inc. VIZIO and SmartCast are trademarks of VIZIO, Inc.
