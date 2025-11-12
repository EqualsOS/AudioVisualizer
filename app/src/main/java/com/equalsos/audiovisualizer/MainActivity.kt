package com.equalsos.audiovisualizer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleService: Button
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvOrientation: TextView
    private lateinit var tvCameraLabel: TextView
    private lateinit var tvNavbarLabel: TextView
    private lateinit var ivCameraArrow: ImageView
    private lateinit var ivNavbarArrow: ImageView
    private lateinit var btnAuto: Button
    private lateinit var colorGrid: GridLayout // NEW: For color picker

    private var hasAudioPermission = false
    private var hasOverlayPermission = false
    private var isAutoMode = true

    private lateinit var orientationEventListener: OrientationEventListener
    private var lastRotation: Int = -1

    private val handler = Handler(Looper.getMainLooper())

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VisualizerService.ACTION_SERVICE_STOPPED) {
                updateUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- Find all views ---
        btnToggleService = findViewById(R.id.btnToggleService)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvOrientation = findViewById(R.id.tvOrientation)
        tvCameraLabel = findViewById(R.id.tv_camera_label)
        tvNavbarLabel = findViewById(R.id.tv_navbar_label)
        ivCameraArrow = findViewById(R.id.iv_camera_arrow)
        ivNavbarArrow = findViewById(R.id.iv_navbar_arrow)
        btnAuto = findViewById(R.id.btn_pos_auto)
        colorGrid = findViewById(R.id.color_grid) // NEW: Find the grid

        // --- Set listeners ---
        btnToggleService.setOnClickListener { toggleService() }

        findViewById<Button>(R.id.btn_pos_top).setOnClickListener { setManualPosition("TOP") }
        findViewById<Button>(R.id.btn_pos_bottom).setOnClickListener { setManualPosition("BOTTOM") }
        findViewById<Button>(R.id.btn_pos_left).setOnClickListener { setManualPosition("LEFT") }
        findViewById<Button>(R.id.btn_pos_right).setOnClickListener { setManualPosition("RIGHT") }
        btnAuto.setOnClickListener { setAutoMode() }

        // NEW: Set click listeners for all color swatches
        setupColorGridListeners()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            serviceStateReceiver,
            IntentFilter(VisualizerService.ACTION_SERVICE_STOPPED)
        )

        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                val currentRotation = display.rotation
                if (currentRotation != lastRotation) {
                    lastRotation = currentRotation
                    onRotationChanged(currentRotation)
                }
            }
        }

        updateAutoButtonUI()
    }

    // NEW: Click handler for all color swatches
    fun onColorSwatchClicked(view: View) {
        // Get the color from the swatch's backgroundTint
        val color = view.backgroundTintList?.defaultColor
        if (color != null) {
            sendColorCommand(color)
        }
    }

    // This function is now just for setup, the click is handled by the XML's onClick
    private fun setupColorGridListeners() {
        // The onClick is now handled by `android:onClick="onColorSwatchClicked"`
        // in color_picker_grid.xml, so this function is simpler.
        // We could add selection visual logic here in the future.
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateUI()

        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        lastRotation = display.rotation
        updateOrientationUI(lastRotation)

        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
    }

    override fun onPause() {
        super.onPause()
        orientationEventListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serviceStateReceiver)
    }

    private fun onRotationChanged(rotation: Int) {
        updateOrientationUI(rotation)

        if (VisualizerService.isRunning) {
            handler.postDelayed({
                toggleService() // Stop
                handler.postDelayed({
                    toggleService() // Start
                }, 50)
            }, 50)
        }
    }

    private fun updateOrientationUI(rotation: Int) {
        when (rotation) {
            Surface.ROTATION_0 -> {
                tvOrientation.text = "Orientation: Portrait"
                tvCameraLabel.text = "Camera Section"
                tvNavbarLabel.text = "Navbar Section"
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_up)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_down)
            }
            Surface.ROTATION_90 -> {
                tvOrientation.text = "Orientation: Landscape (Left)"
                tvCameraLabel.text = "Camera Section"
                tvNavbarLabel.text = "Navbar Section"
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_left)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_right)
            }
            Surface.ROTATION_180 -> {
                tvOrientation.text = "Orientation: Portrait (Upside Down)"
                tvCameraLabel.text = "Camera Section (was Navbar)"
                tvNavbarLabel.text = "Navbar Section (was Camera)"
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_down)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_up)
            }
            Surface.ROTATION_270 -> {
                tvOrientation.text = "Orientation: Landscape (Right)"
                tvCameraLabel.text = "Camera Section"
                tvNavbarLabel.text = "Navbar Section"
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_right)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_left)
            }
        }
    }

    private fun checkPermissions() {
        hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

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

        btnToggleService.text = if (VisualizerService.isRunning) {
            "Stop Visualizer"
        } else {
            "Start Visualizer"
        }
    }

    private fun toggleService() {
        val intent = Intent(this, VisualizerService::class.java)
        if (VisualizerService.isRunning) {
            stopService(intent)
        } else {
            isAutoMode = true
            updateAutoButtonUI()

            val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            intent.putExtra("POSITION", getAutoPosition(display.rotation))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        btnToggleService.postDelayed({ updateUI() }, 100)
    }

    private fun getAutoPosition(rotation: Int): String {
        return when (rotation) {
            Surface.ROTATION_0 -> "BOTTOM"
            Surface.ROTATION_90 -> "RIGHT"
            Surface.ROTATION_270 -> "LEFT"
            Surface.ROTATION_180 -> "TOP"
            else -> "BOTTOM"
        }
    }

    private fun setManualPosition(position: String) {
        isAutoMode = false
        updateAutoButtonUI()
        sendPositionCommand(position)
    }

    private fun setAutoMode() {
        isAutoMode = true
        updateAutoButtonUI()
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        sendPositionCommand(getAutoPosition(display.rotation))
    }

    private fun updateAutoButtonUI() {
        btnAuto.isEnabled = true
    }

    private fun sendPositionCommand(position: String) {
        if (!VisualizerService.isRunning) {
            Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(VisualizerService.ACTION_UPDATE_POSITION)
        intent.putExtra("POSITION", position)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // NEW: Send color choice to the service
    private fun sendColorCommand(color: Int) {
        if (!VisualizerService.isRunning) {
            Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(VisualizerService.ACTION_UPDATE_COLOR)
        intent.putExtra("COLOR", color)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // --- Permission Launchers ---
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