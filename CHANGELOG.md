## 1.27.0-26.2 — 2026-11-22

- New ranking `xpvault:balance`: the richest players by XP balance, the same list as `/baltop`. Vystorm Boards shows it on a board with `{type: top, source: "xpvault:balance", rows: 10}`, and the same values are placeholders, for example `%vystorm_xpvault_top_balance_1_name%` and `%vystorm_xpvault_rank_balance%`. Offline players are listed from the first start, because the balances are already stored.
- Players who hid their balance with `/economy privacy` are not listed and have no rank or value in it, as on `/baltop`; accounts hidden by `top.hide-name-prefixes` are left out too. While `privacy.enabled` is off every balance is visible again, as before.
- Showing the ranking on a board needs Vystorm Core 0.67.0 or newer and Vystorm Boards 1.21.0 or newer. The plugin itself still runs with Vystorm Core 0.65.0 or newer; with an older Core it offers no ranking and everything else works as before.
- New switch `rankings.enabled`, on by default and listed in `/vadmin features` as "Rankings" in seven languages. Off, or with `top.enabled` off, the ranking is not offered; the change applies on `/economy admin reload` without a restart.
- The old placeholders `%vystorm_xpvault_top_<n>_name%`, `%vystorm_xpvault_top_<n>_balance%` and `%vystorm_xpvault_rank%` stay as they are. No data file changes.

## 1.26.2-26.2 — 2026-11-20

- Fixed: after a term deposit or a donation goal booking, `/economy history`, statements and `/economy admin history` said the history was unavailable after the next server start. The journal file was kept as it was, so all bookings show again with this version.
- New setting `history.max-entries` (default 2,000, as before; 500 to 20,000): how many bookings the server keeps for history and statements. With shop or job plugins 2,000 can cover less than a day; raise it if 7 and 30 day statements look short. Applies on `/economy admin reload`; the statement and history texts name the current number. A language file already on your server keeps its own text.
- Going back to an older version: older versions show the history as unavailable, and leave the file untouched, when the journal holds more than 2,000 entries or any term deposit or donation booking. Before a downgrade, set `history.max-entries` back to 2000 and start and stop the server once to remove the first cause. Balances are never affected; the journal is informational only.

## 1.26.1-26.2 — 2026-11-20

- Fixed: a `config.yml` with a YAML mistake no longer resets the settings on `/economy admin reload`, and the settings menu no longer saves over it. The file is left as it is, the console and the reload answer name the line and column of the mistake, and the last working settings stay active (at start: the default settings).
- Fixed: an unreadable `data/away-income.json` is copied to `away-income.json.broken-<date-time>` before the next payment writes the file again.
- `/vadmin features` names every switch of the plugin in seven languages ("Payments", "Term deposits", "AFK pause") instead of the key path. Six switches (online bonus, bonus notifications, bonus status, banks, standing orders, away summary) had lost the names they already had in `/vadmin config`; they show them again.
- A feature that needs another switch now names it in full: "This needs “Payments” (payments.enabled), which is switched off on this server." Before, the message only gave the key. A language file already on your server keeps its own text.
- README: the supported servers are written as tested: Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2.

## 1.26.0-26.2 — 2026-11-18

