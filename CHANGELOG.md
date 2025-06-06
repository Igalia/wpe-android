# ChangeLog

All notable changes to this project will be documented in this file.

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
