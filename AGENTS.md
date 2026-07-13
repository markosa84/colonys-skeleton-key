# Working in this repo

Build, run and architecture live in `CLAUDE.md`. Code-level gotchas live in
`src/main/java/AGENTS.md`. This file is about the *workflow* — the things that cost time.

- **The automated check is `gradlew test`** (JUnit 6; 476 tests, including the screen-reader gate over
  all 217 labelled frames; no game, no display). `gradlew build` wires it into `check`, **and with it
  a JaCoCo coverage gate** (≥92% line, ≥90% branch, `win32` excluded). A change that stops testing
  something fails the build — raise the floor when coverage rises, never lower it to fit a change.

- **Everything is testable headless; nothing needs the game.** The `Robot` cannot exist in a headless
  JVM, so it hides behind seams (`ScreenGrabber`, `RobotKeyboard.Taps`, `Slider.Ticks`) — see
  CLAUDE.md "Testing seams". Reach for those before you conclude something "can only be checked
  live", and prefer `Slider.Ticks` to inventing another near-zero `Timing`.

- **Commit messages are load-bearing: they choose the version number.** Releases are cut by
  **release-please**, which reads the [Conventional Commits](https://www.conventionalcommits.org)
  since the last release. **Nothing lints this**, so a mistyped prefix does not fail — it silently
  ships nothing.

  | Prefix | Effect on the next release |
  | --- | --- |
  | `feat:` | **minor** bump (1.2.0 → 1.3.0) |
  | `fix:` / `perf:` | **patch** bump (1.2.0 → 1.2.1) |
  | `feat!:` / any type + `!`, or a `BREAKING CHANGE:` footer | **major** bump (1.2.0 → 2.0.0) |
  | `test:` `refactor:` `build:` `docs:` `chore:` | **no release at all** — they appear in the CHANGELOG and wait for the next `feat:`/`fix:` |
  | anything else (`feats:`, no prefix, "fixed the thing") | **nothing.** Invisible to release-please, invisible in the CHANGELOG |

  So: a bug fix the player can feel is a `fix:`, not a `chore:`. If a release is needed anyway (a
  rebuild, a packaging change), put a **`Release-As: 1.2.3`** footer in the commit body — that forces
  exactly that version, once. The scope is optional and free-form (`fix(reader): ...`).

- **How a release actually happens** (never tag by hand; never edit `version.txt` or
  `.release-please-manifest.json` — release-please owns both):
  1. Push conventional commits to `main`. release-please opens/updates a **"chore: release X.Y.Z"**
     PR carrying the CHANGELOG and the version bump. Nothing is released yet.
  2. **Merging that PR is the decision to release.** It creates a **draft** GitHub release, and the
     `package` job then runs the full suite and attaches the zip + `.sha256` to it.
  3. **Download the zip and run the exe once**, then publish the release by hand. CI cannot launch an
     interactive hotkey app, so that click is the only smoke test the artifact ever gets. A draft has
     no git tag — the tag appears when you publish.

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
