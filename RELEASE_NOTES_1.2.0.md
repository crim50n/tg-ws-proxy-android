## TG WS Proxy 1.2.0

This release simplifies the main screen and improves runtime control.

### Changed

- Simplify proxy control to a single Connect/Stop action.
- Show the connection link in a compact read-only field with a copy action.
- Keep restart in the service notification instead of the main screen.
- Show uptime with proxy status and use a compact error-statistics row.
- Show only total traffic by default, with detailed runtime counters controlled
  by an Interface setting.
- Reset connection and traffic statistics on every proxy start or restart.
- Generate a replacement secret as a settings draft until Save is pressed.
- Keep Save and Cancel visible above the software keyboard.

### Added

- Restart action in the foreground-service notification.
- Live traffic in the service notification.
- Optional 30-minute local diagnostic recording with redaction, size limits,
  explicit export, and automatic retention cleanup.
- Automatic daily and manual update checks through the latest GitHub Release,
  with the repository's static `update.json` manifest as a fallback.
- Complete English and Russian strings for the updated controls.

Notification restart closes all client and upstream connections, rebuilds the
WebSocket pool, reloads saved settings, and starts a fresh proxy session.
