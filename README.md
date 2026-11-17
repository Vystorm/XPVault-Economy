# XPVaultEconomy

XPVaultEconomy turns Minecraft experience into your server's money. One XP point is one coin. When a shop plugin charges 500 through Vault, those 500 points come straight off the player's experience bar, and paying someone moves real XP from one bar to the other. Players also earn a small bonus for staying online.

Player guide: in game under `/vmenu tutorials` (English and German), or [TUTORIAL.md](TUTORIAL.md) (German).

## Features

- **XP is the balance.** Every Vault deposit, withdrawal or payment changes level and XP bar right away. Whole numbers only, no bank accounts, one global account per player.
- **Nothing lost on first join.** The XP a player already has becomes their starting balance.
- **Online bonus.** By default 2 XP after the first minute, growing 2 % with every further minute. Short disconnects don't break the streak (30 minutes grace). An optional AFK pause stops the bonus for idle players.
- **Paying each other.** `/pay`, clickable chat buttons (pay, reply, page through) and a confirm step that shows the level you'll end up at. Up to 20 favourite payees can be saved.
- **Requests and splitting.** Ask someone for XP with a reason, or split a bill across up to 16 players. Each payer confirms their own share; open requests expire after 24 hours. Creators can cancel their own open requests, including individual split shares, using the chat button or `/economy requests cancel <id>`. Players can block requests from a specific person.
- **Player menu.** With Vystorm Core 0.64.0+, sneak + right-click a player with an empty hand: pay them, request XP from them, set up a standing order to them, add them to your XP bank, or (admins) see their booking history, without typing a command.
- **Personal limits.** Players can cap single payments and their daily total.
- **History and privacy.** `/economy history` shows the last ten bookings and can export up to 100 to CSV. `/economy privacy on` hides your balance from the leaderboard and other players.
- **Personal statements.** `/economy statement` opens a period selector with incoming XP, outgoing XP and net change over the last 1, 7 or 30 days, grouped by booking type. Export the displayed snapshot, including its bookings, to CSV. Uses the retained journal (2,000 entries across the server), so this is a partial activity summary, not a complete balance reconciliation.
- **Savings goal.** Set a target like `/economy goal set 10000 Equipment` and watch the progress. Nothing is reserved.
- **XP cheques.** `/economy cheque 500 3` turns XP into three tradeable paper cheques of 500 each. Right-click redeems one; each cheque works exactly once, so copies are worthless.
- **XP banks.** Shared XP pots for a team, a town or a couple: `/economy bank` opens a dialog to create a bank, add members and deposit or withdraw. XP in a bank is safe when you die and stays off the leaderboard. Every booking is logged, and no XP is ever doubled or lost, even when the disk is full. Optionally available to other plugins through the Vault bank API.
- **Standing orders.** Pay rent, wages or a subscription automatically: `/economy standing add Steve 500 1d` pays Steve 500 XP every day, even while you are offline. Your personal limits apply, failed runs are reported, and an order pauses after repeated failures. It never pays twice for the same date.
- **Term deposits.** Lock XP for 1, 7 or 30 days with `/economy term` and get it back with interest (1 %, 5 %, 15 % by default), also while offline. Early withdrawal returns the XP without interest.
- **Away summary.** Were you offline while someone paid you? On your next join one line tells you how much came in, in how many payments and from whom (`economy.away-summary`).
- **Leaderboard.** `/baltop` with pages, cached for 30 seconds.
- **Account menu.** `/economy` or `/vmenu` → Economy → XP Account opens a dialog with balance, bonus, payments and leaderboard. With the Vystorm client mod it becomes a native panel with a balance box in the corner, a bonus countdown and toasts for payments.
- **In the browser.** With Vystorm Core's web platform there is an XP Account page with account, payments, leaderboard and admin tools (`/economy web`).
- **English, German and Simplified Chinese (zh_cn)**, picked per player. More languages via `lang/<code>.yml`.

## Installation

The optional client mod has separate version requirements. On servers without native Paper dialogs, Core presents the same forms and confirmation actions in chat.


