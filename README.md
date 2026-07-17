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
- Optional Cloudflare-first routing for every Telegram DC
- Automatic per-network route selection with lightweight direct/Cloudflare/TCP probes
- Route-aware direct WebSocket pooling and automatic reset after network changes
- Foreground service, wake lock, notification restart/stop actions, and Quick Settings tile
- Abridged, intermediate, and padded-intermediate MTProto framing
- English and Russian UI
- Optional detailed connection, traffic, route, error, and pool statistics
- In-memory live connection log and explicit five-minute diagnostic report export
- System, Light, Dark, and Android 12+ Material You appearance
- Built-in English and Russian help for routing and diagnostics
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
| Automatic connection mode | Enabled | Selects a temporary route for the current network without overwriting manual settings |
| DC redirects | DC 2 and 4 | Manual-mode `DC:IP` WebSocket target mappings |
| CF proxy | Enabled, before TCP | Manual-mode Cloudflare fallback before direct TCP |
| CF first | Disabled | Manual-mode Cloudflare priority for every Telegram DC |
| Pool size | `4` | Manual-mode pre-established connections per DC and media mode, `0..16` |
| Buffer size | `256 KB` | Manual-mode socket buffer size, `4..4096 KB` |

Automatic mode starts each proxy run and network with `WS -> CF -> TCP`, a
256 KB socket buffer, and a direct WebSocket pool of 4. When it selects
`CF -> WS -> TCP`, direct pool warmup is disabled. Turn automatic mode off to
show all manual routing, server, buffer, and pool controls.

Settings are saved automatically. Leaving Settings restarts a running proxy
only when effective connection settings changed.

### Automatic Connection Mode

Automatic connection mode is enabled by default. Each proxy start and each
validated network handover begins with this route order:

```text
WS -> Cloudflare -> TCP
```

The app runs two lightweight direct WebSocket, Cloudflare, and TCP reachability
rounds for DC 2 and DC 4. Probe results are isolated per runtime so a delayed
result from an old network cannot change a replacement runtime. Probes do not
contain Telegram account data and are not included in user traffic counters.

Repeated direct failures with successful Cloudflare checks can temporarily
select `Cloudflare -> WS -> TCP` for the current runtime. Any successful direct
WebSocket observation prevents that automatic switch. Automatic decisions are
not written to DataStore and do not overwrite manual settings. The effective
route order is displayed on the main screen.

Automatic mode also watches active direct WebSocket media sessions. If a media
session sends requests without receiving data, the app closes the stalled
connection and routes that datacenter's media retries through Cloudflare for one
minute. A repeated stall after a direct retry extends the override to ten
minutes. Other datacenters and regular Telegram sessions keep their current
route. This per-session fallback resets on a proxy restart or network change and
is disabled in manual mode.

Automatic performance defaults are route-aware:

- `WS -> Cloudflare -> TCP`: 256 KB socket buffer and direct WS pool size 4
- `Cloudflare -> WS -> TCP`: 256 KB socket buffer and direct WS pool disabled

Turn automatic connection mode off to expose every manual routing, server,
Cloudflare domain, buffer, and pool setting. In manual mode the app may display
or notify a recommendation, but it never applies the recommendation or restarts
the proxy because of it.

### Diagnostics

The Connection log always keeps the latest 300 redacted events in memory. It
does not continuously write them to storage. Start a diagnostic report
explicitly to record up to five minutes for export. Reports redact proxy links,
secrets, IPv4 addresses, and IPv6 addresses. Deleting a saved report does not
clear the in-memory events shown on screen.

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

The default traffic path is:

```text
Telegram -> local MTProto listener -> direct WSS -> Cloudflare WSS -> Telegram TCP
```

The direct WebSocket path connects to the configured redirect IP while using a
Telegram domain for TLS SNI and the HTTP `Host` header. WebSocket frames and
buffered MTProto packets are limited to 16 MiB to prevent unbounded allocation.
Pools and active sessions are discarded after a validated Wi-Fi, cellular, VPN,
or Ethernet handover so connections from the previous network cannot be reused.
Automatic mode then starts from the default route order and reevaluates the new
network. Manual mode keeps the configured route and rebuilds only its direct WS
pool.

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
