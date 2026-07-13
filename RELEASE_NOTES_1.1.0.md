## TG WS Proxy 1.1.0

This release focuses on transport reliability, bounded resource usage, and
Android service lifecycle correctness.

### Changed

- Rebuild the direct WebSocket pool after Wi-Fi or mobile network changes.
- Use validated Cloudflare domains with independent sticky selection per DC.
- Expand the bundled Cloudflare fallback domain pool.
- Apply a cooldown to direct WebSocket IPs that time out.
- Validate listener, DC redirect, Cloudflare, buffer, pool, and secret settings.
- Report the proxy as running only after the local listener is bound.

### Fixed

- Remove unsafe mid-session retry behavior that could desynchronize MTProto AES-CTR streams.
- Bound TLS handshakes, HTTP upgrade responses, WebSocket frames, and MTProto packets.
- Validate WebSocket upgrade headers and support fragmented frames.
- Close active client sockets during service shutdown.
- Prevent stale pool refill tasks from restoring connections from an old network.
- Stop logging the MTProto secret and full proxy link.
- Prevent the persisted proxy secret from being included in Android backups.

### Verification

- Added unit coverage for MTProto splitting, packet limits, secret parsing,
  DC redirect parsing, and Cloudflare domain validation.
- Release builds run unit tests and Android lint before APK publication.
