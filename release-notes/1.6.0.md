# TG WS Proxy 1.6.0

## Highlights

- Added a more efficient default power profile based on background CPU and radio-wakeup testing.
- Device wake lock, prepared WebSocket connections, periodic route checks, and background Cloudflare-domain updates are now disabled by default.
- The default idle WebSocket keepalive interval is now 180 seconds instead of 30 seconds.

## Existing installations

- Existing installations are migrated only when all five effective power settings still match the previous defaults.
- If any power setting differs from the previous default, the complete profile is preserved without modification.
- Users whose profile is migrated see a one-time explanation listing every changed setting and where to adjust it if their network requires a more persistent background connection.

## Interface and guidance

- Added scrollable English and Russian migration guidance suitable for smaller screens and larger accessibility font sizes.
- Updated the Power usage help section to explain the new defaults, expected battery benefit, reliability tradeoffs, and exact migration rule.
