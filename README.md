# TG WS Proxy for Android

Native on-device MTProto proxy for Telegram. The app listens on
`127.0.0.1:1443`, authenticates clients with a generated MTProto secret, and
relays traffic to Telegram data centers over TLS WebSocket connections. If the
direct WebSocket route is unavailable, it can use a Cloudflare proxy domain or
fall back to direct TCP.

Telegram is configured through a standard `tg://proxy` link. The app does not
require root, an external proxy server, an account, analytics, or ads.

## Features

- Native Kotlin proxy engine with MTProto AES-CTR re-encryption
- Direct WebSocket transport with per-DC connection pools
- Validated Cloudflare domain pool with per-DC sticky balancing
- Configurable Cloudflare-first or TCP-first fallback order
- Automatic pool reset and warmup after network changes
- Foreground service, wake lock, notification restart/stop actions, and Quick Settings tile
- Abridged, intermediate, and padded-intermediate MTProto framing
- English and Russian UI
- Runtime traffic, connection, fallback, error, and pool statistics
- Optional time-limited local diagnostic log with explicit export and redaction
- Daily update notification with a static repository manifest fallback
- Strict configuration validation and persistent DataStore settings

## Requirements

- Android 8.0 or newer (API 26+)
- No root access
- JDK 17 for source builds

## Usage

1. Install the APK and open TG WS Proxy.
2. Start the proxy from the app or the Quick Settings tile.
3. Tap **Open in Telegram**, or copy the generated `tg://proxy` link.
4. Confirm the MTProto proxy in Telegram.

The default listener is local-only. Binding to `0.0.0.0` exposes the proxy to
the local network; clients still need the generated secret.

## Configuration

| Setting | Default | Description |
|---|---|---|
| Host | `127.0.0.1` | Local listen address or hostname |
| Port | `1443` | MTProto listener port |
| Secret | Random | Persisted 16-byte secret shown as 32 hexadecimal characters |
| DC redirects | DC 2 and 4 | `DC:IP` WebSocket target mappings |
| CF proxy | Enabled, first | Cloudflare domain fallback and priority |
| Pool size | `4` | Pre-established connections per DC and media mode, `0..16` |
| Buffer size | `256 KB` | Socket buffer size, `4..4096 KB` |

Saved settings apply after the running proxy is restarted.

## Update Checks

The app checks the repository's latest GitHub Release at most once every 24
hours. If the API is unavailable or rate-limited, it falls back to
[`update.json`](update.json) from the `master` branch. A manual check is also
available on the About screen.

For compatibility with installations made before `update.json` was published,
an HTTP 404 falls back to the literal `versionCode` and `versionName` assignments
in `app/build.gradle.kts`. Other manifest errors are reported instead of being
silently ignored.

The manifest contains only `versionCode`, `versionName`, and an HTTPS release
page URL. The app accepts release URLs only for this repository and opens the
page in the user's browser. It does not download or install APKs itself. Android
continues to enforce the application signing certificate during installation.

For a release, update the literal version assignments in `app/build.gradle.kts`
and the matching values and tag URL in `update.json` before publishing the tag.

## Transport Behavior

The traffic path is:

```text
Telegram -> local MTProto listener -> direct WSS -> Cloudflare WSS -> Telegram TCP
```

The direct WebSocket path connects to the configured redirect IP while using a
Telegram domain for TLS SNI and the HTTP `Host` header. WebSocket frames and
buffered MTProto packets are limited to 16 MiB to prevent unbounded allocation.
Pools are discarded after a network handover so connections from a previous
Wi-Fi or mobile route cannot be reused.

TLS certificate verification for proxy WebSocket traffic is intentionally
disabled to preserve the upstream transport behavior used for IP redirection
and censorship bypass. SNI, WebSocket upgrade validation, frame validation,
and `Sec-WebSocket-Accept` verification are still enforced. Do not change this
trust model without testing all direct and Cloudflare routes.

## Building

```bash
# Unit tests
./gradlew :app:testDebugUnitTest

# Android lint
./gradlew :app:lintDebug

# Development APK
./gradlew :app:assembleDebug

# Minified release APK
./gradlew :app:assembleRelease
```

Build outputs:

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

Release signing is enabled only when ignored file
`app/keystore/signing.properties` and its referenced keystore are present.
GitHub release builds require the configured signing secrets.

## Architecture

- `ui/`: Compose screens and view model
- `data/ConfigRepository.kt`: Preferences DataStore persistence
- `service/ProxyService.kt`: Android foreground-service lifecycle
- `proxy/TgWsProxyServer.kt`: listener and client-session lifecycle
- `proxy/Bridge.kt`: upstream selection and bidirectional re-encryption
- `proxy/RawWebSocket.kt`: custom TLS/RFC 6455 transport
- `proxy/WsPool.kt`: pre-established direct WebSocket connections
- `proxy/CfProxyDomains.kt`: validated Cloudflare domain management

## Credits

Maintained by [crim50n](https://github.com/crim50n).

Inspired by [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) by Flowseal.
This Android implementation is maintained independently.

## License

[MIT License](LICENSE)
