package net.scoresmp.calendar.service;

import net.scoresmp.calendar.ScoreCalendarPlugin;
import net.scoresmp.calendar.model.Calendar;
import net.scoresmp.calendar.model.CalendarType;
import net.scoresmp.calendar.model.DayDefinition;
import net.scoresmp.calendar.model.DayStatus;
import net.scoresmp.calendar.model.EffectSettings;
import net.scoresmp.calendar.model.ItemSpec;
import net.scoresmp.calendar.model.Reward;
import net.scoresmp.calendar.schedule.PlayerProgress;
import net.scoresmp.calendar.schedule.Schedule;
import net.scoresmp.calendar.storage.CalendarRepository;
import net.scoresmp.calendar.storage.CalendarRepository.ClaimRequest;
import net.scoresmp.calendar.storage.CalendarRepository.ItemData;
import net.scoresmp.calendar.util.ItemCodec;
import net.scoresmp.calendar.util.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * The claim flow:
 * <ol>
 *     <li>verify the calendar is open, the player may use it, the day is unlocked and not yet claimed;</li>
 *     <li>record the claim (status PROCESSING) and the full reward delivery in one database transaction;
 *     the UNIQUE constraint makes a second claim of the same day impossible, also across restarts;</li>
 *     <li>deliver the items (overflow is stored) and run commands exactly once;</li>
 *     <li>mark the claim COMPLETED, update the GUI and play effects.</li>
 * </ol>
 * A per-player-and-calendar lock blocks double clicks and simultaneous claims.
 */
public final class ClaimService {

