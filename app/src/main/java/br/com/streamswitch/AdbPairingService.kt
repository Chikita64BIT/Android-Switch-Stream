package br.com.streamswitch

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

/** Keeps a notification reply alive while ADB discovers, pairs and connects. */
class AdbPairingService : Service() {
    companion object {
        const val EXTRA_PORT = "pair_port"
        const val EXTRA_CODE = "pair_code"
    }

    private val worker = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getStringExtra(EXTRA_CODE)
        val rawPort = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
        if (code == null || !code.matches(Regex("\\d{6}")) || rawPort !in -1..65535) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val port = rawPort.takeIf { it > 0 }
        val notification = AdbPairingNotifications.pairingProgressNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AdbPairingNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            startForeground(AdbPairingNotifications.NOTIFICATION_ID, notification)
        }

        worker.execute {
            try {
                WirelessAdbController.pairAndConnect(applicationContext, port, code)
                AdbPairingNotifications.showConnected(this)
            } catch (error: Exception) {
                val message = error.message ?: "Falha no pareamento. Gere um código novo e tente novamente."
                SharedState.adbConnected = false
                SharedState.adbStatus = message
                AdbPairingNotifications.showFailure(this, message)
            } finally {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
