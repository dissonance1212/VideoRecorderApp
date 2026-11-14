package com.videorecorder.maxquality

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val REQUEST_CODE_SCREEN_CAPTURE = 1001
    private val REQUEST_CODE_OVERLAY = 1002
    private val REQUEST_CODE_STORAGE = 1003
    private val REQUEST_CODE_AUDIO = 1004
    private val REQUEST_CODE_NOTIFICATION = 1005

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvResolution: TextView
    private lateinit var tvFps: TextView
    private lateinit var tvBitrate: TextView
    private lateinit var cbRecordAudio: CheckBox
    private lateinit var cbRecordSystemAudio: CheckBox
    private lateinit var cbShowTouches: CheckBox

    private var isRecording = false
    private var screenWidth = 1920
    private var screenHeight = 1080
    private var screenDensity = 1f

    companion object {
        const val ACTION_RECORDING_STARTED = "ACTION_RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "ACTION_RECORDING_STOPPED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)

        initViews()
        detectScreenSpecs()
        checkAllPermissions()
        setupClickListeners()
        observeRecordingState()
    }

    private fun initViews() {
        btnRecord = findViewById(R.id.btnRecord)
        tvStatus = findViewById(R.id.tvStatus)
        tvResolution = findViewById(R.id.tvResolution)
        tvFps = findViewById(R.id.tvFps)
        tvBitrate = findViewById(R.id.tvBitrate)
        cbRecordAudio = findViewById(R.id.cbRecordAudio)
        cbRecordSystemAudio = findViewById(R.id.cbRecordSystemAudio)
        cbShowTouches = findViewById(R.id.cbShowTouches)
    }

    private fun detectScreenSpecs() {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        screenDensity = dm.density

        val maxFps = getMaxSupportedFps()
        val bitrateMbps = calculateOptimalBitrate()

        tvResolution.text = "Resolution: ${screenWidth}x${screenHeight}"
        tvFps.text = "FPS: $maxFps"
        tvBitrate.text = "Bitrate: ${bitrateMbps} Mbps (Max)"
    }

    private fun getMaxSupportedFps(): Int {
        return try {
            val display = windowManager.defaultDisplay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val mode = display.mode
                mode.refreshRate.toInt()
            } else {
                120
            }
        } catch (e: Exception) {
            120
        }
    }

    private fun calculateOptimalBitrate(): Int {
        val bpp = 0.15
        val bitrate = (screenWidth * screenHeight * 120 * bpp).toLong()
        return (bitrate / 1_000_000).toInt().coerceAtLeast(50).coerceAtMost(200)
    }

    private fun checkAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission()
            return
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestStoragePermission()
            return
        }

        enableRecording()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivityForResult(intent, REQUEST_CODE_OVERLAY)
    }

    private fun requestNotificationPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATION)
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_AUDIO)
    }

    private fun requestStoragePermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE_STORAGE)
    }

    private fun enableRecording() {
        btnRecord.isEnabled = true
        tvStatus.text = "Готов к записи экрана\nМакс. качество • 120 FPS"
    }

    private fun setupClickListeners() {
        btnRecord.setOnClickListener {
            if (!isRecording) {
                startScreenCapture()
            } else {
                stopRecording()
            }
        }
    }

    private fun startScreenCapture() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_SCREEN_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
                    startRecordingService(projection, resultCode)
                } else {
                    Toast.makeText(this, "Разрешение на захват экрана отклонено", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_CODE_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    checkAllPermissions()
                } else {
                    Toast.makeText(this, "Нужно разрешение на наложение", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkAllPermissions()
        } else {
            Toast.makeText(this, "Разрешение необходимо для работы", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecordingService(projection: android.media.projection.MediaProjection, resultCode: Int) {
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_MEDIA_PROJECTION, projection)
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_RECORD_AUDIO, cbRecordAudio.isChecked)
            putExtra(ScreenRecordService.EXTRA_RECORD_SYSTEM_AUDIO, cbRecordSystemAudio.isChecked)
            putExtra(ScreenRecordService.EXTRA_SHOW_TOUCHES, cbShowTouches.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopRecording() {
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        startService(intent)
    }

    private fun observeRecordingState() {
        lifecycleScope.launch {
            val startedReceiver = registerReceiver(object : androidx.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    isRecording = true
                    btnRecord.isSelected = true
                    tvStatus.text = "● ЗАПИСЬ ИДЁТ\nНажмите кнопку на экране для остановки"
                    Toast.makeText(this@MainActivity, "Запись начата", Toast.LENGTH_SHORT).show()
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(this), IntentFilter(ACTION_RECORDING_STARTED))

            val stoppedReceiver = registerReceiver(object : androidx.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    isRecording = false
                    btnRecord.isSelected = false
                    tvStatus.text = "Готов к записи экрана\nМакс. качество • 120 FPS"
                    val path = intent.getStringExtra("path")
                    Toast.makeText(this@MainActivity, "Видео сохранено: $path", Toast.LENGTH_LONG).show()
                }
            }, androidx.core.content.ContextCompat.getMainExecutor(this), IntentFilter(ACTION_RECORDING_STOPPED))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopRecording()
        }
    }
}