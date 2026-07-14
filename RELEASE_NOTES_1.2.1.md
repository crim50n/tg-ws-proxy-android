## TG WS Proxy 1.2.1

This patch release improves connection stability and diagnostic privacy.

### Changed

- Keep active WebSocket and direct TCP connections alive during idle periods.
- Try Cloudflare fallback domains with limited parallelism.
- Back off Cloudflare domains that return HTTP 429, from 45 seconds up to
  5 minutes.
- Stop Cloudflare domain refresh when the proxy runtime exits.
- Avoid recording normal coroutine cancellation as a client-session failure.

### Diagnostics

- Redact IPv4 and IPv6 addresses from exported diagnostic logs.
- Preserve packet counters while continuing to redact sensitive packet data.
