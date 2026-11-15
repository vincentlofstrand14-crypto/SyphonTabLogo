# ScoreBounties

Item-based bounty system for **ScoreSMP**. Players offer **physical items** as bounties; whoever kills the target receives exactly those items. No economy, no money, no Vault.

- Server: **Paper 1.21.11** · Java **21**
- Dependencies: none required. PlaceholderAPI is optional.
- Storage: SQLite. Paper already ships the driver, so nothing extra is shaded.

---

## Installation

1. Stop the server.
2. Put `ScoreBounties.jar` (see `release/`) in `plugins/`.
3. Start the server. This creates:

```
plugins/ScoreBounties/
├── config.yml
├── messages.yml
├── gui.yml
├── database/
│   └── bounties.db
└── backups/
```

4. Edit the files if you want, then run `/bounty admin reload`. Database settings (`database.file`) take effect after a restart.

To build it yourself: `mvn package` → `target/ScoreBounties.jar` (this also runs the test suite).

---

## Commands

`/bountys` is an alias of `/bounty`, so every command works with either.

| Command | Description | Permission |
|---|---|---|
| `/bounty` | Open the bounty menu | `scorebounties.use` |
| `/bounty add <player>` | Open the deposit menu and add items to a player's bounty | `scorebounties.add` |
| `/bounty set <player>` | Open the deposit menu and **replace** a player's bounty. Old contributors get their items back | `scorebounties.set` |
| `/bounty info <player>` | Show a player's bounty in chat | `scorebounties.info` |
| `/bounty top [items\|contributions\|claimed\|hunters]` | Leaderboards | `scorebounties.top` |
| `/bounty claim` | Collect stored rewards and returned items | `scorebounties.claim` |
| `/bounty help` | Command help | – |
| `/bounty admin reload` | Reload config, messages and GUI files | `scorebounties.admin.reload` |
| `/bounty admin remove <player>` | Remove a bounty and return every item to its contributors | `scorebounties.admin.remove` |
| `/bounty admin clear` | Remove all bounties and return every item | `scorebounties.admin.clear` |
| `/bounty admin restore <player>` | Restore the last admin-removed bounty from items contributors have not collected yet | `scorebounties.admin.restore` |

Leaderboards: `items` = active bounty items per target, `contributions` = active contributions per player, `claimed` = how often a player's bounty was claimed, `hunters` = bounties claimed as the killer.

## Permissions

| Permission | Default |
|---|---|
| `scorebounties.use`, `.add`, `.set`, `.info`, `.top`, `.claim` | everyone |
| `scorebounties.admin.reload`, `.remove`, `.clear`, `.restore` | op |
| `scorebounties.admin` | op. Grants all four admin permissions |

Normal players never need a wildcard.

---

## How it works

### Placing a bounty
`/bounty add <player>` opens a 6-row menu. The top 5 rows are free slots for your items. The bottom row has **Cancel** (left), **Info** (middle) and **Confirm** (right). You can move items in and out freely until you confirm.

On confirm the plugin checks the items, the target, self-bounty, blacklist, limits, cooldown and permission. It takes the items out of the menu, saves the exact item stacks in one database transaction, and only after that succeeds does it broadcast:

```
SCORESMP > Aimed Put a Bounty on Notch
```

If anything fails, the items are returned. Cancelling or closing the menu returns everything.

`/bounty set <player>` uses the same menu. If the target already has a bounty, a chat warning is shown, the confirm button turns orange, and it must be clicked **twice** (within `gui.replace-confirm-seconds`). The old contributions and the new one are swapped in a single transaction. Each previous contributor gets their own items back, or later via `/bounty claim` if they are offline.

### Claiming
When a player kills a bounty target, the plugin first checks eligibility (see `kill-eligibility` / `anti-farming` in config.yml). It then removes the bounty from memory, which is the atomic "claim", so only one kill can ever win it. It writes the payout in one transaction that verifies every row is still active. Then it gives the killer the exact stored items: enchantments, names, lore, flags, damage, potion data, custom model data, PDC and all other data components are preserved.

Anything that does not fit in the killer's inventory stays in `/bounty claim`. Nothing is ever dropped on the ground. A killer who disconnects receives the reward on their next join.

