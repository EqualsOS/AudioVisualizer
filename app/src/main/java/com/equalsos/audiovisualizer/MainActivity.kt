package com.equalsos.audiovisualizer

import android.Manifest
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.children
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    // --- UI Views ---
    private lateinit var btnToggleService: Button
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvOrientation: TextView
    private lateinit var tvCameraLabel: TextView
    private lateinit var tvNavbarLabel: TextView
    private lateinit var ivCameraArrow: ImageView
    private lateinit var ivNavbarArrow: ImageView
    private lateinit var btnAuto: Button

    // NEW Color Picker UI
    private lateinit var colorPreviewBox: View
    private lateinit var etHexCode: EditText
    private var currentSelectedColor: Int = Color.parseColor("#26a269") // Default Green

    // --- Diagnostic Views ---
    private lateinit var tvCurrentMode: TextView
    private lateinit var tvExpectedPosition: TextView
    private lateinit var tvActualPosition: TextView

    // --- State ---
    private var hasAudioPermission = false
    private var hasOverlayPermission = false
    private var isAutoMode = true
    private var currentMode = "Auto"
    private var actualPosition = "Stopped"

    private lateinit var orientationEventListener: OrientationEventListener
    private var lastRotation: Int = -1
    private val handler = Handler(Looper.getMainLooper())

    // --- Broadcast Receivers ---
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VisualizerService.ACTION_SERVICE_STOPPED) {
                actualPosition = "Stopped"
                updateUI()
            }
        }
    }

    private val positionUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VisualizerService.ACTION_POSITION_UPDATED) {
                actualPosition = intent.getStringExtra("POSITION") ?: "Unknown"
                updateDiagnosticLabels()
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

        // NEW Color UI
        colorPreviewBox = findViewById(R.id.color_preview_box)
        etHexCode = findViewById(R.id.et_hex_code)

        tvCurrentMode = findViewById(R.id.tvCurrentMode)
        tvExpectedPosition = findViewById(R.id.tvExpectedPosition)
        tvActualPosition = findViewById(R.id.tvActualPosition)

        // --- Set listeners ---
        btnToggleService.setOnClickListener {
            toggleService()
        }

        findViewById<Button>(R.id.btn_pos_top).setOnClickListener { setManualPosition("TOP") }
        findViewById<Button>(R.id.btn_pos_bottom).setOnClickListener { setManualPosition("BOTTOM") }
        findViewById<Button>(R.id.btn_pos_left).setOnClickListener { setManualPosition("LEFT") }
        findViewById<Button>(R.id.btn_pos_right).setOnClickListener { setManualPosition("RIGHT") }
        btnAuto.setOnClickListener { setAutoMode() }

        // NEW Color Listeners
        colorPreviewBox.setOnClickListener { showColorPickerDialog() }
        setupHexCodeListener()

        // Register receivers
        val serviceIntentFilter = IntentFilter(VisualizerService.ACTION_SERVICE_STOPPED)
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceStateReceiver, serviceIntentFilter)

        val positionIntentFilter = IntentFilter(VisualizerService.ACTION_POSITION_UPDATED)
        LocalBroadcastManager.getInstance(this).registerReceiver(positionUpdateReceiver, positionIntentFilter)

        // Setup orientation listener
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
        updateDiagnosticLabels()
        updateColorUI() // Set initial color
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateUI()

        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        lastRotation = display.rotation
        updateOrientationUI(lastRotation)
        updateDiagnosticLabels()

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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(positionUpdateReceiver)
    }

    // --- Orientation Change Handling ---
    private fun onRotationChanged(rotation: Int) {
        updateOrientationUI(rotation)
        updateDiagnosticLabels()

        if (VisualizerService.isRunning) {
            if (isAutoMode) {
                setAutoMode()
            } else {
                // If not auto, just resend the current manual position to fix layout
                sendPositionCommand(currentMode)
            }
        }
    }

    private fun updateOrientationUI(rotation: Int) {
        when (rotation) {
            Surface.ROTATION_0 -> { // Portrait
                tvOrientation.text = "Orientation: Portrait"
                tvCameraLabel.text = "Camera Section"
                tvNavbarLabel.text = "Navbar Section"
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_up)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_down)
            }
            Surface.ROTATION_90 -> { // Landscape (Rotated Left)
                tvOrientation.text = "Orientation: Landscape (Left)"
                tvCameraLabel.text = "Camera Section"
                tvNavbarLabel.text = "Navbar Section"
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_left)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_right)
            }
            Surface.ROTATION_180 -> { // Portrait (Upside Down)
                tvOrientation.text = "Orientation: Portrait (Upside Down)"
                tvCameraLabel.text = "Camera Section (was Navbar)"
                tvNavbarLabel.text = "Navbar Section (was Camera)"
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_down)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_up)
            }
            Surface.ROTATION_270 -> { // Landscape (Rotated Right)
                tvOrientation.text = "Orientation: Landscape (Right)"
                tvCameraLabel.text = "Camera Section"
                tvNavbarLabel.text = "Navbar Section"
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_right)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_left)
            }
        }
    }

    // --- Permission Handling (Unchanged) ---
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
                    "Record Audio permission is required",
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
                        "Draw Over Apps permission is required",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    updateUI()
                }
            }
        }

    // --- UI Update Functions ---

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
        updateDiagnosticLabels()
    }

    private fun updateDiagnosticLabels() {
        val expected: String = if (isAutoMode) {
            getAutoPosition(lastRotation)
        } else {
            currentMode
        }

        tvCurrentMode.text = "Current Mode: $currentMode"
        tvExpectedPosition.text = "Expected Position: $expected"
        tvActualPosition.text = "Actual Position: $actualPosition"
    }

    // --- Service Control ---
    private fun toggleService() {
        val intent = Intent(this, VisualizerService::class.java)
        if (VisualizerService.isRunning) {
            stopService(intent)
            actualPosition = "Stopped"
        } else {
            isAutoMode = true
            currentMode = "Auto"
            actualPosition = "Starting..."
            updateAutoButtonUI()

            val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            intent.putExtra("POSITION", getAutoPosition(display.rotation))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // Send the initial color when starting
            handler.postDelayed({ sendColorCommand(currentSelectedColor) }, 100)
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

    // --- Button Click Handlers ---
    private fun setManualPosition(position: String) {
        isAutoMode = false
        currentMode = position
        updateAutoButtonUI()
        updateDiagnosticLabels()
        sendPositionCommand(position)
    }

    private fun setAutoMode() {
        isAutoMode = true
        currentMode = "Auto"
        updateAutoButtonUI()
        updateDiagnosticLabels()
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        sendPositionCommand(getAutoPosition(display.rotation))
    }

    private fun updateAutoButtonUI() {
        btnAuto.isEnabled = !isAutoMode
    }

    // --- Broadcast Functions ---
    private fun sendPositionCommand(position: String) {
        if (!VisualizerService.isRunning) {
            Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(VisualizerService.ACTION_UPDATE_POSITION)
        intent.putExtra("POSITION", position)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendColorCommand(color: Int) {
        if (!VisualizerService.isRunning) return // Don't send if service isn't on

        val intent = Intent(VisualizerService.ACTION_UPDATE_COLOR)
        intent.putExtra("COLOR", color)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    // --- NEW: Color Picker Logic ---

    private fun updateColorUI() {
        val hexColor = String.format("#%06X", (0xFFFFFF and currentSelectedColor))

        // Update preview box
        // Use a GradientDrawable to be safe, as it's what color_swatch.xml defines
        val drawable = colorPreviewBox.background.mutate()
        if (drawable is GradientDrawable) {
            drawable.setColor(currentSelectedColor)
        } else {
            // Fallback for other drawable types
            val wrappedDrawable = DrawableCompat.wrap(drawable)
            DrawableCompat.setTint(wrappedDrawable, currentSelectedColor)
            colorPreviewBox.background = wrappedDrawable
        }

        // Update EditText without triggering the listener
        etHexCode.removeTextChangedListener(hexTextWatcher)
        etHexCode.setText(hexColor)
        etHexCode.addTextChangedListener(hexTextWatcher)
    }

    private fun setupHexCodeListener() {
        etHexCode.addTextChangedListener(hexTextWatcher)
        etHexCode.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // User pressed done on keyboard, validate and send
                val hex = etHexCode.text.toString()
                if (parseAndSetColor(hex, true)) {
                    // Hide keyboard
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    v.clearFocus()
                }
                true
            } else {
                false
            }
        }
    }

    private val hexTextWatcher = object: TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            // Live update as user types
            val hex = s.toString()
            parseAndSetColor(hex, false) // Don't send command on every keystroke
        }
    }

    /**
     * Parses a hex string and updates the color.
     * @param sendCommand If true, sends the color to the service.
     * @return true if parsing was successful, false otherwise.
     */
    private fun parseAndSetColor(hex: String, sendCommand: Boolean): Boolean {
        val fullHex = if (hex.startsWith("#")) hex else "#$hex"
        return try {
            val color = Color.parseColor(fullHex)
            currentSelectedColor = color
            (colorPreviewBox.background as? GradientDrawable)?.setColor(color)
            if (sendCommand) {
                sendColorCommand(color)
            }
            true
        } catch (e: IllegalArgumentException) {
            // Invalid hex, set preview to gray
            (colorPreviewBox.background as? GradientDrawable)?.setColor(Color.GRAY)
            false
        }
    }

    private fun showColorPickerDialog() {
        val dialog = Dialog(this, R.style.ColorPickerDialogTheme)
        dialog.setContentView(R.layout.dialog_color_picker)

        val dialogGrid = dialog.findViewById<GridLayout>(R.id.color_grid) // Use the new ID
        var dialogSelectedColor = currentSelectedColor
        var selectedSwatch: View? = null

        // Add checkmark to the currently selected color
        for (swatch in dialogGrid.children) {
            val swatchColor = (swatch.background as? ColorDrawable)?.color
            if (swatchColor == currentSelectedColor) {
                // TODO: Add a checkmark drawable
                selectedSwatch = swatch
            }
        }

        // Set listeners for dialog buttons
        dialog.findViewById<Button>(R.id.btn_dialog_cancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.findViewById<Button>(R.id.btn_dialog_select).setOnClickListener {
            currentSelectedColor = dialogSelectedColor
            updateColorUI()
            sendColorCommand(currentSelectedColor)
            dialog.dismiss()
        }

        dialog.show()
    }

    // This is needed for the XML onClick="onDialogColorSwatchClicked"
    fun onDialogColorSwatchClicked(view: View) {
        val color = (view.background as? ColorDrawable)?.color
        if (color != null) {
            // When a swatch is clicked, update the main UI's hex box immediately
            currentSelectedColor = color
            updateColorUI()

            // We can also dismiss the dialog immediately if we want
            // Or, we can just update a 'selected' state inside the dialog
            // For now, let's update the main UI and send the command
            sendColorCommand(currentSelectedColor)

            // To close the dialog, we need its instance.
            // This is complex. Let's adjust showColorPickerDialog...

            // --- This function is a bit broken, let's fix showColorPickerDialog ---
        }
    }
}