- Requires Vystorm Core 0.65.0 or newer. Update Core before you update this plugin.
- Every feature can now be switched on and off in `config.yml`. All switches are on by default, so a server that updates keeps working as before; the new keys are added to your existing `config.yml` with their comments. Each switch is listed in Core's `/vadmin features` with a one-line description in seven languages and applies at once, without a restart (only `tutorial.enabled` needs one).
  - Paying: `payments.enabled` (`/pay`, favourites, the pay form, web payments, "Pay" in the player menu), `requests.enabled` (requests and splitting; asking and accepting also need `payments.enabled`), `limits.enabled` (off: the players' limits are not applied but stay stored), `cheques.enabled` (off: no new cheques; issued cheques can still be redeemed).
  - Your account: `history.enabled`, `statements.enabled`, `privacy.enabled` (off: every balance is visible again; the players' choices stay stored), `savings-goal.enabled`, `account-menu.enabled` (off: a bare `/economy` shows the balance line), `top.enabled` (the leaderboard).
  - Online bonus: `online-rewards.afk.enabled` is the new switch of the AFK pause; `pause-after-seconds: 0` still means no pause.
  - Menus and pages: `player-menu.enabled` (the entries in Core's player menu), `web.enabled` (the XP Account web page), `tutorial.enabled` (needs a restart).
  - Keys that already existed keep their names and are now listed as switches too: `online-rewards.enabled`, `online-rewards.notifications.enabled`, `online-rewards.show-status-in-expbal`, `banks.enabled`, `standing-orders.enabled`, `term-deposits.enabled`, `funds.enabled`, `economy.away-summary`.
- No switch takes or deletes XP. Banks, requests, standing orders, deposits, donation goals and cheques stay stored. Term deposits are still paid back when their term ends while `term-deposits.enabled` is off, and donation goals are now also paid out or refunded while `funds.enabled` is off (before, they waited for the switch).
- A switched-off feature answers one shared message, "This feature is switched off on this server.", its subcommands are hidden from help and tab completion and its buttons leave the account menu, the panel, the web page and the player menu. A feature that needs another switch names it.
- Tab completion and help follow a switch at once, without registering the command again.
- Fix: the confirm dialog of `/pay` and of accepting a payment request no longer reopens when your balance changes while it is open (for example through the online bonus), so a click on Confirm pays. The amount is checked against your balance at the moment you confirm; if it no longer suffices, the dialog says so and nothing is paid.
- Placeholders: the plugin registers its values with Vystorm Core under the namespace `xpvault`: balance (plain and formatted), level, rank, leaderboard places, XP in banks, savings goal progress, online bonus streak, next payout and time to it, and the number of accounts. Examples: `%vystorm_xpvault_balance%` (the balance as a number) and `{xpvault:top:1:name}` (the name on place 1). `/vadmin placeholders xpvault` lists them. A value of a switched-off feature is empty.
- README: seven languages instead of three, donation goals in the feature list, the Spigot test of 1.19.0 named in the requirements, and the old "no bank accounts" line corrected.

## 1.25.0-26.2 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`).
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.24.0-26.2 — 2026-11-16

- Account panel: a new "More" section with buttons for Send XP, Payment requests, XP banks, Donation goals, Standing orders, Term deposits, the XP statement and the Leaderboard. Each button closes the panel and opens the menu that was only reachable by command before; Payment requests shows your request list in chat, as `/economy requests` does. A button appears only when its feature is switched on and you have its permission.

## 1.23.0-26.2 — 2026-11-12

- Donation goals: `/economy fund` or the account menu → Donation goals. A player opens a public goal with a title, a target and a deadline; anyone donates XP. When the target is reached the XP goes to the owner at once; if the deadline passes or the owner cancels, every donor gets their XP back. Bookings are crash-safe like XP banks. With Vystorm Core 0.55.0 or newer, offline players find the result in their inbox. Settings under `funds`, also in the first-run setup.

## 1.22.0-26.2 — 2026-11-11

- Term deposits: `/economy term` or the account menu → Term deposits. Lock XP for 1, 7 or 30 days (configurable) and get it back with interest when the term ends, also while you are offline. Taking it out early returns the XP without interest. Every booking is crash-safe like XP banks and standing orders. With Vystorm Core 0.55.0 offline owners find the payout note in their inbox. Settings under `term-deposits`, also in the first-run setup.

## 1.21.0-26.2 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.

## 1.20.0-26.2 (2026-11-07)

Extend the existing shared XP banks into configurable group treasuries. `/economy bank policy <bank> <member> <deposit:on/off> <withdraw:on/off> <single-XP> <daily-XP>` sets independent deposit and withdrawal rights plus single-payment and daily withdrawal limits for an existing member. The bank dialog exposes the same policy editor. Values are whole XP from 0 to 2147483647; zero is a zero allowance, not unlimited. UTC midnight resets daily usage. Owners and `xpeconomy.admin` retain full access; unchanged legacy members retain their existing access.

Policy changes, daily usage, balances and pending player bookings use the existing atomic `data/banks.json` write and economy transaction lock. Policy write failures retain the old policy; failed money commits restore the player's booking and do not consume daily allowance. Removing and re-adding a member does not erase same-day spending. Existing bank files load without conversion. Do not downgrade a server relying on the new restrictions to an older version that ignores these new fields.

`/economy bank history <bank> [page]` and the bank's history button expose the latest 500 durable bank records, including actor, time, balance changes and policy details. History survives restarts while the bank exists; explicitly deleting a bank removes its account history. The existing trusted Vault bank API has no acting-player identity, so player-specific limits apply to player commands/dialogs, not arbitrary privileged plugin API calls. All new interface/help text is provided in seven bundled languages.

## 1.19.0-26.2 - Spigot and CraftBukkit compatibility

- Require Vystorm Core 0.51.0 or newer.
- Observe Bukkit chat and movement events for reward activity and use shared portable menus and messaging.
- Validation: 173 automated checks (0 skipped), plus combined startup, listener registration and Core diagnostics/help on official Spigot26.3 and CraftBukkit26.3.

## 1.18.0-26.2 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Include translated tutorials in all four languages.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

## 1.17.0-26.2 (2026-11-05)

- `/economy statement summary [1|7|30]` shows a private chat summary of retained personal bookings, incoming XP, outgoing XP and net change by booking type. Uses the existing balance permission and bounded journal; no dialog, export or account changes. English, German and Chinese command names and messages are included.

## 1.16.0-26.2 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode and `api-version: '1.20.6'`; newer servers still need their own required Java runtime.
- Requires Vystorm Core 0.48.0. Account, payments, requests, banks, standing orders and statements use Core's portable dialogs. Servers without native dialogs show forms and confirmations in chat; no economy feature is disabled. Native dialogs remain available on current Paper. The optional client mod retains its own server/client requirements.
- Offline `compat1206` compilation is part of `check`; production classes also compile against the baseline API and Adventure libraries. Tests cover native versus chat selection and plugin class loading with the actual 1.20.6 libraries.

## 1.15.0-26.2 (2026-11-03)

- New: **personal XP statements**. `/economy statement [1|7|30]` opens a dialog with incoming XP, outgoing XP and net change, grouped by booking type, for the last 1, 7 or 30 days (default 7). Switch periods using buttons or open it from the account dialog. Export the displayed snapshot, or use `/economy statement export [1|7|30]` for a fresh CSV with totals, breakdown and every retained personal booking in that period. Files stay in the plugin's `exports` folder and replace the previous export for that player and period. Uses `xpeconomy.balance`, localized Core commands (`auszug`, `账单`) and English, German and Simplified Chinese texts. No new config or storage migration. Statements reuse the persistent journal, clearly indicate its server-wide 2,000-entry limit and exclude vanilla XP changes; they do not claim to reconstruct the player's balance.

## 1.14.0-26.2 (2026-11-02)

- New: withdraw your own open payment requests with `/economy requests cancel <id>` or the cancel button in the request list. Individual split-payment shares can be cancelled too. Tab suggests only your open outgoing requests. Cancellation is saved atomically, survives restarts and prevents payment even from an already opened confirmation dialog; requests already processing or completed cannot be cancelled. Uses `xpeconomy.pay`, with English, German and Simplified Chinese texts and localized commands. No new configuration is needed.

## 1.13.0-26.2 (unreleased)

- New: **away summary**. Players who were offline while someone paid them (`/pay`, standing orders) get one chat line a few seconds after their next join: "While you were away you received 1,500 XP in 3 payments from Steve, Alex" (with the account button). Shown once, informational only (balances never depend on it), stored in `data/away-income.json`. Config `economy.away-summary` (default on), also in `/vadmin config XPVaultEconomy`.

## 1.12.0-26.2 (unreleased)

Needs Vystorm Core 0.45.0.

- Simplified Chinese (zh_cn) translation.
- `/money`, `/pay`, `/baltop` and `/economy` now run through Vystorm Core's command system: subcommands on every level, help, tab completion and error messages in the player's language (`/economy kasse einzahlen`, `/economy 银行 存入` work next to `/economy bank deposit` for everyone; the old German words such as `konto`, `zahlen`, `ziel`, `kasse`, `dauerauftrag`, `erstellen`, `pausieren` keep working), `on`/`off` also as `an`/`aus` or `开`/`关`, a clickable `/economy help` (`hilfe`, `帮助`), a "did you mean" for typos and entries in `/vhelp`. New localized root commands `/xpguthaben`, `/xpzahlen`, `/xprangliste`, `/xpwirtschaft` (German) and `/经验余额`, `/经验转账`, `/经验排行`, `/经验经济` (Chinese) from the language files (`commands.*`); which languages are active is set server-wide in Core (`commands.localized-aliases`). All English commands, aliases (`/bal`, `/moneytop`, `/xptop`, `/xpeconomy`, `/xpeco`) and argument forms stay; amounts, names and IDs are checked exactly as before. The commands are no longer declared in plugin.yml.

## 1.11.0-26.2 (2026-10-30)

- New: **XP banks** (`/economy bank`, German alias `kasse`). Players create named, shared XP pots (3–16 letters, digits, `_` or `-`), add members, and deposit or withdraw. XP in a bank is not on anyone's XP bar, so it is safe when you die and does not count for `/baltop`. `/economy bank` opens a dialog with your banks. Each bank shows its balance, owner, members and its latest bookings, and has an amount field with deposit and withdraw buttons. Owners also get add or remove member and close bank. Closing pays the remaining XP to the owner. Every bank change is written to `data/banks.json` (atomically) before the XP moves the other way, and it is reverted if the second step fails, so XP is never doubled or lost. Player bookings appear in `/economy history` as "XP bank". Admins (`xpeconomy.admin`) can see, withdraw from and close every bank, and `/economy admin info` shows the number of banks and their total. Permission `xpeconomy.bank` (default: everyone). Config: `banks.enabled`, `banks.max-per-player` (3) and `banks.max-members` (10). The optional `banks.vault-api` (default off) offers the banks to other plugins through the Vault bank API. Non-empty banks are never deleted through that API.
- New: **Standing orders** (`/economy standing`, German alias `dauerauftrag`). A player pays another player a fixed amount every interval (`12h`, `1d`, `1w`, at least `standing-orders.minimum-interval-minutes`, default 60). Runs are either unlimited or a set count. The first payment runs right after confirmation, and later ones also run while the payer is offline. Each run is a normal player payment: it respects personal `/economy limits`, refunds the payer when the recipient's balance is full, and is recorded on both sides as "Standing order". An order's next date is saved before the XP moves, so a crash can skip one payment but never pay twice. Dates missed during downtime are not paid afterwards. Failed runs (for example not enough XP) are reported. After `standing-orders.pause-after-failures` failures in a row (default 3), the order pauses, and the payer is told on their next join. Orders can be listed, paused, resumed and cancelled with clickable chat buttons or in a dialog. Recipients see the orders that pay them. Permission `xpeconomy.standing` (default: everyone). Config: `standing-orders.enabled` and `standing-orders.max-per-player` (10). Data: `data/standing-orders.json`.
- The account dialog (`/economy`) has the buttons "XP banks" and "Standing orders". The player menu (Core 0.43.0, sneak + right-click a player) adds "Standing order to <player>" (with the recipient filled in) and, for bank owners, "Add <player> to your XP bank". Tab completion covers both commands, including your bank names and order numbers. All new settings are in `/vadmin config XPVaultEconomy`.

- Fixed (before release):
  - A bank could no longer be withdrawn from once `economy.maximum-balance` was lowered below its balance (every withdrawal failed with "bank full"); only deposits are capped now.
  - The first standing-order payment, made right after confirmation, now queues an account save at once instead of waiting for the next save interval.
  - A crash between a bank booking or standing-order payment and the next account save (a few seconds later) could create or lose the XP of that booking. `banks.json` and `standing-orders.json` now record the player balances of each booking in the same atomic write (`pending`) until an account save contains them; on start, unsaved ones are put back (logged as a warning). Bank withdrawals and closing a bank now credit the player first and take it back if the bank file cannot be written.

## 1.10.0-26.2 (2026-10-29)

- New: XP amounts in commands accept the shorthands `k` (thousand) and `m` (million), any letter case, with dot or comma as decimal mark: `/pay Steve 2.5k`, `/economy cheque 1m`, `/economy admin give Alex 10k`, `/economy request`, `split`, `limits` and `goal set`. Amounts that are not whole XP after expansion (e.g. `1.0005k`) are refused as before; plain numbers work unchanged. The amount error messages show examples.
- New: `/economy admin history <player> [count]` shows a player's latest recorded bookings (default 10, at most 50) with time, cause, counterpart, change, resulting balance and payment ID, for support cases. Uses the existing booking journal; needs `xpeconomy.admin`. Tab completion suggests `history`, player names and counts.
- New: Player menu entries: "Pay <player>" opens the /pay dialog with the recipient filled in, "Request XP from <player>" asks for amount and purpose and sends the same request as `/economy request`, and admins (`xpeconomy.admin`) get "Booking history of <player>" (same output as `/economy admin history`). Needs Vystorm Core 0.43.0, sneak + right-click a player with an empty hand; older Cores are unaffected.

## 1.9.1-26.2 (2026-10-28)

- Registers itself for the Vystorm Core update check (Core 0.40.2+); older Cores are unaffected.

## 1.9.0-26.2 (2026-10-28)

- New: XP cheques. `/economy cheque <XP> [count]` (German alias `scheck`, also `withdraw`) takes the XP from your balance and gives you glowing paper cheques of that value (up to 64 at once). Hand them over, sell them in chest shops, store them in chests – right-click redeems one and pays the XP to whoever holds it. Every cheque has a unique id in `data/cheques.json`; copied or edited cheques redeem nothing, and a cheque that does not fit a full balance stays valid. Cheques cannot be crafted into maps or books. Permission `xpeconomy.cheque` (default: everyone). `/economy admin info` shows how many cheques are outstanding and their total value.
- `/baltop` and `/economy top` suggest the leaderboard pages that actually exist instead of always 1–3.

## 1.8.7-26.2 (2026-10-27)

- Fix `/economy requests list`: tab completion suggested `list` since 1.8.6, but the command answered with the usage error. It now shows the request list (any letter case), like `/economy requests`.
- Tab completion for `/economy requests accept|decline` now suggests the IDs of open, unexpired requests you still have to answer (newest first, at most 20).
- The `/economy` usage shown for an unknown sub-command now lists every sub-command (goal, privacy, history, favorite, request, requests, split, requestblock, limits) instead of only the original six.

## 1.8.6-26.2 (2026-10-26)

- Complete the existing favorite pay command with recipients and amounts, and expose requests list in tab completion. Payment execution and confirmation remain unchanged.

## 1.8.5 (2026-10-25)

- Accept ON/OFF in any letter case for /economy requestblock. Request ownership, storage and payment checks stay in place.

# Changelog

## 1.8.4 (2026-10-24)

- Split-payment tab completion now completes each comma-separated payer, preserves previously typed names, and excludes duplicates. Suggestions stop at the existing 16-payer limit; payment processing is unchanged.

## 1.8.3 (2026-10-22)

- Build against the older Paper/Adventure API and allow servers from 1.21.8; Java 25 remains required. Use Core 0.28.1 or newer on older servers.

## 1.8.2-26.2 – 2026-10-18

- Konfiguration im Core-Editor: ab Vystorm Core 0.27.0 ist die ganze `config.yml` unter `/vadmin config XPVaultEconomy` bearbeitbar (Recht `vystorm_core.config.xpvaulteconomy`); die 16 Werte der Server-Einstellungen tragen dieselben Beschriftungen. Speichern wirkt wie `/economy admin reload`; `storage.*`, `sync.*` und `economy.maximum-balance` erst nach einem Neustart. Ältere Cores überspringen das still.

## 1.8.1-26.2 — 2026-10-18

- **Rechte:** deutsche Beschreibungen, Sammelknoten `xpeconomy.*`, `xpeconomy.admin` enthält alle anderen Knoten; `/economy` mit `permission: xpeconomy.balance`.
- **Tab-Vorschläge:** Zahlungsanfragen erschienen bisher schon mit `xpeconomy.balance`, obwohl sie `xpeconomy.pay` brauchen – behoben. Alle Aliase, Unterargumente (`privacy`, `history export`, `requests accept|decline`, `requestblock`), Spieler, Beträge und Seiten werden vorgeschlagen. Logik in `EconomyTab`, Test `EconomyTabTest`.

## 1.8.0-26.2 — 2026-10-13

- **Payment requests and split payments:** players can request a payment, block requests from a player, or split a total across up to 16 distinct payers; each payer confirms their own share.
- **Personal payment limits:** per-transfer and UTC-day limits cover player payments through commands, web and client UI. Direct Vault/API transactions and ordinary XP changes remain separate.
- **Transaction history:** players can view and export their own recent receipts; the server keeps a bounded history with shared payment IDs on both sides. It is informational and is not an atomic recovery ledger.
- **Privacy and account controls:** players can hide balances from public leaderboards/lookups, save up to 20 payee favorites, and set a personal savings goal without reserving XP.
- **Safer XP rewards and payment review:** online rewards can pause during detected inactivity, and the account dialog/native panel previews the level and progress change before payment confirmation.
- Requests, preferences and history are local to each server even when balances use MySQL.

## Unreleased

- **Persönliches Sparziel:** Spieler können ein Ziel mit Namen speichern, dessen Fortschritt im Konto sehen und optional einmalig eine Meldung beim Erreichen erhalten. Es werden keine XP reserviert oder verschoben.
- **Zahlungsvorschau:** Dialog und nativer Konto-Panel zeigen vor der Bestätigung aktuelles und erwartetes Level samt XP-Fortschrittsbalken. Die vorhandene Überweisungsbuchung bleibt unverändert.
- **Empfängerfavoriten:** Bis zu 20 Empfänger werden mit UUID und aktuellem Namen gespeichert. Die Auswahl füllt nur das Formular aus; die bestehende Bestätigung bleibt erforderlich.

## 1.7.3

### Client-Mod

Uses the extensions of Vystorm Core 0.26.4 and client mod 0.9.0; older Cores and clients keep the 1.7.2 behaviour.

- **Bonus countdown bar:** the next online-bonus payout is a bar below the balance box that the client counts down
  itself ("Bonus +2.5 XP in 0:42", `Bar.countdown` with the `{time}` label). It is sent again only when the payout,
  the streak or the end time changes (more than 2 s drift), instead of a patch every second. Clients before 0.9.0 keep
  the seconds line in the box.
- **HUD stacking:** the balance box and the bonus bar stack with other plugins' elements at the top right instead of
  covering them.
- **Payment form asks first:** the panel's payment form shows a confirmation before sending (client 0.9.0; the server
  checks are the same either way).
- **Fixed:** the amount field of the payment form was a number range from 1 to the balance limit (2 billion steps). The
  client accepts at most 100,000 steps per range and would have rejected the whole panel. It is now an integer text
  field, checked on the server as before (whole number, 1 up to the limit).
- **Settings buttons:** "Server settings" and the new "HUD display" open Core's settings panel on the right section
  (`Ui.Button.openSettings`) instead of a dialog.
- Hidden UI (key H) is checked with `ClientUi.visible`.
- New language keys `client.hud-bonus`, `client.panel-pay-confirm-title`, `client.panel-pay-confirm-text`,
  `client.panel-hud-settings` (en, de).
- Tests render the account panel with Core's `PanelTesting`, which checks it against the client schema, and validate
  HUD elements and toasts with `ClientUiTesting` instead of Core internals.

## 1.7.2

### Client-Mod

Native displays of the Vystorm client mod through Vystorm Core (`web-client-ui` 0.20.0+, `panels` 0.25.1+). Players
without the mod, or with native elements hidden (key H), keep chat, action bar and dialogs unchanged.

- **XP account panel:** `/vmenu` → Economy → XP account and `/economy` open a native screen with balance, level
  progress, limit, the online bonus with a client-side countdown bar, a payment form (same checks as `/pay`, balance and
  permission re-checked at the click) and the leaderboard (sorted off the server thread). Without the mod the existing
  dialog opens (panel fallback); on a Core without panels the dialog stays the menu entry.
- **HUD box** (top right): balance, level and "Bonus +X XP in N s", updated at most once per second and only when it
  changed. Personal switch `/vsettings` → *XP account display* (Core `PlayerPrefs`, default on).
- **Toasts:** online-bonus payouts appear as a toast instead of the action bar/chat line; a received payment also
  shows a toast (the chat line with its reply button stays).
- New language keys `client.*` (en, de). Tests: `ClientFeaturesTest` (HUD/toast JSON, panel render output, payment
  handler).

## 1.7.1

- Admin dashboard: `/vadmin` → *XP Vault Economy* now offers every admin action (status, player balance,
  leaderboard, give/take/set, reload) with labels from the language files (`admin.dashboard.*`, en/de). Core runs
  them as the clicking admin with the normal permission checks and asks for confirmation before balance changes and
  reloads. Before, Core's built-in entry only offered *info* and *set*.
- **Storage on Vystorm Core (new format, no automatic import of old data):** `storage.type: yaml` now writes
  `data/balances.yml` through Core's `VersionedStore` (schema version, crash-safe write, unreadable files are backed
  up and stop the start). `storage.type: mysql` uses Core's shared database (`database.shared` in Core's config,
  table `<storage.table-prefix>_balances`, default `xpeco`, versioned with `SchemaMigrator`) instead of an own
  connection; `storage.mysql.*` and the MySQL driver library are gone. In mysql mode accounts load off the server
  thread before the Vault providers are registered; commands answer `storage.loading` meanwhile. The online-bonus
  series are stored in `data/online-rewards.yml` (`VersionedStore`). The final save on disable runs on the storage
  thread.
