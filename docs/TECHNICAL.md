# XPVaultEconomy: technical details

Companion to the [README](../README.md). Storage, client mod, web page, requests and testing.

## Vault provider

- Registers as `XPVaultEconomy` with priority Highest, for both the legacy Vault economy API and Vault 2.
- Whole numbers only; fractions and negative amounts are rejected. No bank accounts. World-specific calls use the same global account.
- Online balances are synchronised with the experience bar every second (`sync.online-interval-ticks`), changed accounts saved every five seconds. Offline balances are kept in storage.
- On first join the XP a player already has is imported, plus anything deposited through Vault before.
- Texts other plugins receive through Vault (`format()`, currency names, error messages) have no viewer. They use the fixed `language` if set, otherwise Core's default language. A `config.yml` from before 1.6.0 that still has the German default currency names keeps German Vault texts.

## Storage

- **`yaml`** (default): `plugins/XPVaultEconomy/data/balances.yml`, written through Vystorm Core's `VersionedStore` (JSON state with a schema version, crash-safe write). An unreadable file is backed up next to it (`.corrupt-<time>`) and the plugin stays disabled instead of starting with empty balances.
- **`mysql`**: Vystorm Core's shared database (`database.shared`, credentials only in Core's config). Table `<storage.table-prefix>_balances` (default `xpeco_balances`), schema version in `vystorm_schema_versions`. Accounts are loaded off the server thread once Core reports the database. Until then no Vault provider is registered and the commands answer "still loading". Without the database the plugin disables itself, with no fallback to the file, so balances never diverge between servers. All database access runs on the storage thread.
- Online-bonus streaks are always local: `data/online-rewards.yml` (also a `VersionedStore`).
- No automatic import of old data: `balances.yml`/`online-rewards.yml` in the plugin folder and the own MySQL table of 1.7.0 and older (`storage.mysql.*`) are not read any more. Transfer them manually if needed.

## Requests, limits and history

- `/economy request` asks for a payment; `/economy requests` lists your requests with accept/decline. Open requests expire after 24 hours. A balance and level preview appears before every payment. Requests alone never move XP.
- `/economy split` divides a whole-number total across at most 16 distinct payers. Remainder points go out by stably sorted UUIDs, so the sum stays exact.
- Rate limits: at most 16 new requests per minute and 20 open incoming requests per payer.
- `/economy limits` sets personal limits (0 = unlimited). The daily limit uses UTC days and covers every player payment path including web and client. Shops, direct Vault/API bookings and normal XP changes are separate.
- `/economy history` shows the last ten received bookings; `history export` writes up to 100 own rows to `exports/<UUID>.csv`. The server-wide history holds at most 2,000 bookings. Player payments carry the same payment ID on both sides. Generic Vault/API calls only record credit or debit as the cause, since Vault passes no business reason. Vanilla XP changes are not logged.
- Requests, limits, privacy and history are local files per server, even when balances use MySQL. There is no cross-server limit guarantee.
- An accepted request is stored as `PROCESSING` before the account moves and is not retried automatically after a crash. Such entries and leftover limit reservations must be checked against balances. History is saved through the storage worker and is not a recovery source; the latest entries can be missing after a crash. There is no shared atomic transaction across balance storage, requests and history. Unreadable files are never overwritten.

## Privacy and AFK pause

`/economy privacy on` hides the account from public leaderboards and other players' balance lookups. The owner and authorised admins still see it. Settings are per UUID and apply offline. Unreadable privacy data hides public balances until fixed.

`online-rewards.afk.pause-after-seconds` (0 = off) pauses the bonus. Movement or looking around, interactions, inventory actions, building, commands and chat count as activity. AFK time gives no catch-up bonus. This is a simple activity check, not bot detection.

## Savings goal and favourites

A goal is a target and label stored in the player's persistent data; it never reserves or moves XP. `notify on` sends a one-time notice when it is reached. Progress shows in the native panel, the account dialog and the web `/account` response. With Core 0.26.5's optional onboarding capability, setting a goal also completes its plugin-feature task.

