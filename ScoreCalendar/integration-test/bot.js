// End-to-end test bot for ScoreCalendar. Usage: node bot.js phase1|phase2
// The bot is op (ops.json) and joins an offline-mode Paper server on localhost.
const mineflayer = require('mineflayer');

const phase = process.argv[2] || 'phase1';
const results = [];
const chat = [];
let bot;

function check(name, ok, detail = '') {
  results.push({ name, ok: !!ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  [${phase}] ${name}${detail ? ' :: ' + detail : ''}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function text(v) {
  if (v == null) return '';
  return typeof v === 'string' ? v : JSON.stringify(v);
}
function count(name) {
  return bot.inventory.items().filter((i) => i.name === name).reduce((a, i) => a + i.count, 0);
}
function slot(i) {
  return bot.currentWindow ? bot.currentWindow.slots[i] : null;
}
function itemText(item) {
  return item ? JSON.stringify(item) : '';
}

async function waitFor(fn, timeout = 8000, step = 100) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    try {
      const v = fn();
      if (v) return v;
    } catch (e) { /* retry */ }
    await sleep(step);
  }
  return null;
}
async function waitForChat(re, timeout = 8000, since = 0) {
  return waitFor(() => chat.slice(since).find((m) => re.test(m)), timeout);
}
async function command(cmd, wait = 800) {
  bot.chat(cmd);
  await sleep(wait);
}
async function openWindow(cmd, titleRe, timeout = 8000) {
  let opened = null;
  const listener = (w) => { opened = w; };
  bot.on('windowOpen', listener);
  if (cmd) bot.chat(cmd);
  const w = await waitFor(() => opened && titleRe.test(text(opened.title)) ? opened : null, timeout);
  bot.removeListener('windowOpen', listener);
  return w;
}
async function clickAndWaitWindow(slotIndex, button, mode, titleRe) {
  let opened = null;
  const listener = (w) => { opened = w; };
  bot.on('windowOpen', listener);
  await bot.clickWindow(slotIndex, button, mode).catch(() => {});
  const w = await waitFor(() => opened && titleRe.test(text(opened.title)) ? opened : null, 8000);
  bot.removeListener('windowOpen', listener);
  return w;
}
async function click(slotIndex, button = 0, mode = 0, after = 400) {
  await bot.clickWindow(slotIndex, button, mode).catch(() => {});
  await sleep(after);
}
async function close() {
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
  await sleep(500);
}

function connect() {
  return new Promise((resolve, reject) => {
    bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'ScoreBot', version: '1.21.11', auth: 'offline' });
    bot.on('message', (m) => {
      const s = m.toString();
      chat.push(s);
      console.log('  chat> ' + s);
    });
    bot.once('spawn', () => resolve());
    bot.once('error', reject);
    bot.once('kicked', (r) => reject(new Error('kicked: ' + text(r))));
  });
}

