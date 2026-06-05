package com.example.pa2.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * TFLite YOLO detector that supports three Ultralytics export layouts:
 *
 *  1. YOLO26 end-to-end (default for `model.export(format="tflite")`):
 *     output shape  = [1, 300, 6]
 *     row layout    = [x1, y1, x2, y2, score, class_id]   (xyxy, normalized 0..1)
 *     no NMS needed (already done in the graph)
 *
 *  2. YOLO11 / YOLO26 "one-to-many" head (channel-major, common for INT8):
 *     output shape  = [1, 84, 8400]
 *     row layout    = [cx, cy, w, h, c0..c79]              (xywh, in input pixels)
 *     per-class NMS required
 *
 *  3. YOLO11 / YOLO26 "one-to-many" head (anchor-major, less common):
 *     output shape  = [1, 8400, 84]
 *     row layout    = same channels, transposed
 *     per-class NMS required
 *
 * The format is detected from the output tensor's shape at load time.
 */
class YoloDetector(private val context: Context) {

    companion object {
        private const val TAG = "YoloDetector"
        const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80
        private const val CHANNELS_ONE_TO_MANY = 4 + NUM_CLASSES   // 84
        private const val END2END_ROW_SIZE = 6                    // x,y,x,y,score,cls
        private const val DEFAULT_CONF_THRESHOLD = 0.25f
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 50
    }

    private enum class OutputLayout {
        END2END,                  // [1, N, 6]
        ONE_TO_MANY_CHANNEL_MAJOR, // [1, 84, 8400]
        ONE_TO_MANY_ANCHOR_MAJOR   // [1, 8400, 84]
    }

    private var interpreter: Interpreter? = null
    private var currentPrecision: Precision = Precision.FP32

    private var outputLayout: OutputLayout = OutputLayout.END2END
    private var outDim1: Int = 0
    private var outDim2: Int = 0

    private var inputIsQuantized = false
    private var inputScale = 0f
    private var inputZeroPoint = 0
    private var outputIsQuantized = false
    private var outputScale = 0f
    private var outputZeroPoint = 0
    private var inputDataType: DataType = DataType.FLOAT32
    private var outputDataType: DataType = DataType.FLOAT32

    @Volatile var confidenceThreshold: Float = DEFAULT_CONF_THRESHOLD

    var modelSizeBytes: Long = 0L
        private set

    /** Human-readable name of the delegate currently in use ("GPU" or "CPU(4 threads)"). */
    var activeDelegate: String = "CPU(4 threads)"
        private set

    fun precision(): Precision = currentPrecision

    @Synchronized
    fun load(precision: Precision) {
        close()
        val asset = precision.assetName
        Log.i(TAG, "Loading $asset")
        val mapped = FileUtil.loadMappedFile(context, asset)
        modelSizeBytes = context.assets.openFd(asset).use { it.length }

        val options = Interpreter.Options().apply {
            setNumThreads(4)
            // FP32 and FP16 benefit massively from the GPU delegate (~2-4x).
            // INT8 stays on CPU because the GPU delegate has limited INT8 op
            // coverage and would silently fall back, polluting the comparison.
            if (precision != Precision.INT8) {
                try {
                    addDelegate(org.tensorflow.lite.gpu.GpuDelegate())
                    activeDelegate = "GPU"
                } catch (e: Throwable) {
                    Log.w(TAG, "GPU delegate unavailable, falling back to CPU: ${e.message}")
                    activeDelegate = "CPU(4 threads)"
                }
            } else {
                activeDelegate = "CPU(4 threads)"
            }
        }
        val itp = Interpreter(mapped, options)

        val inT = itp.getInputTensor(0)
        val outT = itp.getOutputTensor(0)
        Log.i(TAG, "Input  shape=${inT.shape().toList()} dtype=${inT.dataType()}")
        Log.i(TAG, "Output shape=${outT.shape().toList()} dtype=${outT.dataType()}")

        val inShape = inT.shape()
        require(inShape.size == 4 && inShape[0] == 1 && inShape[3] == 3) {
            "Unexpected input shape: ${inShape.toList()} (expected [1,H,W,3])"
        }
        if (inShape[1] != INPUT_SIZE || inShape[2] != INPUT_SIZE) {
            Log.w(TAG, "Model input is ${inShape[1]}x${inShape[2]}, app expects ${INPUT_SIZE}")
        }

        inputDataType = inT.dataType()
        outputDataType = outT.dataType()
        val inQ = inT.quantizationParams()
        inputScale = inQ.scale; inputZeroPoint = inQ.zeroPoint
        inputIsQuantized = (inputDataType == DataType.UINT8 || inputDataType == DataType.INT8)

        val outQ = outT.quantizationParams()
        outputScale = outQ.scale; outputZeroPoint = outQ.zeroPoint
        outputIsQuantized = (outputDataType == DataType.UINT8 || outputDataType == DataType.INT8)

        // Detect output layout.
        val s = outT.shape()
        require(s.size == 3 && s[0] == 1) { "Unexpected output rank: ${s.toList()}" }
        outDim1 = s[1]; outDim2 = s[2]
        outputLayout = when {
            s[2] == END2END_ROW_SIZE -> OutputLayout.END2END
            s[1] == CHANNELS_ONE_TO_MANY -> OutputLayout.ONE_TO_MANY_CHANNEL_MAJOR
            s[2] == CHANNELS_ONE_TO_MANY -> OutputLayout.ONE_TO_MANY_ANCHOR_MAJOR
            else -> throw IllegalStateException("Cannot interpret output shape ${s.toList()}")
        }
        Log.i(TAG, "Detected output layout: $outputLayout (shape=${s.toList()})")

        interpreter = itp
        currentPrecision = precision
        Log.i(TAG, "Loaded ${precision.displayName} | size=$modelSizeBytes B")
    }

