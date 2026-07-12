# Working in this repo

Build, run and architecture live in `CLAUDE.md`. Code-level gotchas live in
`src/main/java/AGENTS.md`. This file is about the *workflow* — the things that cost time.

- **The automated check is `gradlew test`** (JUnit 5; 116 tests including the 34/34 screen-reader
  gate; no game, no display). `gradlew build` wires it into `check`.

- **Throwaway harnesses go in the scratchpad, not the repo.** The public API covers driving the
  tool (`LockSession`, `Slider`, `LockReader`, `GameScreen` are public); a harness that needs
  package-private members must declare the same package (e.g.
  `io.github.markosa84.colonysskeletonkey.vision`). Compile against the built classes:
  `gradlew classes` then `javac -cp build\classes\java\main -d <out> Tool.java`, and run with
  `-cp "build\classes\java\main;<out>"` plus `--enable-native-access=ALL-UNNAMED -Dsun.java2d.uiScale=1`.

- **PowerShell 5.1's `Set-Content -Encoding utf8` writes a BOM**, and `javac` rejects it with
  `illegal character: '﻿'`. Write source files with the Write tool (or `-Encoding utf8NoBOM`).

- **Sub-15ms delays need a spin loop.** `Thread.sleep` / `Robot.delay` granularity is ~15ms on Windows,
  which is coarser than the key timings being measured. Spin on `LockSupport.parkNanos`, and set
  `robot.setAutoDelay(0)`.

- **"Did both keypresses land?" must be answered after a fixed, generous wait — never with a
  settle-until-stable read.** The game *queues* slides, so a settle can return in the lull between the
  first slide finishing and the buffered second one starting. That artefact produced a
  non-monotonic result (gap 40ms "dropped", 80ms "both", 120ms "dropped") and sent a whole
  investigation down the wrong path.

- **A live harness that presses keys can cost the player real lockpicks.** Prove every move legal with
  `LockSolver.applyMove` *before* pressing, check the observed state *after*, and abort on the first
  surprise. A scratch tool that skipped this strained ~20 times and broke several picks. Note a strain
  is *not* only "a plate at the end of its own track" — pressing a plate into its own wall strains too.

- **Never trust a claim in the docs over a measurement.** One session overturned three: slides were
  said to be dropped mid-animation (they are queued), a pick break was said to reset the puzzle (only
  at skill level 0), and a strain was said to shake the plates unreadably (the reader sees nothing).
  Each had been written down confidently, with a plausible mechanism, from a single observation.
