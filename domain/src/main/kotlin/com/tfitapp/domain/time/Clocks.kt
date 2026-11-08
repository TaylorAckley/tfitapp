package com.tfitapp.domain.time

import java.time.Instant

/**
 * Monotonic milliseconds that never jump backwards or forwards with wall-clock
 * changes. The timer derives every phase boundary from this, so a missed UI tick
 * or a clock adjustment cannot drift it. Android binds this to
 * `SystemClock.elapsedRealtime()`.
 */
public fun interface MonotonicClock {
    public fun nowMs(): Long
}

/** Wall-clock time for "when did this happen" records. */
public fun interface WallClock {
    public fun now(): Instant
}
