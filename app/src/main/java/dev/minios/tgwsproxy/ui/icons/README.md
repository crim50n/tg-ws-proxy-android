# Tabler Outline Icons

`TablerIcons.kt` vendors 34 icons from [Tabler Icons v3.31.0](https://github.com/tabler/tabler-icons/tree/v3.31.0/icons/outline), by Paweł Kuna and contributors, under the MIT license. The unmodified upstream license is bundled in the APK at `app/src/main/assets/licenses/tabler-icons.txt`.

Regenerate from the repository root with `python3 tools/generate_tabler_icons.py` (requires network access). The generator downloads only the selected SVGs and the license from the pinned release. Each generated property links to its source SVG. SVG path data is retained verbatim; the provider preserves the original 24x24 viewport, no fill, 2px stroke, and round caps and joins. No icon library dependency is required.

Properties are lazy, cached Compose `ImageVector` instances, compatible with existing screen helpers and `Icon` tinting. Back, external-link, route, and left/right switch icons use Compose RTL auto-mirroring. Media playback, refresh, text/document, and non-directional symbols retain their upstream orientation. Application launcher and notification/tile artwork is not part of this subset.

Material-to-Tabler semantic substitutions include `NetworkCheck` to `Network`, `SystemUpdate` to `DeviceMobileDown`, `BatterySaver` to `BatteryEco`, and `ReceiptLong` to `Receipt`. Screen sizing, content descriptions, tinting, and interaction behavior are unchanged.
