package com.weirdo.neural.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.weirdo.neural.MainActivity
import com.weirdo.neural.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service que segura o processo vivo enquanto uma geração
 * acontece. Não faz inferência — isso vive em [GenerationController].
 *
 * O botão "Parar" da notificação chama [GenerationController.cancel].
 */
@AndroidEntryPoint
class InferenceService : Service() {

    companion object {
        private const val CHANNEL_ID = "weirdo_inference"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.weirdo.neural.START_INFERENCE"
        private const val ACTION_STOP = "com.weirdo.neural.STOP_INFERENCE"
        private const val ACTION_CANCEL = "com.weirdo.neural.CANCEL_GENERATION"

        fun start(context: Context) {
            val intent = Intent(context, InferenceService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, InferenceService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    @Inject lateinit var generation: GenerationController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                generation.cancel()
                return START_NOT_STICKY
            }
            else -> {
                ensureChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                return START_NOT_STICKY
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Geração em andamento",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mostra quando o modelo está gerando uma resposta"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, InferenceService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("weirdo.neural")
            .setContentText("Gerando resposta…")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Parar", cancelIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
