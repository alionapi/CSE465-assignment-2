package com.example.pa2.util

import androidx.camera.core.ImageProxy

/**
 * Two-stage YUV → ARGB → square resize pipeline.
 *
 * Stage 1 ([extractFrame], camera thread): copies the Y, U, V byte planes
 * out of the [ImageProxy] into reusable scratch arrays, captures the source
 * dimensions, strides, and rotation, then returns a [Frame] snapshot. Cheap
 * (raw memcpy of ~0.5 MB) so it can run on the camera thread without
 * blocking incoming frames.
 *
 * Stage 2 ([convert], detect thread): walks the destination square in
 * the on-screen orientation, computes which source YUV pixel each
 * destination needs (folding rotation into the index math), runs ITU-R
 * BT.601 integer YUV→RGB on each pixel, and writes ARGB straight into
 * the supplied [out] buffer. No bitmap allocations, no rotation copy.
 *
 * Two scratch sets ([Frame]) so stage 2 can overlap with the next stage 1.
 *
 * Thread safety: [extractFrame] is called from the camera thread,
 * [convert] from the detect thread. Each [Frame] is owned by exactly
 * one thread at a time (camera owns it on extract, detect owns it after
 * pollFrame, then it's recycled).
 */
class FastFrameConverter(val outSize: Int = 640) {

    /** A single captured frame's raw YUV data + metadata. */
    class Frame {
        var yBytes: ByteArray = ByteArray(0)
        var uBytes: ByteArray = ByteArray(0)
        var vBytes: ByteArray = ByteArray(0)
        var srcW: Int = 0
        var srcH: Int = 0
        var rotation: Int = 0
        var yRowStride: Int = 0
        var uvRowStride: Int = 0
        var uvPixelStride: Int = 0
    }

    /**
     * Copies Y/U/V byte planes from [proxy] into [frame] (which must NOT be
     * concurrently in use by [convert]). Cheap — runs on the camera thread.
     */
    fun extractFrame(proxy: ImageProxy, frame: Frame) {
        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]

        val ySize = yPlane.buffer.remaining()
        val uSize = uPlane.buffer.remaining()
        val vSize = vPlane.buffer.remaining()
        if (frame.yBytes.size < ySize) frame.yBytes = ByteArray(ySize)
        if (frame.uBytes.size < uSize) frame.uBytes = ByteArray(uSize)
        if (frame.vBytes.size < vSize) frame.vBytes = ByteArray(vSize)
        yPlane.buffer.get(frame.yBytes, 0, ySize)
        uPlane.buffer.get(frame.uBytes, 0, uSize)
        vPlane.buffer.get(frame.vBytes, 0, vSize)

        frame.srcW = proxy.width
        frame.srcH = proxy.height
        frame.rotation = proxy.imageInfo.rotationDegrees
        frame.yRowStride = yPlane.rowStride
        frame.uvRowStride = uPlane.rowStride
        frame.uvPixelStride = uPlane.pixelStride
    }

    /**
     * Resizes/rotates the [frame]'s YUV bytes to outSize×outSize ARGB pixels,
     * writing into [out]. Caller-supplied [out] must have length
     * [outSize] * [outSize].
     */
    fun convert(frame: Frame, out: IntArray) {
        require(out.size == outSize * outSize)

        val srcW = frame.srcW
        val srcH = frame.srcH
        val rot = frame.rotation
        val yBytes = frame.yBytes
        val uBytes = frame.uBytes
        val vBytes = frame.vBytes
        val yRowStride = frame.yRowStride
        val uvRowStride = frame.uvRowStride
        val uvPixelStride = frame.uvPixelStride

        val rotW: Int; val rotH: Int
        when (rot) {
            90, 270 -> { rotW = srcH; rotH = srcW }
            else    -> { rotW = srcW; rotH = srcH }
        }
        val srcSquare = minOf(rotW, rotH)
        val cropX0 = (rotW - srcSquare) / 2
        val cropY0 = (rotH - srcSquare) / 2
        val outSz = outSize

        var di = 0
        for (dy in 0 until outSz) {
            val sy = cropY0 + (dy * srcSquare) / outSz
            for (dx in 0 until outSz) {
                val sx = cropX0 + (dx * srcSquare) / outSz

                val rawX: Int; val rawY: Int
                when (rot) {
                    90 -> { rawX = sy; rawY = (srcH - 1) - sx }
                    180 -> { rawX = (srcW - 1) - sx; rawY = (srcH - 1) - sy }
                    270 -> { rawX = (srcW - 1) - sy; rawY = sx }
                    else -> { rawX = sx; rawY = sy }
                }

                val y = (yBytes[rawY * yRowStride + rawX].toInt() and 0xFF) - 16
                val uvCol = (rawX shr 1) * uvPixelStride
                val uvRow = (rawY shr 1) * uvRowStride
                val u = (uBytes[uvRow + uvCol].toInt() and 0xFF) - 128
                val v = (vBytes[uvRow + uvCol].toInt() and 0xFF) - 128

                val y1192 = 1192 * y
                var r = (y1192 + 1634 * v) shr 10
                var g = (y1192 - 833 * v - 400 * u) shr 10
                var b = (y1192 + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255

                out[di++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