    private final ScoreCalendarPlugin plugin;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public ClaimService(ScoreCalendarPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Attempts to claim a day. {@code onFinished} runs on the main thread when the attempt is over
     * (successful or not) so the GUI can refresh.
     */
    public void claim(Player player, Calendar calendar, int day, Runnable onFinished) {
        String lock = player.getUniqueId() + ":" + calendar.id();
        if (!inFlight.add(lock)) {
            return; // a claim for this calendar is already being processed (double click)
        }
        boolean async = false;
        try {
            async = tryClaim(player, calendar, day, () -> {
                inFlight.remove(lock);
                onFinished.run();
            });
        } finally {
            if (!async) {
                inFlight.remove(lock);
            }
        }
    }

    public boolean isClaiming(Player player, Calendar calendar) {
        return inFlight.contains(player.getUniqueId() + ":" + calendar.id());
    }

    /** @return true when an asynchronous claim was started (the lock is released by the callback). */
    private boolean tryClaim(Player player, Calendar calendar, int day, Runnable release) {
        CalendarManager manager = plugin.calendars();
        Instant now = Instant.now();
        Placeholders ph = manager.placeholders(player, calendar, now);
        ph.set("day", day);
        ph.set("reward_name", calendar.reward(day).displayName());

        if (!manager.isLoaded(player.getUniqueId())) {
            plugin.messages().send(player, "data-loading", ph, calendar);
            manager.loadPlayer(player.getUniqueId());
            return false;
        }
        if (!manager.isOpen(calendar, now)) {
            plugin.messages().send(player, "calendar-closed", ph, calendar);
            plugin.effects().play(player, calendar.effects("closed"), ph);
            return false;
        }
        if (!manager.canAccess(player, calendar)) {
            plugin.messages().send(player, "calendar-no-access", ph, calendar);
            return false;
        }
        if (day < 1 || day > calendar.days()) {
            return false;
        }
        DayDefinition def = calendar.day(day);
        if (def != null && def.permission() != null && !player.hasPermission(def.permission())) {
            plugin.messages().send(player, "day-no-permission", ph, calendar);
            plugin.effects().play(player, calendar.effects("locked"), ph);
            return false;
        }
        DayStatus status = manager.status(player.getUniqueId(), calendar, day, now);
        Placeholders dayPh = manager.dayPlaceholders(player, calendar, day, status, now, ph);
        switch (status) {
            case LOCKED -> {
                plugin.messages().send(player, calendar.type() == CalendarType.DAILY ? "daily-locked" : "reward-locked", dayPh, calendar);
                plugin.effects().play(player, calendar.effects("locked"), dayPh);
                return false;
            }
            case CLAIMED -> {
                plugin.messages().send(player, "already-claimed", dayPh, calendar);
                plugin.effects().play(player, calendar.effects("already-claimed"), dayPh);
                return false;
            }
            case MISSED -> {
                plugin.messages().send(player, "reward-missed", dayPh, calendar);
                plugin.effects().play(player, calendar.effects("locked"), dayPh);
                return false;
            }
            case AVAILABLE -> {
                // continue below
            }
        }

        Reward reward = calendar.reward(day);
        List<ItemData> items = new ArrayList<>();
        try {
            for (ItemSpec spec : reward.items()) {
                ItemStack built = spec.buildReward(dayPh, player);
                items.add(new ItemData(ItemCodec.toBytes(withAmount(built, 1)), spec.rewardAmount(dayPh)));
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not build the reward of " + calendar.id() + " day " + day
                    + "; nothing was claimed. Check the reward configuration.", ex);
            plugin.messages().send(player, "claim-error", dayPh, calendar);
            return false;
        }
        List<String> commands = resolveCommands(reward.commands(), player, calendar, day, dayPh);

        PlayerProgress progress = manager.progress(player.getUniqueId(), calendar);
        Schedule schedule = calendar.schedule();
        boolean daily = calendar.type() == CalendarType.DAILY;
        String cycle = schedule.currentCycle(progress, now);
        int dailyCycle = daily ? schedule.dailyState(progress, now).cycle() : 0;
        LocalDate today = schedule.gameDate(now);
        ClaimRequest request = new ClaimRequest(calendar.id(), player.getUniqueId(), player.getName(), cycle, day, items,
                commands, daily, dailyCycle, today);

        UUID uuid = player.getUniqueId();
        plugin.repository().claim(request).whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Player online = Bukkit.getPlayer(uuid);
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Claim of " + calendar.id() + " day " + day + " by " + player.getName() + " failed", error);
                    if (online != null) {
                        plugin.messages().send(online, "claim-error", dayPh, calendar);
                    }
                    return;
                }
                switch (result.type()) {
                    case ALREADY_CLAIMED, ALREADY_CLAIMED_TODAY -> {
                        manager.loadPlayer(uuid);
                        if (online != null) {
                            plugin.messages().send(online, "already-claimed", dayPh, calendar);
                            plugin.effects().play(online, calendar.effects("already-claimed"), dayPh);
                        }
                    }
                    case CREATED -> {
                        PlayerProgress updated = manager.progress(uuid, calendar).withClaim(cycle, day);
                        if (daily) {
                            updated = updated.withDaily(dailyCycle, today);
                        }
                        manager.updateProgress(uuid, calendar, updated);
                        plugin.delivery().deliver(uuid, result.items(), result.commands(), dayPh);
                        if (online != null) {
                            Placeholders after = manager.placeholders(online, calendar, Instant.now()).addAll(dayPh);
                            after.set("days_claimed", manager.claimedCount(uuid, calendar, Instant.now()));
                            plugin.messages().send(online, "reward-claimed", after, calendar);
                            EffectSettings effects = calendar.effects("claim").merge(reward.effects());
                            plugin.effects().play(online, effects, after);
                        }
                        if (plugin.mainConfig().debug()) {
                            plugin.getLogger().info(player.getName() + " claimed " + calendar.id() + " day " + day + " (cycle " + cycle + ")");
                        }
                    }
                }
            } finally {
                release.run();
            }
        }));
        return true;
    }

    private static ItemStack withAmount(ItemStack item, int amount) {
        ItemStack copy = item.clone();
        copy.setAmount(amount);
        return copy;
    }

    /** Replaces placeholders in reward commands at claim time (including PlaceholderAPI when available). */
    public List<String> resolveCommands(List<String> commands, OfflinePlayer player, Calendar calendar, int day, Placeholders base) {
        List<String> out = new ArrayList<>();
        Placeholders p = new Placeholders(base);
        p.set("player", player.getName() == null ? player.getUniqueId().toString() : player.getName());
        p.set("uuid", player.getUniqueId().toString());
        p.set("day", day);
        p.set("calendar", calendar.id());
        Function<String, String> papi = plugin.placeholderHook() == null ? null : plugin.placeholderHook().resolver(player);
        for (String c : commands) {
            String resolved = p.apply(c);
            if (papi != null) {
                resolved = papi.apply(resolved);
            }
            // Commands must stay single-line; strip any line breaks a placeholder might have produced.
            out.add(resolved.replace('\n', ' ').replace('\r', ' '));
        }
        return out;
    }

    /**
     * Gives a day's reward to a player without marking the day as claimed (administrator command).
     * Works for offline players too: the reward is delivered when they join.
     */
    public CompletableFuture<CalendarRepository.ClaimResult> give(OfflinePlayer target, Calendar calendar, int day) {
        Reward reward = calendar.reward(day);
        Player online = target.getPlayer();
        Placeholders ph = plugin.calendars().placeholders(online, calendar, Instant.now());
        ph.set("day", day);
        ph.set("reward_name", reward.displayName());
        if (online == null) {
            ph.set("player", target.getName() == null ? target.getUniqueId().toString() : target.getName());
        }
        List<ItemData> items = new ArrayList<>();
        for (ItemSpec spec : reward.items()) {
            ItemStack built = spec.buildReward(ph, online);
            items.add(new ItemData(ItemCodec.toBytes(withAmount(built, 1)), spec.rewardAmount(ph)));
        }
        List<String> commands = resolveCommands(reward.commands(), target, calendar, day, ph);
        return plugin.repository().queueDelivery(calendar.id(), day, target.getUniqueId(), target.getName(), items, commands, "ADMIN_GIVE")
                .thenApply(result -> {
                    plugin.delivery().deliver(target.getUniqueId(), result.items(), result.commands(), ph);
                    return result;
                });
    }
}
