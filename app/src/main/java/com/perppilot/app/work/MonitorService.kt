package com.perppilot.app.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.perppilot.app.R
import com.perppilot.app.graph
import com.perppilot.app.notify.AppNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real-time loop for things WorkManager's 15-minute minimum is too slow for:
 * - TradingView alerts relayed through ntfy (polled every 15 s)
 * - event contracts that expire every 5–15 minutes (scanned every 30 s)
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val s = applicationContext.graph.settings.current
        val parts = listOfNotNull(
            "TradingView 警報".takeIf { s.signals.enabled },
            "事件合約".takeIf { s.eventMonitor },
            "${s.engine.strategyType.label}（每分鐘）".takeIf { needsMinuteCycle(s) },
        ).ifEmpty { listOf("待命") }
        val notification = NotificationCompat.Builder(this, AppNotifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("PerpPilot 即時監控中")
            .setContentText(parts.joinToString("、"))
            .setOngoing(true)
            .setContentIntent(AppNotifier.openAppIntent(this))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        running.value = true
        if (loop?.isActive != true) {
            loop = scope.launch {
                var tick = 0L
                while (isActive) {
                    val graph = applicationContext.graph
                    val cur = graph.settings.current
                    if (cur.signals.enabled) runCatching { graph.pollRelay() }
                    if (cur.eventMonitor && tick % 2 == 0L) runCatching { graph.runEventCycle() }
                    if (needsMinuteCycle(cur) && tick % 4 == 0L) runCatching { graph.runTradingCycle() }
                    tick++
                    delay(15_000)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "stop"
        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> get() = running

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))

        fun stop(context: Context) {
            if (running.value) context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_STOP))
        }

        /** Intraday strategies (5m/15m bars) must be evaluated far more often than every 15 minutes. */
        fun needsMinuteCycle(s: com.perppilot.app.data.AppSettings): Boolean =
            s.engine.activeStrategy().ltfFrame.millis <= com.perppilot.core.model.Timeframe.M15.millis &&
                (s.engine.autoTrade || s.engine.notifySignals || s.backgroundScan)

        /** Starts or stops the service to match the settings. */
        fun sync(context: Context) {
            val s = context.graph.settings.current
            if (s.signals.enabled || s.eventMonitor || needsMinuteCycle(s)) start(context) else stop(context)
        }
    }
}
