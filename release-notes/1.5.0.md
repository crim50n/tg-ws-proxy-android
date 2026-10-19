# TG WS Proxy 1.5.0

## Highlights

- Diagnostics now run continuously, so a privacy-safe report can be shared immediately after a problem without reproducing it under manual recording.
- Added a bounded, queued, and batched diagnostic writer with severity levels, crash capture, seven-day event retention, and approximately 4 MB of rotating history.
- Added Android themed-icon support and hardened configuration, backup, and update-installer integration.

## Reliability

- Made proxy runtime resources generation-owned so a delayed old runtime cannot stop or clear its replacement.
- Bounded the complete runtime shutdown and prevented restart from launching a second runtime after a stop timeout.
- Kept the CPU wake lock for the configured proxy runtime while ensuring it is released on normal stop, failure, timeout, and service destruction.
- Cancelled obsolete Cloudflare-domain refresh requests and prevented stale responses from replacing a newer domain pool.
- Improved service startup and shutdown ordering across network callbacks, route probes, notifications, and background refresh work.

## Diagnostics and privacy

- Moved diagnostic initialization, export, and clearing away from the main thread.
- Added per-event retention, overflow accounting, immutable report snapshots, and deterministic ordering for export and clear actions.
- Redacted proxy links, secrets, IP addresses, configured hostnames, and hostnames found in exception messages before display and export.
- Preserved useful crash stack identifiers while applying privacy filtering to legacy and current diagnostic entries.
- Removed manual start/finish recording controls and restored diagnostic-log scroll position across configuration changes.
