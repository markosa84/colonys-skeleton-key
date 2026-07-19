# Automation / screen-reader notes

- **There are two readers behind `LockAnalyzer`, and `LatticeReader` is the default.** It reads the
  lock from **ratios of the lock's own contrast** (tone-free), matches `LockReader` on every labelled
  frame, and additionally reads HDR. `LockReader` is the pixel-calibrated reference, kept behind
  `--reader=legacy`. Both share the measured geometry in `FanGeometry` — including the hole walk and
  the skip window — and the `luminance` primitive in `Pixels`, so **neither reader depends on the
  other** (`LatticeReader` used to borrow both from `LockReader`; it no longer does). If you touch
  photometry, change it in the reader that owns it; if you touch geometry, it lives in `FanGeometry`
  and both readers move together. **Do not delete `LockReader`** — it is the calibration reference and
  the corpus gate for the measured constants everything else is derived from.

- **Both readers have a regression gate — run it after any change:** `gradlew test` (wired into
  `check`/`build`) replays every labelled frame in `src/test/data/frames/` through **both**
  `LockReaderTest` and `LatticeReaderTest` and must stay green on **every one** (the 4K calibration
  census, the `6p-gap-shadow` regression frame, the 7-plate census, the 133-frame resolution sweep
  (19 modes, floored at 1280×720), and the gamma corpus). `LatticeReaderTest` additionally reads the
  labelled `hdr/` corpus `LockReader`
  refuses (HDR is not an invertible LUT — see CLAUDE.md's dead ends), and pins whole-corpus safety
  invariants: never a wrong plate count, offsets always in range. To debug offline,
  `new LatticeReader(Viewport.REFERENCE)` (or `LockReader`) is safe anywhere: pure image analysis
  (all Robot captures live in `GameScreen`), so feed `ImageIO.read(...)` images straight into
  `detectPlateCount` / `readState`. `LatticeReader`'s constants are **ratios** (edit
  them directly); `LockReader`'s and `FanGeometry`'s pixel constants are 4K reference values mapped
  through `Viewport` at construction — edit the reference constants, never the scaled instance fields.

- **Every colour and luminance constant in `LockReader` assumes the CALIBRATED gamma, and `Tone` is
  what makes that true.** `new LockReader(viewport)` means "this frame is already at gamma 2.7" —
  right for the fixtures, and right for nothing else. Live, `AutoLockpick` measures the gamma off the
  frame once per F8 (`Tone.estimate`) and hands it to the reader, which maps every pixel through it
  inside `isPin` and the hole scan. **If you add a pixel gate to `LockReader`, it must sit behind
  `tone.map(...)` too** — an absolute number applied to a raw frame is a number that is only true for
  the author's screen. The curves are measured (`tools/ToneTable.java`), never fitted: see CLAUDE.md's
  dead end on extrapolating one, which is a mistake that has already been made here and it destroys
  the pins.

- **Better still, don't need the curve.** `LatticeReader` (the default) and
  `GameScreen.pickCounterFingerprint` both ask only for **ratios of what is in the box**, so they
  hold under any monotone tone map — which is what a gamma LUT and an HDR tonemap both are.
  `GameScreen` no longer takes a `Tone` at all: it cuts the lockpick counter at the midpoint of the
  panel's own ink and white plateaus (`Tone.panelSplit`, shared with the gamma probe). The absolute
  `110` it replaced was not broken on any measured frame — it was clearing gamma 3.2's ink by ten
  levels and clearing HDR's only via a curve the reader itself refuses to trust. Prefer a gate the
  frame answers for itself over one the `Tone` has to rescue.

- **Plate index orientation:** index `0` = back-most plate (top of the fan), `n-1` = front (the dark
  plate holding the keyhole/pick). The user's "panel 1..N" numbering is the REVERSE (front→back), so
  their "5th panel" of a 5-plate lock is our index 0. The fixture directory names use CODE indices.

- **You can drive the live game yourself** without the F8 hotkey loop (which never returns). Write a
  one-shot `main` that calls `Win32.setProcessDpiAware()`, checks
  `Win32.foregroundProcessName().equalsIgnoreCase("G1R-Win64-Shipping.exe")`, wires the graph the
  way `AutoLockpick.main` does (`GameScreen`/`LockReader` → `LiveLockView`/`LivePoller`,
  `RobotKeyboard` → `KeySender`, `Slider` with `Timing.GAME`), and calls
  `new LockSession(...).run()` directly. Force the game to the front first from PowerShell —
  `AppActivate` is unreliable; use `AttachThreadInput` + `ShowWindow(SW_RESTORE)` +
  `SetForegroundWindow`, and select it **by process name**, never by title: a Chrome window about the
  game matches any title test. Always keep the foreground check — without it a stray `A`/`D` lands in
  whatever window is focused.

- **A harness that presses keys must never trust its own cursor across a reset.** A pick break re-homes
  the selection. A scratch tool that assumed otherwise drove the wrong plate into an illegal move over
  and over, strained a dozen times and cost the player several lockpicks. Re-home (`endCursor`), and
  verify every move against `applyMove` before pressing and against the observed state afterwards.

