package com.example.pa2.ui.theme

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.pa2.databinding.FragmentLiveBinding
import com.example.pa2.detector.Detection
import com.example.pa2.detector.Precision
import com.example.pa2.detector.YoloDetector
import com.example.pa2.export.LoggerHolder
import com.example.pa2.export.SessionLogger
import com.example.pa2.metrics.MetricsRegistry
import com.example.pa2.metrics.SystemStats
import com.example.pa2.util.FastFrameConverter
import com.example.pa2.voice.SpeechController
import com.example.pa2.voice.VoiceCommandParser
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LiveFragment : Fragment() {

    companion object { private const val TAG = "LiveFragment" }

    private var _b: FragmentLiveBinding? = null
    private val b get() = _b!!
    private val vm: SharedViewModel by activityViewModels()

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val detectExecutor = Executors.newSingleThreadExecutor()

    private var detector: YoloDetector? = null
    private lateinit var systemStats: SystemStats
    private lateinit var speech: SpeechController

    private val running = AtomicBoolean(true)
    private val frameIndex = AtomicLong(0)

    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    @Volatile private var pendingPrecision: Precision = Precision.FP32

    // --- Extension state (extra voice features) ---

    /** When true, only the target class is drawn on the overlay. */
    @Volatile private var filterMode: Boolean = false

    /** When true, the app speaks "<label> found" via TTS the first time the target appears. */
    @Volatile private var announceMode: Boolean = false

    /** Tracks last target-found state so we only speak on transitions, not every frame. */
    @Volatile private var lastTargetHit: Boolean = false

    /** Snapshot of the most recent ARGB frame, copied lazily from [argbOut]. */
    @Volatile private var lastFrameArgb: IntArray? = null
    @Volatile private var lastFrameArgbStamp: Long = 0L
    @Volatile private var lastFrameDetections: List<Detection> = emptyList()

    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady: Boolean = false

    // --- Frame pipeline (camera thread → detect thread, double-buffered) ---
    private val frameConverter = FastFrameConverter(640)
    private val frameSlots = arrayOf(FastFrameConverter.Frame(), FastFrameConverter.Frame())
    private var slotInUse = 0
    private val argbOut = IntArray(640 * 640)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, b: Bundle?): View {
        _b = FragmentLiveBinding.inflate(inflater, container, false)
        return this.b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        systemStats = SystemStats(requireContext())
        speech = SpeechController(requireContext())
        detector = YoloDetector(requireContext())

        // TextToSpeech for the "announce when found" voice command and CountQuery responses.
        tts = android.speech.tts.TextToSpeech(requireContext().applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.US
                ttsReady = true
            }
        }

        // Initialize precision selector
        val precision = vm.precision.value ?: Precision.FP32
        applyPrecisionToUi(precision)
        loadDetectorAsync(precision)

        b.btnFp32.setOnClickListener { switchPrecision(Precision.FP32) }
        b.btnFp16.setOnClickListener { switchPrecision(Precision.FP16) }
        b.btnInt8.setOnClickListener { switchPrecision(Precision.INT8) }
        // Tap the voice button to toggle hands-free continuous listening.
        // Long-press for a single one-shot capture (useful if you want to
        // speak just one command without leaving the recognizer running).
        b.btnVoice.setOnClickListener { toggleContinuousVoice() }
        b.btnVoice.setOnLongClickListener { startVoiceOneShot(); true }
        b.btnPause.setOnClickListener {
            running.set(!running.get())
            b.btnPause.text = if (running.get()) "Pause Inference" else "Resume Inference"
            if (!running.get()) b.overlay.clear()
        }
        b.btnClearTarget.setOnClickListener {
            vm.clearTarget()
            updateTargetLabel("Not set")
            Toast.makeText(requireContext(), "Target cleared", Toast.LENGTH_SHORT).show()
        }

        vm.target.observe(viewLifecycleOwner) { t ->
            updateTargetLabel(if (t.classId < 0) "Not set" else t.label)
        }
        vm.lastVoiceTranscript.observe(viewLifecycleOwner) { txt ->
            b.tvVoice.text = if (txt.isBlank()) "—" else txt
        }
        vm.precision.observe(viewLifecycleOwner) { applyPrecisionToUi(it) }

        // Camera
        if (hasCameraPermission()) bindCamera()
        else b.tvStatus.text = "Waiting for camera permission…"
    }

    override fun onResume() {
        super.onResume()
        // If we previously couldn't bind because permission was missing,
        // try again now that the user may have just granted it.
        if (hasCameraPermission() && imageAnalysis == null && _b != null) {
            bindCamera()
        }
    }

    fun onPermissionsResult(camera: Boolean, mic: Boolean) {
        if (camera && _b != null) bindCamera()
        if (!mic) Toast.makeText(requireContext(), "Mic denied — voice disabled", Toast.LENGTH_LONG).show()
    }

    private fun hasCameraPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            requireContext(), android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun bindCamera() {
        val ctx = requireContext()
        val providerFuture = ProcessCameraProvider.getInstance(ctx)
        providerFuture.addListener({
            val provider = providerFuture.get()
            preview = Preview.Builder().build().also {
                it.setSurfaceProvider(b.preview.surfaceProvider)
            }
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                // Smaller analysis frame ⇒ faster YUV→ARGB conversion.
                // 480×360 still has plenty of detail for YOLO26n at 640 input.
                .setTargetResolution(android.util.Size(480, 360))
                .build().also { ia ->
                    ia.setAnalyzer(cameraExecutor) { proxy ->
                        try { handleFrame(proxy) }
                        finally { proxy.close() }
                    }
                }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageAnalysis
                )
                b.tvStatus.text = "Camera ready · ${vm.precision.value?.displayName}"
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                b.tvStatus.text = "Camera bind failed: ${e.message}"
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun handleFrame(proxy: androidx.camera.core.ImageProxy) {
        if (!running.get()) return
        val det = detector ?: return

        // Camera thread (this thread): cheap copy of YUV planes into a slot.
        // Double-buffered so the detect thread can be busy on the previous slot.
        val slot = frameSlots[slotInUse]
        slotInUse = 1 - slotInUse
        try { frameConverter.extractFrame(proxy, slot) }
        catch (e: Exception) { Log.e(TAG, "extractFrame failed", e); return }

        // Detect thread: real work happens here.
        detectExecutor.execute {
            // 1) YUV → ARGB → 640×640 (in argbOut, reused).
            try { frameConverter.convert(slot, argbOut) }
            catch (e: Exception) { Log.e(TAG, "convert failed", e); return@execute }

            // 2) Inference.
            val (dets, latency) = try { det.detect(argbOut) }
            catch (e: Exception) { Log.e(TAG, "detect failed", e); emptyList<Detection>() to 0L }

            val precision = det.precision()
            val pm = MetricsRegistry.forPrecision(precision)
            val target = vm.target.value ?: SharedViewModel.TargetState.NONE
            val targetActive = target.classId >= 0
            val targetHit = targetActive && dets.any { it.classId == target.classId }
            val meanConf = if (dets.isEmpty()) null else dets.map { it.score }.average().toFloat()
            pm.recordFrame(latency, meanConf, targetActive, targetHit)
            pm.modelSizeBytes = det.modelSizeBytes


            lastFrameDetections = dets
            if (frameIndex.get() % 10L == 0L) {
                val snap = lastFrameArgb ?: IntArray(argbOut.size).also { lastFrameArgb = it }
                System.arraycopy(argbOut, 0, snap, 0, argbOut.size)
                lastFrameArgbStamp = System.currentTimeMillis()
            }

            if (announceMode && targetActive) {
                val nowHit = targetHit
                if (nowHit && !lastTargetHit) {
                    speakAsync("${target.label} found")
                }
                lastTargetHit = nowHit
            } else {
                lastTargetHit = false
            }

            val (preds, scores) = SessionLogger.summarize(dets)
            val rec = SessionLogger.Record(
                timestampMs = System.currentTimeMillis(),
                frameIndex = frameIndex.incrementAndGet(),
                precision = precision.displayName,
                latencyMs = latency,
                rollingFps = pm.rollingFps(),
                avgLatencyMs = pm.avgLatencyMs(),
                p95LatencyMs = pm.p95LatencyMs(),
                targetClass = if (targetActive) target.label else null,
                targetHit = targetHit,
                numDetections = dets.size,
                predictedClasses = preds,
                confidenceScores = scores,
                batteryCurrentRaw = systemStats.batteryCurrentNow(),
                batteryLevelPct = systemStats.batteryLevelPct(),
                memBytes = systemStats.appMemoryBytes(),
                thermal = systemStats.thermalStatus(),
                inputResolution = "${YoloDetector.INPUT_SIZE}x${YoloDetector.INPUT_SIZE}",
                delegate = det.activeDelegate
            )

            if (rec.frameIndex % 5L == 0L) {
                LoggerHolder.logger.add(rec)
            }
            val now = System.currentTimeMillis()
            val updateText = (now - lastTextUpdateMs) >= 250L
            if (updateText) lastTextUpdateMs = now
            val avgLatency = pm.avgLatencyMs()
            val fps = pm.rollingFps()
            val numDets = dets.size
            val statusLine = if (updateText)
                buildStatusLine(precision, targetActive, targetHit, target.label, numDets)
            else null
            view?.post {
                if (_b == null) return@post
                b.overlay.update(dets, target.classId, filterMode)
                if (updateText) {
                    b.tvLatency.text = String.format(Locale.US, "%.1f ms", avgLatency)
                    b.tvFps.text = String.format(Locale.US, "%.2f", fps)
                    b.tvCount.text = "$numDets"
                    b.tvStatus.text = statusLine
                }
            }
        }
    }

    @Volatile private var lastTextUpdateMs: Long = 0L

    private fun buildStatusLine(
        p: Precision, targetActive: Boolean, hit: Boolean, label: String, n: Int
    ): String {
        return when {
            !targetActive -> "${p.displayName} is active: $n object${if (n == 1) "" else "s"} detected"
            hit -> "$label found with ${p.displayName}"
            else -> "${p.displayName} is active: looking for $label"
        }
    }

    private fun applyPrecisionToUi(p: Precision) {
        val targetId = when (p) {
            Precision.FP32 -> b.btnFp32.id
            Precision.FP16 -> b.btnFp16.id
            Precision.INT8 -> b.btnInt8.id
        }
        if (b.precisionGroup.checkedButtonId != targetId) {
            b.precisionGroup.check(targetId)
        }
    }

    private fun loadDetectorAsync(p: Precision) {
        b.tvStatus.text = "Loading ${p.displayName}…"
        detectExecutor.execute {
            try {
                detector?.load(p)
                view?.post {
                    if (_b == null) return@post
                    b.tvStatus.text = "${p.displayName} ready"
                    vm.setPrecision(p)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load ${p.assetName}", e)
                view?.post {
                    if (_b == null) return@post
                    b.tvStatus.text = "Failed to load ${p.displayName}: ${e.message}"
                    Toast.makeText(requireContext(),
                        "Couldn't load ${p.assetName}. Check assets.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun switchPrecision(p: Precision) {
        if (p == detector?.precision()) { applyPrecisionToUi(p); return }
        pendingPrecision = p
        loadDetectorAsync(p)
    }

    private fun toggleContinuousVoice() {
        if (speech.isContinuous()) {
            speech.stopContinuous()
            b.btnVoice.text = "Voice target"
            b.tvVoice.text = "—"
            return
        }
        if (!checkVoiceReady()) return
        b.btnVoice.text = "Voice ON (tap to stop)"
        b.tvVoice.text = "Listening…"
        speech.startContinuous(makeVoiceCallback(continuous = true))
    }

    private fun startVoiceOneShot() {
        if (speech.isContinuous()) speech.stopContinuous()
        if (!checkVoiceReady()) return
        b.btnVoice.isEnabled = false
        b.tvVoice.text = "Listening…"
        speech.startOneShot(makeVoiceCallback(continuous = false))
    }

    private fun checkVoiceReady(): Boolean {
        if (!speech.isAvailable()) {
            Toast.makeText(requireContext(), "Speech not available on device", Toast.LENGTH_LONG).show()
            return false
        }
        if (!speech.hasMicPermission()) {
            Toast.makeText(requireContext(), "Microphone permission required", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun makeVoiceCallback(continuous: Boolean) = object : SpeechController.Callback {
        override fun onPartial(text: String) {
            view?.post { if (_b != null) b.tvVoice.text = "… $text" }
        }
        override fun onFinal(text: String) {
            view?.post {
                if (_b == null) return@post
                vm.setVoiceTranscript(text)
                // Show the heard text so the user can see if recognition was wrong.
                b.tvVoice.text = text
                if (!continuous) b.btnVoice.isEnabled = true
                handleParsed(VoiceCommandParser.parse(text))
            }
        }
        override fun onError(reason: String) {
            view?.post {
                if (_b == null) return@post
                if (!continuous) b.btnVoice.isEnabled = true
                // In continuous mode, show transient errors briefly but keep
                // the "Listening…" hint so the user knows we'll re-arm.
                b.tvVoice.text = if (continuous) "Listening… ($reason)" else "Error: $reason"
            }
        }
        override fun onListeningStarted() {
            view?.post { if (_b != null && b.tvVoice.text.startsWith("…").not()) b.tvVoice.text = "Listening…" }
        }
        override fun onListeningStopped() {}
    }

    private fun handleParsed(result: VoiceCommandParser.Result) {
        when (result) {
            is VoiceCommandParser.Result.TargetSet -> {
                vm.setTarget(result.classId, result.label)
                Toast.makeText(requireContext(), "Target: ${result.label}", Toast.LENGTH_SHORT).show()
            }
            is VoiceCommandParser.Result.ClearTarget -> {
                vm.clearTarget()
                filterMode = false
                Toast.makeText(requireContext(), "Target cleared", Toast.LENGTH_SHORT).show()
            }
            is VoiceCommandParser.Result.SwitchPrecision -> {
                val p = Precision.fromName(result.precision)
                switchPrecision(p)
                Toast.makeText(requireContext(), "Switching to ${p.displayName}", Toast.LENGTH_SHORT).show()
            }
            is VoiceCommandParser.Result.CountQuery -> {
                val n = lastFrameDetections.count { it.classId == result.classId }
                val msg = when (n) {
                    0 -> "I don't see any ${result.label}"
                    1 -> "I see 1 ${result.label}"
                    else -> "I see $n ${result.label}s"
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                speakAsync(msg)
            }
            is VoiceCommandParser.Result.ToggleAnnounce -> {
                announceMode = result.enable
                lastTargetHit = false
                val msg = if (result.enable) "Announcements on" else "Announcements off"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                if (result.enable) speakAsync("Announcements enabled")
            }
            is VoiceCommandParser.Result.Pause -> {
                if (running.get()) {
                    running.set(false)
                    b.btnPause.text = "Resume Inference"
                    b.overlay.clear()
                    Toast.makeText(requireContext(), "Paused", Toast.LENGTH_SHORT).show()
                }
            }
            is VoiceCommandParser.Result.Resume -> {
                if (!running.get()) {
                    running.set(true)
                    b.btnPause.text = "Pause Inference"
                    Toast.makeText(requireContext(), "Resumed", Toast.LENGTH_SHORT).show()
                }
            }
            is VoiceCommandParser.Result.Screenshot -> {
                takeScreenshot()
            }
            is VoiceCommandParser.Result.FilterOnly -> {
                vm.setTarget(result.classId, result.label)
                filterMode = true
                Toast.makeText(requireContext(),
                    "Showing only ${result.label}", Toast.LENGTH_SHORT).show()
            }
            is VoiceCommandParser.Result.ShowAll -> {
                filterMode = false
                Toast.makeText(requireContext(), "Showing all detections", Toast.LENGTH_SHORT).show()
            }
            is VoiceCommandParser.Result.SwitchToFastest -> {
                val fastest = pickFastestPrecision()
                if (fastest != null) {
                    switchPrecision(fastest)
                    Toast.makeText(requireContext(),
                        "Fastest so far: ${fastest.displayName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(),
                        "Not enough data yet — run each precision a bit first",
                        Toast.LENGTH_LONG).show()
                }
            }
            is VoiceCommandParser.Result.ResetStats -> {
                MetricsRegistry.resetAll()
                Toast.makeText(requireContext(), "Benchmark stats reset", Toast.LENGTH_SHORT).show()
            }
            is VoiceCommandParser.Result.Unsupported -> {
                Toast.makeText(requireContext(), "Unsupported: ${result.reason}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Speak a short phrase via TTS, queuing if a previous utterance is still playing. */
    private fun speakAsync(text: String) {
        if (!ttsReady) return
        try {
            tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "pa2_${System.nanoTime()}")
        } catch (_: Throwable) { /* ignore */ }
    }

    /**
     * Returns the precision whose rolling FPS is the highest among precisions
     * that have actually been exercised (≥ 30 frames). null if no precision
     * has enough samples yet.
     */
    private fun pickFastestPrecision(): Precision? {
        val candidates = Precision.values().mapNotNull { p ->
            val m = MetricsRegistry.forPrecision(p)
            if (m.frames >= 30L) p to m.rollingFps() else null
        }
        return candidates.maxByOrNull { it.second }?.first
    }

    /**
     * Save the most recently processed frame, with bounding-box overlays drawn on top,
     * to the device gallery via MediaStore. Called by the "screenshot" voice command.
     */
    private fun takeScreenshot() {
        val argbSnap = lastFrameArgb
        if (argbSnap == null) {
            Toast.makeText(requireContext(), "No frame yet", Toast.LENGTH_SHORT).show()
            return
        }
        // Reconstruct a 640×640 Bitmap from the ARGB snapshot.
        val src = android.graphics.Bitmap.createBitmap(argbSnap, 640, 640,
            android.graphics.Bitmap.Config.ARGB_8888)
        val dets = lastFrameDetections
        val target = vm.target.value ?: SharedViewModel.TargetState.NONE

        // Draw overlay onto a copy of the frame.
        val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        val w = out.width.toFloat(); val h = out.height.toFloat()
        val box = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 6f; isAntiAlias = true
        }
        val tgt = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 10f; isAntiAlias = true
            color = android.graphics.Color.parseColor("#00E676")
        }
        val txt = android.graphics.Paint().apply {
            textSize = 28f; isAntiAlias = true; isFakeBoldText = true
        }
        for (d in dets) {
            val isTarget = d.classId == target.classId
            if (filterMode && !isTarget) continue
            val color = if (isTarget) android.graphics.Color.parseColor("#00E676")
            else android.graphics.Color.parseColor("#FFEA00")
            box.color = color
            txt.color = color
            val r = android.graphics.RectF(d.box.left*w, d.box.top*h, d.box.right*w, d.box.bottom*h)
            canvas.drawRect(r, if (isTarget) tgt else box)
            canvas.drawText("${d.classLabel} ${(d.score*100).toInt()}%",
                r.left, (r.top - 6f).coerceAtLeast(28f), txt)
        }

        // Save to MediaStore Pictures/PA2.
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val name = "pa2_$ts.jpg"
        val resolver = requireContext().contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/PA2")
        }
        try {
            val uri = resolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            )
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { os ->
                    out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, os)
                }
                Toast.makeText(requireContext(),
                    "Saved Pictures/PA2/$name", Toast.LENGTH_LONG).show()
                speakAsync("Saved")
            }
        } catch (e: Exception) {
            Log.e(TAG, "screenshot save failed", e)
            Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateTargetLabel(label: String) {
        b.tvTarget.text = label
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { detector?.close() } catch (_: Exception) {}
        speech.stopContinuous()
        speech.stop()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        cameraExecutor.shutdown()
        detectExecutor.shutdown()
        _b = null
    }
}
