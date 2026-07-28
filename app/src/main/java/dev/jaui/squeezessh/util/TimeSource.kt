package dev.jaui.squeezessh.util

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zeitquelle als Abhaengigkeit statt direkter Aufrufe von [System.currentTimeMillis].
 * Tests koennen damit Zeitstempel festnageln, ohne auf Wanduhr-Zufall angewiesen zu sein.
 */
fun interface TimeSource {
    fun nowMillis(): Long
}

@Singleton
class SystemTimeSource @Inject constructor() : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
