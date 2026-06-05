package com.example.pa2.ui.theme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.pa2.databinding.FragmentExportBinding
import com.example.pa2.export.LoggerHolder
import com.example.pa2.metrics.MetricsRegistry
import java.util.Locale

class ExportFragment : Fragment() {

    private var _b: FragmentExportBinding? = null
    private val b get() = _b!!
    private val vm: SharedViewModel by activityViewModels()

    private val exports = mutableListOf<String>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshSession()
            handler.postDelayed(this, 750L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, b: Bundle?): View {
        _b = FragmentExportBinding.inflate(inflater, container, false)
        return this.b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.btnCsv.setOnClickListener { doExport(csv = true) }
        b.btnJson.setOnClickListener { doExport(csv = false) }
        b.btnClear.setOnClickListener {
            LoggerHolder.logger.clear()
            refreshSession()
            Toast.makeText(requireContext(), "Session log cleared", Toast.LENGTH_SHORT).show()
        }
        vm.precision.observe(viewLifecycleOwner) { p -> b.tvSelectedModel.text = p.displayName }
        refreshSession()
    }

    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause()  { super.onPause();  handler.removeCallbacks(tick) }

    private fun refreshSession() {
        if (_b == null) return
        b.tvFramesLogged.text = LoggerHolder.logger.size().toString()
        // Aggregate target success across precisions.
        val all = MetricsRegistry.all()
        val totalSet = all.sumOf { it.targetSetFrames }
        val totalHit = all.sumOf { it.targetHitFrames }
        val pct = if (totalSet == 0L) 0f else totalHit.toFloat() * 100f / totalSet
        b.tvTargetSuccess.text = String.format(Locale.US, "%.0f%%", pct)
        b.tvSavedSessions.text = exports.size.toString()
        b.tvLatestPath.text = if (exports.isEmpty()) "No export yet" else exports.last()
        // History list
        b.layoutHistory.removeAllViews()
        if (exports.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = "No exported sessions yet."
                textSize = 14f
                setPadding(0, 8, 0, 8)
            }
            b.layoutHistory.addView(tv)
        } else {
            for (path in exports.asReversed()) {
                val tv = TextView(requireContext()).apply {
                    text = "• $path"
                    textSize = 14f
                    setPadding(0, 6, 0, 6)
                }
                b.layoutHistory.addView(tv)
            }
        }
    }

    private fun doExport(csv: Boolean) {
        val ctx = requireContext()
        if (LoggerHolder.logger.size() == 0) {
            Toast.makeText(ctx, "No data to export yet — run live detection first", Toast.LENGTH_LONG).show()
            return
        }
        val path = if (csv) LoggerHolder.logger.exportCsv(ctx) else LoggerHolder.logger.exportJson(ctx)
        if (path == null) {
            Toast.makeText(ctx, "Export failed", Toast.LENGTH_LONG).show()
        } else {
            exports += path
            Toast.makeText(ctx, "Saved to $path", Toast.LENGTH_LONG).show()
            refreshSession()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
