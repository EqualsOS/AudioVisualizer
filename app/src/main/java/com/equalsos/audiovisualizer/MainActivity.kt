package com.equalsos.audiovisualizer

import android.Manifest
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.children
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton // Added Import
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    // --- UI Views ---
    private lateinit var btnToggleService: MaterialButton // Changed type to MaterialButton
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvOrientationValue: TextView
    private lateinit var tvCameraLabel: TextView
    private lateinit var tvNavbarLabel: TextView
    private lateinit var ivCameraArrow: ImageView
    private lateinit var ivNavbarArrow: ImageView
    private lateinit var btnAuto: Button
    private lateinit var tvVisualizerStatus: TextView

    // Color Picker UI
    private lateinit var colorPreviewBox: View
    private lateinit var etHexCode: EditText
    private lateinit var tvColorLabel: TextView
    private var currentSelectedColor: Int = Color.parseColor("#26a269")

    // Mirrored Switches
    private lateinit var switchMirrorVert: SwitchMaterial
    private lateinit var switchMirrorHoriz: SwitchMaterial
    private var isMirrorVert = false
    private var isMirrorHoriz = false

    // --- Diagnostic Views ---
    private lateinit var tvCurrentMode: TextView
    private lateinit var tvExpectedPosition: TextView
    private lateinit var tvActualPosition: TextView

    // --- State ---
    private var hasAudioPermission = false
    private var hasOverlayPermission = false
    private var isAutoMode = true
    private var currentMode = "AUTO"
    private var actualPosition = "STOPPED"
    private var visualizerStatus = "STOPPED"

    private lateinit var orientationEventListener: OrientationEventListener
    private var lastRotation: Int = -1
    private val handler = Handler(Looper.getMainLooper())

    // --- Broadcast Receivers ---
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VisualizerService.ACTION_SERVICE_STOPPED) {
                actualPosition = "STOPPED"
                visualizerStatus = "STOPPED"
                updateUI()
            }
        }
    }

    private val positionUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VisualizerService.ACTION_POSITION_UPDATED) {
                actualPosition = intent.getStringExtra("POSITION") ?: "UNKNOWN"
                updateDiagnosticLabels()
            }
        }
    }

    private val statusUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == VisualizerService.ACTION_STATUS_UPDATED) {
                visualizerStatus = intent.getStringExtra("STATUS") ?: "UNKNOWN"
                updateUI()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggleService = findViewById(R.id.btnToggleService)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvOrientationValue = findViewById(R.id.tvOrientationValue)
        tvCameraLabel = findViewById(R.id.tv_camera_label)
        tvNavbarLabel = findViewById(R.id.tv_navbar_label)
        ivCameraArrow = findViewById(R.id.iv_camera_arrow)
        ivNavbarArrow = findViewById(R.id.iv_navbar_arrow)
        btnAuto = findViewById(R.id.btn_pos_auto)
        tvVisualizerStatus = findViewById(R.id.tvVisualizerStatus)

        colorPreviewBox = findViewById(R.id.color_preview_box)
        etHexCode = findViewById(R.id.et_hex_code)
        tvColorLabel = findViewById(R.id.tv_color_label)

        switchMirrorVert = findViewById(R.id.switch_mirror_vert)
        switchMirrorHoriz = findViewById(R.id.switch_mirror_horiz)

        tvCurrentMode = findViewById(R.id.tvCurrentMode)
        tvExpectedPosition = findViewById(R.id.tvExpectedPosition)
        tvActualPosition = findViewById(R.id.tvActualPosition)

        btnToggleService.setOnClickListener {
            toggleService()
        }

        findViewById<Button>(R.id.btn_pos_top).setOnClickListener { setManualPosition("TOP") }
        findViewById<Button>(R.id.btn_pos_bottom).setOnClickListener { setManualPosition("BOTTOM") }
        findViewById<Button>(R.id.btn_pos_left).setOnClickListener { setManualPosition("LEFT") }
        findViewById<Button>(R.id.btn_pos_right).setOnClickListener { setManualPosition("RIGHT") }
        btnAuto.setOnClickListener { setAutoMode() }

        val colorClickListener = View.OnClickListener { showColorPickerDialog() }
        colorPreviewBox.setOnClickListener(colorClickListener)
        tvColorLabel.setOnClickListener(colorClickListener)
        setupHexCodeListener()

        switchMirrorVert.setOnCheckedChangeListener { _, isChecked ->
            isMirrorVert = isChecked
            sendMirroredCommand()
        }
        switchMirrorHoriz.setOnCheckedChangeListener { _, isChecked ->
            isMirrorHoriz = isChecked
            sendMirroredCommand()
        }

        val serviceIntentFilter = IntentFilter(VisualizerService.ACTION_SERVICE_STOPPED)
        LocalBroadcastManager.getInstance(this).registerReceiver(serviceStateReceiver, serviceIntentFilter)

        val positionIntentFilter = IntentFilter(VisualizerService.ACTION_POSITION_UPDATED)
        LocalBroadcastManager.getInstance(this).registerReceiver(positionUpdateReceiver, positionIntentFilter)

        val statusIntentFilter = IntentFilter(VisualizerService.ACTION_STATUS_UPDATED)
        LocalBroadcastManager.getInstance(this).registerReceiver(statusUpdateReceiver, statusIntentFilter)

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
        updateColorUI()
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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusUpdateReceiver)
    }

    private fun onRotationChanged(rotation: Int) {
        updateOrientationUI(rotation)
        updateDiagnosticLabels()

        if (VisualizerService.isRunning) {
            handler.postDelayed({
                toggleService()
                handler.postDelayed({
                    toggleService()
                }, 50)
            }, 50)
        }
    }

    private fun updateOrientationUI(rotation: Int) {
        val orientationText = when (rotation) {
            Surface.ROTATION_0 -> "PORTRAIT"
            Surface.ROTATION_90 -> "LANDSCAPE (LEFT)"
            Surface.ROTATION_180 -> "PORTRAIT (UPSIDE DOWN)"
            Surface.ROTATION_270 -> "LANDSCAPE (RIGHT)"
            else -> "UNKNOWN"
        }

        val fullText = "Orientation: $orientationText"
        val spannable = SpannableString(fullText)
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            13, // Start of value
            fullText.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvOrientationValue.text = spannable

        when (rotation) {
            Surface.ROTATION_0 -> {
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_up)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_down)
            }
            Surface.ROTATION_90 -> {
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_left)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_right)
            }
            Surface.ROTATION_180 -> {
                ivCameraArrow.setImageResource(R.drawable.ic_arrow_down)
                ivNavbarArrow.setImageResource(R.drawable.ic_arrow_up)
            }
            Surface.ROTATION_270 -> {
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

    private fun updateUI() {
        if (hasAudioPermission && hasOverlayPermission) {
            tvPermissionStatus.text = "All permissions granted."
            btnToggleService.isEnabled = true
        } else {
            var status = "Permissions required:\n"
            if (!hasAudioPermission) status += "- Record Audio\n"
            if (!hasOverlayPermission) status += "- Draw Over Other Apps\n"
            tvPermissionStatus.text = status
            btnToggleService.isEnabled = false
        }

        if (VisualizerService.isRunning) {
            btnToggleService.text = "STOP VISUALIZER"
            // FIX: Ensure setIconResource is called on MaterialButton
            btnToggleService.setIconResource(R.drawable.ic_stop_24)
        } else {
            btnToggleService.text = "START VISUALIZER"
            btnToggleService.setIconResource(R.drawable.ic_play_arrow_24)
        }

        tvVisualizerStatus.text = "Visualizer Status: $visualizerStatus"
        updateDiagnosticLabels()
    }

    private fun updateDiagnosticLabels() {
        val expected: String = if (isAutoMode) {
            getAutoPosition(lastRotation)
        } else {
            currentMode
        }

        tvCurrentMode.text = "CURRENT MODE: $currentMode"
        tvExpectedPosition.text = "Expected Position: $expected"
        tvActualPosition.text = "Actual Position: $actualPosition"
    }

    private fun toggleService() {
        val intent = Intent(this, VisualizerService::class.java)
        if (VisualizerService.isRunning) {
            stopService(intent)
            actualPosition = "STOPPED"
            visualizerStatus = "STOPPED"
        } else {
            val startPos = if (isAutoMode) {
                val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                getAutoPosition(display.rotation)
            } else {
                currentMode
            }

            actualPosition = "STARTING..."
            visualizerStatus = "STARTING..."
            updateAutoButtonUI()

            intent.putExtra("POSITION", startPos)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            handler.postDelayed({
                sendColorCommand(currentSelectedColor)
                sendMirroredCommand()
            }, 100)
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
        currentMode = position
        updateAutoButtonUI()
        updateDiagnosticLabels()
        sendPositionCommand(position)
    }

    private fun setAutoMode() {
        isAutoMode = true
        currentMode = "AUTO"
        updateAutoButtonUI()
        updateDiagnosticLabels()
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        sendPositionCommand(getAutoPosition(display.rotation))
    }

    private fun updateAutoButtonUI() {
        btnAuto.isEnabled = !isAutoMode
    }

    private fun sendPositionCommand(position: String) {
        if (!VisualizerService.isRunning) {
            return
        }
        val intent = Intent(VisualizerService.ACTION_UPDATE_POSITION)
        intent.putExtra("POSITION", position)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendColorCommand(color: Int) {
        if (!VisualizerService.isRunning) return

        val intent = Intent(VisualizerService.ACTION_UPDATE_COLOR)
        intent.putExtra("COLOR", color)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendMirroredCommand() {
        if (!VisualizerService.isRunning) return
        val intent = Intent(VisualizerService.ACTION_UPDATE_MIRRORED)
        intent.putExtra("IS_MIRROR_VERT", isMirrorVert)
        intent.putExtra("IS_MIRROR_HORIZ", isMirrorHoriz)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateColorUI() {
        val hexColor = String.format("#%06X", (0xFFFFFF and currentSelectedColor))
        val drawable = colorPreviewBox.background.mutate()
        if (drawable is GradientDrawable) {
            drawable.setColor(currentSelectedColor)
        } else {
            val wrappedDrawable = DrawableCompat.wrap(drawable)
            DrawableCompat.setTint(wrappedDrawable, currentSelectedColor)
            colorPreviewBox.background = wrappedDrawable
        }

        etHexCode.removeTextChangedListener(hexTextWatcher)
        etHexCode.setText(hexColor)
        etHexCode.addTextChangedListener(hexTextWatcher)
    }

    private fun setupHexCodeListener() {
        etHexCode.addTextChangedListener(hexTextWatcher)
        etHexCode.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val hex = etHexCode.text.toString()
                if (parseAndSetColor(hex, true)) {
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
            val hex = s.toString()
            parseAndSetColor(hex, false)
        }
    }

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
            (colorPreviewBox.background as? GradientDrawable)?.setColor(Color.GRAY)
            false
        }
    }

    private fun showColorPickerDialog() {
        val dialog = Dialog(this, R.style.ColorPickerDialogTheme)
        dialog.setContentView(R.layout.dialog_color_picker)

        val container = dialog.findViewById<LinearLayout>(R.id.color_grid_container)
        val btnSelect = dialog.findViewById<Button>(R.id.btn_dialog_select)
        var dialogSelectedColor = currentSelectedColor

        btnSelect.setBackgroundColor(dialogSelectedColor)

        // Iterate through the 5 rows (LinearLayouts)
        // The container has the included layout as its child, wait, I flattened it in xml
        // so container has rows directly.
        if (container != null) {
            for (i in 0 until container.childCount) {
                val row = container.getChildAt(i) as? LinearLayout
                if (row != null) {
                    for (j in 0 until row.childCount) {
                        val swatch = row.getChildAt(j)
                        swatch.setOnClickListener {
                            val color = (it.background as? ColorDrawable)?.color
                            // If ColorDrawable fails (due to backgroundTint), use backgroundTintList
                            if (color != null) {
                                dialogSelectedColor = color
                                btnSelect.setBackgroundColor(color)
                            } else if (it.backgroundTintList != null) {
                                val tintColor = it.backgroundTintList!!.defaultColor
                                dialogSelectedColor = tintColor
                                btnSelect.setBackgroundColor(tintColor)
                            }
                        }
                    }
                }
            }
        }

        dialog.findViewById<Button>(R.id.btn_dialog_cancel).setOnClickListener {
            dialog.dismiss()
        }

        btnSelect.setOnClickListener {
            currentSelectedColor = dialogSelectedColor
            updateColorUI()
            sendColorCommand(currentSelectedColor)
            dialog.dismiss()
        }

        dialog.show()
    }
}