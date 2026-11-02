package br.com.streamswitch

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AdbPairingNotifications.ACTION_DISCONNECT -> {
                WirelessAdbController.disconnect(context)
                AdbPairingNotifications.cancel(context)
            }
            AdbPairingNotifications.ACTION_PAIR -> receivePairing(context, intent)
        }
    }

    private fun receivePairing(context: Context, intent: Intent) {
        val input = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AdbPairingNotifications.INPUT_PAIR_DATA)
            ?.toString()
            ?.trim()
        val codeOnly = input?.replace(Regex("[\\s-]"), "")?.takeIf { it.matches(Regex("\\d{6}")) }
        val portAndCode = input?.let {
            Regex("^(?:(?:\\d{1,3}\\.){3}\\d{1,3}:)?(\\d{1,5})\\s+(\\d{6})$").matchEntire(it)
        }
        val port = portAndCode?.groupValues?.get(1)?.toIntOrNull()
        val code = codeOnly ?: portAndCode?.groupValues?.get(2)
        if (code == null || (port != null && port !in 1..65535)) {
            val message = "Digite os 6 dígitos do código. Se falhar, envie porta e código: 37123 123456."
            SharedState.adbConnected = false
            SharedState.adbStatus = message
            AdbPairingNotifications.showFailure(context, message)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val message = "Depuração sem fio exige Android 11 ou superior."
            SharedState.adbConnected = false
            SharedState.adbStatus = message
            AdbPairingNotifications.showFailure(context, message)
            return
        }

        SharedState.adbConnected = false
        SharedState.adbStatus = "Pareando ADB Wi-Fi…"
        val service = Intent(context, AdbPairingService::class.java)
            .putExtra(AdbPairingService.EXTRA_PORT, port ?: -1)
            .putExtra(AdbPairingService.EXTRA_CODE, code)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        } catch (_: Exception) {
            val message = "Não consegui iniciar o pareamento. Abra o app e tente novamente."
            SharedState.adbStatus = message
            AdbPairingNotifications.showFailure(context, message)
        }
    }
}
