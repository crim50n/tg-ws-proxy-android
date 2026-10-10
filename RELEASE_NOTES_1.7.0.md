# TG WS Proxy 1.7.0

## Highlights

- Added optional proxy startup after Android finishes booting. It is disabled by default and saved immediately when enabled in Settings.
- Added quiet new-version notifications only while the proxy service is running. Automatic checks run at most once every 24 hours; stopped apps are not scheduled or awakened for update checks.
- Update notifications appear once per version, survive internal proxy restarts, and are removed when the service stops. Tapping opens update controls without automatically downloading or installing an APK.

## Transport reliability and efficiency

- Added per-endpoint TCP fallback single-flight and exponential failure backoff, reset on network changes. Failed and interrupted connection attempts are closed.
- TCP connections are considered ready only after relay initialization has been sent successfully, without sending it twice.
- Ordinary WebSocket sessions no longer use Telegram's media-only domain as a fallback. Media sessions keep their existing domain order.
- Added the HTTP OPTIONS prefix to reserved obfuscated-handshake patterns.
- Prepared WebSocket connections now rotate automatically and retry failed refills with exponential backoff using monotonic deadlines.
- Successful parallel pool connections become available immediately. Pending connections are closed on stop, network reset, or coroutine cancellation; obsolete results cannot repopulate a replacement pool.
- Pool maintenance uses expiration and retry deadlines rather than periodic polling. The existing efficient defaults remain unchanged; disabling prepared connections disables pool dialing and rotation.
- Removed duplicate AES-CTR work from MTProto packet splitting by reusing plaintext already available in the bridge.

## Privacy and update lifecycle

- Removed private Cloudflare domains and endpoint-bearing transport error messages from affected Logcat paths.
- Automatic UI and service update checks share a single interval gate. Cancellation closes metadata requests even while a response body is being read.
- Notification navigation preserves an open Settings draft and does not replay consumed notification intents after activity recreation.
- Added aligned English and Russian startup and notification guidance.

## Verification

- JVM unit tests, Android lint, debug builds, and R8-minified release builds passed with JDK 17.
- Real-device boot delivery, OEM background restrictions, and notification behavior still require device validation.
