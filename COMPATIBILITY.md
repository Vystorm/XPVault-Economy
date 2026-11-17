## Spigot/CraftBukkit release 1.19.0-26.2

Requires Core0.64.0+. Official Spigot26.3 and CraftBukkit26.3 combined startup and registration tests passed. Existing automated tests and configured older-Paper compilation checks passed. This is not a claim that every gameplay scenario was exercised on each platform.

Existing settings, saved data and all seven language bundles are retained.

# Compatibility — 1.16.0 (2026-11-03)

Target range: **Paper 1.20.6 – 26.x**, Java **21 bytecode**, Vystorm Core **0.64.0+**, VaultUnlocked. Use the Java runtime required by the chosen Paper release. The historic `-26.2` filename suffix does not express the minimum.

All production sources compile against the local Paper 1.20.6 API and its libraries. The independent offline `compat1206` task is required by `check`; override the library root with `-Ppaper1206Libraries=/path/to/libraries`. No current Paper jar is included in that compilation classpath. Tests use the current API, with a separate isolated classloader verifying feature detection, chat fallback selection and plugin class linkage against the actual 1.20.6 libraries.

Core owns the separate native-dialog adapter and selects it by API class availability. XPVaultEconomy uses Core's portable dialog model and local portable navigation buttons. On 1.20.6, account, payment/request, bank, standing-order and statement dialogs appear in chat with inputs and confirmation actions. No economy feature is disabled. Native dialogs retain their existing forms and callbacks on current Paper. Optional web/client integrations retain their own requirements.

This release has compile and unit-test evidence, not a new live-server certification. The maintainer should test startup on Paper 1.20.6-151 with Java 21, Vault registration and purchases, chat form input/confirm/cancel, bank transfers, requests, standing orders, statement export, cheques, XP rewards and persistence after restart. Also verify native dialogs on current Paper, plus optional web/client integrations where installed. No server was started during this backport.

## Historical verification (previous release, 2026-10-22)

The following results predate 1.16.0 and do not describe its minimum requirements.

Minimum for the current six-plugin collection: **Paper/Purpur 1.21.8, Java 25**. Use Core 0.28.1 wherever Core is installed. The historic `-26.2` filename suffix does not express the new minimum.

Validated builds: Paper 1.21.8 #60, 1.21.11 #132, 26.1.2 #74, 26.2 #134 and 26.3 #211; Purpur 1.21.8 #2497 and 26.3 #2654. On each: 17 XP checks, 321 block/dialog checks, 162 tree generation cases / 500 checks including actual world storage/reload/rebuild and listener registration. Seven restarts confirmed all six plugins/dependencies and the enabled pause-menu data pack. Current collection unit results: 1,265 passed, eight database tests skipped, zero failures; the unchanged BetterPortals result is reused.

Paper 1.21.7 is excluded because Player.closeDialog() is absent. 1.21.6 lacks the required native dialog API. The new API declaration is supported by functional tests, not by startup alone. Compile against Paper 1.21.11 / Adventure 4 for this range; newer Adventure builder call descriptors are not backward compatible.

Tested dependencies: VaultUnlocked 2.20.1 and ProtocolLib 5.5.0-SNAPSHOT-f0e959a. Automatic ProtocolLib update checks were disabled in test fixtures after an updater-only shutdown race. ProtocolLib warns that its author has not tested 26.3. Real-client portal rendering/teleportation, proxy/payment crash recovery, database integration, optional web and client/resource-pack combinations were not certified. Resource-pack formats must match the actual server/client version. No Folia/plain-Spigot or paid-plugin compatibility claim. Do not hot-reload.

Full local evidence is in `~/Documents/Codex/2026-10-22/free-plugin-compat/`; the sanitized report and reproducible probe sources are versioned in the vystorm-site repository under `tools/marketplace/reports/2026-10-22-backport-*` and `tools/marketplace/compatibility/`. No live deployment was performed.
