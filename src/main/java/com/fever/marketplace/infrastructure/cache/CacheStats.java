package com.fever.marketplace.infrastructure.cache;

/**
 * Cache performance metrics
 */
public record CacheStats(
        long hits,
        long misses,
        long errors,
        long invalidations,
        int activeEntries
) {
    public double hitRatio() {
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }

    public boolean isHealthy() {
        return hitRatio() > 0.75 && errors < (hits + misses) * 0.05;
    }

    public String summary() {
        return String.format("Hit ratio: %.1f%%, Active entries: %d",
                hitRatio() * 100, activeEntries);
    }
}