- **The session is fully drivable by fakes.** `LockSession` depends only on the seam interfaces it
  owns (`LockView`, `MoveExecutor`, `CursorKeys`) — see `FakeGame` in the test tree, which simulates
  a lock with hidden connections and the game's real strain/break/reset rules (knobs: `lieAtPlay`,
  `hiddenPlate`/`hideWhen`, `unreadableAtPlay`, `unreadableOnceOpen`). Prefer extending that over
  building a live harness.

- **So is everything else — the `Robot` is behind a seam.** `java.awt.Robot` cannot be constructed
  *or subclassed* in a headless JVM, which is why `GameScreen` takes a `ScreenGrabber` and
  `RobotKeyboard` a `Taps` (both package-private; the public constructors still take the `Robot`, so
  no call site changed). A fake `ScreenGrabber` cropping a fixture frame is byte-for-byte what the
  live grabber returns, so `GameScreen`/`LivePoller`/`LiveLockView` test against real frames.
  `Slider.Ticks` is the same idea for the clock: a virtual one lets the tests run the **real**
  `Timing.GAME` (5s break animation, 12s give-up) instantly. Don't add a near-zero `Timing` where a
  fake clock will do, and don't call something "only checkable live" before checking these.

- **Measure the game's animation, don't guess it.** Measured: plates move 112→207ms after the key,
  stable read at 269ms; the moving plate reads `UNKNOWN` while it travels. Sampling too early turns
  a successful move into a phantom "strain" and corrupts the session's idea of the state. A plate
  that STAYS `UNKNOWN` in a state that stopped changing (~1.5s) is settled with an unresolved
  row - `Slider` returns that state with its `UNKNOWN` entries and the session works around it.
  Full tables: `docs/INTERNALS.md` "Measured timings".

- **A strain does not block input; a pick *break* does.** A legal slide sent 0ms after a rejected move
  still lands, and the shake is invisible to the reader (no `UNKNOWN`, no offset change). Only the
  ~4-5s pick-replacement animation swallows keys. Don't add waiting around strains.

- **`capture()` is ~79ms, `readState()` is ~8ms** — the screenshot dominates every poll. Poll with
  `GameScreen.captureLock()`, which grabs the lock's 1300x1120 box (sized with a safety belt;
  `CaptureBoxTest` proves it contains everything the reader samples at every viewport) and
  composites it into a *reused* view-sized canvas so the reader's coordinates still land.
  Never dump that image through `Captures`: every pixel outside the box is stale. (`LiveLockView`
  saves the full `capture()` for exactly this reason, and a test pins it.)

- **Everything in the vision layer is VIEW-local; only `GameScreen` knows where the window is.** The
  `Viewport` carries the game's origin on the virtual desktop, and `GameScreen` is the one class that
  adds it — it keeps each box twice, once as the reader sees it and once translated for the grabber.
  So an image reaching `LockReader` always has the game's top-left at (0,0), whether the game fills
  the screen or sits in a corner of it. Don't leak the origin past `GameScreen`; the reader needed
  **no change at all** for windowed play, and that is the property to preserve.

- **A wrong viewport is silent by construction.** `detectPins` clamps its scan box to the frame, so
  a viewport describing the wrong rectangle yields "no pins" → `detectPlateCount` = -1 → "no lock
  detected", over a dumped frame in which the lock is plainly visible. That is a *feature* (better
  than an out-of-bounds crash) and a trap: it makes an acquisition bug look exactly like a reader
  bug. `LockReader.describe` exists to tell them apart, and `WindowedGameTest` pins the case that
  shipped. Before suspecting a threshold, run `--diagnose` on the frame.

- **A row that fails to read in a settled frame is a reader bug to fix, not a fact of the lock.**
  The one live case (a difficulty-4 chest) was an arch-gap shadow the old walk mistook for a
  seventh hole - fixed by the exact walk + deskewed row gate, pinned by the `6p-gap-shadow`
  fixture; drop any new failure dump into `src/test/data/frames/` the same way. Until fixed, such
  a row reads `UNKNOWN` and the session tolerates it: **never learn a connection row from a diff
  that contains `UNKNOWN`** - undo the move and probe again from a different configuration
  (`LockSession.partiallyObserved` is the pattern). And confirm "solved" only from a fresh **direct**
  read whose every plate is 0 with none `UNKNOWN` - never from a model-filled row (a filled zero is a
  guess). The pin-pop that used to carry this was removed; the hole rows carry it now.

- **The slide-sequence fixtures are usable ground truth**, because each frame differs from the
  previous by exactly one plate step. Chain the arithmetic from a known frame and a reader that is
  wrong anywhere breaks the whole sequence. That is how `LockReaderTest`'s expectations were derived
  from a single user-labelled frame (`5p-plates-1-2-opposed/step-0`).