async function phase1() {
  await connect();
  await sleep(3000);
  await command('/clear ScoreBot');
  await command('/calendar admin problems', 1500);
  check('admin problems command works', chat.some((m) => /No configuration problems found/.test(m)));

  await command('/calendar admin open christmas');
  for (const d of [1, 2, 3, 11, 12]) await command(`/calendar admin unlockday christmas ${d}`, 400);
  check('admin open/unlockday commands', chat.some((m) => /forced OPEN/.test(m)) && chat.some((m) => /Day 1 of christmas is now unlocked/.test(m)));

  // ---- calendar GUI
  let w = await openWindow('/calendar christmas', /CHRISTMAS CALENDAR/);
  check('calendar GUI opens with configured title', w, w && text(w.title));
  if (!w) return;
  check('GUI has 54 slots', w.inventoryStart === 54 || w.slots.length >= 54, 'inventoryStart=' + w.inventoryStart);
  check('day 1 available (lime pane x1)', slot(10)?.name === 'lime_stained_glass_pane' && slot(10)?.count === 1, itemText(slot(10)));
  check('day 2 available with amount 2', slot(11)?.count === 2, itemText(slot(11)));
  check('day 4 locked (gray pane x4)', slot(13)?.name === 'gray_stained_glass_pane' && slot(13)?.count === 4, itemText(slot(13)));
  check('day 12 uses special style (nether star)', slot(23)?.name === 'nether_star', itemText(slot(23)));
  check('day 24 moved to slot 40 (premium style when locked)', slot(40)?.name === 'light_blue_stained_glass_pane', itemText(slot(40)));
  check('close button in slot 49', slot(49)?.name === 'barrier');
  check('decoration star in slot 4', slot(4)?.name === 'nether_star');
  check('background fills empty slots', slot(48)?.name === 'red_stained_glass_pane' && slot(51)?.name === 'black_stained_glass_pane',
    itemText(slot(51)));

  // ---- exploit attempts on GUI items
  await click(4, 0, 1);            // shift-click decoration
  await click(4, 0, 2);            // number key 1 onto decoration
  await click(49, 0, 0);           // pick up barrier? (it is a CLOSE button)
  w = await openWindow('/calendar christmas', /CHRISTMAS CALENDAR/);
  await click(13, 0, 6);           // double click (collect to cursor)
  await click(13, 0, 4);           // drop key on locked day
  await close();
  await sleep(1000);
  const stolen = bot.inventory.items().filter((i) => /glass_pane|nether_star|barrier|book/.test(i.name));
  check('no GUI items can be taken (shift/number key/double click/drop)', stolen.length === 0, JSON.stringify(stolen.map((i) => i.name)));

  // ---- claim day 1 with click spam
  w = await openWindow('/calendar christmas', /CHRISTMAS CALENDAR/);
  for (let i = 0; i < 6; i++) { bot.clickWindow(10, 0, 0).catch(() => {}); await sleep(60); }
  await sleep(2500);
  check('day 1 reward delivered exactly once despite click spam (5 diamonds)', count('diamond') === 5, 'diamonds=' + count('diamond'));
  check('claim message shown', chat.some((m) => /You opened door Day 1/.test(m)));
  check('day 1 now shown as claimed (red pane)', slot(10)?.name === 'red_stained_glass_pane', itemText(slot(10)));
  await click(10);
  await sleep(800);
  check('claiming again is refused', count('diamond') === 5 && chat.some((m) => /already claimed this reward/.test(m)));

  // ---- locked day
  const before = chat.length;
  await click(13);
  check('locked day cannot be claimed', await waitForChat(/not unlocked yet/, 3000, before));

  // ---- enchanted item reward (day 12)
  await click(23);
  await sleep(1500);
  const sword = bot.inventory.items().find((i) => i.name === 'diamond_sword');
  check('day 12 gives the custom enchanted sword', sword && /Frostbite/.test(itemText(sword)) && /sharpness/.test(itemText(sword)), itemText(sword));

  // ---- reward preview (right click)
  const pv = await clickAndWaitWindow(11, 1, 0, /DAY 2/);
  check('right-click opens reward preview', pv, pv && text(pv.title));
  check('preview shows the reward item', pv && pv.slots[0]?.name === 'emerald', pv && itemText(pv.slots[0]));
  await click(0);
  await close();
  check('preview items cannot be taken', count('emerald') === 0);

  // ---- command reward (day 11)
  w = await openWindow('/calendar christmas', /CHRISTMAS CALENDAR/);
  const lvl = bot.experience.level;
  await click(22, 0, 0, 1500);
  check('command reward executed (30 levels)', bot.experience.level >= lvl + 30, `level ${lvl} -> ${bot.experience.level}`);
  await close();

  // ---- full inventory: reward is stored, then collected
  await command('/give ScoreBot minecraft:dirt 2304', 1200);
  w = await openWindow('/calendar christmas', /CHRISTMAS CALENDAR/);
  const b2 = chat.length;
  await click(11, 0, 0, 2000);
  check('full inventory: rewards are stored, not lost', await waitForChat(/inventory is full/, 4000, b2) && count('emerald') === 0);
  check('stored-rewards button appears', await waitFor(() => slot(51)?.name === 'hopper', 8000), itemText(slot(51)));
  await close();
  await command('/clear ScoreBot minecraft:dirt', 1000);
  const pending = await openWindow('/calendar collect', /STORED REWARDS/);
  check('stored rewards menu lists the emeralds', pending && pending.slots[0]?.name === 'emerald', pending && itemText(pending.slots[0]));
  if (pending) {
    await click(0, 0, 0, 2000);
  }
  check('stored emeralds collected (16)', count('emerald') === 16, 'emeralds=' + count('emerald'));
  await close();
  const again = await openWindow('/calendar collect', /STORED REWARDS/);
  check('nothing left to collect (no duplication)', again && again.slots[0]?.name !== 'emerald');
  await close();

  // ---- admin give
  await command('/calendar admin give ScoreBot christmas 5', 1500);
  check('admin give delivers the reward', count('end_crystal') === 2, 'end_crystal=' + count('end_crystal'));

  // ---- reward editor: add an item and save
  let ed = await openWindow('/calendar admin edit christmas', /EDIT/);
  check('admin edit menu opens', ed, ed && text(ed.title));
  if (ed) {
    const picker = await clickAndWaitWindow(20, 0, 0, /REWARDS/);
    check('reward day picker opens', picker);
    const editor = picker && await clickAndWaitWindow(0, 0, 0, /REWARD/);
    check('reward editor opens with existing items', editor && editor.slots[0]?.name === 'diamond', editor && itemText(editor.slots[0]));
    if (editor) {
      const crystalSlot = editor.slots.findIndex((s, i) => i >= 54 && s && s.name === 'end_crystal');
      await click(crystalSlot, 0, 1, 800); // shift-click into the workspace
      check('item moved into the reward workspace', bot.currentWindow?.slots.slice(0, 36).some((s) => s && s.name === 'end_crystal'));
      await click(45, 0, 0, 2000); // save
      check('reward saved', chat.some((m) => /Saved the reward of christmas day 1/.test(m)));
      check('editor buttons cannot be taken', !bot.inventory.items().some((i) => /concrete|command_block|bucket/.test(i.name)));
    }
    await close();
  }

  // ---- visual layout editor: move day 1 to slot 37 (swap with snow) and save with number key 9
  ed = await openWindow('/calendar admin edit christmas', /EDIT/);
  const layout = ed && await clickAndWaitWindow(19, 0, 0, /EDITOR/);
  check('visual layout editor opens', layout, layout && text(layout.title));
  if (layout) {
    await click(10, 0, 0, 600);
    await click(37, 0, 0, 1000);
    await click(10, 8, 2, 2500); // number key 9 = save
    check('layout saved', chat.some((m) => /Saved changes to christmas/.test(m)));
  }
  await close();
  w = await openWindow('/calendar christmas', /CHRISTMAS CALENDAR/);
  check('day 1 now in slot 37 after layout edit', slot(37)?.name === 'red_stained_glass_pane' && slot(10)?.name === 'snow_block',
    itemText(slot(37)) + ' / ' + itemText(slot(10)));

  // ---- disconnect during claim (day 3: 4 golden apples)
  check('no golden apples yet', count('golden_apple') === 0);
  bot.clickWindow(12, 0, 0).catch(() => {});
  await sleep(30);
  bot.quit();
  await sleep(1500);
}

