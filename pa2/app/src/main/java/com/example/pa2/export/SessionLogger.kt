package com.example.pa2.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.pa2.detector.Detection
import com.example.pa2.detector.Precision
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Buffers per-frame log records during a session and writes them to
 * the user's Downloads folder as CSV or JSON when requested.
 */
class SessionLogger {

    data class Record(
        val timestampMs: Long,
        val frameIndex: Long,
        val precision: String,
        val latencyMs: Long,
        val rollingFps: Float,
        val avgLatencyMs: Float,
        val p95LatencyMs: Float,
        val targetClass: String?,
        val targetHit: Boolean,
        val numDetections: Int,
        val predictedClasses: List<String>,
        val confidenceScores: List<Float>,
        val batteryCurrentRaw: Long,
        val batteryLevelPct: Int,
        val memBytes: Long,
        val thermal: String,
        val inputResolution: String,
        val delegate: String
    )

    private val records = CopyOnWriteArrayList<Record>()

    fun add(rec: Record) { records.add(rec) }
    fun size(): Int = records.size
    fun clear() { records.clear() }

    fun exportCsv(context: Context): String? = export(context, "csv")
    fun exportJson(context: Context): String? = export(context, "json")

    private fun export(context: Context, kind: String): String? {
        if (records.isEmpty()) return null
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "pa2_session_${ts}.${kind}"
        val payload: ByteArray = if (kind == "csv") buildCsv() else buildJson()
        return writeToDownloads(context, fileName, payload, if (kind == "csv") "text/csv" else "application/json")
    }

    private fun buildCsv(): ByteArray {
        val sb = StringBuilder()
        sb.append("timestamp_ms,frame_index,precision,latency_ms,rolling_fps,avg_latency_ms,p95_latency_ms,")
            .append("target_class,target_hit,num_detections,predicted_classes,confidence_scores,")
            .append("battery_current_raw,battery_level_pct,mem_bytes,thermal,input_resolution,delegate\n")
        for (r in records) {
            sb.append(r.timestampMs).append(',')
              .append(r.frameIndex).append(',')
              .append(r.precision).append(',')
              .append(r.latencyMs).append(',')
              .append(String.format(Locale.US, "%.3f", r.rollingFps)).append(',')
              .append(String.format(Locale.US, "%.3f", r.avgLatencyMs)).append(',')
              .append(String.format(Locale.US, "%.3f", r.p95LatencyMs)).append(',')
              .append(csvCell(r.targetClass ?: "")).append(',')
              .append(r.targetHit).append(',')
              .append(r.numDetections).append(',')
              .append(csvCell(r.predictedClasses.joinToString("|"))).append(',')
              .append(csvCell(r.confidenceScores.joinToString("|") { String.format(Locale.US, "%.3f", it) })).append(',')
              .append(r.batteryCurrentRaw).append(',')
              .append(r.batteryLevelPct).append(',')
              .append(r.memBytes).append(',')
              .append(csvCell(r.thermal)).append(',')
              .append(csvCell(r.inputResolution)).append(',')
              .append(csvCell(r.delegate)).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun csvCell(s: String): String {
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            return "\"${s.replace("\"", "\"\"")}\""
        }
        return s
    }

    private fun buildJson(): ByteArray {
        val sb = StringBuilder()
        sb.append("{\"records\":[")
        var first = true
        for (r in records) {
            if (!first) sb.append(',') else first = false
            sb.append('{')
                .append("\"timestamp_ms\":").append(r.timestampMs).append(',')
                .append("\"frame_index\":").append(r.frameIndex).append(',')
                .append("\"precision\":\"").append(r.precision).append("\",")
                .append("\"latency_ms\":").append(r.latencyMs).append(',')
                .append("\"rolling_fps\":").append(String.format(Locale.US, "%.3f", r.rollingFps)).append(',')
                .append("\"avg_latency_ms\":").append(String.format(Locale.US, "%.3f", r.avgLatencyMs)).append(',')
                .append("\"p95_latency_ms\":").append(String.format(Locale.US, "%.3f", r.p95LatencyMs)).append(',')
                .append("\"target_class\":").append(jsonStr(r.targetClass)).append(',')
                .append("\"target_hit\":").append(r.targetHit).append(',')
                .append("\"num_detections\":").append(r.numDetections).append(',')
                .append("\"predicted_classes\":[")
                .append(r.predictedClasses.joinToString(",") { jsonStr(it) })
                .append("],")
                .append("\"confidence_scores\":[")
                .append(r.confidenceScores.joinToString(",") { String.format(Locale.US, "%.3f", it) })
                .append("],")
                .append("\"battery_current_raw\":").append(r.batteryCurrentRaw).append(',')
                .append("\"battery_level_pct\":").append(r.batteryLevelPct).append(',')
                .append("\"mem_bytes\":").append(r.memBytes).append(',')
                .append("\"thermal\":").append(jsonStr(r.thermal)).append(',')
                .append("\"input_resolution\":").append(jsonStr(r.inputResolution)).append(',')
                .append("\"delegate\":").append(jsonStr(r.delegate))
                .append('}')
        }
        sb.append("]}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun jsonStr(s: String?): String {
        if (s == null) return "null"
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$esc\""
    }

    private fun writeToDownloads(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        mime: String
    ): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/PA2")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri).use { it?.write(bytes) }
                "Downloads/PA2/$fileName"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "PA2"
                )
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, fileName)
                FileOutputStream(out).use { it.write(bytes) }
                out.absolutePath
            }
        } catch (e: Exception) {
            Log.e("SessionLogger", "Export failed", e)
            null
        }
    }

    /** Helper: extract record-friendly per-detection fields. */
    companion object {
        fun summarize(detections: List<Detection>): Pair<List<String>, List<Float>> =
            detections.map { it.classLabel } to detections.map { it.score }
    }
}

/** Process-wide single logger so all fragments share the same buffer. */
object LoggerHolder {
    val logger = SessionLogger()
}
