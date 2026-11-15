package net.scoresmp.bounties;

import net.scoresmp.bounties.gui.BountyListMenu;
import net.scoresmp.bounties.gui.ClaimMenu;
import net.scoresmp.bounties.gui.DepositMenu;
import net.scoresmp.bounties.model.Bounty;
import net.scoresmp.bounties.model.Contribution;
import net.scoresmp.bounties.service.ClaimService;
import net.scoresmp.bounties.storage.Database;
import net.scoresmp.bounties.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BountyFlowTest {

    private static final int CONFIRM = 53;
    private static final int CANCEL = 45;

    private ServerMock server;
    private ScoreBounties plugin;

    /** A player whose killer can be set, since the mock does not track combat. */
    static final class FighterMock extends PlayerMock {
        private Player killer;

        FighterMock(ServerMock server, String name) {
            super(server, name, UUID.nameUUIDFromBytes(("fighter:" + name).getBytes()));
        }

        @Override
        public Player getKiller() {
            return killer;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        TestSupport.useTestCodec();
        plugin = MockBukkit.load(ScoreBounties.class);
        config("restrictions.deposit-cooldown-seconds", 0);
        config("claims.deliver-on-join-delay-seconds", 0);
        config("claims.save-player-data-on-delivery", false); // not implemented by the mock server
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void config(String key, Object value) throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set(key, value);
        yaml.save(file);
        plugin.reload();
    }

    /** Waits for every queued database operation and the resulting main-thread callbacks. */
    private void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            plugin.database().submit("flush", c -> null).get(10, TimeUnit.SECONDS);
            server.getScheduler().performTicks(3);
        }
    }

    private FighterMock fighter(String name) throws Exception {
        FighterMock player = new FighterMock(server, name);
        server.addPlayer(player);
        settle();
        TestSupport.messages(player);
        return player;
    }

    private PlayerMock player(String name) throws Exception {
        PlayerMock player = server.addPlayer(name);
        settle();
        TestSupport.messages(player);
        return player;
    }

    private static ItemStack diamonds(int amount) {
        return new ItemStack(Material.DIAMOND, amount);
    }

    private static ItemStack customSword() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(Text.item("&#00B2FFReaper"));
        meta.lore(List.of(Text.item("&7Forged on ScoreSMP")));
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.setCustomModelData(4242); // deprecated in 1.21.4+ but still a valid data component
        ((Damageable) meta).setDamage(120);
        sword.setItemMeta(meta);
        return sword;
    }

    private DepositMenu openDeposit(PlayerMock player, Player target, boolean replace) {
        assertTrue(player.performCommand("bounty " + (replace ? "set " : "add ") + target.getName()));
        InventoryHolder holder = net.scoresmp.bounties.gui.Menu.openMenu(player);
        assertInstanceOf(DepositMenu.class, holder, "deposit menu should be open");
        return (DepositMenu) holder;
    }

    private InventoryClickEvent click(PlayerMock player, int raw, ClickType type, InventoryAction action) {
        InventoryView view = player.getOpenInventory();
        InventoryClickEvent event = new InventoryClickEvent(view,
                InventoryType.SlotType.CONTAINER, raw, type, action);
        server.getPluginManager().callEvent(event);
        return event;
    }

    /**
     * Raw view slot showing the given player-inventory slot. Found through the view
     * itself so the test does not depend on the server's raw slot numbering.
     */
    private static int raw(PlayerMock player, int inventorySlot) {
        InventoryView view = player.getOpenInventory();
        int top = view.getTopInventory().getSize();
        ItemStack marker = new ItemStack(Material.STRUCTURE_VOID, 1);
        ItemStack original = player.getInventory().getItem(inventorySlot);
        player.getInventory().setItem(inventorySlot, marker);
        try {
            for (int raw = top; raw < top + 36; raw++) {
                ItemStack item = view.getItem(raw);
                if (item != null && item.getType() == Material.STRUCTURE_VOID) {
                    return raw;
                }
            }
            throw new AssertionError("slot not found");
        } finally {
            player.getInventory().setItem(inventorySlot, original);
        }
    }

    /** Shift-clicks a player inventory item into the deposit menu. */
    private void shiftIn(PlayerMock player, int hotbar) {
        InventoryClickEvent event = click(player, raw(player, hotbar), ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertTrue(event.isCancelled(), "shift-click is performed by the plugin");
    }

    private void confirm(PlayerMock player) throws Exception {
        click(player, CONFIRM, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        settle();
    }

    private void placeBounty(PlayerMock contributor, Player target, ItemStack... items) throws Exception {
        contributor.getInventory().clear();
        for (int i = 0; i < items.length; i++) {
            contributor.getInventory().setItem(i, items[i].clone());
        }
        openDeposit(contributor, target, false);
        for (int i = 0; i < items.length; i++) {
            shiftIn(contributor, i);
        }
        confirm(contributor);
    }

    private void kill(FighterMock victim, Player killer, boolean direct) throws Exception {
        victim.killer = killer;
        DamageSource source = (DamageSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DamageSource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCausingEntity", "getDirectEntity" -> direct ? (Entity) killer : null;
                    case "isIndirect" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "TestDamageSource";
                    default -> null;
                });
        PlayerDeathEvent event = new PlayerDeathEvent(victim, source, new ArrayList<>(), 0, null, true);
        server.getPluginManager().callEvent(event);
        settle();
    }

    private int count(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private ItemStack find(PlayerMock player, Material material) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                return item;
            }
        }
        return null;
    }

    private long query(String sql) throws Exception {
        File file = new File(plugin.getDataFolder(), "database/bounties.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static boolean saw(List<String> messages, String text) {
        return messages.stream().anyMatch(m -> m.contains(text));
    }

    // ------------------------------------------------------------------
    // 1. Creating a bounty + 14. announcement
    // ------------------------------------------------------------------

    @Test
    void createBountyStoresExactItemsAndBroadcasts() throws Exception {
        PlayerMock aimed = player("Aimed");
        FighterMock notch = fighter("Notch");
        ItemStack sword = customSword();
        aimed.getInventory().setItem(0, diamonds(16));
        aimed.getInventory().setItem(1, sword.clone());

        DepositMenu menu = openDeposit(aimed, notch, false);
        assertTrue(Text.plain(aimed.getOpenInventory().title()).contains("Notch"), "target name in title");
        assertTrue(TestSupport.messages(notch).isEmpty(), "no broadcast when the menu opens");
        shiftIn(aimed, 0);
        shiftIn(aimed, 1);
        assertEquals(2, menu.contents().size());
        assertNull(aimed.getInventory().getItem(0));
        confirm(aimed);

        Bounty bounty = plugin.bounties().bounty(notch.getUniqueId());
        assertNotNull(bounty);
        assertEquals(17, bounty.totalAmount());
        assertEquals(2, bounty.stackCount());
        assertEquals(1, bounty.contributorCount());
        assertTrue(bounty.itemStacks().stream().anyMatch(i -> i.isSimilar(sword)), "exact custom item kept");
        assertEquals(0, count(aimed, Material.DIAMOND));
        assertFalse(net.scoresmp.bounties.gui.Menu.openMenu(aimed) instanceof DepositMenu);

        List<String> seen = TestSupport.messages(notch);
        assertTrue(seen.contains("SCORESMP > Aimed Put a Bounty on Notch"), "exact broadcast, got " + seen);
        assertEquals(0, query("SELECT COUNT(*) FROM deposit_journal"), "journal cleared");
        assertEquals(2, query("SELECT COUNT(*) FROM contribution_items WHERE status = 'ACTIVE'"));
    }

    // ------------------------------------------------------------------
    // 2. Adding items to an existing bounty
    // ------------------------------------------------------------------

    @Test
    void addCombinesContributions() throws Exception {
        PlayerMock a = player("Alpha");
        PlayerMock c = player("Charlie");
        FighterMock target = fighter("Target");
        placeBounty(a, target, diamonds(10));
        placeBounty(c, target, new ItemStack(Material.END_CRYSTAL, 16));

        Bounty bounty = plugin.bounties().bounty(target.getUniqueId());
        assertEquals(26, bounty.totalAmount());
        assertEquals(2, bounty.contributorCount());
        List<String> seen = TestSupport.messages(target);
        assertTrue(seen.contains("SCORESMP > Charlie Put a Bounty on Target"), "broadcast on add: " + seen);
        assertTrue(saw(TestSupport.messages(c), "You added 16 items"));
    }

    // ------------------------------------------------------------------
    // 3. Replacing a bounty (+ 10. offline contributor returns)
    // ------------------------------------------------------------------

    @Test
    void setReplacesAfterSecondConfirmAndReturnsItems() throws Exception {
        PlayerMock a = player("Alpha");
        PlayerMock c = player("Charlie");
        FighterMock target = fighter("Target");
        placeBounty(a, target, diamonds(10));
        assertEquals(0, count(a, Material.DIAMOND));

        c.getInventory().setItem(0, new ItemStack(Material.EMERALD, 5));
        openDeposit(c, target, true);
        assertTrue(saw(TestSupport.messages(c), "already has a bounty"), "replacement warning");
        shiftIn(c, 0);
        confirm(c); // arms only
        assertEquals(10, plugin.bounties().bounty(target.getUniqueId()).totalAmount(), "not replaced yet");
        assertTrue(saw(TestSupport.messages(c), "Click confirm again"));
        Thread.sleep(200);
        confirm(c);

        Bounty bounty = plugin.bounties().bounty(target.getUniqueId());
        assertEquals(5, bounty.totalAmount());
        assertEquals(Material.EMERALD, bounty.itemStacks().get(0).getType());
        assertEquals(10, count(a, Material.DIAMOND), "previous contributor got items back");
        assertTrue(saw(TestSupport.messages(a), "was returned to you"));
        assertEquals(1, query("SELECT COUNT(*) FROM contributions WHERE status = 'REPLACED'"));
    }

    @Test
    void offlineContributorReceivesReturnedItemsOnJoin() throws Exception {
        PlayerMock a = player("Alpha");
        PlayerMock c = player("Charlie");
        FighterMock target = fighter("Target");
        placeBounty(a, target, diamonds(10));
        a.disconnect();
        settle();

        c.getInventory().setItem(0, new ItemStack(Material.EMERALD, 5));
        openDeposit(c, target, true);
        shiftIn(c, 0);
        confirm(c);
        Thread.sleep(200);
        confirm(c);
        assertEquals(1, query("SELECT COUNT(*) FROM pending_claims WHERE status = 'PENDING'"), "saved for later");

        a.getInventory().clear();
        a.reconnect();
        settle();
        server.getScheduler().performTicks(5);
        settle();
        assertEquals(10, count(a, Material.DIAMOND), "delivered on join");
        assertEquals(0, query("SELECT COUNT(*) FROM pending_claims WHERE status = 'PENDING'"));
    }

    // ------------------------------------------------------------------
    // 4./5./6. Cancel, close, disconnect
    // ------------------------------------------------------------------

    @Test
    void cancelReturnsItemsWithoutBroadcast() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock target = fighter("Target");
        a.getInventory().setItem(0, diamonds(32));
        openDeposit(a, target, false);
        shiftIn(a, 0);
        assertEquals(0, count(a, Material.DIAMOND));
        click(a, CANCEL, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        settle();
        assertEquals(32, count(a, Material.DIAMOND));
        assertNull(plugin.bounties().bounty(target.getUniqueId()));
        assertFalse(saw(TestSupport.messages(target), "Put a Bounty"));
        assertEquals(0, query("SELECT COUNT(*) FROM deposit_journal"));
    }

    @Test
    void closingTheMenuReturnsItems() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock target = fighter("Target");
        a.getInventory().setItem(0, customSword());
        openDeposit(a, target, false);
        shiftIn(a, 0);
        settle();
        assertEquals(1, query("SELECT COUNT(*) FROM deposit_journal"), "open menu contents are journaled");
        a.closeInventory();
        settle();
        ItemStack back = find(a, Material.NETHERITE_SWORD);
        assertNotNull(back);
        assertTrue(back.isSimilar(customSword()));
        assertEquals(0, query("SELECT COUNT(*) FROM deposit_journal"));
    }

    @Test
    void disconnectingDuringDepositKeepsItemsForRejoin() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock target = fighter("Target");
        a.getInventory().setItem(0, diamonds(20));
        openDeposit(a, target, false);
        shiftIn(a, 0);
        a.disconnect();
        settle();
        assertNull(plugin.bounties().bounty(target.getUniqueId()));
        long stored = query("SELECT COUNT(*) FROM pending_claims WHERE status = 'PENDING'")
                + query("SELECT COUNT(*) FROM deposit_journal");
        assertTrue(stored >= 1, "items are stored in the database");
        a.reconnect();
        settle();
        server.getScheduler().performTicks(5);
        settle();
        assertEquals(20, count(a, Material.DIAMOND));
    }

    @Test
    void crashDuringDepositIsRecoveredFromJournal() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock target = fighter("Target");
        a.getInventory().setItem(0, diamonds(12));
        DepositMenu menu = openDeposit(a, target, false);
        shiftIn(a, 0);
        settle();
        assertEquals(1, query("SELECT COUNT(*) FROM deposit_journal"));
        // Simulate the start-up recovery that runs after a crash.
        int recovered = plugin.database().recoverJournals().get(10, TimeUnit.SECONDS);
        assertEquals(1, recovered);
        assertEquals(1, query("SELECT COUNT(*) FROM pending_claims WHERE status = 'PENDING' AND source = 'DEPOSIT_RETURN' AND amount = 12"));
        menu.getInventory().clear(); // the crashed menu no longer exists
    }

    // ------------------------------------------------------------------
    // 7. Kill + 12. duplicate prevention
    // ------------------------------------------------------------------

    @Test
    void killerReceivesExactItemsOnce() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock killer = fighter("Killer");
        FighterMock victim = fighter("Victim");
        ItemStack sword = customSword();
        placeBounty(a, victim, diamonds(64), sword);
        TestSupport.messages(killer);

        kill(victim, killer, true);
        kill(victim, killer, true); // repeated death event

        assertEquals(64, count(killer, Material.DIAMOND));
        ItemStack received = find(killer, Material.NETHERITE_SWORD);
        assertNotNull(received);
        assertTrue(received.isSimilar(sword), "enchantments, name, lore, flags, damage, model data preserved");
        assertEquals(120, ((Damageable) received.getItemMeta()).getDamage());
        assertNull(plugin.bounties().bounty(victim.getUniqueId()));
        List<String> seen = TestSupport.messages(killer);
        assertTrue(seen.contains("SCORESMP > Killer claimed the bounty on Victim!"), "claimed broadcast: " + seen);
        assertEquals(1, query("SELECT COUNT(*) FROM claim_history"));
        assertEquals(1, plugin.bounties().stats(killer.getUniqueId()).bountiesClaimed());
        assertEquals(1, plugin.bounties().stats(victim.getUniqueId()).timesClaimedOn());
    }

    @Test
    void databaseRefusesToPayTheSameContributionTwice() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock killer = fighter("Killer");
        FighterMock victim = fighter("Victim");
        placeBounty(a, victim, diamonds(5));
        List<Contribution> contributions = plugin.bounties().bounty(victim.getUniqueId()).contributions();
        kill(victim, killer, true);
        Exception error = assertThrows(Exception.class, () -> plugin.database().commitBountyClaim(victim.getUniqueId(),
                "Victim", killer.getUniqueId(), "Killer", contributions, List.of(), null).get(10, TimeUnit.SECONDS));
        assertInstanceOf(Database.ConflictException.class, error.getCause());
        assertEquals(5, count(killer, Material.DIAMOND));
    }

    @Test
    void environmentalAndIneligibleDeathsDoNotPay() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock killer = fighter("Killer");
        FighterMock victim = fighter("Victim");
        placeBounty(a, victim, diamonds(5));

        kill(victim, killer, false); // last hit by killer, but died to the environment
        assertNotNull(plugin.bounties().bounty(victim.getUniqueId()));
        assertTrue(saw(TestSupport.messages(killer), "the kill was not direct"));

        killer.setAddress(new InetSocketAddress("203.0.113.7", 1234));
        victim.setAddress(new InetSocketAddress("203.0.113.7", 4321));
        kill(victim, killer, true); // alt account on the same IP
        assertNotNull(plugin.bounties().bounty(victim.getUniqueId()));
        assertTrue(saw(TestSupport.messages(killer), "share an IP"));

        killer.addAttachment(plugin, "scorebounties.claim", false);
        killer.setAddress(new InetSocketAddress("203.0.113.8", 1234));
        kill(victim, killer, true);
        assertNotNull(plugin.bounties().bounty(victim.getUniqueId()));
        assertEquals(0, count(killer, Material.DIAMOND));
    }

    @Test
    void repeatKillCooldownBlocksFarming() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock killer = fighter("Killer");
        FighterMock victim = fighter("Victim");
        placeBounty(a, victim, diamonds(5));
        kill(victim, killer, true);
        placeBounty(a, victim, diamonds(7));
        kill(victim, killer, true);
        assertEquals(5, count(killer, Material.DIAMOND));
        assertNotNull(plugin.bounties().bounty(victim.getUniqueId()));
        assertTrue(saw(TestSupport.messages(killer), "too recently"));
    }

    // ------------------------------------------------------------------
    // 8./9. Full inventory and /bounty claim
    // ------------------------------------------------------------------

    @Test
    void fullInventoryStoresRewardsForClaimMenu() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock killer = fighter("Killer");
        FighterMock victim = fighter("Victim");
        placeBounty(a, victim, diamonds(64), new ItemStack(Material.TOTEM_OF_UNDYING));
        for (int i = 0; i < 36; i++) {
            killer.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        }
        TestSupport.messages(killer);
        kill(victim, killer, true);
        assertEquals(0, count(killer, Material.DIAMOND));
        assertTrue(saw(TestSupport.messages(killer), "were stored"));
        assertEquals(2, plugin.claims().cache(killer.getUniqueId()).size());
        assertEquals(2, query("SELECT COUNT(*) FROM pending_claims WHERE status = 'PENDING'"));
        assertEquals(0, query("SELECT COUNT(*) FROM pending_claims WHERE status = 'PENDING' AND owner_uuid = '"
                + a.getUniqueId() + "'"));

        // claim-all with a full inventory does nothing
        assertTrue(killer.performCommand("bounty claim"));
        assertInstanceOf(ClaimMenu.class, net.scoresmp.bounties.gui.Menu.openMenu(killer));
        click(killer, 49, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        settle();
        assertTrue(saw(TestSupport.messages(killer), "inventory is full"));

        // free one slot: one stack fits
        killer.getInventory().setItem(0, null);
        Thread.sleep(200);
        click(killer, 49, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        settle();
        assertEquals(1, plugin.claims().cache(killer.getUniqueId()).size());

        // free everything else: collect the rest by clicking the item itself
        killer.getInventory().remove(Material.DIRT);
        settle();
        InventoryHolder holder = net.scoresmp.bounties.gui.Menu.openMenu(killer);
        assertInstanceOf(ClaimMenu.class, holder);
        Thread.sleep(200);
        click(killer, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        settle();
        assertEquals(0, plugin.claims().cache(killer.getUniqueId()).size());
        assertEquals(1, count(killer, Material.TOTEM_OF_UNDYING));
        assertEquals(64, count(killer, Material.DIAMOND));
        assertEquals(0, query("SELECT COUNT(*) FROM pending_claims WHERE status = 'PENDING'"));

        // clicking again (stale menu / double click) cannot give anything twice
        Thread.sleep(200);
        click(killer, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        settle();
        assertEquals(64, count(killer, Material.DIAMOND));
        assertEquals(1, count(killer, Material.TOTEM_OF_UNDYING));
    }

    @Test
    void partialStackDeliveryKeepsTheRest() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock killer = fighter("Killer");
        FighterMock victim = fighter("Victim");
        placeBounty(a, victim, diamonds(64));
        for (int i = 0; i < 36; i++) {
            killer.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
        }
        killer.getInventory().setItem(0, diamonds(40)); // room for 24 more
        kill(victim, killer, true);
        assertEquals(64, count(killer, Material.DIAMOND));
        ClaimService.Cache cache = plugin.claims().cache(killer.getUniqueId());
        assertEquals(1, cache.size());
        assertEquals(40, cache.claims().get(0).amount());
        assertEquals(40, query("SELECT amount FROM pending_claims WHERE status = 'PENDING'"));
    }

    @Test
    void killerWhoLeavesReceivesRewardOnJoin() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock killer = fighter("Killer");
        FighterMock victim = fighter("Victim");
        placeBounty(a, victim, diamonds(30));
        victim.killer = killer;
        kill(victim, killer, true);
        // Now a second scenario where the killer disconnects before the transaction finishes
        placeBounty(a, victim, new ItemStack(Material.GOLD_INGOT, 9));
        config("anti-farming.repeat-kill-cooldown-minutes", 0);
        killer.getInventory().clear();
        victim.killer = killer;
        DamageSource source = (DamageSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DamageSource.class}, (proxy, method, args) ->
                        method.getName().equals("getCausingEntity") ? killer : null);
        server.getPluginManager().callEvent(new PlayerDeathEvent(victim, source, new ArrayList<>(), 0, null, true));
        killer.disconnect(); // before the database callback runs
        settle();
        assertEquals(0, count(killer, Material.GOLD_INGOT));
        assertEquals(1, query("SELECT COUNT(*) FROM pending_claims WHERE status = 'PENDING'"));
        killer.reconnect();
        settle();
        server.getScheduler().performTicks(5);
        settle();
        assertEquals(9, count(killer, Material.GOLD_INGOT));
    }

    // ------------------------------------------------------------------
    // 11. Restart
    // ------------------------------------------------------------------

    @Test
    void bountiesSurviveRestart() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock target = fighter("Target");
        ItemStack sword = customSword();
        placeBounty(a, target, sword, diamonds(3));
        server.getPluginManager().disablePlugin(plugin);
        assertFalse(plugin.isEnabled());
        server.getPluginManager().enablePlugin(plugin);
        assertTrue(plugin.isEnabled());
        Bounty bounty = plugin.bounties().bounty(target.getUniqueId());
        assertNotNull(bounty);
        assertEquals(4, bounty.totalAmount());
        assertTrue(bounty.itemStacks().stream().anyMatch(i -> i.isSimilar(sword)));
        assertEquals(1, bounty.contributorCount());
    }

    @Test
    void shutdownWithOpenDepositReturnsItems() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock target = fighter("Target");
        a.getInventory().setItem(0, diamonds(8));
        openDeposit(a, target, false);
        shiftIn(a, 0);
        server.getPluginManager().disablePlugin(plugin);
        server.getPluginManager().enablePlugin(plugin);
        settle();
        server.getScheduler().performTicks(5);
        settle();
        assertEquals(8, count(a, Material.DIAMOND));
    }

    // ------------------------------------------------------------------
    // 13. Pagination and main menu
    // ------------------------------------------------------------------

    @Test
    void mainMenuPaginatesAndSorts() throws Exception {
        config("restrictions.allow-offline-targets", true);
        PlayerMock a = player("Alpha");
        for (int i = 0; i < 50; i++) {
            PlayerMock target = server.addPlayer("T" + i);
            placeBounty(a, target, diamonds(i + 1));
        }
        settle();
        assertEquals(50, plugin.bounties().all().size());
        assertTrue(a.performCommand("bounty"));
        BountyListMenu menu = (BountyListMenu) net.scoresmp.bounties.gui.Menu.openMenu(a);
        int heads = 0;
        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = a.getOpenInventory().getTopInventory().getItem(slot);
            if (item != null && item.getType() == Material.PLAYER_HEAD) {
                heads++;
            }
        }
        assertEquals(45, heads);
        ItemStack first = a.getOpenInventory().getTopInventory().getItem(0);
        List<String> lore = first.getItemMeta().lore().stream().map(Text::plain).toList();
        assertTrue(lore.contains("BOUNTY"));
        assertTrue(lore.stream().anyMatch(l -> l.startsWith("- ") && l.contains("x Diamond")), "item lines: " + lore);
        assertTrue(lore.stream().anyMatch(l -> l.startsWith("Contributors: 1")));
        assertTrue(lore.contains("Click to view bounty"));
        assertEquals("T49", Text.plain(first.getItemMeta().displayName()), "newest first");

        click(a, 53, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        settle();
        heads = 0;
        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = a.getOpenInventory().getTopInventory().getItem(slot);
            if (item != null && item.getType() == Material.PLAYER_HEAD) {
                heads++;
            }
        }
        assertEquals(5, heads);
        assertEquals(1, menu.page());
        assertTrue(Text.plain(a.getOpenInventory().title()).contains("2/2"));

        Thread.sleep(200);
        click(a, 48, ClickType.LEFT, InventoryAction.PICKUP_ALL); // sort by most items
        settle();
        ItemStack top = a.getOpenInventory().getTopInventory().getItem(0);
        assertEquals("T49", Text.plain(top.getItemMeta().displayName()));
        assertEquals(0, menu.page());

        // clicking a head opens the details menu, which shows the real items
        Thread.sleep(200);
        click(a, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        settle();
        assertEquals("BOUNTY » T49", Text.plain(a.getOpenInventory().title()));
        assertEquals(Material.DIAMOND, a.getOpenInventory().getTopInventory().getItem(0).getType());
        assertEquals(50, a.getOpenInventory().getTopInventory().getItem(0).getAmount());
        InventoryClickEvent take = click(a, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        assertTrue(take.isCancelled(), "items in the details menu cannot be taken");
    }

    // ------------------------------------------------------------------
    // Anti-exploit
    // ------------------------------------------------------------------

    @Test
    void forbiddenInteractionsAreBlocked() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock target = fighter("Target");
        a.getInventory().setItem(0, diamonds(10));
        a.getInventory().setItem(1, new ItemStack(Material.GRAY_STAINED_GLASS_PANE, 10));
        DepositMenu menu = openDeposit(a, target, false);

        // taking or swapping a button / pane
        assertTrue(click(a, 46, ClickType.LEFT, InventoryAction.PICKUP_ALL).isCancelled());
        assertTrue(click(a, 49, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP).isCancelled());
        assertTrue(click(a, 50, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled());
        // double-click collect (could pull panes) and creative clone
        assertTrue(click(a, 0, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR).isCancelled());
        assertTrue(click(a, raw(a, 1), ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR).isCancelled());
        assertTrue(click(a, 0, ClickType.MIDDLE, InventoryAction.CLONE_STACK).isCancelled());
        // shift-clicking glass panes must not merge into the filler panes
        shiftIn(a, 1);
        assertEquals(10, menu.getInventory().getItem(0).getAmount());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.getInventory().getItem(0).getType());
        assertNull(menu.getInventory().getItem(46).getItemMeta().lore(), "filler unchanged");
        assertEquals(1, menu.getInventory().getItem(46).getAmount());
        // drag across a button slot
        Map<Integer, ItemStack> slots = new HashMap<>();
        slots.put(1, diamonds(5));
        slots.put(53, diamonds(5));
        InventoryDragEvent drag = new InventoryDragEvent(a.getOpenInventory(), null, diamonds(10), false, slots);
        server.getPluginManager().callEvent(drag);
        assertTrue(drag.isCancelled());
        // normal placement into a deposit slot is allowed
        assertFalse(click(a, 5, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled());

        // double confirm submits only once
        shiftIn(a, 0);
        click(a, CONFIRM, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        Thread.sleep(200);
        click(a, CONFIRM, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        settle();
        assertEquals(1, query("SELECT COUNT(*) FROM contributions"));
        assertEquals(20, plugin.bounties().bounty(target.getUniqueId()).totalAmount());
        assertEquals(0, count(a, Material.DIAMOND));
    }

    @Test
    void emptyDepositAndBlacklistAndCreative() throws Exception {
        config("restrictions.blacklisted-materials", List.of("BEDROCK"));
        PlayerMock a = player("Alpha");
        FighterMock target = fighter("Target");
        openDeposit(a, target, false);
        confirm(a);
        assertTrue(saw(TestSupport.messages(a), "at least one item"));
        assertNull(plugin.bounties().bounty(target.getUniqueId()));

        a.getInventory().setItem(0, new ItemStack(Material.BEDROCK));
        shiftIn(a, 0);
        assertEquals(Material.BEDROCK, a.getInventory().getItem(0).getType(), "blacklisted item stays");
        assertTrue(saw(TestSupport.messages(a), "cannot put Bedrock"));
        a.closeInventory();
        settle();

        a.setGameMode(GameMode.CREATIVE);
        a.performCommand("bounty add Target");
        assertFalse(net.scoresmp.bounties.gui.Menu.openMenu(a) instanceof DepositMenu);
        assertTrue(saw(TestSupport.messages(a), "creative"));
    }

    // ------------------------------------------------------------------
    // 15./16. Commands and permissions
    // ------------------------------------------------------------------

    @Test
    void commandArgumentsAndPermissions() throws Exception {
        PlayerMock a = player("Alpha");
        a.performCommand("bounty add");
        assertTrue(saw(TestSupport.messages(a), "Usage: /bounty add <player>"));
        a.performCommand("bounty add Nobody123");
        assertTrue(saw(TestSupport.messages(a), "has never joined"));
        a.performCommand("bounty add Alpha");
        assertTrue(saw(TestSupport.messages(a), "on yourself"));
        a.performCommand("bounty nonsense");
        assertTrue(saw(TestSupport.messages(a), "Unknown subcommand"));
        a.performCommand("bounty info Alpha");
        assertTrue(saw(TestSupport.messages(a), "does not have an active bounty"));
        a.performCommand("bounty admin reload");
        assertTrue(saw(TestSupport.messages(a), "do not have permission"));
        a.performCommand("bountys help");
        assertTrue(saw(TestSupport.messages(a), "/bounty add <player>"), "alias works");

        a.addAttachment(plugin, "scorebounties.add", false);
        FighterMock target = fighter("Target");
        a.performCommand("bounty add Target");
        assertTrue(saw(TestSupport.messages(a), "do not have permission"));
        assertFalse(net.scoresmp.bounties.gui.Menu.openMenu(a) instanceof DepositMenu);

        a.performCommand("bountys");
        assertInstanceOf(BountyListMenu.class, net.scoresmp.bounties.gui.Menu.openMenu(a));
        assertEquals(InventoryType.CHEST, a.getOpenInventory().getTopInventory().getType());
        assertNotNull(target);
    }

    @Test
    void offlineTargetsAreRefusedByDefault() throws Exception {
        PlayerMock a = player("Alpha");
        PlayerMock target = player("Target");
        target.disconnect();
        a.performCommand("bounty add Target");
        assertTrue(saw(TestSupport.messages(a), "must be online"));
    }

    // ------------------------------------------------------------------
    // Admin, info, top
    // ------------------------------------------------------------------

    @Test
    void adminRemoveAndRestore() throws Exception {
        PlayerMock a = player("Alpha");
        PlayerMock b = player("Bravo");
        FighterMock target = fighter("Target");
        placeBounty(a, target, diamonds(10));
        placeBounty(b, target, new ItemStack(Material.EMERALD, 4));
        a.disconnect();
        settle();
        PlayerMock admin = player("Admin");
        admin.setOp(true);

        admin.performCommand("bounty admin remove Target");
        settle();
        assertNull(plugin.bounties().bounty(target.getUniqueId()));
        assertTrue(saw(TestSupport.messages(admin), "Removed the bounty on Target"));
        assertEquals(4, count(b, Material.EMERALD), "online contributor gets items back");

        admin.performCommand("bounty admin restore Target");
        settle();
        Bounty restored = plugin.bounties().bounty(target.getUniqueId());
        assertNotNull(restored);
        assertEquals(10, restored.totalAmount(), "only the uncollected contribution is restored");
        assertTrue(saw(TestSupport.messages(admin), "already been collected"));
        assertEquals(4, count(b, Material.EMERALD), "collected items are not duplicated");

        a.reconnect();
        settle();
        server.getScheduler().performTicks(5);
        settle();
        assertEquals(0, count(a, Material.DIAMOND), "restored items are no longer returned");

        admin.performCommand("bounty admin restore Target");
        settle();
        assertTrue(saw(TestSupport.messages(admin), "nothing to restore"));
        assertEquals(10, plugin.bounties().bounty(target.getUniqueId()).totalAmount());

        admin.performCommand("bounty admin clear");
        settle();
        assertTrue(plugin.bounties().all().isEmpty());
        assertEquals(10, count(a, Material.DIAMOND));
    }

    @Test
    void infoAndLeaderboard() throws Exception {
        PlayerMock a = player("Alpha");
        FighterMock killer = fighter("Killer");
        FighterMock t1 = fighter("Tango");
        FighterMock t2 = fighter("Uniform");
        placeBounty(a, t1, diamonds(3), new ItemStack(Material.TOTEM_OF_UNDYING, 2));
        placeBounty(a, t2, diamonds(30));
        TestSupport.messages(a);

        a.performCommand("bounty info Tango");
        List<String> info = TestSupport.messages(a);
        assertTrue(saw(info, "BOUNTY » Tango"));
        assertTrue(saw(info, "3x Diamond"));
        assertTrue(saw(info, "2x Totem of Undying"));
        assertTrue(saw(info, "Contributors: Alpha"));

        a.performCommand("bounty top");
        List<String> top = TestSupport.messages(a);
        int uniform = indexOf(top, "Uniform");
        int tango = indexOf(top, "Tango");
        assertTrue(uniform >= 0 && tango > uniform, "sorted by item count: " + top);

        kill(t1, killer, true);
        a.performCommand("bounty top hunters");
        assertTrue(saw(TestSupport.messages(a), "#1 Killer - 1 bounties claimed"));
        a.performCommand("bounty top claimed");
        assertTrue(saw(TestSupport.messages(a), "#1 Tango - 1 times claimed"));
    }

    private static int indexOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }
}
