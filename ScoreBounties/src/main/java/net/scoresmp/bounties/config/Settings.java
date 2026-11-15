package net.scoresmp.bounties.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Typed view of config.yml. Re-created on every reload.
 */
public final class Settings {

    public final boolean debug;

    public final String databaseFile;
    public final boolean backupsEnabled;
    public final boolean backupOnStartup;
    public final int backupIntervalHours;
    public final int backupKeep;

    public final String dateFormat;
    public final String dateTimeFormat;
    public final int maxItemLines;
    public final int searchTimeoutSeconds;
    public final boolean showContributorInDetails;
    public final int replaceConfirmSeconds;

    public final boolean allowOfflineTargets;
    public final boolean blockCreativeDeposits;
    public final int depositCooldownSeconds;
    public final int maxStacksPerDeposit;
    public final int maxStacksPerBounty;
    public final Set<Material> blacklistedMaterials;
    public final Set<String> disabledDepositWorlds;

    public final boolean allowSelfBounty;

    public final boolean requireClaimPermission;
    public final boolean requireDirectKill;
    public final boolean blockSameIp;
    public final boolean sameIpIgnoreLocal;
    public final int minimumKillerPlaytimeMinutes;
    public final Set<String> disabledClaimWorlds;

    public final int minimumBountyAgeSeconds;
    public final int repeatKillCooldownMinutes;

    public final boolean instantDelivery;
    public final boolean deliverOnJoin;
    public final int deliverOnJoinDelaySeconds;
    public final boolean savePlayerDataOnDelivery;

    public final boolean announcePlaced;
    public final boolean announceClaimed;
    public final boolean soundEnabled;
    public final String soundPlaced;
    public final String soundClaimed;
    public final float soundVolume;
    public final float soundPitch;

    public final int leaderboardSize;

    public Settings(YamlConfiguration c, Logger logger) {
        debug = c.getBoolean("debug", false);

        databaseFile = c.getString("database.file", "database/bounties.db");
        backupsEnabled = c.getBoolean("database.backups.enabled", true);
        backupOnStartup = c.getBoolean("database.backups.on-startup", true);
        backupIntervalHours = Math.max(0, c.getInt("database.backups.interval-hours", 6));
        backupKeep = Math.max(1, c.getInt("database.backups.keep", 14));

        dateFormat = c.getString("gui.date-format", "dd/MM/yyyy");
        dateTimeFormat = c.getString("gui.date-time-format", "dd/MM/yyyy HH:mm");
        maxItemLines = Math.max(1, c.getInt("gui.max-item-lines", 8));
        searchTimeoutSeconds = Math.max(5, c.getInt("gui.search-timeout-seconds", 30));
        showContributorInDetails = c.getBoolean("gui.show-contributor-in-details", true);
        replaceConfirmSeconds = Math.max(2, c.getInt("gui.replace-confirm-seconds", 10));

        allowOfflineTargets = c.getBoolean("restrictions.allow-offline-targets", false);
        blockCreativeDeposits = c.getBoolean("restrictions.block-creative-deposits", true);
        depositCooldownSeconds = Math.max(0, c.getInt("restrictions.deposit-cooldown-seconds", 5));
        maxStacksPerDeposit = Math.max(0, c.getInt("restrictions.max-stacks-per-deposit", 0));
        maxStacksPerBounty = Math.max(0, c.getInt("restrictions.max-stacks-per-bounty", 0));
        Set<Material> blacklist = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("restrictions.blacklisted-materials")) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                logger.warning("Unknown material in restrictions.blacklisted-materials: " + name);
            } else {
                blacklist.add(material);
            }
        }
        blacklistedMaterials = blacklist;
        disabledDepositWorlds = lower(c.getStringList("restrictions.disabled-deposit-worlds"));

        allowSelfBounty = c.getBoolean("self-bounty.allow", false);

        requireClaimPermission = c.getBoolean("kill-eligibility.require-claim-permission", true);
        requireDirectKill = c.getBoolean("kill-eligibility.require-direct-kill", true);
        blockSameIp = c.getBoolean("kill-eligibility.block-same-ip", true);
        sameIpIgnoreLocal = c.getBoolean("kill-eligibility.same-ip-ignore-local", true);
        minimumKillerPlaytimeMinutes = Math.max(0, c.getInt("kill-eligibility.minimum-killer-playtime-minutes", 0));
        disabledClaimWorlds = lower(c.getStringList("kill-eligibility.disabled-worlds"));

        minimumBountyAgeSeconds = Math.max(0, c.getInt("anti-farming.minimum-bounty-age-seconds", 0));
        repeatKillCooldownMinutes = Math.max(0, c.getInt("anti-farming.repeat-kill-cooldown-minutes", 30));

        instantDelivery = c.getBoolean("claims.instant-delivery", true);
        deliverOnJoin = c.getBoolean("claims.deliver-on-join", true);
        deliverOnJoinDelaySeconds = Math.max(0, c.getInt("claims.deliver-on-join-delay-seconds", 3));
        savePlayerDataOnDelivery = c.getBoolean("claims.save-player-data-on-delivery", true);

        announcePlaced = c.getBoolean("announcements.bounty-placed", true);
        announceClaimed = c.getBoolean("announcements.bounty-claimed", true);
        soundEnabled = c.getBoolean("announcements.sound.enabled", true);
        soundPlaced = c.getString("announcements.sound.placed", "minecraft:block.note_block.pling");
        soundClaimed = c.getString("announcements.sound.claimed", "minecraft:ui.toast.challenge_complete");
        soundVolume = (float) c.getDouble("announcements.sound.volume", 0.6);
        soundPitch = (float) c.getDouble("announcements.sound.pitch", 1.2);

        leaderboardSize = Math.max(1, Math.min(50, c.getInt("leaderboard.size", 10)));
    }

    private static Set<String> lower(Iterable<String> values) {
        Set<String> result = new HashSet<>();
        for (String value : values) {
            result.add(value.toLowerCase(Locale.ROOT));
        }
        return result;
    }
}
