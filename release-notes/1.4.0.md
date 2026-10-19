# TG WS Proxy 1.4.0

## Highlights

- Added secure in-app update downloads from GitHub releases with checksum, package, version, and signing-certificate verification.
- Added independent power controls for CPU wake lock, prepared WebSocket connections, route probes, Cloudflare refresh, WebSocket keepalive, and notification traffic updates.
- Redesigned Settings around Local proxy, Connection, Power usage, Interface, and Support and diagnostics.
- Moved the connection log into Settings and added a visible recording indicator on the main screen.

## Reliability

- Fixed proxy shutdowns that could remain stuck while blocking socket reads occupied all IO workers.
- Added deterministic closure of active client and upstream sockets, a dedicated lifecycle thread, and bounded runtime shutdown.
- Added explicit Starting, Running, Restarting, Stopping, and Stopped service states across the app and Quick Settings tile.
- Added direct-media stall detection with temporary per-datacenter Cloudflare fallback.
- Added system-DNS fallback through bootstrapped DNS-over-HTTPS with caching, shared outage cooldown, and controlled recovery probes.
- Added clear handling for a local proxy port already being in use.

## Diagnostics and interface

- Diagnostic recording now continues until manually finished and rotates approximately 4 MB of recent events.
- Added a warning when many connections use obsolete proxy settings, including instructions and an explanation of their battery and connection impact.
- Added event-driven restoration of a dismissed foreground notification without periodic polling.
- Added optional traffic-free notifications and reduced periodic background work when power-related features are disabled.
- Improved bilingual connection, diagnostics, update, and troubleshooting guidance.
