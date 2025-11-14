package com.equalsos.audiovisualizer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class VisualizerService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var visualizer: Visualizer? = null
    private var visualizerView: VisualizerView? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var currentPosition: String = "BOTTOM"
    private var currentStatus: String = "STARTING..."
    private var currentMode: String = "AUTO"

    private var userMirrorVert = false
    private var userMirrorHoriz = false

    // --- Broadcast Receivers ---
    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("POSITION")?.let { position ->
                Log.d(TAG, "Received position command: $position")
                if (currentMode != "AUTO") {
                    updateOverlayPosition(position)
                }
            }
        }
    }

    private val colorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_COLOR) {
                val color = intent.getIntExtra("COLOR", Color.WHITE)
                visualizerView?.setColor(color)
            }
        }
    }

    private val mirroredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_MIRRORED) {
                userMirrorVert = intent.getBooleanExtra("IS_MIRROR_VERT", false)
                userMirrorHoriz = intent.getBooleanExtra("IS_MIRROR_HORIZ", false)
                Log.d(TAG, "Received mirror prefs: V=$userMirrorVert, H=$userMirrorHoriz")
                applyMirrorLogic()
            }
        }
    }

    private val numBarsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_NUM_BARS) {
                val numBars = intent.getIntExtra("NUM_BARS", 45)
                Log.d(TAG, "Received numBars command: $numBars")
                visualizerView?.setNumBars(numBars)
            }
        }
    }

    private val forceInitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FORCE_INIT) {
                Log.d(TAG, "Force initializing visualizer...")
                startVisualizer()
            }
        }
    }

    private val pingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PING) {
                Log.d(TAG, "Received PING, responding with state.")
                broadcastStatus(currentStatus)
                broadcastActualPosition(currentPosition)
            }
        }
    }

    private val modeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SET_MODE) {
                val newMode = intent.getStringExtra("MODE") ?: "AUTO"

                handler.removeCallbacks(updatePositionRunnable)
                Log.d(TAG, "Mode set to: $newMode. Rotation debounce canceled.")

                currentMode = newMode

                if (currentMode == "AUTO") {
                    updatePositionForCurrentOrientation()
                }
            }
        }
    }

    // --- Watchdog and Debouncer Logic ---
    private val handler = Handler(Looper.getMainLooper())
    private var lastFftTime: Long = 0
    private val fftTimeout = 150L
    private val clearBarsRunnable = Runnable {
        if (System.currentTimeMillis() - lastFftTime > fftTimeout) {
            visualizerView?.updateVisualizer(ByteArray(0))
        }
    }

    private val updatePositionRunnable = Runnable {
        updatePositionForCurrentOrientation()
    }


    companion object {
        var isRunning = false
        private const val NOTIFICATION_CHANNEL_ID = "VisualizerServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "VisualizerService"

        const val ACTION_SERVICE_STOPPED = "com.equalsos.audiovisualizer.ACTION_SERVICE_STOPPED"
        const val ACTION_UPDATE_POSITION = "com.equalsos.audiovisualizer.ACTION_UPDATE_POSITION"
        const val ACTION_POSITION_UPDATED = "com.equalsos.audiovisualizer.ACTION_POSITION_UPDATED"
        const val ACTION_UPDATE_COLOR = "com.equalsos.audiovisualizer.ACTION_UPDATE_COLOR"
        const val ACTION_UPDATE_MIRRORED = "com.equalsos.audiovisualizer.ACTION_UPDATE_MIRRORED"
        const val ACTION_UPDATE_NUM_BARS = "com.equalsos.audiovisualizer.ACTION_UPDATE_NUM_BARS"
        const val ACTION_FORCE_INIT = "com.equalsos.audiovisualizer.ACTION_FORCE_INIT"
        const val ACTION_STATUS_UPDATED = "com.equalsos.audiovisualizer.ACTION_STATUS_UPDATED"
        const val ACTION_PING = "com.equalsos.audiovisualizer.ACTION_PING"
        const val ACTION_SET_MODE = "com.equalsos.audiovisualizer.ACTION_SET_MODE"

        const val PREFS_NAME = "AudioVisualizerPrefs"
        const val KEY_NUM_BARS = "numBars"
        const val KEY_COLOR = "color"
        const val KEY_MIRROR_VERT = "mirrorVert"
        const val KEY_MIRROR_HORIZ = "mirrorHoriz"
        const val KEY_MODE = "mode"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        LocalBroadcastManager.getInstance(this).registerReceiver(positionReceiver, IntentFilter(ACTION_UPDATE_POSITION))
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter(ACTION_UPDATE_COLOR))
        LocalBroadcastManager.getInstance(this).registerReceiver(mirroredReceiver, IntentFilter(ACTION_UPDATE_MIRRORED))
        LocalBroadcastManager.getInstance(this).registerReceiver(numBarsReceiver, IntentFilter(ACTION_UPDATE_NUM_BARS))
        LocalBroadcastManager.getInstance(this).registerReceiver(forceInitReceiver, IntentFilter(ACTION_FORCE_INIT))
        LocalBroadcastManager.getInstance(this).registerReceiver(pingReceiver, IntentFilter(ACTION_PING))
        LocalBroadcastManager.getInstance(this).registerReceiver(modeReceiver, IntentFilter(ACTION_SET_MODE))

        isRunning = true
        Log.d(TAG, "onCreate: Service starting...")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged detected.")
        if (currentMode == "AUTO") {
            handler.removeCallbacks(updatePositionRunnable)
            handler.postDelayed(updatePositionRunnable, 200) // 200ms delay
        }
    }

    private fun loadAllPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        currentMode = prefs.getString(KEY_MODE, "AUTO") ?: "AUTO"
        userMirrorVert = prefs.getBoolean(KEY_MIRROR_VERT, false)
        userMirrorHoriz = prefs.getBoolean(KEY_MIRROR_HORIZ, false)
    }

    private fun updatePositionForCurrentOrientation() {
        val display = windowManager.defaultDisplay
        val rotation = display.rotation

        val autoPosition = getAutoPosition(rotation)
        Log.d(TAG, "Auto-updating position for rotation $rotation: $autoPosition")

        updateOverlayPosition(autoPosition)
    }

    private fun getAutoPosition(rotation: Int): String {
        return when (rotation) {
            Surface.ROTATION_0 -> "BOTTOM"
            Surface.ROTATION_90 -> "RIGHT"
            Surface.ROTATION_270 -> "LEFT"
            Surface.ROTATION_180 -> "TOP" // RESTORED ORIGINAL TOP
            else -> "BOTTOM"
        }
    }

    private fun applyMirrorLogic() {
        val baseMirrorHoriz = (currentPosition == "RIGHT" || currentPosition == "TOP")
        val finalIsMirrorHoriz = baseMirrorHoriz xor userMirrorHoriz

        val baseMirrorVert = (currentPosition == "TOP")
        val finalIsMirrorVert = baseMirrorVert xor userMirrorVert

        Log.d(TAG, "Applying mirror logic (H): Pos=$currentPosition, BaseH=$baseMirrorHoriz, UserH=$userMirrorHoriz, FinalH=$finalIsMirrorHoriz")
        Log.d(TAG, "Applying mirror logic (V): Pos=$currentPosition, BaseV=$baseMirrorVert, UserV=$userMirrorVert, FinalV=$finalIsMirrorVert")

        visualizerView?.setMirrored(finalIsMirrorVert, finalIsMirrorHoriz)
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        if (floatingView == null) {
            loadAllPreferences()
            val initialPosition = intent?.getStringExtra("POSITION") ?: "BOTTOM"
            Log.d(TAG, "Service starting with Mode: $currentMode, Position: $initialPosition")

            showOverlay(initialPosition)

            if (currentMode == "AUTO") {
                updatePositionForCurrentOrientation()
            } else {
                updateOverlayPosition(currentPosition)
            }

            broadcastStatus("STARTING...")
            startVisualizer()
        }

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Audio Visualizer")
            .setContentText("Visualizer is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        return notificationBuilder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Visualizer Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    @SuppressLint("InflateParams")
    private fun showOverlay(position: String) {
        if (floatingView != null) {
            return
        }

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.visualizer_overlay, null)

        @Suppress("DEPRECATION")
        floatingView?.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        visualizerView = floatingView?.findViewById(R.id.visualizerView)

        currentParams = createLayoutParams(position)

        try {
            windowManager.addView(floatingView, currentParams)
            currentPosition = position
            handler.postDelayed({ broadcastActualPosition(position) }, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlay() {
        if (floatingView != null && floatingView?.isAttachedToWindow == true) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing view", e)
            }
        }
        floatingView = null
        visualizerView = null
        currentParams = null
    }

    // --- MODIFIED FUNCTION (Added Forced Rebuild Logic) ---
    private fun forceViewUpdate(position: String) {
        // 1. Remove the old view instance (which is potentially corrupted)
        if (floatingView != null) {
            removeOverlay()
        }
        // 2. Re-create and add the new view instance with the updated position
        showOverlay(position)
        // 3. Immediately trigger mirror logic for the new view
        applyMirrorLogic()
        Log.i(TAG, "Forced view update to $position (Full View Rebuild).")
    }

    private fun updateOverlayPosition(position: String) {
        if (floatingView == null || currentParams == null || !floatingView!!.isAttachedToWindow) {
            Log.w(TAG, "updateOverlayPosition called but view is not ready. Re-creating.")
            forceViewUpdate(position)
            return
        }

        val positionChanged = (position != currentPosition)

        // CRITICAL CHECK: If we are swapping sides (LEFT <-> RIGHT), we MUST force a view rebuild.
        val isSideSwap = (position == "LEFT" && currentPosition == "RIGHT") || (position == "RIGHT" && currentPosition == "LEFT")

        if (isSideSwap) {
            // Forcing a complete view rebuild on side swaps is the only reliable way.
            forceViewUpdate(position)
            return
        }

        currentParams = createLayoutParams(position)

        try {
            windowManager.updateViewLayout(floatingView, currentParams)
            currentPosition = position

            if(positionChanged) {
                broadcastActualPosition(position)
            }

            applyMirrorLogic()

        } catch (e: Exception) {
            Log.e(TAG, "Error updating view layout", e)
            forceViewUpdate(position) // Fallback to hard rebuild
        }
    }
    // --- END MODIFIED FUNCTION ---

    private fun broadcastActualPosition(position: String) {
        val intent = Intent(ACTION_POSITION_UPDATED)
        intent.putExtra("POSITION", position)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastStatus(status: String) {
        currentStatus = status
        val intent = Intent(ACTION_STATUS_UPDATED)
        intent.putExtra("STATUS", status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }


    private fun createLayoutParams(position: String): WindowManager.LayoutParams {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val overlayThickness = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics
        ).toInt()

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params: WindowManager.LayoutParams
        var xPos = 0
        var yPos = 0

        val physicalWidth: Int
        val physicalHeight: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds: Rect = windowManager.currentWindowMetrics.bounds
            physicalWidth = bounds.width()
            physicalHeight = bounds.height()
        } else {
            val display = windowManager.defaultDisplay
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            physicalWidth = size.x
            physicalHeight = size.y
        }


        when (position) {
            "TOP" -> {
                visualizerView?.setOrientation(
                    VisualizerView.Orientation.VERTICAL,
                    VisualizerView.DrawDirection.LEFT_TO_RIGHT
                )
                params = WindowManager.LayoutParams(
                    physicalWidth, overlayThickness, // Use exact dimensions
                    layoutFlag, flags, PixelFormat.TRANSLUCENT
                )
                xPos = 0
                yPos = 0
            }
            "BOTTOM" -> {
                visualizerView?.setOrientation(
                    VisualizerView.Orientation.VERTICAL,
                    VisualizerView.DrawDirection.LEFT_TO_RIGHT
                )
                params = WindowManager.LayoutParams(
                    physicalWidth, overlayThickness, // Use exact dimensions
                    layoutFlag, flags, PixelFormat.TRANSLUCENT
                )
                xPos = 0
                yPos = physicalHeight - overlayThickness
            }
            "LEFT" -> {
                visualizerView?.setOrientation(
                    VisualizerView.Orientation.HORIZONTAL,
                    VisualizerView.DrawDirection.LEFT_TO_RIGHT
                )
                params = WindowManager.LayoutParams(
                    overlayThickness, physicalHeight, // Use exact dimensions
                    layoutFlag, flags, PixelFormat.TRANSLUCENT
                )
                xPos = 0
                yPos = 0
            }
            "RIGHT" -> {
                visualizerView?.setOrientation(
                    VisualizerView.Orientation.HORIZONTAL,
                    VisualizerView.DrawDirection.RIGHT_TO_LEFT
                )
                params = WindowManager.LayoutParams(
                    overlayThickness, physicalHeight, // Use exact dimensions
                    layoutFlag, flags, PixelFormat.TRANSLUCENT
                )
                xPos = physicalWidth - overlayThickness
                yPos = 0
            }
            else -> { // Default to BOTTOM logic
                visualizerView?.setOrientation(
                    VisualizerView.Orientation.VERTICAL,
                    VisualizerView.DrawDirection.LEFT_TO_RIGHT
                )
                params = WindowManager.LayoutParams(
                    physicalWidth, overlayThickness, // Use exact dimensions
                    layoutFlag, flags, PixelFormat.TRANSLUCENT
                )
                xPos = 0
                yPos = physicalHeight - overlayThickness
            }
        }

        params.gravity = Gravity.TOP or Gravity.LEFT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        params.x = xPos
        params.y = yPos

        return params
    }

    private fun startVisualizer() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        try {
            visualizer?.release()

            visualizer = Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1]

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val listenerClass = Class.forName("android.media.audiofx.Visualizer\$OnControlStatusChangeListener")

                        val listenerProxy = Proxy.newProxyInstance(
                            listenerClass.classLoader,
                            arrayOf(listenerClass),
                            object : InvocationHandler {
                                override fun invoke(proxy: Any, method: Method, args: Array<Any>): Any? {
                                    if (method.name == "onControlStatusChange") {
                                        val controlGranted = args[1] as Boolean
                                        if (!controlGranted) {
                                            Log.w(TAG, "Audio control lost (e.g., app backgrounded). Freezing bars.")
                                            handler.removeCallbacks(clearBarsRunnable)
                                        } else {
                                            Log.i(TAG, "Audio control granted.")
                                        }
                                    }
                                    return null
                                }
                            }
                        )

                        val setListenerMethod = this.javaClass.getMethod("setControlStatusListener", listenerClass)

                        setListenerMethod.invoke(this, listenerProxy)

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set ControlStatusListener via reflection", e)
                    }
                }

                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            if (fft != null) {
                                visualizerView?.updateVisualizer(fft)
                                lastFftTime = System.currentTimeMillis()

                                handler.removeCallbacks(clearBarsRunnable)
                                handler.postDelayed(clearBarsRunnable, fftTimeout + 10)

                                broadcastStatus("ACTIVE")
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    false,
                    true
                )
                enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            broadcastStatus("RETRYING...")
            handler.postDelayed({ startVisualizer() }, 200)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
        removeOverlay()

        isRunning = false
        Log.d(TAG, "onDestroy: Service stopping.")
        handler.removeCallbacks(clearBarsRunnable)
        handler.removeCallbacks(updatePositionRunnable)
        handler.removeCallbacksAndMessages(null)

        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SERVICE_STOPPED))

        LocalBroadcastManager.getInstance(this).unregisterReceiver(positionReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(colorReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mirroredReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(numBarsReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(forceInitReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(pingReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(modeReceiver)
    }
}