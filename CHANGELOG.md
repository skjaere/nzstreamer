# Changelog

## [0.2.0](https://github.com/skjaere/nzstreamer/compare/v0.1.0...v0.2.0) (2026-11-14)


### Features

* **settings:** move an install that never chose a playback setting to 10 releases ([442e2c7](https://github.com/skjaere/nzstreamer/commit/442e2c70effe72a9bc83b1303baeddce2c0918f0))
* **settings:** try up to 10 releases by default ([c474aee](https://github.com/skjaere/nzstreamer/commit/c474aeed777f6f729767c1983fdf36065906cf9b))


### Bug fixes

* **engine:** undo a cancelled caller's build also when the build ends before the caller waits ([f238f13](https://github.com/skjaere/nzstreamer/commit/f238f13a54dbe564d23d6ad2ed72d5a0001f9178))
* **handoff:** tell the setup server's events in the order the session changed ([f1c0209](https://github.com/skjaere/nzstreamer/commit/f1c0209675253e8a4c2f608960714e8efb1f2e2e))
* **link:** give an ending link its own close reason when the peer leaves ([39d1e2e](https://github.com/skjaere/nzstreamer/commit/39d1e2ecc0d29b2b52afc355aa07d07e8ee9a3ff))
* **link:** say that an ending connection closes for its reason however the peer goes ([a0414b2](https://github.com/skjaere/nzstreamer/commit/a0414b27870544215cf0ea958d6fb151dca04cb8))
* **play:** keep a play's failed attempts from pushing out the releases kept for Resume ([05e7479](https://github.com/skjaere/nzstreamer/commit/05e74791ae83d82b73babbc1bc5ef123e42df4e0))
* **play:** let a TV's remote read a failed play's releases when they fill more than the screen ([4962d04](https://github.com/skjaere/nzstreamer/commit/4962d04887c04b4df5dfbfa8167f2c9dc8971212))
* start a play from the beginning when its watch row was removed ([cb4934f](https://github.com/skjaere/nzstreamer/commit/cb4934f978e5ef92984fb7d3291970acb573986b))