async function phase2() {
  await connect();
  await sleep(6000); // join delay + automatic delivery of pending rewards
  check('reward claimed during disconnect delivered exactly once after restart (4 golden apples)', count('golden_apple') === 4,
    'golden_apple=' + count('golden_apple'));
  check('inventory from before the restart kept (5 diamonds + sword)', count('diamond') === 5 && count('diamond_sword') === 1);
  let w = await openWindow('/calendar christmas', /CHRISTMAS CALENDAR/);
  check('calendar still forced open after restart', w);
  if (w) {
    check('claim of day 1 persisted (claimed, at moved slot 37)', slot(37)?.name === 'red_stained_glass_pane', itemText(slot(37)));
    check('claim of day 3 persisted', slot(12)?.name === 'red_stained_glass_pane', itemText(slot(12)));
    const b = chat.length;
    await click(37, 0, 0, 1200);
    check('claimed day cannot be claimed after restart', await waitForChat(/already claimed/, 3000, b) && count('diamond') === 5);
    await close();
  }

  // ---- PlaceholderAPI
  const b3 = chat.length;
  await command('/papi parse me claimed=%scorecalendar_christmas_days_claimed% total=%scorecalendar_christmas_days_total%', 1500);
  const papi = chat.slice(b3).find((m) => /claimed=\d+ total=24/.test(m));
  if (chat.slice(b3).some((m) => /Unknown or incomplete command|Unknown command/.test(m))) {
    console.log('SKIP  PlaceholderAPI not installed');
  } else {
    check('PlaceholderAPI placeholders resolve', papi && /claimed=5 total=24/.test(papi), papi);
  }

  // ---- 30-day calendar with pages, created in-game
  await command('/calendar admin create e2e30 30 ADVENT', 1500);
  check('30-day calendar created', chat.some((m) => /Created calendar e2e30 \(30 days/.test(m)));
  await close();
  await command('/calendar admin open e2e30');
  await command('/calendar admin unlockday e2e30 29');
  // Opens on the page of the first claimable day (day 29 -> page 2).
  w = await openWindow('/calendar e2e30', /\(2\/2\)/);
  check('30-day calendar opens on page 2 where day 29 is claimable', w, w && text(w.title));
  if (w) {
    check('day 29 is on page 2 and unlocked', slot(10)?.name === 'lime_stained_glass_pane' && slot(10)?.count === 29, itemText(slot(10)));
    const p1 = await clickAndWaitWindow(48, 0, 0, /\(1\/2\)/);
    check('previous page button opens page 1', p1, p1 && text(p1.title));
    check('day 1 on page 1 is locked', p1 && p1.slots[10]?.name === 'gray_stained_glass_pane', p1 && itemText(p1.slots[10]));
    const p2 = p1 && await clickAndWaitWindow(50, 0, 0, /\(2\/2\)/);
    check('next page button opens page 2', p2);
  }
  await close();
  await command('/calendar admin delete e2e30', 800);
  await command('/calendar admin delete e2e30 confirm', 1500);
  check('calendar deleted after confirmation', chat.some((m) => /Deleted calendar e2e30/.test(m)));
  await command('/calendar e2e30', 800);
  check('deleted calendar is gone', chat.some((m) => /no calendar called e2e30/.test(m)));

  // ---- reset
  await command('/calendar admin reset ScoreBot christmas', 1500);
  w = await openWindow('/calendar christmas', /CHRISTMAS CALENDAR/);
  check('reset makes day 1 available again', w && slot(37)?.name === 'lime_stained_glass_pane', w && itemText(slot(37)));
  await close();
  bot.quit();
}

(async () => {
  try {
    if (phase === 'phase1') await phase1(); else await phase2();
  } catch (e) {
    check('bot ran without errors', false, e && e.stack ? e.stack : String(e));
  }
  try { bot && bot.quit(); } catch (e) { /* ignore */ }
  const failed = results.filter((r) => !r.ok);
  console.log(`== ${phase}: ${results.length - failed.length}/${results.length} checks passed`);
  setTimeout(() => process.exit(failed.length ? 1 : 0), 500);
})();