Up to 20 payees are saved by UUID and current name. Picking one only fills the recipient field; the normal confirmation is still needed.

## Vystorm client mod

With the client mod and Vystorm Core 0.25.1+ (client channel on), players get native displays. Everyone else keeps chat, action bar and dialogs.

| Feature | With the mod | Without the mod / UI hidden (H) |
| --- | --- | --- |
| XP account (`/vmenu`, `/economy`) | Native panel: balance, level progress, savings goal, online bonus, payment preview, leaderboard | Dialog menu |
| Balance HUD (top right) | Balance, level, next bonus payout with seconds; toggle in `/vsettings` → XP account display | – (`/money`) |
| Online bonus payout | Toast | Action bar or chat (`online-rewards.notifications.*`) |
| Payment received | Toast plus chat line | Chat line with reply button |

With Core 0.26.4 and client 0.9.0 the bonus countdown is a bar below the balance box that the client counts down itself. Both elements stack with other plugins' HUD elements. The native panel shows the current-to-projected level preview first, then needs a separate Confirm payment button; the dialog shows the same preview on its confirmation screen. Direct `/pay` and web payments behave as before.

## Web page

With Vystorm Core 0.24.0+ and `web.enabled: true` in Core's config, XPVaultEconomy adds the page "XP Account" (module `economy`) to the Vystorm web panel: in the browser via `/web` or `/web economy`, and as an in-game overlay for players with the client mod. Without it, everything stays available in game.

| Tab | What it shows / does | Permission |
| --- | --- | --- |
| Account | Live balance, level and XP bar progress, limit, online-bonus streak with countdown to the next payout (or remaining rejoin grace) | `xpeconomy.balance` |
| Pay | Payment form with confirmation. Same checks and texts as `/pay`; XP comes off the experience bar, so the payer must be in the game | `xpeconomy.pay` |
| Leaderboard | `/baltop` pages (same cache, page size and hidden prefixes), own row highlighted, own rank on other pages, Pay per row | `xpeconomy.top` |
| Admin | Look up a balance (`xpeconomy.balance.others`); give/take/set with confirmation, account count and storage, link to the server settings page (`xpeconomy.admin`) | as listed |

- **Open from the game:** `/economy web`, the Open in web button in the account dialog, and a `[Web]` button in `/money` and `/baltop` (only while the page can be opened).
- **Security:** the browser is never trusted. Every route runs the same plugin methods as the commands, on the server thread; Core checks session, CSRF token, permissions and rate limits first. Payments and admin changes carry a request id, so a double click or retry never moves XP twice. Admin changes go to Core's web audit log.
- **Settings:** server settings appear on Core's settings page (`/app/settings`); the economy page only links there.
- **Texts:** `web.*` in `lang/<code>.yml` (page texts below `web.ui.`).
- **HTTP API** below `/api/economy`, session required: `GET /meta`, `GET /account`, `GET /top?page=N`, `POST /pay {target, amount, requestId}` (online), `GET /lookup?name=`, `GET /admin/info`, `POST /admin/change {action: give|take|set, target, amount, requestId}`. Live events: `account` (own account after every change), `config` (settings reloaded).

## Languages

Texts live in `plugins/XPVaultEconomy/lang/en.yml` and `de.yml` (MiniMessage, `{placeholders}`), copied there on first start. With `language: auto` every player sees their client language, unknown languages fall back to English, and players can pick a language in `/vsettings`. To add one, copy `en.yml` to e.g. `fr.yml`, translate it and run `/economy admin reload`. Missing keys fall back to English.

## Integration test jar

`./gradlew integrationJar` builds a separate server test adapter (`XPVaultEconomyTest.jar`). It only runs with `-Dvystorm.xp.integration=true` on `127.0.0.1:25580`, stops the isolated server afterwards and must never be installed on a production server.
