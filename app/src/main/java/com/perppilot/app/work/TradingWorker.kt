package com.perppilot.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.perppilot.app.data.AppSettings
import com.perppilot.app.graph
import java.util.concurrent.TimeUnit

/** Runs one trading/analysis cycle. Scheduled every 15 minutes (Android's minimum period). */
class TradingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val graph = applicationContext.graph
            // Fallback for missed TradingView alerts when the real-time service is not running.
            runCatching { graph.pollRelay() }
            graph.runTradingCycle()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

object Scheduler {
    private const val PERIODIC = "trading-cycle"
    private const val ONCE = "trading-cycle-now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Keeps the periodic job in line with the settings. */
    fun sync(context: Context, s: AppSettings) {
        val wm = WorkManager.getInstance(context)
        if (s.backgroundScan || s.engine.autoTrade || s.signals.enabled) {
            val req = PeriodicWorkRequestBuilder<TradingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(PERIODIC)
        }
    }

    fun runNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<TradingWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req)
    }
}