- **Paper 1.20.6 – 26.x**, Java **21 or newer** (use the Java version required by your server; plain Spigot is not supported). See [compatibility checks](COMPATIBILITY.md).
- [Vystorm Core](https://github.com/Vystorm/VystormCore) **0.64.0 or newer**, required for portable dialogs and automatic chat fallback.
- VaultUnlocked (registers as `Vault`). XPVaultEconomy serves both the legacy Vault economy API and Vault 2.

Drop the jar into `plugins/` and start the server. An older Core is refused at startup with a console message.

## Commands

| Command | What it does |
|---|---|
| `/money [player]`, `/bal` | Show your balance (another player's needs `xpeconomy.balance.others`) |
| `/pay <player> <XP>` | Pay a player |
| `/baltop [page]`, `/moneytop`, `/xptop` | Leaderboard |
| `/economy` | Open the XP Account menu (`/economy web` for the web page) |
| `/economy goal show\|set <XP> [label]\|clear\|notify on\|off` | Savings goal |
| `/economy favorite list\|add\|remove <player>` | Saved payees |
| `/economy request <player> <XP> <reason>` | Ask a player for XP |
| `/economy split <total> <player,player,…> <reason>` | Split a total across several players |
| `/economy requests [list\|accept\|decline\|cancel <id>]` | Your requests; cancel your own open requests. Tab suggests incoming IDs for accept/decline and outgoing IDs for cancel |
| `/economy requestblock <player> on\|off` | Block requests from a player |
| `/economy limits [single daily]` | Show or set your payment limits (0 = unlimited) |
| `/economy history [export]` | Recent bookings, optional CSV export |
| `/economy statement [1\|7\|30]` | Personal statement dialog with period buttons (default: last 7 days); also in the account dialog |
| `/economy statement summary [1\|7\|30]` | Private chat summary of retained incoming/outgoing XP and booking types; no dialog or file, default 7 days |
| `/economy statement export [1\|7\|30]` | Export totals, booking-type breakdown and all retained personal bookings in that period |
| `/economy privacy on\|off` | Hide your balance from others |
| `/economy cheque <XP> [count]` | Issue up to 64 XP cheques; right-click a cheque to redeem it (`xpeconomy.cheque`) |
| `/economy bank` | XP bank dialog (players); `list`, `info <bank>` |
| `/economy bank create <name>` | Create a shared XP bank (3–16 letters, digits, `_`, `-`) |
| `/economy bank deposit\|withdraw <bank> <XP>` | Move XP between you and a bank (owner and members) |
| `/economy bank add\|remove <bank> <player>` | Manage members (owner) |
| `/economy bank delete <bank>` | Close a bank after confirmation; its XP goes to the owner |
| `/economy standing` | Standing order dialog (players); `list` shows them in chat with buttons |
| `/economy standing add <player> <XP> <interval> [runs]` | New standing order, e.g. `12h`, `1d`, `1w`; runs `0` = until cancelled. Asks for confirmation; the first payment runs right away |
| `/economy standing pause\|resume\|cancel <id>` | Change one of your orders (admins: any order) |
| `/economy admin give\|take\|set <player> <XP>` | Change a balance |
| `/economy admin history <player> [count]` | A player's latest recorded bookings with counterpart (default 10, max 50) |
| `/economy admin info\|reload` | Account count and storage, reload config |

XP amounts in commands also accept the shorthands `k` and `m`: `/pay Steve 2.5k` pays 2,500, `/economy cheque 1m` issues a cheque worth 1,000,000. Only whole XP amounts are accepted.

Aliases: `/xpeconomy`, `/xpeco`. German subcommands (`konto`, `guthaben`, `zahlen`, `rangliste`, `ziel`, `favorit`) work too.

## Permissions

| Permission | Default | For |
|---|---|---|
| `xpeconomy.balance` | everyone | Own balance, account menu, web page, goal, privacy, history, personal statements |
| `xpeconomy.pay` | everyone | Payments, favourites, requests, splits, blocks, limits |
| `xpeconomy.top` | everyone | Leaderboard |
| `xpeconomy.cheque` | everyone | Issue and redeem XP cheques |
| `xpeconomy.bank` | everyone | Create, use and manage XP banks |
| `xpeconomy.standing` | everyone | Create and manage standing orders |
| `xpeconomy.balance.others` | OP | Look up other players' balances |
| `xpeconomy.admin` | OP | `/economy admin`, server settings; includes all others |
| `xpeconomy.*` | OP | Everything |

## Configuration

Admins can edit the whole `config.yml` in game with `/vadmin config XPVaultEconomy` (Core 0.27, permission `vystorm_core.config.xpvaulteconomy`). The common settings are also in `/vsettings` → Server → XP Economy. Saving works like `/economy admin reload`. `storage.*`, `sync.*` and `economy.maximum-balance` need a restart.

```yaml
language: auto            # follow each player's client language, or a fixed code like en
economy:
  starting-balance: 0     # for accounts created through Vault before the first join
  maximum-balance: 2000000000
  currency-singular: ''   # empty = "XP point" from the language file
  currency-plural: ''
storage:
  type: yaml              # yaml or mysql
top:
  page-size: 10
  hide-name-prefixes: []  # e.g. ["npc_"]
online-rewards:
  enabled: true
  base-xp: 2.0
  interval-seconds: 60
  growth-multiplier: 1.02
  reconnect-grace-minutes: 30
  afk:
    pause-after-seconds: 0  # 0 = no AFK pause
banks:
  enabled: true
  max-per-player: 3      # banks one player may own
  max-members: 10        # members besides the owner
  vault-api: false       # offer the banks to other plugins via the Vault bank API
standing-orders:
  enabled: true
  max-per-player: 10
  minimum-interval-minutes: 60
  pause-after-failures: 3  # 0 = never pause
```

**Storage.** `yaml` (default) keeps balances in `plugins/XPVaultEconomy/data/balances.yml`. `mysql` uses Vystorm Core's shared database (credentials in Core's `database.shared`), so one balance follows the player across a network. If the database is unreachable the plugin disables itself rather than falling back to the file. Requests, limits, privacy, history, XP banks and standing orders always stay local to each server.

Data from version 1.7.0 and older (files in the plugin folder, the old own MySQL table) is not imported automatically.

**Statements.** No additional configuration or migration is needed. Statements read `data/transaction-history.json`, including after a restart, and cover rolling 24-hour periods with UTC timestamps. Vanilla XP changes and entries no longer retained by the journal are absent; unsaved entries can be lost in a crash. Incoming/outgoing totals include recorded bank transfers and balance adjustments and are not necessarily earnings/spending. CSV files are saved locally as `exports/<uuid>-statement-<days>d.csv`; the next export for the same player and period replaces that file. Ask an administrator for it. The dialog exports the exact snapshot it displays; the command takes a fresh snapshot. German `/economy auszug` and Chinese `/economy 账单` work too.

## For developers

Build with JDK 25; all Java compilation targets release 21. Core and VaultUnlocked are compile-only. Production compilation and `check` require the Paper 1.20.6 library tree; set `-Ppaper1206Libraries=/path/to/libraries` to override the local default. `compat1206` independently compiles all production sources against that baseline, offline:

```bash
./gradlew build -PvystormCoreJar=/path/Vystorm_Core.jar -PvaultJar=/path/VaultUnlocked.jar
```

Or put them at `libs/Vystorm_Core.jar` and `libs/VaultUnlocked.jar`. Output: `build/libs/XPVaultEconomy-<version>.jar`.

Storage internals, the client mod displays, the web page and its HTTP API, request and history details, and the integration test jar are in [docs/TECHNICAL.md](docs/TECHNICAL.md).

Changes: [CHANGELOG.md](CHANGELOG.md).


## Free feature update 1.20.0-26.2

Extend the existing shared XP banks into configurable group treasuries. `/economy bank policy <bank> <member> <deposit:on/off> <withdraw:on/off> <single-XP> <daily-XP>` sets independent deposit and withdrawal rights plus single-payment and daily withdrawal limits for an existing member. The bank dialog exposes the same policy editor. Values are whole XP from 0 to 2147483647; zero is a zero allowance, not unlimited. UTC midnight resets daily usage. Owners and `xpeconomy.admin` retain full access; unchanged legacy members retain their existing access.

Policy changes, daily usage, balances and pending player bookings use the existing atomic `data/banks.json` write and economy transaction lock. Policy write failures retain the old policy; failed money commits restore the player's booking and do not consume daily allowance. Removing and re-adding a member does not erase same-day spending. Existing bank files load without conversion. Do not downgrade a server relying on the new restrictions to an older version that ignores these new fields.

`/economy bank history <bank> [page]` and the bank's history button expose the latest 500 durable bank records, including actor, time, balance changes and policy details. History survives restarts while the bank exists; explicitly deleting a bank removes its account history. The existing trusted Vault bank API has no acting-player identity, so player-specific limits apply to player commands/dialogs, not arbitrary privileged plugin API calls. All new interface/help text is provided in seven bundled languages.

## License

Copyright (c) 2026 Esoren

Versions after 1.24.0 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.24.0 and earlier were released under the MIT License ([LICENSE-MIT-until-1.24.0.txt](LICENSE-MIT-until-1.24.0.txt)) and stay available under those terms.
