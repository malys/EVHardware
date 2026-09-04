package com.evsuite.hardware

import java.util.concurrent.atomic.AtomicReference

/**
 * Lets a repeated state through only when it actually changed.
 *
 * Two vehicle callbacks report a state rather than an event: the ignition condition arrives
 * about ten times a second and the gear is read once a second, and both were logged every
 * time. On a 400-line retained diagnostic log that is under a minute of history — the bundle
 * a driver sends back is then almost entirely one repeated line, and whatever went wrong
 * before that minute has already been evicted. It also spends the head unit's CPU and log
 * bandwidth continuously to say nothing new.
 *
 * A state worth logging is a state that changed. The transitions are what a diagnostic reads
 * anyway; the repeats between them carry no information the timestamp of the next transition
 * does not already give.
 *
 * Only logging is gated. Callers must still dispatch every callback they receive — dropping a
 * dispatch would change behaviour, dropping a log line changes only noise.
 */
class StateChangeLog<T> {

    private val last = AtomicReference<Any?>(NOTHING)

    /**
     * @return true when [value] differs from the previous accepted one, or nothing was seen yet.
     */
    fun accept(value: T): Boolean = last.getAndSet(value) != value

    /** Forgets what was seen, so the next value logs as if it were the first. */
    fun reset() = last.set(NOTHING)

    private companion object {
        /** Distinct from null, so a first value of null still counts as a change. */
        val NOTHING = Any()
    }
}
