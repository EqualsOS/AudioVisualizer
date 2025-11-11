package com.equalsos.audiovisualizer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleService: Button
    private lateinit var tvPermissionStatus: TextView

    private var hasAudioPermission = false
    private var hasOverlayPermission = false

    companion object {
        var isServiceRunning = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggleService = findViewById(R.id.btnToggleService)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)

        btnToggleService.setOnClickListener {
            toggleService()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateUI()
    }

    private fun checkPermissions() {
        // 1. Check for RECORD_AUDIO permission
        hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // 2. Check for SYSTEM_ALERT_WINDOW (overlay) permission
        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true // Pre-Marshmallow versions have this permission by default
        }

        // Request permissions if not granted
        if (!hasAudioPermission) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (!hasOverlayPermission) {
            requestOverlayPermission()
        }
    }

    private fun updateUI() {
        if (hasAudioPermission && hasOverlayPermission) {
            tvPermissionStatus.text = "All permissions granted"
            btnToggleService.isEnabled = true
        } else {
            var status = "Permissions required:\n"
            if (!hasAudioPermission) status += "- Record Audio\n"
            if (!hasOverlayPermission) status += "- Draw Over Other Apps\n"
            tvPermissionStatus.text = status
            btnToggleService.isEnabled = false
        }

        btnToggleService.text = if (isServiceRunning) {
            "Stop Visualizer"
        } else {
            "Start Visualizer"
        }
    }

    private fun toggleService() {
        val intent = Intent(this, VisualizerService::class.java)
        if (isServiceRunning) {
            stopService(intent)
            isServiceRunning = false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isServiceRunning = true
        }
        updateUI()
    }

    // Launcher for RECORD_AUDIO permission
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                hasAudioPermission = true
                if (!hasOverlayPermission) {
                    requestOverlayPermission()
                } else {
                    updateUI()
                }
            } else {
                Toast.makeText(
                    this,
                    "Record Audio permission is required for the visualizer",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // Function and launcher for SYSTEM_ALERT_WINDOW permission
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                hasOverlayPermission = Settings.canDrawOverlays(this)
                if (!hasOverlayPermission) {
                    Toast.makeText(
                        this,
                        "Draw Over Apps permission is required to show the visualizer",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    updateUI()
                }
            }
        }
}