- **Pin detection is boxed to `RX0..RY1` on purpose:** on the full 4K frame the warm wooden
  background (candles/crates/torches) has far more warm pixels than the brass pins, so a global scan
  locks onto clutter. The lock is framed at a FIXED screen spot (`FAN_CENTER`+`DEPTH_STEP`, verified
  across 4 rooms), which is what makes the fixed box — and the whole reader — work.

- **A missed pin does not read as "no lock". It reads as a SMALLER lock.** The fans of `n` and `n+2`
  share a lattice, so a 6-plate lock's pins cover a 4-plate fan exactly (and a 7-plate covers a
  5-plate). `detectPlateCount` takes the largest fan that fits, which is only safe while every pin is
  seen — lose the two end pins (the faintest, and the ones a dark or HDR frame takes) and it silently
  answers the smaller lock, which is far worse than finding nothing: the session then drives a model
  with the wrong number of plates. A reporter's 6-plate chest read as 4, strained nine times, and
  called *itself* stuck.
  **The guard is `plateBeyond`, and it asks the hole rows, not the pins.** Pins cannot answer it: a
  room's stray warm blobs land on a genuine lock's extension position at up to 14.8× the pin floor,
  while a real outer pin drops to 0.89× — the sets overlap, so no size threshold exists, and the old
  `CLUTTER_ALLOWANCE` "fixed" that by switching the check off on busy frames. A plate is a **row of six
  holes**; measured, the row past a genuine end holds 0 and a real plate's holds 6. If you touch pin
  detection, the frames to test against are `2048x1536/front-plate-sweep` (15 blobs for 5 pins) and
  `LockReaderTest.aSixPlateLockWithBothEndPinsInvisibleIsNeverAFourPlateOne` (the reported bug).

- **Approaches already tried and scrapped** (CLAUDE.md "Dead ends" has the detail): a "pin x →
  offset" reader (the pin never moves); drive-to-centre probing (a connected plate can block a plate
  from ever centring); model-fitting hole positions from a per-plate spacing along a straight sampled
  line (no single rotation flattens every row, and spacing varies 41–54px with depth and position);
  resetting the lock between probes (the reset restores the very blockage you need to escape); and
  playing a solution open-loop.

- **The open-loop failure was misdiagnosed for a whole session — don't repeat the diagnosis.** Slides
  are **queued, not dropped** (six back-to-back at a 0ms gap all landed; a 1ms hold suffices). What the
  game discards is input sent **during a reset animation**, and the old executor pressed `R` then slid
  ~830ms later while the reset still ran. Symptom looked identical to a dropped key.

- **`ROT_DEG` is measured over a 1170-hole census — don't re-derive or re-tune it.** The rows are
  *straight* (≤2px residual); what no single angle can fix is that each plate's row projects at its
  own angle (`opt(depth) = −29.80 + 0.70·depth`) — which is why `readState` tests every blob
  against its row's own deskewed line (`rowSlope`, `ROW_MAX_DY` 12px scaled) and walks the lattice
  in single steps first, bridging a gap only when a hole is genuinely missing. CLAUDE.md
  "Rotation angle" has the numbers and the history.

- **Every plate move goes through `Slider`.** It presses the key, watches through its `LockPoller`,
  and reports `MOVED`/`UNCHANGED`/`RESET` plus `pickBroke`. Don't add a second path — the animation
  contract (`Slider.Timing`, and the `awaitBreak` wait that lets the pick-replacement animation
  finish) belongs in exactly one file.

- **The cursor cannot be read from a frame** — the dark plate is the front one, not the selected one.
  And the game re-homes the selection whenever a pick breaks. A W/S press costs ~10ms, so
  `endCursor(n)` is nearly free: call it after every strain and every reset rather than reasoning about
  where the selection "should" be.

- **A broken pick is invisible in the lock** unless the player is untrained (only level 0 resets the
  puzzle). Detect it from the **remaining-lockpicks counter** — a fixed white box under the lock whose
  pixels change when the number drops. `GameScreen.pickCounterFingerprint()`. Trying to see a break in
  the plates is a dead end that costs the player real picks. And after a break the game swallows input
  for ~4-5s while it swaps the pick.

- **Two cost models, one search.** `Cost.KEYPRESS` (1,1) minimizes keys; `Cost.WALLCLOCK` (300,10) is
  milliseconds, for the automation. `solve(m)` defaults to KEYPRESS and cursor `n-1`; the automation
  must call `solve(m, state, cursor, WALLCLOCK)`. The two have **never produced a different plan**
  (0 divergences over 200 random 6-plate starts, now pinned by `LockSolverTest`) because the slide
  count is fixed by the connection algebra — don't expect the weighting to speed anything up.

- **A plate that won't slide has connections, it doesn't lack them.** Moves are atomic, so a plate
  that is not itself at the end of its track can only be blocked by something it drags. The useful
  contrapositive: if every plate except `p` is off the ends, `p` is guaranteed to move. `LockSession`
  builds on exactly that.
