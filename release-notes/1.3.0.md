# TG WS Proxy 1.3.0

## Highlights

- Added automatic per-network route selection, enabled by default.
- Added lightweight direct WebSocket, Cloudflare, and TCP route probes after startup and network changes.
- Automatic decisions are temporary and never overwrite the user's saved manual routing settings.
- The active runtime route order is now shown on the main screen.
- Route-aware performance defaults use a 256 KB buffer and disable direct WS pool warmup in Cloudflare-first mode.

## Interface

- Redesigned Settings with automatic saving and no Save/Cancel buttons.
- Manual routing, server, buffer, and pool controls are shown only when Automatic connection mode is disabled.
- Added System, Light, Dark, and Material You appearance options.
- Added dedicated Help, Connection log, and About screens.
- Removed the compact traffic card; detailed statistics remain optional.

## Diagnostics

- Added an always-available in-memory live connection log.
- Added explicit five-minute diagnostic report recording and export.
- Diagnostic deletion now removes saved reports without clearing visible in-memory events.
- Expanded redaction for proxy links, secrets, IPv4, and IPv6 addresses.

## Reliability

- Improved network handover detection for Wi-Fi, cellular, VPN, and Ethernet.
- Added runtime isolation for delayed route-probe results.
- Added conservative route-switch criteria that preserve a route when successful connections are observed.
- Settings now restart a running proxy only after effective connection changes.
- Added outbound WebSocket ping, TCP keepalive, Cloudflare retry parallelism, and HTTP 429 cooldown handling.
