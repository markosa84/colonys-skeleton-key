# The Colony's Skeleton Key

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

- **Windows**, and the game running **borderless windowed**.
- **F8 unbound** in the game's controls menu. The tool only *observes* the key, so the game receives
  it too — make sure it does not do anything there.
- Any resolution. The reader is calibrated at 4K and scaled to your screen; it is validated at 23
  display modes from 800×600 up, across 16:9, 16:10, 4:3 and 5:4.
- The game to be the focused window. **Keys are only ever sent while
  `G1R-Win64-Shipping.exe` owns the focus** — alt-tab away mid-run and the tool aborts rather than
  type `W`/`A`/`S`/`D` into whatever you switched to.

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
217 of them, on every build.

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