### Data safety
- Items are stored using Paper's native `ItemStack#serializeAsBytes`, which keeps the full data component patch and runs Mojang's DataFixer when the server is updated.
- All database work runs on one background thread, never the main thread. Each operation is a single SQLite transaction (WAL, `synchronous=FULL`).
- Payouts, returns and replacements use conditional updates (`WHERE status = 'ACTIVE'`). If anything changed in between, the transaction is rolled back, so the database itself refuses to pay or return the same items twice.
- Items inside an open deposit menu are journaled to the database after every change. After a crash they are returned to the owner's `/bounty claim` on the next start.
- Items delivered to an inventory are only marked as delivered after the player's data has been saved (`claims.save-player-data-on-delivery`).
- Players are identified by UUID everywhere.
- Backups: `VACUUM INTO` copies in `backups/`, made on startup and every `interval-hours`, keeping the newest `keep` files.

### Anti-exploit
The deposit menu allows only a whitelist of vanilla actions, and only on deposit slots. It blocks double-click collecting, creative middle-click cloning, number-key swaps onto buttons, drags touching button slots, unknown actions, and creative/spectator mode. Shift-clicks into the menu are performed by the plugin, so they can never merge into the decorative panes. Confirm locks the menu, so a double click cannot submit twice. Other menus cancel every click. Kill rewards ignore NPCs, suicides, and (by default) indirect or environmental deaths, same-IP alt accounts, and repeat kills on the same victim within the cooldown.

---

## Configuring messages (`messages.yml`)
- Colour codes: `&a`, `&l`, ... and hex in the `&#RRGGBB` format (`&x&R&R&G&G&B&B` works too).
- `%prefix%` is replaced by `prefix:` in every message.
- Set any message to `""` to turn it off.
- The two broadcasts are under `messages:`:
  - `bounty-placed`: `%player%`, `%target%`
  - `bounty-claimed`: `%killer%`, `%target%`
- `help` and `admin-help` are lists; add or remove lines freely.

## Configuring menus (`gui.yml`)
- Each menu (`main-menu`, `details-menu`, `deposit-menu`, `claim-menu`) has a `title` and `rows` (2–6). The bottom row holds the buttons; all other slots show content.
- Every button has `slot`, `material`, `name` and `lore`. Slots are 0-based: the bottom row of a 6-row menu is 45–53.
- `style.filler` / `style.accent` set the pane materials. `accent-slots` chooses which bottom-row slots get the accent colour.
- The bounty head lore in the main menu supports `%target%`, `%amount%`, `%stacks%`, `%contributors%`, `%created%`, `%updated%`. A line containing `%items%` expands into one line per item type, using `item-line`, limited by `gui.max-item-lines` in config.yml.
- Claim-menu source labels are under `claim-menu.sources`.

## PlaceholderAPI
Registered automatically when PlaceholderAPI is installed:

| Placeholder | Value |
|---|---|
| `%scorebounties_active%` | Active bounties on the server |
| `%scorebounties_target%` | `true`/`false`: the player has a bounty |
| `%scorebounties_item_count%` | Items on the player's bounty |
| `%scorebounties_claimed%` | Bounties the player has claimed |
| `%scorebounties_stack_count%` | Stacks on the player's bounty |
| `%scorebounties_contributors%` | Contributors to the player's bounty |
| `%scorebounties_times_claimed%` | Times a bounty on the player was claimed |
| `%scorebounties_contributions%` | Contributions the player has placed |
| `%scorebounties_pending%` | Stacks waiting in `/bounty claim` |
| `%scorebounties_total_items%` | Total bounty items on the server |

---

## Tests
`mvn test` runs 28 tests against the real plugin on a MockBukkit server, with a real SQLite database. They cover:
- creating, adding to, replacing, cancelling and closing bounties
- disconnecting, crashing and shutting down during a deposit
- kills and duplicate death events
- the database's double-payout refusal
- indirect deaths, same-IP and no-permission kills, and the farming cooldown
- full and partial inventories, and `/bounty claim`
- offline killers and contributors
- restarts
- pagination, sorting and the details menu
- forbidden menu actions
- invalid commands and permissions
- admin remove, restore and clear
- info and leaderboards
- the exact hex announcements

The mock server cannot run Paper's native item serializer, so the tests swap in a test codec. Test on a staging server before going live.
