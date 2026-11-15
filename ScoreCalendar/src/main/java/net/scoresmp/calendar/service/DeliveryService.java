package net.scoresmp.calendar.service;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.storage.CalendarRepository;
import net.scoresmp.calendar.storage.CalendarRepository.CommandRow;
import net.scoresmp.calendar.storage.CalendarRepository.CommandStatus;
import net.scoresmp.calendar.storage.CalendarRepository.ItemRow;
import net.scoresmp.calendar.util.ItemCodec;
import net.scoresmp.calendar.util.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Delivers reward items and commands safely.
 * <ul>
 *     <li>Items that do not fit are kept in the database (STORED) and can be collected later; nothing is dropped or deleted.</li>
 *     <li>Commands are marked EXECUTING before they run and are never run twice, even after a crash.</li>
 *     <li>All deliveries for one player run one after another, so the same stored item can never be handed out twice.</li>
 * </ul>
 */
public final class DeliveryService {

    /** Outcome of an item delivery. */
    public record ItemResult(int delivered, int stored) {
    }

    private final ScoreCalendarPlugin plugin;
    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> storedCounts = new ConcurrentHashMap<>();

    public DeliveryService(ScoreCalendarPlugin plugin) {
        this.plugin = plugin;
    }

    /** Number of stored (not yet collected) item stacks, as last known. */
    public int storedCount(UUID uuid) {
        return storedCounts.getOrDefault(uuid, 0);
    }

    public void refreshStoredCount(UUID uuid) {
        plugin.repository().countStoredItems(uuid).thenAccept(count -> storedCounts.put(uuid, count));
    }

    public void forget(UUID uuid) {
        storedCounts.remove(uuid);
    }

