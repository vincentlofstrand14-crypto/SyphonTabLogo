package net.scoresmp.bounties.model;

/**
 * Why an item is waiting in a player's claim storage.
 */
public enum ClaimSource {
    /** The player killed a bounty target. */
    BOUNTY_REWARD,
    /** The player's contribution was returned because the bounty was replaced. */
    CONTRIBUTION_RETURN,
    /** The player's contribution was returned because staff removed the bounty. */
    ADMIN_RETURN,
    /** Items left in a deposit menu that was closed, cancelled or interrupted. */
    DEPOSIT_RETURN
}
