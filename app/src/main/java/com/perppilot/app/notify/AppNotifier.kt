package com.perppilot.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.perppilot.app.AppGraph
import com.perppilot.app.MainActivity
import com.perppilot.app.R
import com.perppilot.app.data.SecretKey
import com.perppilot.core.engine.EngineEvent
import com.perppilot.core.engine.EventKind
import com.perppilot.core.engine.Notifier
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Routes engine events to Android notification channels and, optionally, a Telegram bot. */
class AppNotifier(private val context: Context, private val graph: AppGraph) : Notifier {
    private val ids = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())
    private val http by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CH_SIGNALS, "交易訊號", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_TRADES, "開平倉與止損", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_RISK, "風控警示", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_ERRORS, "錯誤", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_SERVICE, "背景監控", NotificationManager.IMPORTANCE_LOW),
        ).forEach(nm::createNotificationChannel)
    }

    override fun notify(event: EngineEvent) {
        val s = graph.settings.current
        val channel = when (event.kind) {
            EventKind.SIGNAL, EventKind.INFO -> CH_SIGNALS
            EventKind.ORDER, EventKind.CLOSE, EventKind.STOP -> CH_TRADES
            EventKind.RISK -> CH_RISK
            EventKind.ERROR -> CH_ERRORS
        }
        val show = when (event.kind) {
            EventKind.STOP -> s.notifyStops
            else -> true
        }
        if (show) post(channel, event.title, event.message)
        if (s.telegramEnabled && event.kind != EventKind.INFO && event.kind != EventKind.STOP) {
            sendTelegram("${event.title}\n${event.message}")
        }
    }

    fun post(channel: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(ids.incrementAndGet(), n)
    }

    /** Sends asynchronously; returns immediately. [onResult] receives an error message or null. */
    fun sendTelegram(text: String, onResult: ((String?) -> Unit)? = null) {
        val token = graph.secure.get(SecretKey.TELEGRAM_TOKEN)
        val chat = graph.settings.current.telegramChatId
        if (token.isNullOrBlank() || chat.isBlank()) {
            onResult?.invoke("尚未設定 Telegram Bot Token 或 Chat ID")
            return
        }
        graph.appScope.launch {
            val err = runCatching {
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(FormBody.Builder().add("chat_id", chat).add("text", "【PerpPilot】$text").build())
                    .build()
                http.newCall(req).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}") }
            }.exceptionOrNull()?.message
            onResult?.invoke(err)
        }
    }

    companion object {
        const val CH_SIGNALS = "signals"
        const val CH_TRADES = "trades"
        const val CH_RISK = "risk"
        const val CH_ERRORS = "errors"
        const val CH_SERVICE = "service"

        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
