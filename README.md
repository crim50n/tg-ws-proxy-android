# TG WS Proxy

Minimal Android app (~1.2 MB) that does one thing: runs a local MTProto proxy on your device. Telegram connects to `127.0.0.1:1443` and traffic is relayed to Telegram data centers over WebSocket (TLS), with optional Cloudflare proxy fallback and direct TCP fallback. No ads, no analytics, no extra features — just the proxy.

## Features

- **Local MTProto proxy** — no external server required, runs entirely on-device
- **WebSocket transport** — routes traffic through TLS WebSocket connections to bypass network restrictions
- **Connection pool** — pre-established WS connections for instant handoff (configurable pool size)
- **Cloudflare proxy fallback** — automatic domain rotation with sticky active domain
- **TCP direct fallback** — falls back to standard DC IPs when WS and CF are unavailable
- **Quick Settings tile** — start/stop proxy from the notification shade
- **Foreground service** — persistent notification with auto-restore after swipe (Android 14+)
- **Network-aware pool recovery** — flushes and re-warms WS pool on network restore after device sleep
- **Configurable** — host, port, secret, DC redirect IPs, buffer size, pool size, CF proxy settings

## Requirements

- Android 8.0+ (API 26)
- No root required

## Build

```bash
# Debug
./gradlew assembleDebug

# Release (requires signing key in app/keystore/)
./gradlew :app:assembleRelease
```

JDK 17 required. The release build uses ProGuard/R8 minification and resource shrinking.

## Configuration

All settings are accessible from the in-app Settings screen:

| Setting | Default | Description |
|---|---|---|
| Host | `127.0.0.1` | Listen address |
| Port | `1443` | Listen port |
| Secret | random | 32-char hex MTProto secret |
| DC redirects | Telegram defaults | `DC:IP` mapping for WebSocket targets |
| CF proxy | enabled, priority | Cloudflare proxy with automatic domain discovery |
| Pool size | 4 | Pre-established WS connections per DC |
| Buffer size | 256 KB | Socket buffer size |

## Usage

1. Start the proxy from the app or Quick Settings tile
2. Copy the `tg://proxy` link and open it in Telegram
3. Telegram will connect through the local proxy

## Credits

Based on [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) by Flowseal.

## License

MIT
