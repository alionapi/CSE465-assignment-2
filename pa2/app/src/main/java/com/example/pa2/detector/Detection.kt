package com.example.pa2.detector

import android.graphics.RectF
/**
 * Single detection produced by the YOLO post-processor.
 * Coordinates are in normalized model-input space (0..1) so the overlay
 * can map them to any preview size.
 */
data class Detection(
    val box: RectF,           // left, top, right, bottom in [0,1]
    val classId: Int,
    val classLabel: String,
    val score: Float
)

/**
 * Precision mode for the active TFLite interpreter.
 * Asset names follow Ultralytics' YOLO26 TFLite export naming convention.
 */
enum class Precision(val displayName: String, val assetName: String) {
    FP32("FP32", "yolo26n_float32.tflite"),
    FP16("FP16", "yolo26n_float16.tflite"),
    INT8("INT8", "yolo26n_int8.tflite");

    companion object {
        fun fromName(s: String?): Precision =
            values().firstOrNull { it.displayName.equals(s, ignoreCase = true) } ?: FP32
    }
}
