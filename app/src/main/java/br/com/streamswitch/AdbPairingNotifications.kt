package br.com.streamswitch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.RemoteInput

object AdbPairingNotifications {
    const val ACTION_PAIR = "br.com.streamswitch.action.PAIR_ADB"
    const val ACTION_DISCONNECT = "br.com.streamswitch.action.DISCONNECT_ADB"
    const val INPUT_PAIR_DATA = "pairing_data"

    private const val CHANNEL_ID = "joycon_wireless_adb"
    const val NOTIFICATION_ID = 9421

    fun showPairingPrompt(context: Context) {
        createChannel(context)
        val notification = builder(context)
            .setContentTitle("Configurar Joy-Cons por ADB Wi-Fi")
            .setContentText("Ative Depuração sem fio e envie o código nesta notificação.")
            .setStyle(Notification.BigTextStyle().bigText(
                "Nos Ajustes, ative Depuração sem fio e toque em Parear usando código. " +
                    "Sem fechar essa tela, responda aqui com os 6 dígitos. Se necessário, envie porta e código."
            ))
            .setOngoing(true)
            .addAction(pairAction(context))
            .build()
        manager(context).notify(NOTIFICATION_ID, notification)
    }

    private fun pairAction(context: Context): Notification.Action {
        val intent = Intent(context, AdbPairingReceiver::class.java)
            .setAction(ACTION_PAIR)
        val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pairFlags = PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9421,
            intent,
            pairFlags
        )
        val input = RemoteInput.Builder(INPUT_PAIR_DATA)
            .setLabel("Código de 6 dígitos")
            .setAllowFreeFormInput(true)
            .build()
        return Notification.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Enviar pareamento",
            pendingIntent
        ).addRemoteInput(input).setAllowGeneratedReplies(false).build()
    }

    fun showConnected(context: Context) {
        createChannel(context)
        val disconnectIntent = Intent(context, AdbPairingReceiver::class.java)
            .setAction(ACTION_DISCONNECT)
        val disconnectPendingIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID + 1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Desconectar Joy-Cons",
            disconnectPendingIntent
        ).build()
        val notification = builder(context)
            .setContentTitle("Joy-Cons conectados por ADB Wi-Fi")
            .setContentText("Controles ativos. A depuração pode ser desligada nos Ajustes.")
            .setOngoing(true)
            .addAction(disconnectAction)
            .build()
        manager(context).notify(NOTIFICATION_ID, notification)
    }

    fun showFailure(context: Context, message: String) {
        createChannel(context)
        manager(context).notify(
            NOTIFICATION_ID,
            builder(context)
                .setContentTitle("Não foi possível conectar os Joy-Cons")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setOngoing(false)
                .addAction(pairAction(context))
                .build()
        )
    }

    fun pairingProgressNotification(context: Context): Notification {
        createChannel(context)
        return builder(context)
            .setContentTitle("Pareando Joy-Cons…")
            .setContentText("Aguardando a confirmação da depuração sem fio.")
            .setOngoing(true)
            .build()
    }

    fun cancel(context: Context) {
        manager(context).cancel(NOTIFICATION_ID)
    }

    private fun builder(context: Context): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        return builder.setSmallIcon(android.R.drawable.ic_menu_manage)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .setContentIntent(PendingIntent.getActivity(
                context,
                NOTIFICATION_ID + 2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Controles Joy-Con por ADB Wi-Fi",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Pareamento e estado dos controles Joy-Con" }
        manager(context).createNotificationChannel(channel)
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
