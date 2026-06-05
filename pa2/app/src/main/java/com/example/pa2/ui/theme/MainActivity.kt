package com.example.pa2.ui.theme

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.pa2.R
import com.example.pa2.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: SharedViewModel by viewModels()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cam = grants[Manifest.permission.CAMERA] ?: false
        val mic = grants[Manifest.permission.RECORD_AUDIO] ?: false
        if (!cam) viewModel.setStatus("Camera permission denied — app cannot run")
        else if (!mic) viewModel.setStatus("Microphone permission denied — voice disabled")
        else viewModel.setStatus("Permissions granted")
        // Refresh the currently visible fragment so it can react.
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current is LiveFragment) current.onPermissionsResult(cam, mic)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            replaceFragment(LiveFragment())
        }

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val frag: Fragment = when (tab.position) {
                    0 -> LiveFragment()
                    1 -> DashboardFragment()
                    else -> ExportFragment()
                }
                replaceFragment(frag)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        ensurePermissions()
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.RECORD_AUDIO
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray())
    }
}
