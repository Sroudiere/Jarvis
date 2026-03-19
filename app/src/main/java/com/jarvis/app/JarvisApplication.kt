package com.jarvis.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.jarvis.app.service.JarvisService

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Notification channel is created by JarvisService when it starts,
        // but we pre-create it here so it's available even before first service start.
        val channel = NotificationChannel(
            JarvisService.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