    /**
     * Runs {@code task} on the main thread after all earlier delivery tasks of this player have finished.
     * The task returns a future that completes when its database writes are done.
     */
    public CompletableFuture<Void> exclusive(UUID uuid, Supplier<CompletableFuture<?>> task) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        // Atomically become the new tail of this player's chain, then start after the previous tail.
        CompletableFuture<Void> previous = chains.put(uuid, done);
        CompletableFuture<Void> start = previous == null ? CompletableFuture.completedFuture(null) : previous;
        start.handle((v, t) -> null).thenRun(() -> runOnMain(() -> {
            try {
                CompletableFuture<?> f = task.get();
                (f == null ? CompletableFuture.completedFuture(null) : f).whenComplete((r, t) -> {
                    if (t != null) {
                        plugin.getLogger().log(Level.SEVERE, "Reward delivery step failed", t);
                    }
                    done.complete(null);
                });
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "Reward delivery failed", t);
                done.complete(null);
            }
        }, done));
        done.whenComplete((v, t) -> chains.remove(uuid, done));
        return done;
    }

    private void runOnMain(Runnable runnable, CompletableFuture<Void> onRejected) {
        if (!plugin.isEnabled()) {
            onRejected.complete(null);
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, runnable);
            } catch (IllegalStateException | IllegalArgumentException ex) {
                // Plugin is shutting down: the rows stay pending and are delivered after the restart.
                onRejected.complete(null);
            }
        }
    }

    // ------------------------------------------------------------------ claim delivery

    /**
     * Delivers freshly created reward rows. If the player is offline the rows simply stay pending and are
     * delivered on their next join.
     */
    public CompletableFuture<Void> deliver(UUID uuid, List<ItemRow> items, List<CommandRow> commands, Placeholders placeholders) {
        return exclusive(uuid, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> itemsDone = items.isEmpty() ? CompletableFuture.completedFuture(null)
                    : giveItems(player, items, placeholders).thenAccept(r -> {
            });
            CompletableFuture<Void> commandsDone = runCommands(uuid, commands);
            return CompletableFuture.allOf(itemsDone, commandsDone);
        });
    }

    /** Gives items now (main thread) and records what was delivered and what had to be stored. */
    private CompletableFuture<ItemResult> giveItems(Player player, List<ItemRow> rows, Placeholders placeholders) {
        Map<Long, Integer> remaining = new LinkedHashMap<>();
        int delivered = 0;
        int stored = 0;
        for (ItemRow row : rows) {
            ItemStack template;
            try {
                template = ItemCodec.fromBytes(row.item());
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("Could not decode stored reward item #" + row.id() + " for " + player.getName()
                        + "; it stays stored for an administrator to inspect: " + ex);
                stored++;
                continue;
            }
            int left = addToInventory(player, template, row.amount());
            remaining.put(row.id(), left);
            if (left > 0) {
                stored++;
            } else {
                delivered++;
            }
        }
        if (plugin.mainConfig().savePlayerData() && delivered + stored > 0) {
            // Persist the inventory immediately so a crash right after delivery cannot lose the items.
            player.saveData();
        }
        final int fDelivered = delivered;
        final int fStored = stored;
        if (stored > 0) {
            plugin.messages().send(player, "rewards-stored", Placeholders.of("count", String.valueOf(stored)).addAll(placeholders));
        }
        return plugin.repository().updateItems(remaining).thenApply(v -> {
            refreshStoredCount(player.getUniqueId());
            return new ItemResult(fDelivered, fStored);
        });
    }

    /** Adds {@code amount} of {@code template} (split into valid stacks) and returns the amount that did not fit. */
    private static int addToInventory(Player player, ItemStack template, int amount) {
        int max = Math.max(1, template.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        int left = amount;
        while (left > 0) {
            ItemStack stack = template.clone();
            int n = Math.min(max, left);
            stack.setAmount(n);
            stacks.add(stack);
            left -= n;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stacks.toArray(new ItemStack[0]));
        int notGiven = 0;
        for (ItemStack rest : overflow.values()) {
            notGiven += rest.getAmount();
        }
        return notGiven;
    }

    /**
     * Runs reward commands exactly once: they are marked EXECUTING in the database first, then run on the main
     * thread, then marked DONE. If the player has gone offline in between they are put back to PENDING.
     */
    private CompletableFuture<Void> runCommands(UUID uuid, List<CommandRow> commands) {
        List<CommandRow> pending = new ArrayList<>();
        for (CommandRow c : commands) {
            if (c.status() == CommandStatus.PENDING) {
                pending.add(c);
            }
        }
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<Long> ids = new ArrayList<>();
        for (CommandRow c : pending) {
            ids.add(c.id());
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        plugin.repository().markCommandsExecuting(ids).whenComplete((v, error) -> {
            if (error != null) {
                done.complete(null);
                return;
            }
            runOnMain(() -> {
                Map<Long, CommandStatus> results = new HashMap<>();
                Player player = Bukkit.getPlayer(uuid);
                for (CommandRow c : pending) {
                    if (player == null || !player.isOnline()) {
                        results.put(c.id(), CommandStatus.PENDING);
                        continue;
                    }
                    try {
                        boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.command());
                        results.put(c.id(), ok ? CommandStatus.DONE : CommandStatus.FAILED);
                        if (!ok) {
                            plugin.getLogger().warning("Reward command for " + player.getName() + " (" + c.calendar() + " day " + c.day()
                                    + ") returned false (unknown command?): /" + c.command());
                        }
                    } catch (RuntimeException ex) {
                        results.put(c.id(), CommandStatus.FAILED);
                        plugin.getLogger().log(Level.SEVERE, "Reward command failed: /" + c.command(), ex);
                    }
                }
                plugin.repository().finishCommands(results).whenComplete((r, t) -> done.complete(null));
            }, done);
        });
        return done;
    }

    // ------------------------------------------------------------------ pending deliveries

    /**
     * Delivers everything still owed to a player (called on join and by "collect all").
     *
     * @param includeStored also try items that previously did not fit
     * @param announce      tell the player about the result
     */
    public CompletableFuture<Void> deliverPending(Player player, boolean includeStored, boolean announce) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> result = new CompletableFuture<>();
        exclusive(uuid, () -> {
            CompletableFuture<List<ItemRow>> itemsF = plugin.repository().pendingItems(uuid);
            CompletableFuture<List<CommandRow>> commandsF = plugin.repository().pendingCommands(uuid);
            CompletableFuture<Void> step = new CompletableFuture<>();
            itemsF.thenCombine(commandsF, (items, commands) -> {
                runOnMain(() -> {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online == null || !online.isOnline()) {
                        step.complete(null);
                        return;
                    }
                    List<ItemRow> toGive = new ArrayList<>();
                    for (ItemRow row : items) {
                        if (includeStored || row.status() == CalendarRepository.ItemStatus.PENDING) {
                            toGive.add(row);
                        }
                    }
                    CompletableFuture<ItemResult> given = toGive.isEmpty()
                            ? CompletableFuture.completedFuture(new ItemResult(0, 0))
                            : giveItemsQuiet(online, toGive);
                    CompletableFuture<Void> cmds = runCommands(uuid, commands);
                    given.thenCombine(cmds, (r, v) -> r).whenComplete((r, t) -> {
                        if (announce && r != null && (r.delivered() > 0 || r.stored() > 0)) {
                            runOnMain(() -> {
                                if (r.delivered() > 0) {
                                    plugin.messages().send(online, "rewards-delivered", Placeholders.of("count", String.valueOf(r.delivered())));
                                }
                                if (r.stored() > 0) {
                                    plugin.messages().send(online, "rewards-still-stored", Placeholders.of("count", String.valueOf(r.stored())));
                                }
                            }, new CompletableFuture<>());
                        }
                        step.complete(null);
                    });
                }, step);
                return null;
            }).exceptionally(t -> {
                step.complete(null);
                return null;
            });
            return step;
        }).whenComplete((v, t) -> result.complete(null));
        return result;
    }

    /** Delivers one specific stored item row (clicked in the rewards menu). */
    public CompletableFuture<ItemResult> collect(Player player, long rowId) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<ItemResult> result = new CompletableFuture<>();
        exclusive(uuid, () -> plugin.repository().pendingItems(uuid).thenCompose(rows -> {
            CompletableFuture<ItemResult> inner = new CompletableFuture<>();
            runOnMain(() -> {
                ItemRow target = null;
                for (ItemRow r : rows) {
                    if (r.id() == rowId) {
                        target = r;
                    }
                }
                if (target == null || !player.isOnline()) {
                    inner.complete(new ItemResult(0, 0));
                    return;
                }
                giveItemsQuiet(player, List.of(target)).whenComplete((r, t) -> inner.complete(r == null ? new ItemResult(0, 0) : r));
            }, new CompletableFuture<>());
            return inner.thenApply(r -> {
                result.complete(r);
                return r;
            });
        })).whenComplete((v, t) -> {
            if (!result.isDone()) {
                result.complete(new ItemResult(0, 0));
            }
        });
        return result;
    }

    private CompletableFuture<ItemResult> giveItemsQuiet(Player player, List<ItemRow> rows) {
        Map<Long, Integer> remaining = new LinkedHashMap<>();
        int delivered = 0;
        int stored = 0;
        for (ItemRow row : rows) {
            ItemStack template;
            try {
                template = ItemCodec.fromBytes(row.item());
            } catch (RuntimeException ex) {
                plugin.getLogger().severe("Could not decode stored reward item #" + row.id() + ": " + ex);
                stored++;
                continue;
            }
            int left = addToInventory(player, template, row.amount());
            if (left != row.amount() || left == 0) {
                remaining.put(row.id(), left);
            } else if (row.status() == CalendarRepository.ItemStatus.PENDING) {
                remaining.put(row.id(), left);
            }
            if (left > 0) {
                stored++;
            } else {
                delivered++;
            }
        }
        if (plugin.mainConfig().savePlayerData() && !remaining.isEmpty()) {
            player.saveData();
        }
        final int d = delivered;
        final int s = stored;
        return plugin.repository().updateItems(remaining).thenApply(v -> {
            refreshStoredCount(player.getUniqueId());
            return new ItemResult(d, s);
        });
    }

    /** Decodes stored rows into display items for the rewards menu (main thread). */
    public static ItemStack display(ItemRow row) {
        ItemStack item = ItemCodec.fromBytes(row.item());
        item.setAmount(Math.max(1, Math.min(row.amount(), 99)));
        return item;
    }
}