- Sync, save, web-push and online-bonus timers run in Core `TaskGroup`s that are stopped explicitly on disable (the
  bonus timer is rescheduled in place when its settings change); a failing run is logged throttled by Core and the
  timer keeps running.

## 1.7.0

- Optional web page **XP Account** (module `economy`) for Vystorm Core 0.24.0+ with the web platform, in the browser
  and in the Vystorm client overlay: live balance with level progress and online-bonus countdown, payments (same
  checks and texts as `/pay`, payer must be online), the `/baltop` leaderboard (paged, own rank, respects
  `top.hide-name-prefixes`) and admin tools (balance lookup, give/take/set, info, link to the settings page).
- Payments and admin changes from the web carry a request id and run at most once.
- `/economy web`, an *Open in web* button in the XP Account dialog and `[Web]` buttons in `/money` and `/baltop`
  (only while the page can be opened).
- New language keys `web.*`, `button.web*`, `account.web*` (en, de). Works unchanged with Core 0.22/0.23 (no page).

## 1.6.0

- English and German language files (`lang/en.yml`, `lang/de.yml`) through Vystorm Core's localization: every chat
  message, button, dialog, menu entry and server setting follows the player's language. New config key
  `language: auto` (or a fixed code).
- Money amounts are formatted per language (`1,234 XP points` / `1.234 XP-Punkte`).
- `economy.currency-*` and `online-rewards.notifications.message` may now be empty (new default) to use the texts from
  the language files. The former German defaults (`XP-Punkt`, `XP-Punkte`, the German bonus message) are treated the
  same way; such configs keep German texts for Vault consumers (`format()`, currency names, errors).
- English tutorial (`tutorial_en.yml`), English plugin.yml descriptions and console messages.
- `/economy account` as English alias of `/economy konto`.
- Messages about accounts created through Vault show the stored account name instead of the UUID.
- Requires Vystorm Core 0.22.0 or newer (clear console message with older versions).
- MIT license.

## 1.5.1

- Keep the stored account name when Vault renames an account to a blank name; `top.hide-name-prefixes` for `/baltop`.

## 1.5.0

- XP leaderboard `/baltop` (`/moneytop`, `/xptop`, `/economy top`) with page buttons and a dialog view.

## 1.4.1

- Clickable chat actions in account, payment and bonus messages.

## 1.4.0

- Native XP Account dialog in the Vystorm menu and server settings.

## 1.3.x

- Vystorm Core integration, serialized balance writes, MySQL write path with multi-row upserts, tutorial.
