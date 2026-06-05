package com.example.pa2.ui.theme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.pa2.databinding.FragmentDashboardBinding
import com.example.pa2.detector.Precision
import com.example.pa2.metrics.MetricsRegistry
import com.example.pa2.metrics.PrecisionMetrics
import com.example.pa2.metrics.SystemStats
import java.util.Locale

class DashboardFragment : Fragment() {

    private var _b: FragmentDashboardBinding? = null
    private val b get() = _b!!
    private lateinit var systemStats: SystemStats

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateAll()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, b: Bundle?): View {
        _b = FragmentDashboardBinding.inflate(inflater, container, false)
        return this.b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        systemStats = SystemStats(requireContext())
        b.btnReset.setOnClickListener {
            MetricsRegistry.resetAll()
            updateAll()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun updateAll() {
        if (_b == null) return
        bindRow(b.fp32Frames, b.fp32Latency, b.fp32Fps, b.fp32Success, b.fp32Conf, b.fp32Size,
            b.fp32P95, MetricsRegistry.forPrecision(Precision.FP32))
        bindRow(b.fp16Frames, b.fp16Latency, b.fp16Fps, b.fp16Success, b.fp16Conf, b.fp16Size,
            b.fp16P95, MetricsRegistry.forPrecision(Precision.FP16))
        bindRow(b.int8Frames, b.int8Latency, b.int8Fps, b.int8Success, b.int8Conf, b.int8Size,
            b.int8P95, MetricsRegistry.forPrecision(Precision.INT8))

        // System card
        val cur = systemStats.batteryCurrentNow()
        b.sysBattery.text = if (cur == Long.MIN_VALUE) "n/a" else cur.toString()
        val lvl = systemStats.batteryLevelPct()
        b.sysBatteryLevel.text = if (lvl < 0) "n/a" else "$lvl%"
        b.sysMem.text = String.format(Locale.US, "%.1f MB", systemStats.appMemoryBytes() / 1024.0 / 1024.0)
        b.sysThermal.text = systemStats.thermalStatus()
    }

    private fun bindRow(
        frames: android.widget.TextView,
        latency: android.widget.TextView,
        fps: android.widget.TextView,
        success: android.widget.TextView,
        conf: android.widget.TextView,
        size: android.widget.TextView,
        p95: android.widget.TextView,
        m: PrecisionMetrics
    ) {
        frames.text = m.frames.toString()
        latency.text = String.format(Locale.US, "%.2f ms", m.avgLatencyMs())
        fps.text = String.format(Locale.US, "%.2f", m.rollingFps())
        success.text = String.format(Locale.US, "%.0f%%", m.targetSuccessRate() * 100)
        conf.text = String.format(Locale.US, "%.0f%%", m.meanConfidence() * 100)
        size.text = if (m.modelSizeBytes > 0)
            String.format(Locale.US, "%.2f MB", m.modelSizeBytes / 1024.0 / 1024.0)
        else "—"
        p95.text = String.format(Locale.US, "%.1f ms", m.p95LatencyMs())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