    @Synchronized
    fun close() {
        interpreter?.close()
        interpreter = null
    }

    // Reusable input/output buffers (allocated on first use, sized per model).
    private var reusableInputFloat: ByteBuffer? = null
    private var reusableInputQuant: ByteBuffer? = null
    private var reusableOutput: ByteBuffer? = null

    /**
     * Runs detection on a 640×640 ARGB IntArray that has already been
     * resized and rotated. This is the fast path used by the live pipeline.
     *
     * Returns boxes whose coords are normalized to the model-input square
     * (0..1). Caller maps those to preview/screen space.
     */
    fun detect(argb: IntArray): Pair<List<Detection>, Long> {
        val itp = interpreter ?: return emptyList<Detection>() to 0L
        require(argb.size == INPUT_SIZE * INPUT_SIZE) {
            "argb buffer must be $INPUT_SIZE×$INPUT_SIZE, was ${argb.size}"
        }

        // 1) Fill the input ByteBuffer directly from the ARGB ints.
        val pixCount = argb.size
        val inputBuffer: ByteBuffer = if (inputIsQuantized) {
            val needBytes = pixCount * 3
            val bb = reusableInputQuant?.takeIf { it.capacity() == needBytes }
                ?: ByteBuffer.allocateDirect(needBytes).order(ByteOrder.nativeOrder())
                    .also { reusableInputQuant = it }
            bb.rewind()
            // For YOLO uint8 export the typical quantization is scale=1/255, zp=0,
            // i.e. the quantized value IS the raw 0..255 pixel byte. We use the
            // model's actual scale/zp to be safe.
            val scale = if (inputScale == 0f) 1f / 255f else inputScale
            val zp = inputZeroPoint
            for (i in 0 until pixCount) {
                val px = argb[i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                bb.put(qByte(r, scale, zp))
                bb.put(qByte(g, scale, zp))
                bb.put(qByte(b, scale, zp))
            }
            bb.rewind(); bb
        } else {
            val needBytes = pixCount * 3 * 4   // 3 floats per pixel × 4 bytes
            val bb = reusableInputFloat?.takeIf { it.capacity() == needBytes }
                ?: ByteBuffer.allocateDirect(needBytes).order(ByteOrder.nativeOrder())
                    .also { reusableInputFloat = it }
            bb.rewind()
            val fb = bb.asFloatBuffer()
            val inv = 1f / 255f
            for (i in 0 until pixCount) {
                val px = argb[i]
                fb.put(((px shr 16) and 0xFF) * inv)
                fb.put(((px shr 8) and 0xFF) * inv)
                fb.put((px and 0xFF) * inv)
            }
            bb.rewind(); bb
        }

        // 2) Output buffer (reused across calls of the same precision).
        val totalOut = outDim1 * outDim2
        val outNeed = totalOut * outputDataType.byteSize()
        val outputBuffer = reusableOutput?.takeIf { it.capacity() == outNeed }
            ?: ByteBuffer.allocateDirect(outNeed).order(ByteOrder.nativeOrder())
                .also { reusableOutput = it }
        outputBuffer.rewind()

        // 3) Inference.
        val t0 = System.nanoTime()
        synchronized(this) { itp.run(inputBuffer, outputBuffer) }
        val tMs = (System.nanoTime() - t0) / 1_000_000L

        // 4) Decode.
        outputBuffer.rewind()
        val raw = decodeToFloats(outputBuffer, totalOut)
        val detections = when (outputLayout) {
            OutputLayout.END2END -> postprocessEnd2End(raw)
            OutputLayout.ONE_TO_MANY_CHANNEL_MAJOR -> postprocessOneToMany(raw, transposed = false)
            OutputLayout.ONE_TO_MANY_ANCHOR_MAJOR -> postprocessOneToMany(raw, transposed = true)
        }
        return detections to tMs
    }

    private fun qByte(v: Int, scale: Float, zp: Int): Byte {
        // v is 0..255 (already a normalized pixel byte). Equivalent to:
        //   q = round((v/255) / scale + zp)
        val q = Math.round((v / 255f) / scale + zp).coerceIn(0, 255)
        return q.toByte()
    }

    private fun decodeToFloats(buf: ByteBuffer, total: Int): FloatArray {
        val out = FloatArray(total)
        when (outputDataType) {
            DataType.FLOAT32 -> buf.asFloatBuffer().get(out)
            DataType.UINT8 -> for (i in 0 until total) {
                val q = buf.get(i).toInt() and 0xFF
                out[i] = (q - outputZeroPoint) * outputScale
            }
            DataType.INT8 -> for (i in 0 until total) {
                val q = buf.get(i).toInt()
                out[i] = (q - outputZeroPoint) * outputScale
            }
            else -> throw IllegalStateException("Unsupported output dtype $outputDataType")
        }
        return out
    }

    /**
     * YOLO26 end-to-end: each row is [x1, y1, x2, y2, score, class_id].
     * Box coordinates may be in either pixel space (0..INPUT_SIZE) or
     * normalized (0..1) depending on the export. We detect which by
     * peeking at the first valid row's max coord and pick a divisor
     * accordingly. Padded rows have score = 0 and are filtered out.
     * No NMS needed.
     */
    private fun postprocessEnd2End(out: FloatArray): List<Detection> {
        val numDets = outDim1            // 300
        val rowSize = outDim2            // 6
        val results = ArrayList<Detection>(16)
        val conf = confidenceThreshold

        // Probe coordinate scale: scan up to 5 valid rows for the largest coord.
        var probedScale = 1f      // 1f = already normalized
        run {
            var probed = 0
            var maxCoord = 0f
            for (i in 0 until numDets) {
                val base = i * rowSize
                val score = out[base + 4]
                if (score < conf) continue
                val mx = maxOf(out[base], out[base + 1], out[base + 2], out[base + 3])
                if (mx > maxCoord) maxCoord = mx
                if (++probed >= 5) break
            }
            if (maxCoord > 1.5f) probedScale = INPUT_SIZE.toFloat()
        }

        for (i in 0 until numDets) {
            val base = i * rowSize
            val score = out[base + 4]
            if (score < conf) continue
            val classId = out[base + 5].toInt().coerceIn(0, NUM_CLASSES - 1)
            val x1 = out[base]     / probedScale
            val y1 = out[base + 1] / probedScale
            val x2 = out[base + 2] / probedScale
            val y2 = out[base + 3] / probedScale
            val left   = min(x1, x2).coerceIn(0f, 1f)
            val top    = min(y1, y2).coerceIn(0f, 1f)
            val right  = max(x1, x2).coerceIn(0f, 1f)
            val bottom = max(y1, y2).coerceIn(0f, 1f)
            if (right - left < 1e-3f || bottom - top < 1e-3f) continue
            results += Detection(
                RectF(left, top, right, bottom),
                classId,
                CocoLabels.nameOf(classId),
                score
            )
        }
        return results.sortedByDescending { it.score }.take(MAX_DETECTIONS)
    }

    /**
     * Legacy YOLO11 / YOLO26-fallback "one-to-many" head:
     * channels per anchor = [cx, cy, w, h, c0..c79], xywh.
     * Coordinates may be pixel-space or normalized depending on export;
     * we detect at runtime. Per-class NMS required.
     */
    private fun postprocessOneToMany(out: FloatArray, transposed: Boolean): List<Detection> {
        val anchors: Int
        val channels: Int = CHANNELS_ONE_TO_MANY
        if (transposed) { anchors = outDim1 } else { anchors = outDim2 }

        // Probe scale.
        var probedScale = 1f
        run {
            var probed = 0
            var maxCoord = 0f
            for (a in 0 until anchors) {
                val cx: Float; val cy: Float; val w: Float; val h: Float
                var bestScore = 0f
                if (transposed) {
                    val base = a * channels
                    cx = out[base];     cy = out[base + 1]
                    w  = out[base + 2]; h  = out[base + 3]
                    for (c in 0 until NUM_CLASSES) {
                        val s = out[base + 4 + c]; if (s > bestScore) bestScore = s
                    }
                } else {
                    cx = out[0 * anchors + a]; cy = out[1 * anchors + a]
                    w  = out[2 * anchors + a]; h  = out[3 * anchors + a]
                    for (c in 0 until NUM_CLASSES) {
                        val s = out[(4 + c) * anchors + a]; if (s > bestScore) bestScore = s
                    }
                }
                if (bestScore < confidenceThreshold) continue
                val mx = maxOf(cx, cy, w, h)
                if (mx > maxCoord) maxCoord = mx
                if (++probed >= 5) break
            }
            if (maxCoord > 1.5f) probedScale = INPUT_SIZE.toFloat()
        }

        val candidates = ArrayList<Detection>(64)
        val conf = confidenceThreshold

        for (a in 0 until anchors) {
            val cx: Float; val cy: Float; val w: Float; val h: Float
            var bestCls = -1
            var bestScore = 0f

            if (transposed) {
                val base = a * channels
                cx = out[base];     cy = out[base + 1]
                w  = out[base + 2]; h  = out[base + 3]
                for (c in 0 until NUM_CLASSES) {
                    val s = out[base + 4 + c]
                    if (s > bestScore) { bestScore = s; bestCls = c }
                }
            } else {
                cx = out[0 * anchors + a]
                cy = out[1 * anchors + a]
                w  = out[2 * anchors + a]
                h  = out[3 * anchors + a]
                for (c in 0 until NUM_CLASSES) {
                    val s = out[(4 + c) * anchors + a]
                    if (s > bestScore) { bestScore = s; bestCls = c }
                }
            }
            if (bestScore < conf || bestCls < 0) continue

            val nx = cx / probedScale
            val ny = cy / probedScale
            val nw = w  / probedScale
            val nh = h  / probedScale
            val left   = (nx - nw / 2f).coerceIn(0f, 1f)
            val top    = (ny - nh / 2f).coerceIn(0f, 1f)
            val right  = (nx + nw / 2f).coerceIn(0f, 1f)
            val bottom = (ny + nh / 2f).coerceIn(0f, 1f)
            if (right - left < 1e-3f || bottom - top < 1e-3f) continue
            candidates += Detection(
                RectF(left, top, right, bottom),
                bestCls,
                CocoLabels.nameOf(bestCls),
                bestScore
            )
        }
        return nms(candidates, IOU_THRESHOLD).take(MAX_DETECTIONS)
    }

    private fun nms(boxes: List<Detection>, iouThr: Float): List<Detection> {
        val byClass = boxes.groupBy { it.classId }
        val kept = ArrayList<Detection>()
        for ((_, group) in byClass) {
            val sorted = group.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept += best
                val it2 = sorted.iterator()
                while (it2.hasNext()) {
                    if (iou(best.box, it2.next().box) > iouThr) it2.remove()
                }
            }
        }
        return kept.sortedByDescending { it.score }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bt = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bt - t)
        val ua = (a.right - a.left) * (a.bottom - a.top) +
                (b.right - b.left) * (b.bottom - b.top) - inter
        return if (ua <= 0f) 0f else inter / ua
    }
}
