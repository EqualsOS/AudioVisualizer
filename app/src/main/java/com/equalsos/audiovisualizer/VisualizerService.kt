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
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class VisualizerService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var visualizer: Visualizer? = null
    private var visualizerView: VisualizerView? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var currentPosition: String = "BOTTOM" // Track position internally
    private var currentStatus: String = "STARTING..." // Track status internally

    // --- Broadcast Receivers ---
    private val positionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("POSITION")?.let { position ->
                Log.d(TAG, "Received position command: $position")
                updateOverlayPosition(position)
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
                val isMirrorVert = intent.getBooleanExtra("IS_MIRROR_VERT", false)
                val isMirrorHoriz = intent.getBooleanExtra("IS_MIRROR_HORIZ", false)
                visualizerView?.setMirrored(isMirrorVert, isMirrorHoriz)
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

    // NEW: Ping Receiver
    private val pingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PING) {
                Log.d(TAG, "Received PING, responding with state.")
                broadcastStatus(currentStatus)
                broadcastActualPosition(currentPosition)
            }
        }
    }

    // --- Watchdog for empty audio ---
    private val handler = Handler(Looper.getMainLooper())
    private var lastFftTime: Long = 0
    private val fftTimeout = 150L
    private val clearBarsRunnable = Runnable {
        if (System.currentTimeMillis() - lastFftTime > fftTimeout) {
            visualizerView?.updateVisualizer(ByteArray(0))
        }
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
        const val ACTION_FORCE_INIT = "com.equalsos.audiovisualizer.ACTION_FORCE_INIT"
        const val ACTION_STATUS_UPDATED = "com.equalsos.audiovisualizer.ACTION_STATUS_UPDATED"
        const val ACTION_PING = "com.equalsos.audiovisualizer.ACTION_PING" // NEW
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
        LocalBroadcastManager.getInstance(this).registerReceiver(forceInitReceiver, IntentFilter(ACTION_FORCE_INIT))
        LocalBroadcastManager.getInstance(this).registerReceiver(pingReceiver, IntentFilter(ACTION_PING)) // Register PING

        isRunning = true
        Log.d(TAG, "onCreate: Service starting...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        if (floatingView == null) {
            val initialPosition = intent?.getStringExtra("POSITION") ?: "BOTTOM"
            showOverlay(initialPosition)

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

    // --- THIS IS THE MODIFIED FUNCTION ---
    @SuppressLint("InflateParams")
    private fun showOverlay(position: String) {
        // This function is now ONLY for creating the view the FIRST time
        if (floatingView != null) {
            return // Avoid re-creating if it already exists
        }

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.visualizer_overlay, null)

        // --- NEWLY ADDED LINE ---
        // Tell the view to lay out behind the system bars
        @Suppress("DEPRECATION")
        floatingView?.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        // --- END OF NEWLY ADDED LINE ---

        visualizerView = floatingView?.findViewById(R.id.visualizerView)

        currentParams = createLayoutParams(position)

        try {
            windowManager.addView(floatingView, currentParams)
            // Update internal state
            currentPosition = position
            handler.postDelayed({ broadcastActualPosition(position) }, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    // --- END OF MODIFIED FUNCTION ---

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

    private fun updateOverlayPosition(position: String) {
        // Guard against calls when view isn't ready
        if (floatingView == null || currentParams == null || !floatingView!!.isAttachedToWindow) {
            Log.w(TAG, "updateOverlayPosition called but view is not ready. Re-creating.")
            // As a fallback, remove any old view and create a new one
            removeOverlay()
            showOverlay(position)
            return
        }

        // Create the new params based on the position
        currentParams = createLayoutParams(position)

        try {
            // Use updateViewLayout to apply the new params
            windowManager.updateViewLayout(floatingView, currentParams)

            // Update internal state and broadcast
            currentPosition = position
            broadcastActualPosition(position)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating view layout", e)
            // If update fails (e.g., view got detached), fall back to remove/add
            removeOverlay()
            showOverlay(position)
        }
    }

    private fun broadcastActualPosition(position: String) {
        val intent = Intent(ACTION_POSITION_UPDATED)
        intent.putExtra("POSITION", position)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastStatus(status: String) {
        // Update internal state
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

        // Get physical screen size using the correct API
        val physicalWidth: Int
        val physicalHeight: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds: Rect = windowManager.currentWindowMetrics.bounds
            physicalWidth = bounds.width()
            physicalHeight = bounds.height()
        } else {
            // Fallback for older devices
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
                xPos = 0 // Pin to physical top
                yPos = 0 // Pin to physical top
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
                yPos = physicalHeight - overlayThickness // Pin to physical bottom
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
                xPos = 0 // Pin to physical left
                yPos = 0 // Pin to physical top
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
                xPos = physicalWidth - overlayThickness // Pin to physical right
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

        // Set the base gravity for ALL positions to TOP|LEFT
        // This makes (x,y) coordinates relative to the physical top-left corner
        params.gravity = Gravity.TOP or Gravity.LEFT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Apply the calculated coordinates
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
        handler.removeCallbacksAndMessages(null)

        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SERVICE_STOPPED))

        LocalBroadcastManager.getInstance(this).unregisterReceiver(positionReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(colorReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mirroredReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(forceInitReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(pingReceiver) // Unregister PING
    }
}
