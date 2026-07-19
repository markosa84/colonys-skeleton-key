# The Colony's Skeleton Key

[![ci](https://github.com/markosa84/colonys-skeleton-key/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/markosa84/colonys-skeleton-key/actions/workflows/ci.yml)
[![release](https://img.shields.io/github/v/release/markosa84/colonys-skeleton-key?display_name=tag&sort=semver&label=release)](https://github.com/markosa84/colonys-skeleton-key/releases/latest)
[![licence: MIT](https://img.shields.io/github/license/markosa84/colonys-skeleton-key)](LICENSE)

**An automatic lockpicker for the Gothic 1 Remake lockpicking minigame.** Open a lock in the game,
press **F8**, and watch it open.

It reads the lock off the screen, works out how the plates are wired to each other by experimenting
on them, solves the puzzle, and types the solution back into the game. It never resets the lock, and
if a lockpick breaks along the way it keeps everything it has learned and carries on. A hard
six-plate chest takes about fifteen seconds.

There is nothing to configure and nothing to tell it — not even your character's lockpicking level.
It works that out too, by watching the lockpick counter.

## Download and run

1. Grab **`ColonysSkeletonKey-<version>-win64.zip`** from the
   [Releases page](../../releases/latest).
2. Unzip it anywhere.
3. Run **`ColonysSkeletonKey.exe`**. A console window opens and waits.
4. In the game, open a lock and keep the game focused. Press **F8**.

**No Java needed** — the zip contains its own. Quit with `Ctrl-C` or by closing the console.

Two things Windows will do, because the release is not code-signed (a certificate costs real money):

- **SmartScreen** may say *"Windows protected your PC"*. Click **More info → Run anyway**. Every
  release publishes a `.sha256` checksum next to the zip if you want to verify what you downloaded.
- **Your antivirus may flag it.** That is not unreasonable of it: this program watches the screen and
  synthesises keystrokes, which is what it has to do, and it is also what a lot of malware does. The
  entire source is here — [`AutoLockpick.java`](src/main/java/io/github/markosa84/colonysskeletonkey/AutoLockpick.java)
  is the whole entry point — and you can build it yourself in a minute (below).

## What it needs from the game

- **Windows**, and the game running **windowed or borderless**.
- **F8 unbound** in the game's controls menu. The tool only *observes* the key, so the game receives
  it too — make sure it does not do anything there.
- Any resolution from **1280×720** up, any monitor. Each F8 measures the window the game is drawing
  into and scales the 4K calibration onto it, so it does not matter whether the game fills your screen.
  The reader is validated at 19 display modes from 1280×720 up, across 16:9, 16:10, 4:3 and 5:4; below
  720p the tool declines the lock rather than risk a misread.
- **Any gamma.** Set the slider wherever you like it: each F8 reads the brightness back off the
  screen and corrects for it. Validated end to end across the whole range, 1.2 to 3.2.
- The game to be the focused window. **Keys are only ever sent while
  `G1R-Win64-Shipping.exe` owns the focus** — alt-tab away mid-run and the tool aborts rather than
  type `W`/`A`/`S`/`D` into whatever you switched to.

## If it says "No 4-7 plate lock detected"

It saves two files in `captures/`: the frame it was looking at, and a `.txt` beside it saying which
rectangle it thought it was reading and what it found there. **Open the `.txt`.** If the saved frame
shows the lock perfectly well, the pixels were fine and the tool was reading the wrong part of them —
please [open an issue](../../issues) with both files. You can also replay any saved frame yourself,
without the game running:

```bat
ColonysSkeletonKey.exe --diagnose captures\no-lock-20260713-103856-180.png
```

## If it gets stuck, loops, or opens the wrong thing

Every F8 writes a complete log of that attempt to `captures\f8-<time>.log` — the whole environment,
what the reader saw, and a move-by-move trace of what it did and why. If a lock does not open, or the
tool does something odd, **[open an issue](../../issues) and attach that file** (plus any `.png`/`.txt`
that appear beside it). It is far more use than a screenshot: it says exactly where the run went wrong.

## What it does, plainly

It takes screenshots of your screen and it presses keys. That is all it does.

It does **not** read or modify the game's memory, touch any game file, alter the executable, or go
anywhere near copy protection. It contains no game assets. It is an ordinary program looking at the
same pixels you are and pressing the same keys you would.

The interesting part is that a screenshot does not tell you how the lock is *wired* — which plates
drag which others, and in which direction. Nothing on screen says. So the tool finds out by
experiment, choosing its probes so that they cannot strain the pick where that is possible at all,
and it never resets the lock, because a reset just restores the blockage it is trying to escape.
[`docs/INTERNALS.md`](docs/INTERNALS.md) is the long version, with all the measurements.

## Build it yourself

You need **JDK 25** (any distribution) and nothing else — the app has zero dependencies.

```bat
gradlew.bat build          :: compiles and runs the full test suite
gradlew.bat run            :: runs it against the game (same as lockpick.bat)
gradlew.bat releaseZip     :: builds the portable zip in build/release/
```

The whole suite runs anywhere, with no game and no display: the solver's optimality proofs, the
session against its fake game, the executor, the capture-box geometry, and the reader's calibration —
which replays every labelled frame in [`src/test/data/frames`](src/test/data/frames/README.md), all
189 of them, on every build.

## Disclaimer

The Colony's Skeleton Key is an unofficial, fan-made companion utility for Gothic 1 Remake. It is not
affiliated with, endorsed by, sponsored by, or associated with THQ Nordic or Alkimia Interactive.

Gothic and Gothic 1 Remake are trademarks or registered trademarks of their respective owners. No
game files, copyrighted assets, source code, encryption keys, or DRM-circumvention functionality are
included with this project.

Users must own a legitimate copy of the game and are responsible for ensuring that their use of this
utility complies with the game platform's terms and all applicable agreements. Note that platform
terms of service commonly restrict automated input; this tool sends keystrokes to the game, and
whether that is acceptable under the terms you have agreed to is your call to make, not this
README's.

This software is provided "as is", without warranty of any kind.

## Licence

[MIT](LICENSE). Copyright (c) 2026 markosa84.
