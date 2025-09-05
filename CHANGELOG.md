# ChangeLog

All notable changes to this project will be documented in this file.

## v0.2.2 (2.48.6) - 2025-09-05

### Changed

- WPE WebKit updated to [version 2.48.6](https://wpewebkit.org/release/wpewebkit-2.48.6.html) (#211).
- Use WPE WebKit and GStreamer version information obtained at run time
  to determine versioned identifiers for process assets. This avoids
  manually editing them on every version update (#212).

### Fixed

- Fix incorrect double-close of socket file descriptor during service startup,
  which allowed removing a workaround in WebKit (#210).


## v0.2.1 (2.48.5) - 2025-08-11

### Changed

- WPE WebKit updated to [version 2.48.5](https://wpewebkit.org/release/wpewebkit-2.48.5.html) (#208).

### Added

- The WPE WebKit version in use at runtime is now sent to the debug log
  during startup (#209).


## v0.2.0 (2.48.3) - 2024-06-04

### Changed

- WPE WebKit updated to [version 2.48.3](https://wpewebkit.org/release/wpewebkit-2.48.3.html) (#204, #194, #191).
- Use mangled entry points for service process entry points (#184, #196)
- Remove `onInputMethodContextOut()` call that is no longer needed after
  the WPE WebKit update (#203).
- Improved dependency handling in the `bootstrap.py` script (#195).

### Fixed

- Apply system bar and IME insets to Minibrowser views, to avoid them
  overlapping UI elements (#190, #193, #202).
- Fix crash when opening the Minibrowser settings view with system-wide
  dark mode enabled (#201).


## v0.1.3 (2.46.3) - 2025-01-14

### Changed

- Improved the CI and build system

### Added

- Added new APIs to better handle security features, in particular for testing
  purposes.
- Added the "DisableWebSecurity" setting in WPESettings to enable/disable all
  CORS verifications.
- Added the "setTLSErrorsPolicy" feature in WPEView to accept/reject invalid
  SSL certificates by default.
- Added the "onReceivedSslError" callback in WPEViewClient to allow the handle
  of invalid SSL certificates on demand.

## v0.1.2 (2.46.3) - 2024-11-19

### Changed

- WPE WebKit updated to [version 2.46.3](https://wpewebkit.org/release/wpewebkit-2.46.3.html).
