package com.example.filemanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.io.File

/** FTP server ko background me chalane wali foreground service (notification ke saath) */
class FtpService : Service() {

    companion object {
        const val ACTION_START = "com.example.filemanager.ftp.START"
        const val ACTION_STOP = "com.example.filemanager.ftp.STOP"
        private const val CHANNEL = "ftp"
        private const val NOTIF_ID = 43

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, FtpService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FtpService::class.java).setAction(ACTION_STOP))
        }
    }

    private var server: FtpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var starting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "FTP server", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startFg("Shuru ho raha hai...")
                if (server == null && !starting) begin()
            }
            ACTION_STOP -> shutdown()
            else -> if (server == null && !starting) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        releaseLock()
        FtpState.running = false
        FtpState.clients = 0
        super.onDestroy()
    }

    private fun begin() {
        starting = true
        FtpConfig.load(applicationContext)
        val cfg = FtpConfig
        val root = File(cfg.root)
        val user = cfg.user
        val pass = cfg.pass
        val port = cfg.port
        val ro = cfg.readOnly
        Thread {
            try {
                val ip = localIp() ?: throw IllegalStateException("Wi-Fi ya hotspot se connect karo")
                if (!root.isDirectory) throw IllegalStateException("Root folder nahi mila")
                val srv = FtpServer(root, user, pass, port, ro, ip) { n -> main.post { FtpState.clients = n } }
                srv.start()
                server = srv
                val addr = "ftp://$ip:$port"
                main.post {
                    FtpState.address = addr
                    FtpState.error = null
                    FtpState.running = true
                    updateNotification(addr)
                }
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AllInOneFM:ftp").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                main.post {
                    FtpState.error = e.message ?: "Server shuru nahi hua"
                    FtpState.running = false
                    stopSelf()
                }
            } finally {
                starting = false
            }
        }.start()
    }

    private fun shutdown() {
        server?.stop()
        server = null
        releaseLock()
        FtpState.running = false
        FtpState.clients = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        wakeLock = null
    }

    private fun startFg(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, RemoteActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FtpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("FTP server chal raha hai")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop", stop
                ).build()
            )
        return b.build()
    }
}
