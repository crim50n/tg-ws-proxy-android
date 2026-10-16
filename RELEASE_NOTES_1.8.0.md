# TG WS Proxy 1.8.0

## Connection awareness

- The app now distinguishes a missing network, Wi-Fi sign-in, Android network restrictions, unconfirmed internet access, and Telegram connection problems.
- Network outages pause upstream attempts, prepared connections, and route checks without stopping the local proxy service.
- Outage errors and delayed results from previous networks no longer influence automatic routing. Recovery on the same network is handled too.
- Telegram responses can confirm connectivity even when Android cannot validate internet access. A successful transport handshake alone is not reported as a working Telegram connection.
- Network changes cancel pending TLS and DNS-over-HTTPS requests. Routing decisions are isolated by runtime and network epoch.

## Simpler interface

- Added a compact connection status with an icon and green, amber, or red indication.
- Removed technical route lists, explanatory paragraphs, the redundant status heading, and the manual connection-check button from the main screen.
- Route-order details are shown only when detailed statistics are enabled.
- Material You adaptive colors are now disabled by default. Previously saved appearance choices are preserved.

## Power and notifications

- Existing efficient power defaults and media-stall thresholds are unchanged.
- Connection awareness uses Android callbacks and existing traffic, without adding periodic internet tests or network-outage notifications.
- Optional route probes remain controlled by the existing setting.

## Verification

- JVM unit tests, Android lint, debug builds, and R8-minified release builds passed with JDK 17.
- Real-device Wi-Fi/mobile handover, captive portal, VPN, and OEM network restrictions still require device validation.
