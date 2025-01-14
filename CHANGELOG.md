# ChangeLog

All notable changes to this project will be documented in this file.

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
