package net.scoresmp.bounties.storage;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Allocates row IDs in memory so that a whole operation can be prepared on the
 * main thread and written in a single database transaction.
 */
public final class Ids {

    private final AtomicLong contribution = new AtomicLong();
    private final AtomicLong item = new AtomicLong();
    private final AtomicLong claim = new AtomicLong();

    void seed(long maxContribution, long maxItem, long maxClaim) {
        contribution.set(maxContribution);
        item.set(maxItem);
        claim.set(maxClaim);
    }

    public long nextContribution() {
        return contribution.incrementAndGet();
    }

    public long nextItem() {
        return item.incrementAndGet();
    }

    public long nextClaim() {
        return claim.incrementAndGet();
    }
}
