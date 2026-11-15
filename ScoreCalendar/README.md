# ScoreCalendar

A fully customizable reward calendar plugin for **ScoreSMP** (Paper 1.21.11, Java 21).

Advent calendars, 30-day event calendars, daily login streaks, weekly rewards, or any custom number of days
(1-1000). Every GUI slot, item, reward, date, message, sound and effect is configured in YAML or edited in-game.

---

## Contents

1. [Installation](#1-installation)
2. [Files](#2-files)
3. [Commands](#3-commands)
4. [Permissions](#4-permissions)
5. [Creating a calendar](#5-creating-a-calendar)
6. [Calendar types](#6-calendar-types)
7. [Unlock schedules and timezones](#7-unlock-schedules-and-timezones)
8. [Rewards](#8-rewards)
9. [Items and materials](#9-items-and-materials)
10. [GUI layout: size, title, slots, pages](#10-gui-layout-size-title-slots-pages)
11. [Day appearance](#11-day-appearance)
12. [Custom clickable items and actions](#12-custom-clickable-items-and-actions)
13. [Custom menus](#13-custom-menus)
14. [Effects: sounds, particles, titles, fireworks](#14-effects-sounds-particles-titles-fireworks)
15. [Messages and colors](#15-messages-and-colors)
16. [Placeholders](#16-placeholders)
17. [In-game editors](#17-in-game-editors)
18. [Safety: duplicate protection, full inventories, crashes](#18-safety-duplicate-protection-full-inventories-crashes)
19. [Troubleshooting configuration errors](#19-troubleshooting-configuration-errors)
20. [Building from source](#20-building-from-source)

---

## 1. Installation

1. Put `ScoreCalendar.jar` in `plugins/` (Paper 1.21.11, Java 21).
2. Optional: install **PlaceholderAPI** (placeholders) and/or **LuckPerms** (`required-groups`).
3. Start the server. On the first start these files are created:
   `config.yml`, `messages.yml`, and five example calendars in `calendars/`.
4. Edit, then run `/calendar admin reload`.

No other dependencies are needed (SQLite ships with Paper).

## 2. Files

```
plugins/ScoreCalendar/
├── config.yml            # global settings, /calendars menu, backups
├── messages.yml          # every message (overridable per calendar)
├── calendars/            # one file per calendar; the file name is the calendar name
│   ├── christmas.yml     # 24-day advent calendar (full example, starts 2026-12-01)
│   ├── summer.yml        # 30 days on two pages
│   ├── halloween.yml     # 14 days, 24h claim window
│   ├── daily.yml         # daily login streak (type DAILY)
│   └── weekly.yml        # one reward per weekday (type WEEKLY)
├── database/calendar.db  # SQLite: claims, pending deliveries, states
└── backups/              # database backups, deleted calendars, editor backups
```

Configuration files are the source of all calendar designs and rewards. The database only stores player
progress, open/close overrides, manual unlocks and the reward delivery queue.

## 3. Commands

Aliases: `/cal`, `/advent`. Every command has tab completion.

| Command | Description |
|---|---|
| `/calendar` | Open the default calendar (`default-calendar` in config.yml; `auto` = the open calendar with the highest priority) |
| `/calendar <name>` | Open a specific calendar |
| `/calendars` | Menu with all calendars |
| `/calendar rewards [name]` | Overview of all rewards and their status |
| `/calendar collect` | Collect rewards that did not fit in your inventory |
| `/calendar info [name]` | Calendar information in chat |
| `/calendar admin` | Admin GUI |
| `/calendar admin create <name> [days] [type]` | Create a calendar (e.g. `create easter 14 ADVENT`) |
| `/calendar admin delete <name>` | Delete (type the command again with `confirm` within 30 s) |
| `/calendar admin edit <name>` | Edit menu for a calendar |
| `/calendar admin reload` | Reload everything and list configuration problems |
| `/calendar admin give <player> <calendar> <day>` | Give a day's reward (works for offline players: delivered on join) |
| `/calendar admin reset <player> <calendar>` | Reset one player's progress |
| `/calendar admin resetall <calendar>` | Reset everyone (needs `confirm`; a backup is made first) |
| `/calendar admin preview <name>` | Open a calendar without claiming |
| `/calendar admin open <name>` / `close <name>` / `auto <name>` | Force open, force closed, or follow the schedule again |
| `/calendar admin setday <player> <calendar> <day>` | Set progress: days 1..day are marked claimed (0 = none) |
| `/calendar admin unlockday` / `lockday` / `autoday <calendar> <day>` | Manually unlock/lock one day, or return it to the schedule |
| `/calendar admin history <player>` | Claim history |
| `/calendar admin backup` | Write a database backup now |
| `/calendar admin problems` | List all configuration problems |
| `/calendar admin editor <open\|save\|cancel\|next\|prev>` | Control the visual layout editor (also clickable in chat) |

## 4. Permissions

| Permission | Default | Allows |
|---|---|---|
| `scorecalendar.use` | everyone | `/calendar`, `rewards`, `collect`, `info` |
| `scorecalendar.open` | everyone | open calendars by name, `/calendars` |
| `scorecalendar.admin` | op | `/calendar admin` (each action needs its own permission) |
| `scorecalendar.admin.create` | op | create calendars |
| `scorecalendar.admin.delete` | op | delete calendars |
| `scorecalendar.admin.edit` | op | calendar edit menu |
| `scorecalendar.admin.editor` | op | visual layout editor, reward editor, item editor |
| `scorecalendar.admin.reload` | op | reload, backup, problems |
| `scorecalendar.admin.give` | op | give rewards |
| `scorecalendar.admin.reset` | op | reset/setday/history |
| `scorecalendar.admin.resetall` | op | reset everyone |
| `scorecalendar.admin.preview` | op | preview mode |
| `scorecalendar.admin.unlock` | op | open/close calendars, manual day unlocks |
| `scorecalendar.admin.*` | op | all admin permissions (for staff groups only) |

Per calendar: `permission: scorecalendar.calendar.vip` and/or `required-groups: [vip, mvp]` (LuckPerms groups,
inherited groups count; without LuckPerms the `group.<name>` permission is checked).
Per day: `day-settings.<day>.permission` (e.g. premium days).

## 5. Creating a calendar

**In-game:** `/calendar admin create <name> <days> [type]`. This writes a complete, commented file with an
automatic layout for that number of days, then opens the editor. Add rewards with **Edit rewards**.

**By hand:** copy `calendars/christmas.yml` to `calendars/<name>.yml` and change it. The file name is the calendar
name (lowercase letters, digits, `-`, `_`).

### A 24-day calendar

```yaml
enabled: true
display-name: "&#00B2FF&lCHRISTMAS CALENDAR"
type: ADVENT
days: 24
unlock:
  type: DAILY
  timezone: Europe/Stockholm
  start-date: "2026-12-01"
  start-time: "00:00"
  end-date: "2026-12-24"
gui:
  title: "&#00B2FF&lCHRISTMAS CALENDAR"
  size: 54
  day-slots: "10-16,19-25,28-34,39-41"   # 24 slots
rewards:
  day-1:
    items:
      - material: DIAMOND
        amount: 5
  day-2:
    items:
      - material: GOLDEN_APPLE
        amount: 2
    commands:
      - "give %player% emerald 10"
  # ... day-3 to day-24
```

### A 30-day calendar

```yaml
type: SEASONAL
days: 30
unlock:
  type: DAILY
  start-date: "2027-06-15"
  end-date: "2027-07-14"
gui:
  size: 54
  day-slots: "10-16,19-25,28-34,37-43"   # 28 slots: days 29-30 continue on page 2
  items:
    previous-page: {slot: 48, material: ARROW, name: "&e« Previous", visible: HAS_PREVIOUS_PAGE, actions: [PREVIOUS_PAGE]}
    next-page:     {slot: 50, material: ARROW, name: "&eNext »",     visible: HAS_NEXT_PAGE,     actions: [NEXT_PAGE]}
```

Or fit all 30 days on one page: `size: 54` with `day-slots: "0-29"`. There is no 24-day limit: when there are
more days than day slots, the extra days continue on further pages automatically.

## 6. Calendar types

| `type` | Behavior |
|---|---|
| `ADVENT` | Days unlock according to the unlock schedule. Each day can be claimed once. |
| `SEASONAL` | Same as ADVENT, intended for an event window (`start-date` to `end-date`). |
| `CUSTOM` | Every day has its own unlock date (unlock type `SCHEDULED` by default). |
| `DAILY` | Daily reward streak: every real day a player may claim the **next** reward of their own sequence. The allowance resets daily at `start-time`. `claiming.daily.reset-on-miss` sends players back to day 1 when they miss a day; `restart-when-complete` starts over after the last day. |
| `WEEKLY` | One reward per weekday (day 1 = `unlock.week-start`, Monday by default). Claims reset every week. |

Each calendar has its own claims; calendars never share or overwrite progress.

Repeatable rewards: `claiming.reset: DAILY | WEEKLY | MONTHLY` lets unlocked days be claimed again after each
reset (default `NEVER`).

## 7. Unlock schedules and timezones

```yaml
unlock:
  type: DAILY              # DAILY, SCHEDULED, INTERVAL, MANUAL, ALL_AT_ONCE
  timezone: Europe/Stockholm
  start-date: "2026-12-01" # calendar opens; with DAILY day 1 unlocks here
  start-time: "00:00"      # time of day for unlocks (and daily resets)
  end-date: "2026-12-24"   # calendar closes at the end of this day...
  end-time: "23:59"        # ...or at this time (optional)
  claim-until: "2026-12-26 23:59"  # optional grace period for late claims
  interval: 12h            # only for INTERVAL: one day every 12 hours
  week-start: MONDAY       # WEEKLY calendars
  schedule:                # explicit dates (SCHEDULED), or overrides for any type
    day-1: "2026-12-01 18:00"
    day-2: "2026-12-03 18:00"

claiming:
  claim-window: unlimited  # or 24h: a day is MISSED if not claimed within 24h of unlocking
  reset: NEVER
  require-sequential: false  # true = days must be claimed in order
```

* **DAILY**: day N unlocks N-1 days after `start-date`, at `start-time`.
* **SCHEDULED**: each day unlocks at its own date (`unlock.schedule.day-N` or `day-settings.N.unlock`).
* **INTERVAL**: day N unlocks `(N-1) × interval` after the start.
* **MANUAL**: days only unlock with `/calendar admin unlockday` (or the Manual Unlocks menu).
* **ALL_AT_ONCE**: every day is available while the calendar is open.

Any day can be overridden: `day-settings: {24: {unlock: "2026-12-23 18:00"}}`.

All times are real dates in the configured timezone (default from `config.yml`, `Europe/Stockholm`). Daylight saving
time is handled automatically: a 12:00 unlock is at 12:00 local time before and after the switch. Unlocks never
depend on server restarts. Players see the next unlock through `%unlock_countdown%`, `%next_unlock%`, and so on.

Administrators can override everything: `/calendar admin open|close|auto <name>` and `unlockday|lockday|autoday`.

## 8. Rewards

Each day has its own reward. A reward may contain items, commands, or both.

```yaml
rewards:
  day-12:
    name: "Frostbite Sword"            # %reward_name%
    lore: ["&7Forged in the ice of the north."]   # %reward_lore% (preview text)
    items:
      - material: DIAMOND_SWORD
        name: "&#00B2FF&lFrostbite"
        lore: ["&7A cold blade."]
        enchantments: {sharpness: 5, unbreaking: 3}
        custom-model-data: 1001
      - material: DIAMOND
        amount: 128                     # more than one stack is fine
      - "GOLDEN_APPLE:2"                # short form
    commands:                           # console commands, run exactly once per claim
      - "eco give %player% 1000"
      - "lp user %player% permission settemp cosmetic.hat true 7d"
    # Optional reward-specific effects (override the calendar's claim effects):
    message: "%prefix%&7You found the &bFrostbite&7!"
    broadcast: "%prefix%&f%player% &7claimed day %day%!"
    title: {title: "&b&lFROSTBITE!", subtitle: "&7Day %day%"}
    sound: {type: ENTITY_PLAYER_LEVELUP, pitch: 1.5}
    effects:
      particles: {type: SNOWFLAKE, count: 60}
      firework: {type: STAR, colors: ["#00B2FF", "#FFFFFF"]}
```

Command placeholders: `%player%`, `%uuid%`, `%day%`, `%calendar%`, plus PlaceholderAPI placeholders.

**Exact items:** items placed in the in-game reward editor are stored with *all* their data (every enchantment,
name, lore, attribute, custom model data, container contents and other components) as
`serialized: <base64>`. Paper upgrades them automatically after a Minecraft update.
Plain items without extra data are stored readably as `material` + `amount`.

Players right-click a day to preview its reward. `/calendar rewards` shows every day with its status.

## 9. Items and materials

Every item in the plugin (days, buttons, decorations, background, icons, rewards) supports:

```yaml
material: PLAYER_HEAD          # any 1.21.11 item material
amount: 1                      # or "%day%" for day items (1-99)
name: "&#00B2FF&lNAME"
lore: ["line 1", "line 2"]
enchantments: {sharpness: 5}   # or a list ["SHARPNESS:5"]; old names like DAMAGE_ALL work too
flags: [HIDE_ENCHANTS, HIDE_ATTRIBUTES]   # or [ALL]
custom-model-data: 1234
item-model: "myserver:gift"    # 1.21.4+ item model
glow: true                     # enchantment glint without enchantments (false removes it)
unbreakable: true
hide-tooltip: false
head-texture: "<base64 textures value | texture hash | https://textures.minecraft.net/texture/...>"
head-owner: "%player%"         # the viewer's own head, or a player name
serialized: "<base64>"         # exact item (written by the in-game editors)
```

## 10. GUI layout: size, title, slots, pages

```yaml
gui:
  title: "&#00B2FF&lCHRISTMAS &8(%page%/%pages%)"
  type: CHEST                  # CHEST (size 9/18/27/36/45/54), HOPPER (5), DISPENSER/DROPPER (9)
  size: 54
  view-when-closed: false      # true: players can look before it opens (days show as locked)
  allow-overlap: false         # true: allow elements to share a slot on purpose
  day-slots: "10-16,19-25,28-34,39-41"   # days fill these slots in order; extra days go to the next page
  background:
    enabled: true
    material: BLACK_STAINED_GLASS_PANE
    name: " "
    fill-empty-slots: true
  items:
    day-24:                    # move one day: slot (and page, default 1)
      slot: 40
    day-30:
      slot: 22
      page: 2
    border:                    # decoration: no actions
      slots: "0-8,45-53"       # one item in many slots
      material: RED_STAINED_GLASS_PANE
      name: " "
    close:
      slot: 49
      material: BARRIER
      name: "&cClose"
      actions: [CLOSE]
```

* Slots are validated against the inventory size. Out-of-range slots are reported.
* Overlaps are reported: two days in one slot is an error; an item over a day is a warning (the day wins).
  Use `allow-overlap: true` on an item, or different `visible:` conditions, to share a slot on purpose.
* Empty slots stay empty when the background is disabled.
* Every calendar has its own layout. Different sizes need different `day-slots`; nothing is predefined.

## 11. Day appearance

```yaml
day-items:                     # defaults for every day
  locked:    {material: GRAY_STAINED_GLASS_PANE,  amount: "%day%", name: "&7&lDAY %day%", lore: ["&7Unlocks in &f%unlock_countdown%", "", "&cLocked"]}
  available: {material: LIME_STAINED_GLASS_PANE,  amount: "%day%", name: "&#00B2FF&lDAY %day%", glow: true, lore: ["&7Click to claim!", "%reward_items%"]}
  claimed:   {material: RED_STAINED_GLASS_PANE,   amount: "%day%", name: "&c&lDAY %day%", lore: ["&cAlready claimed"]}
  missed:    {material: BLACK_STAINED_GLASS_PANE, amount: "%day%", name: "&8&lDAY %day%"}   # claim window passed

day-styles:                    # named partial overrides
  special:
    available: {material: NETHER_STAR, glow: true}
  premium:
    available: {material: DIAMOND_BLOCK, amount: 1}

day-settings:
  12:
    style: special
  24:
    style: premium
    permission: scorecalendar.premium      # only these players can claim day 24
    appearance:                             # overrides only for this day
      claimed: {material: DIAMOND_BLOCK, glow: false}
```

Order: `day-items` → the day's `style` → the day's own `appearance`. Only the fields you set are changed.

Day placeholders: `%day%`, `%reward_name%`, `%reward_lore%` (multi-line), `%reward_items%` (multi-line list),
`%reward_status%`, `%unlock_date%`, `%unlock_countdown%`, plus all calendar placeholders below.

## 12. Custom clickable items and actions

Any item in `gui.items` (or `gui.custom-items` / `gui.decorations`) can have actions:

```yaml
gui:
  items:
    information:
      slot: 45
      material: BOOK
      name: "&#00B2FF&lINFORMATION"
      actions:
        - "OPEN_MENU:information"
        - "SOUND:UI_BUTTON_CLICK:0.6:1.2"
      right-click-actions:        # optional, for right-clicks
        - "MESSAGE:&7Left-click for details."
    website:
      slot: 53
      material: PAPER
      name: "&bVisit our website"
      permission: ""              # optional: only shown to players with this permission
      visible: ALWAYS             # ALWAYS, HAS_NEXT_PAGE, HAS_PREVIOUS_PAGE, MULTIPLE_PAGES, HAS_PENDING_REWARDS
      pages: [1]                  # optional: only on these pages
      actions:
        - "MESSAGE:%prefix%&7Visit &bscoresmp.net&7!"
        - "CLOSE"
```

| Action | Example |
|---|---|
| `CLOSE` | close the menu |
| `BACK` | back to the previous menu |
| `REFRESH` | redraw the menu |
| `MESSAGE:<text>` | `MESSAGE:&aHello %player%` (`\n` for new lines) |
| `BROADCAST:<text>` | message to everyone |
| `ACTIONBAR:<text>` | action bar message |
| `TITLE:<title>\|<subtitle>` | `TITLE:&bHi\|&7there` |
| `PLAYER_COMMAND:<cmd>` | run a command as the player (`PLAYER_COMMAND:spawn`) |
| `CONSOLE_COMMAND:<cmd>` | run a command as console (`CONSOLE_COMMAND:give %player% diamond 1`) |
| `OPEN_MENU:<name>` | a custom menu from `menus:`, or built-in `calendar`, `calendars`, `rewards`, `progress`, `pending` |
| `OPEN_CALENDAR:<name>` | open another calendar |
| `NEXT_PAGE`, `PREVIOUS_PAGE`, `PAGE:<n>` | pagination |
| `SOUND:<sound>[:volume[:pitch]]` | `SOUND:ENTITY_PLAYER_LEVELUP:1:1.5` |
| `PARTICLE:<particle>[:count]` | `PARTICLE:HAPPY_VILLAGER:20` |
| `COLLECT_REWARDS` | open the stored-rewards menu |

Actions are validated when the configuration loads; invalid actions are reported and never run. Actions only come
from configuration files, never from player input. New action types can be registered in code with
`ActionRegistry#register`.

## 13. Custom menus

```yaml
menus:
  information:                 # OPEN_MENU:information
    title: "&#00B2FF&lINFORMATION"
    size: 27
    background: {enabled: true, material: BLACK_STAINED_GLASS_PANE, name: " "}
    items:
      how:
        slot: 13
        material: OAK_SIGN
        name: "&#00B2FF&lHOW IT WORKS"
        lore: ["&7Day %next_day% opens in %next_unlock%"]
      back:
        slot: 22
        material: ARROW
        name: "&7« Back"
        actions: ["OPEN_MENU:calendar"]
```

## 14. Effects: sounds, particles, titles, fireworks

```yaml
effects:                       # events: open, close, claim, locked, already-claimed, closed
  claim:
    sound:     {enabled: true, type: UI_TOAST_CHALLENGE_COMPLETE, volume: 1.0, pitch: 1.0}
    particles: {enabled: true, type: HAPPY_VILLAGER, count: 30, offset: 0.5, speed: 0.05}
    title:     {enabled: true, title: "&#00B2FF&lREWARD CLAIMED!", subtitle: "&7You claimed Day %day%", fade-in: 10, stay: 50, fade-out: 15}
    actionbar: {enabled: true, message: "&7%days_claimed%/%days_total% claimed"}
    broadcast: {enabled: false, message: "%prefix%&f%player% &7claimed day %day%"}
    firework:  {enabled: false, type: BALL_LARGE, colors: ["#00B2FF"], fade-colors: ["#FFFFFF"], trail: true, flicker: false}
    messages:  ["%prefix%&7Extra chat line"]
```

* Sounds: classic names (`ENTITY_PLAYER_LEVELUP`) or keys (`minecraft:entity.player.levelup`); resource-pack
  sounds with their own namespace work too.
* Particles: e.g. `HAPPY_VILLAGER`, `TOTEM_OF_UNDYING`, `END_ROD`, `FIREWORK`, `SNOWFLAKE`, `SOUL`,
  `DUST` (with `color: "#00B2FF"` and `size: 1.5`).
* Particles are capped at 500 per effect, fireworks never cause damage, and nothing repeats. Every part can be
  turned off with `enabled: false`.

## 15. Messages and colors

All messages are in `messages.yml`. Any key can be overridden per calendar under `messages:` in the calendar
file. `%prefix%` inserts the prefix; an empty message (`""`) is not sent; lists send several lines.

Formats (they can be mixed):

* legacy: `&a`, `&l`, `&o`, `&r` ...
* hex: `&#00B2FF`
* MiniMessage (`minimessage: true` in config.yml): `<gradient:#00B2FF:#FFFFFF>text</gradient>`, `<bold>`,
  `<italic>`, `<#00B2FF>`

ScoreSMP defaults: main blue `#00B2FF`, secondary blue `#00A6FC`, gray `#898989`, white `#FFFFFF`,
prefix `&#00B2FF&lSCORESMP &#898989> `.

## 16. Placeholders

In GUIs and messages: `%player%`, `%calendar%`, `%calendar_name%`, `%calendar_description%` (multi-line),
`%days_total%`, `%days_unlocked%`, `%days_claimed%`, `%days_remaining%`, `%days_unclaimed%`, `%progress%`,
`%status%`, `%opens_at%`, `%closes_at%`, `%opens_in%`, `%closes_in%`, `%next_day%`, `%next_unlock%`,
`%next_unlock_date%`, `%timezone%`, `%page%`, `%pages%`, `%stored_rewards%`, and the day placeholders from §11.
With PlaceholderAPI installed, PAPI placeholders work in every text too.

**PlaceholderAPI** (`%scorecalendar_<placeholder>%` uses the default/active calendar;
`%scorecalendar_<calendar>_<placeholder>%` a specific one, e.g. `%scorecalendar_christmas_days_claimed%`):

`name`, `name_formatted`, `id`, `days_total`, `days_unlocked`, `days_claimed`, `days_remaining`, `available`,
`progress`, `next_day`, `next_unlock`, `next_unlock_date`, `status`, `open`, `claimed_<day>`, `status_<day>`,
`stored_rewards`.

## 17. In-game editors

`/calendar admin` → click a calendar (left: edit, right: preview, shift: open-state cycle).

**Edit menu:**

* **Edit GUI layout:** the visual editor. It shows the calendar exactly as players see it.
  * Left-click an item, then left-click another slot to move it (occupied slots swap).
  * Right-click a button or decoration to edit its material, name, lore, glow, amount, model data and actions.
    Right-click a day to edit its reward.
  * Shift-click an empty slot to create a new button. Press **Q** on a button to delete it (asks first).
  * Number keys: **1** previous page, **2** next page (beyond the last page creates a new one), **8** cancel,
    **9** save.
  * Chat buttons [SAVE] [CANCEL] [◀ PAGE] [PAGE ▶] [OPEN] work too (close the inventory to click them).
  * Moves are a draft until you save.
* **Edit rewards:** pick a day. The top four rows are a workspace; place real items there (shift-click works).
  The fifth row lists commands: click to edit, right-click to remove, use the command block to add.
  Name, description, preview, clear, **Save** and **Cancel** are in the bottom row. Nothing is saved until Save.
  Cancel, or closing the menu, discards changes and gives back the items you added.
* **Day appearance:** edit the locked/available/claimed/missed look and the background.
* **Unlock settings:** type, timezone, dates, times, grace period, interval, claim window, reset period and
  sequential claiming. Uses a draft with Save/Cancel.
* **Manual unlocks:** unlock or lock single days.
* **Open state, Preview, Reset player, Reset everyone, Player history, Number of days, Rename, Delete.**

Every editor save keeps the previous file in `backups/editor/` and preserves the comments in your YAML.
Menu items can never be taken: every click in plugin menus is cancelled (only the reward editor workspace allows
item movement), and any plugin GUI item found in a player inventory is removed automatically.

## 18. Safety: duplicate protection, full inventories, crashes

* **One claim per day:** the database has a unique key on calendar + player UUID + cycle + day, so a second claim
  of the same day is impossible, even across restarts or with simultaneous clicks. A per-player lock and a click
  cooldown stop double-click spam before it reaches the database.
* **Claim flow:** checks (open, access, unlocked, not claimed) → one transaction records the claim (PROCESSING)
  and the full reward → items are given → commands run → claim marked COMPLETED → GUI refresh and effects.
* **Full inventory:** everything that fits is given; the rest is stored in the database (never dropped or deleted).
  Players get a message and collect with `/calendar collect` or the hopper button; it is also delivered
  automatically on the next join.
* **Commands run exactly once:** commands are marked EXECUTING before they run and DONE after. If the server
  crashes in between, they are **not** run again; they are logged on startup for review (`UNCERTAIN`).
* **Disconnect or shutdown during a claim:** the reward is already recorded, so it is delivered on the next join.
* After items are delivered the player's data is saved immediately (`delivery.save-player-data-after-claim`).
* All database work runs on a dedicated background thread. The shutdown waits for it to finish.
* **Backups:** on startup, before delete/reset/resetall, and on demand. The newest 10 are kept (`backups.keep`).
  Deleted calendars are moved to `backups/calendars/`, not destroyed.

## 19. Troubleshooting configuration errors

Broken configuration is never silently ignored. On startup and on `/calendar admin reload`, every problem is
logged with the file and the exact setting, for example:

```
[calendars/christmas.yml] rewards.day-5.items[0].material: 'DIAMOND_SWORDD' is not a valid Minecraft material for 1.21 (example: DIAMOND, LIME_STAINED_GLASS_PANE)
[calendars/christmas.yml] gui.items.close: slot 60 is outside the GUI (valid slots: 0-53)
[calendars/christmas.yml] gui: day 3 and day 4 are both in slot 12 on page 1; move one of them (day 3 is hidden)
[calendars/christmas.yml] unlock.timezone: 'Stockholm' is not a valid timezone (examples: Europe/Stockholm, UTC, America/New_York); using Europe/Stockholm
[calendars/christmas.yml] gui.items.info.actions[0]: unknown action type 'OPENMENU' in 'OPENMENU:info'. Valid types: CLOSE, BACK, ...
```

The admin who reloads sees the first problems in chat; `/calendar admin problems` lists them all. Where possible a
safe default is used (an invalid item shows as a BARRIER, so it is visible in-game) and the rest of the calendar
keeps working.

| Symptom | Fix |
|---|---|
| "This calendar is currently closed" | Check `unlock.start-date`/`end-date`/`timezone`, or `/calendar admin open <name>` |
| A day never unlocks | DAILY needs `start-date`; SCHEDULED needs a date for every day; MANUAL needs `/calendar admin unlockday` |
| `days` error after editing | `days:` must be a number. Rewards go under `rewards:` and per-day options under `day-settings:` |
| Some days missing in the GUI | More days than `day-slots` → they are on the next page; add page buttons or more slots |
| Reward command does nothing | The console log shows "returned false (unknown command?)": test the command in the console with a real name |
| Players got no items | `/calendar collect`, or check the console for "stored" messages |

## 20. Building from source

```bash
cd ScoreCalendar
mvn package                    # target/ScoreCalendar.jar (unit tests run automatically)
cd integration-test && bash run.sh   # optional end-to-end test on a real Paper 1.21.11 server (needs node + jq)
```

The GitHub workflow `.github/workflows/scorecalendar.yml` does both on every push.
