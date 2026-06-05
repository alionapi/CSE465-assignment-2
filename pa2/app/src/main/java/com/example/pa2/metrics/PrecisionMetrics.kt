package com.example.pa2.metrics

import com.example.pa2.detector.Precision
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Aggregates per-precision runtime statistics: rolling FPS, average latency,
 * p95 latency, mean confidence, frame counter, target detection success rate.
 *
 * One instance is shared across the activity (held by [MetricsRegistry]) so
 * the Dashboard can read what Live just produced.
 */
class PrecisionMetrics(val precision: Precision) {

    private val latencyWindowMs = ArrayDeque<Long>()
    private val confidenceWindow = ArrayDeque<Float>()
    private var frameTimestampsMs = ArrayDeque<Long>()

    @Volatile var modelSizeBytes: Long = 0L
    @Volatile var frames: Long = 0L
    @Volatile var totalLatencyMs: Long = 0L
    @Volatile var targetSetFrames: Long = 0L     // frames where a target was active
    @Volatile var targetHitFrames: Long = 0L     // frames where target was detected

    private val maxWindow = 200

    @Synchronized
    fun recordFrame(latencyMs: Long, meanConfidence: Float?, targetActive: Boolean, targetHit: Boolean) {
        frames++
        totalLatencyMs += latencyMs

        latencyWindowMs.addLast(latencyMs)
        while (latencyWindowMs.size > maxWindow) latencyWindowMs.removeFirst()

        if (meanConfidence != null) {
            confidenceWindow.addLast(meanConfidence)
            while (confidenceWindow.size > maxWindow) confidenceWindow.removeFirst()
        }

        val now = System.currentTimeMillis()
        frameTimestampsMs.addLast(now)
        // Drop frames older than 2s for FPS calc
        while (frameTimestampsMs.isNotEmpty() && now - frameTimestampsMs.peekFirst() > 2000) {
            frameTimestampsMs.removeFirst()
        }

        if (targetActive) {
            targetSetFrames++
            if (targetHit) targetHitFrames++
        }
    }

    @Synchronized
    fun rollingFps(): Float {
        if (frameTimestampsMs.size < 2) return 0f
        val span = frameTimestampsMs.peekLast() - frameTimestampsMs.peekFirst()
        return if (span <= 0) 0f else (frameTimestampsMs.size - 1) * 1000f / span
    }

    @Synchronized
    fun avgLatencyMs(): Float =
        if (frames == 0L) 0f else totalLatencyMs.toFloat() / frames

    @Synchronized
    fun p95LatencyMs(): Float {
        if (latencyWindowMs.isEmpty()) return 0f
        val sorted = latencyWindowMs.sorted()
        val idx = (sorted.size * 0.95f).toInt().coerceAtMost(sorted.size - 1)
        return sorted[idx].toFloat()
    }

    @Synchronized
    fun meanConfidence(): Float =
        if (confidenceWindow.isEmpty()) 0f
        else confidenceWindow.sum() / confidenceWindow.size

    @Synchronized
    fun targetSuccessRate(): Float =
        if (targetSetFrames == 0L) 0f else targetHitFrames.toFloat() / targetSetFrames

    @Synchronized
    fun reset() {
        latencyWindowMs.clear()
        confidenceWindow.clear()
        frameTimestampsMs.clear()
        frames = 0L; totalLatencyMs = 0L
        targetSetFrames = 0L; targetHitFrames = 0L
    }
}

/** Process-wide registry of per-precision metrics. */
object MetricsRegistry {
    private val map = ConcurrentHashMap<Precision, PrecisionMetrics>().apply {
        Precision.values().forEach { put(it, PrecisionMetrics(it)) }
    }
    fun forPrecision(p: Precision): PrecisionMetrics = map[p]!!
    fun all(): List<PrecisionMetrics> = Precision.values().map { map[it]!! }
    fun resetAll() { map.values.forEach { it.reset() } }
}
