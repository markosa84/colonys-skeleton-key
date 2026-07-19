# Changelog

## [1.4.0](https://github.com/markosa84/colonys-skeleton-key/compare/v1.3.0...v1.4.0) (2026-07-19)


### Features

* **session:** recover from a misread connection instead of giving up on the lock ([4bc1c20](https://github.com/markosa84/colonys-skeleton-key/commit/4bc1c20588c6ef1f78cdc97a7358a187307e9cf1))
* **session:** recover from a misread connection instead of giving up on the lock ([aea9f5c](https://github.com/markosa84/colonys-skeleton-key/commit/aea9f5c018c6aaef1bbd3c934baae2bfabdcd031))


### Bug Fixes

* **vision:** threshold the lockpick counter against the panel's own ink and white ([89bd350](https://github.com/markosa84/colonys-skeleton-key/commit/89bd35024a8713e1ad48d4e0871af5e1336ea66d))


### Refactoring

* **vision:** extract the affine ViewMapping the viewport already computes ([582ed23](https://github.com/markosa84/colonys-skeleton-key/commit/582ed23312553010d0378601f0730b7acaaf3885))
* **vision:** gate on a 1280x720 read floor, drop the sub-floor modes ([8b78869](https://github.com/markosa84/colonys-skeleton-key/commit/8b788692cb34b97fc3a7bceb9b27b5bed9ded008))
* **vision:** remove pin-pop detection; confirm the goal from a direct all-zero read ([b4ce517](https://github.com/markosa84/colonys-skeleton-key/commit/b4ce517212675eb2624a07486cd2232773e35c7f))


### Tests

* **vision:** pin the labelled HDR corpus, read tone-free ([4679163](https://github.com/markosa84/colonys-skeleton-key/commit/467916368ff304786b1b32b6191f9375317a3b12))
* **vision:** share the frame corpus and pin the safety invariants over both readers ([729b891](https://github.com/markosa84/colonys-skeleton-key/commit/729b891d156f16c6e7db5d3ce99d80efa7b9b8e0))

## [1.3.0](https://github.com/markosa84/colonys-skeleton-key/compare/v1.2.0...v1.3.0) (2026-07-16)


### Features

* save a full per-F8 run log, and stamp the build for bug reports ([0766999](https://github.com/markosa84/colonys-skeleton-key/commit/0766999a207e00595485c0875fd6d463a0ff4096))


### Bug Fixes

* **session:** open the hard locks that used to loop forever or give up ([bf83a07](https://github.com/markosa84/colonys-skeleton-key/commit/bf83a0745b2d5046d4ef0d5760a5cfb76fa55669))

## [1.2.0](https://github.com/markosa84/colonys-skeleton-key/compare/v1.1.0...v1.2.0) (2026-07-15)


### Features

* **vision:** read the lock from its own contrast — tone-free reader, HDR-proof, now the default ([0ebb7ee](https://github.com/markosa84/colonys-skeleton-key/commit/0ebb7ee80d7242ba18df5294f43b27c9c066b136))
* **vision:** tone-free lattice reader, HDR-proof, now the default ([d64fb64](https://github.com/markosa84/colonys-skeleton-key/commit/d64fb64e534d87ee8b50081b79f7edeaa09d6ee3))


### Documentation

* describe the two-reader seam and the new default ([b665e7d](https://github.com/markosa84/colonys-skeleton-key/commit/b665e7d1e8913021ed807843f0537aa09b68def8))

## [1.1.0](https://github.com/markosa84/colonys-skeleton-key/compare/v1.0.0...v1.1.0) (2026-07-13)


### Features

* **vision:** read the lock at any in-game gamma, and through the game's own window ([abe6df9](https://github.com/markosa84/colonys-skeleton-key/commit/abe6df9e89237b98d22e131d7ef842b8d675385d))

## 1.0.0 (2026-07-13)


### Tests

* cover every class outside win32, and migrate to JUnit 6 ([229ef3c](https://github.com/markosa84/colonys-skeleton-key/commit/229ef3c8f701cb1209c805e091e828978c76687a))


### CI

* cut releases from conventional commits, and badge the README ([39c1642](https://github.com/markosa84/colonys-skeleton-key/commit/39c1642ed387106d91f85ae4596e06839accaf8b))
