package com.videorecorder.maxquality

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecordService : LifecycleService() {

    private val TAG = "ScreenRecordService"
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val isRecording = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputFile: File? = null
    private var overlayWindow: WindowManager? = null
    private var overlayView: android.view.View? = null
    private var screenDensity = 1f
    private var screenWidth = 1920
    private var screenHeight = 1080
    private var targetFps = 120
    private var targetBitrate = 50_000_000
    private var recordAudio = true
    private var recordSystemAudio = true
    private var showTouches = false

    companion object {
        const val ACTION_START = "ACTION_START_RECORDING"
        const val ACTION_STOP = "ACTION_STOP_RECORDING"
        const val EXTRA_MEDIA_PROJECTION = "EXTRA_MEDIA_PROJECTION"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RECORD_AUDIO = "EXTRA_RECORD_AUDIO"
        const val EXTRA_RECORD_SYSTEM_AUDIO = "EXTRA_RECORD_SYSTEM_AUDIO"
        const val EXTRA_SHOW_TOUCHES = "EXTRA_SHOW_TOUCHES"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "screen_record_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val dm = getSystemService(DisplayManager::class.java)
        val metrics = DisplayMetrics()
        dm.getDisplays()[0].getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.density
        Log.d(TAG, "Screen: ${screenWidth}x${screenHeight} density=$screenDensity")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START -> {
                    val projection = it.getParcelableExtra<MediaProjection>(EXTRA_MEDIA_PROJECTION)
                    val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, 0)
                    recordAudio = it.getBooleanExtra(EXTRA_RECORD_AUDIO, true)
                    recordSystemAudio = it.getBooleanExtra(EXTRA_RECORD_SYSTEM_AUDIO, true)
                    showTouches = it.getBooleanExtra(EXTRA_SHOW_TOUCHES, false)
                    if (projection != null) {
                        startRecording(projection, resultCode)
                    }
                }
                ACTION_STOP -> stopRecording()
            }
        }
        return START_STICKY
    }

    private fun startRecording(projection: MediaProjection, resultCode: Int) {
        if (isRecording.getAndSet(true)) return

        scope.launch {
            try {
                mediaProjection = projection
                outputFile = createOutputFile()
                setupMediaRecorder()
                createVirtualDisplay()
                startOverlayButton()
                startForeground(NOTIFICATION_ID, buildNotification("Recording screen..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                mediaRecorder?.start()
                Log.d(TAG, "Recording started: ${outputFile?.absolutePath}")
                sendBroadcast(Intent(MainActivity.ACTION_RECORDING_STARTED).putExtra("path", outputFile?.absolutePath))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                isRecording.set(false)
                stopSelf()
            }
        }
    }

    private fun setupMediaRecorder() {
        mediaRecorder = MediaRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            
            if (recordAudio) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            if (recordSystemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // System audio captured via MediaProjection
            }

            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            
            val videoEncoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    MediaRecorder.getVideoEncoderCapability(MediaRecorder.VideoEncoder.HEVC, screenWidth, screenHeight).isSupported
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
            setVideoEncoder(if (videoEncoder) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            
            if (recordAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48000)
                setAudioEncodingBitRate(320_000)
            }

            setVideoSize(screenWidth, screenHeight)
            setVideoFrameRate(targetFps)
            setVideoEncodingBitRate(targetBitrate)
            
            setOutputFile(outputFile!!.absolutePath)
            prepare()
        }
    }

    private fun createVirtualDisplay() {
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenRecordDisplay",
            screenWidth,
            screenHeight,
            (screenDensity * 160).toInt(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            (if (showTouches) DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION else 0),
            mediaRecorder!!.surface,
            null,
            null
        )
    }

    private fun createOutputFile(): File {
        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: filesDir
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "ScreenRec_${timestamp}.mp4"
        return File(dir, filename)
    }

    private fun startOverlayButton() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = 0
            y = 200
        }

        overlayWindow = getSystemService(WindowManager::class.java)
        overlayView = android.view.LayoutInflater.from(this).inflate(R.layout.overlay_stop_button, null)
        
        val stopBtn = overlayView!!.findViewById<android.widget.ImageView>(R.id.ivStopButton)
        stopBtn.setOnClickListener { stopRecording() }
        
        overlayView!!.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.tag = event.rawX to event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val (startX, startY) = v.tag as Pair<Float, Float>
                    params.x = (event.rawX - startX).toInt()
                    params.y = (event.rawY - startY).toInt()
                    overlayWindow!!.updateViewLayout(v, params)
                    true
                }
                else -> false
            }
        }

        overlayWindow!!.addView(overlayView!!, params)
    }

    private fun stopRecording() {
        if (!isRecording.getAndSet(false)) return

        scope.launch {
            try {
                mediaRecorder?.stop()
                mediaRecorder?.reset()
                mediaRecorder?.release()
                mediaRecorder = null
                
                virtualDisplay?.release()
                virtualDisplay = null
                
                mediaProjection?.stop()
                mediaProjection = null
                
                removeOverlayButton()
                
                stopForeground(true)
                stopSelf()
                
                val path = outputFile?.absolutePath
                Log.d(TAG, "Recording stopped: $path")
                sendBroadcast(Intent(MainActivity.ACTION_RECORDING_STOPPED).putExtra("path", path))
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping recording", e)
            }
        }
    }

    private fun removeOverlayButton() {
        overlayWindow?.let {
            overlayView?.let { it.removeView(it) }
            overlayView = null
        }
        overlayWindow = null
    }

    private fun buildNotification(text: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording_notification)
            .setContentTitle("Screen Recorder")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Screen Recording",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active screen recording session"
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (isRecording.get()) {
            stopRecording()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}