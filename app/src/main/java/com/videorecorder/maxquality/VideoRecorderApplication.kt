package com.videorecorder.maxquality

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner

class VideoRecorderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver())
    }

    companion object {
        lateinit var instance: VideoRecorderApplication
            private set

        fun getInstance(): Context = instance.applicationContext
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
    }
}

class AppLifecycleObserver : androidx.lifecycle.DefaultLifecycleObserver {
    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {}
    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {}
}