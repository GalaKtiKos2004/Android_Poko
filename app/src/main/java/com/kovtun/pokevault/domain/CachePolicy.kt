package com.kovtun.pokevault.domain

enum class Freshness { MISSING, STALE, FRESH }

/**
 * Data freshness policy: an entry younger than the TTL is FRESH and is shown without
 * hitting the network; an older one is STALE (shown immediately, refreshed in background);
 * no entry at all is MISSING (network is the only option).
 */
object CachePolicy {

    fun freshness(updatedAt: Long?, now: Long, ttlMillis: Long): Freshness = when {
        updatedAt == null -> Freshness.MISSING
        now - updatedAt >= ttlMillis -> Freshness.STALE
        else -> Freshness.FRESH
    }

    fun shouldFetch(updatedAt: Long?, now: Long, ttlMillis: Long, force: Boolean): Boolean =
        force || freshness(updatedAt, now, ttlMillis) != Freshness.FRESH

    /** Entries updated before this moment are stale. */
    fun staleBefore(now: Long, ttlMillis: Long): Long = now - ttlMillis

    fun hoursToMillis(hours: Int): Long = hours * 60L * 60L * 1000L
}
