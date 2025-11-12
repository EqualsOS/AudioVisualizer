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
import android.graphics.PixelFormat
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

    // Watchdog to clear bars when audio stops
    private val handler = Handler(Looper.getMainLooper())
    private val clearBarsRunnable = Runnable {
        visualizerView?.updateVisualizer(ByteArray(0)) // Send empty data
    }
    private val DATA_TIMEOUT_MS = 150L // Clear bars if no data for 150ms

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_POSITION -> {
                    intent.getStringExtra("POSITION")?.let { position ->
                        Log.d(TAG, "Received position command: $position")
                        updateOverlayPosition(position)
                    }
                }
                ACTION_UPDATE_COLOR -> {
                    val color = intent.getIntExtra("COLOR", -1)
                    if (color != -1) {
                        Log.d(TAG, "Received color command: $color")
                        visualizerView?.setColor(color)
                    }
                }
            }
        }
    }

    companion object {
        var isRunning = false
        private const val NOTIFICATION_CHANNEL_ID = "VisualizerServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "VisualizerService"

        const val ACTION_SERVICE_STOPPED = "com.equalsos.audiovisualizer.ACTION_SERVICE_STOPPED"
        const val ACTION_UPDATE_POSITION = "com.equalsos.audiovisualizer.ACTION_UPDATE_POSITION"
        const val ACTION_UPDATE_COLOR = "com.equalsos.audiovisualizer.ACTION_UPDATE_COLOR"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        val intentFilter = IntentFilter()
        intentFilter.addAction(ACTION_UPDATE_POSITION)
        intentFilter.addAction(ACTION_UPDATE_COLOR)
        LocalBroadcastManager.getInstance(this).registerReceiver(commandReceiver, intentFilter)

        isRunning = true
        Log.d(TAG, "onCreate: Service starting...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        if (floatingView == null) {
            val initialPosition = intent?.getStringExtra("POSITION") ?: "BOTTOM"
            showOverlay(initialPosition)
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
            removeOverlay()
        }

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.visualizer_overlay, null)
        visualizerView = floatingView?.findViewById(R.id.visualizerView)

        currentParams = createLayoutParams(position)

        try {
            windowManager.addView(floatingView, currentParams)
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

    private fun updateOverlayPosition(position: String) {
        if (floatingView == null || floatingView?.isAttachedToWindow == false) {
            showOverlay(position)
            return
        }

        val newParams = createLayoutParams(position)

        try {
            windowManager.updateViewLayout(floatingView, newParams)
            currentParams = newParams
        } catch (e: Exception) {
            Log.e(TAG, "Error updating view layout", e)
        }
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

        when (position) {
            "TOP" -> {
                visualizerView?.setOrientation(
                    VisualizerView.Orientation.VERTICAL,
                    VisualizerView.DrawDirection.LEFT_TO_RIGHT
                )
                params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, overlayThickness,
                    layoutFlag, flags, PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP
            }
            "BOTTOM" -> {
                visualizerView?.setOrientation(
                    VisualizerView.Orientation.VERTICAL,
                    VisualizerView.DrawDirection.LEFT_TO_RIGHT
                )
                params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, overlayThickness,
                    layoutFlag, flags, PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.BOTTOM
            }
            "LEFT" -> {
                visualizerView?.setOrientation(
                    VisualizerView.Orientation.HORIZONTAL,
                    VisualizerView.DrawDirection.LEFT_TO_RIGHT
                )
                params = WindowManager.LayoutParams(
                    overlayThickness, WindowManager.LayoutParams.MATCH_PARENT,
                    layoutFlag, flags, PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.LEFT
            }
            "RIGHT" -> {
                visualizerView?.setOrientation(
                    VisualizerView.Orientation.HORIZONTAL,
                    VisualizerView.DrawDirection.RIGHT_TO_LEFT
                )
                params = WindowManager.LayoutParams(
                    overlayThickness, WindowManager.LayoutParams.MATCH_PARENT,
                    layoutFlag, flags, PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.RIGHT
            }
            else -> {
                visualizerView?.setOrientation(
                    VisualizerView.Orientation.VERTICAL,
                    VisualizerView.DrawDirection.LEFT_TO_RIGHT
                )
                params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, overlayThickness,
                    layoutFlag, flags, PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.BOTTOM
            }
        }

        params.x = 0
        params.y = 0

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
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            // Reset the watchdog timer
                            handler.removeCallbacks(clearBarsRunnable)
                            handler.postDelayed(clearBarsRunnable, DATA_TIMEOUT_MS)

                            if (fft != null) {
                                visualizerView?.updateVisualizer(fft)
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up handler
        handler.removeCallbacks(clearBarsRunnable)

        visualizer?.apply {
            enabled = false
            release()
        }
        visualizer = null
        removeOverlay()

        isRunning = false
        Log.d(TAG, "onDestroy: Service stopping.")
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_SERVICE_STOPPED))

        LocalBroadcastManager.getInstance(this).unregisterReceiver(commandReceiver)
    }
}