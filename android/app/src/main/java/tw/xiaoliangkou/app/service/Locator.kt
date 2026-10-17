package tw.xiaoliangkou.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 拿手機現在的位置。先問 Google 定位服務，抓不到（室內、沒有 Google 服務的手機）再問系統的 GPS／網路定位，
 * 最後才用手機記得的上一個位置。
 */
object Locator {
    enum class Status { OK, NO_PERMISSION, OFF }

    /** 上一個位置太舊就不算數 */
    const val MAX_AGE_MS = 10 * 60_000L

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun status(context: Context): Status = when {
        !hasPermission(context) -> Status.NO_PERMISSION
        !isEnabled(context) -> Status.OFF
        else -> Status.OK
    }

    /** 手機的「定位」開關 */
    fun isEnabled(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

    /** 現在的位置；都抓不到就用 10 分鐘內的上一個位置，再沒有就是 null（最多等 [googleMs] + [systemMs]） */
    suspend fun current(context: Context, googleMs: Long = 12_000L, systemMs: Long = 10_000L): Location? {
        if (!hasPermission(context)) return null
        return withTimeoutOrNull(googleMs) { fromGoogle(context) }
            ?: withTimeoutOrNull(systemMs) { fromSystem(context) }
            ?: lastKnown(context)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fromGoogle(context: Context): Location? {
        val token = CancellationTokenSource()
        return try {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            token.cancel()
            throw e
        } catch (e: Exception) {
            null // 沒有 Google 服務、服務太舊
        }
    }

    /** 系統的定位：GPS 和網路定位一起問，誰先回來用誰（室內 GPS 常常等不到） */
    private suspend fun fromSystem(context: Context): Location? = coroutineScope {
        val lm = context.getSystemService(LocationManager::class.java) ?: return@coroutineScope null
        val ps = providers(context, lm)
        val results = Channel<Location?>(ps.size)
        val jobs = ps.map { p -> launch { results.send(fromProvider(context, lm, p)) } }
        repeat(ps.size) {
            val loc = results.receive()
            if (loc != null) {
                jobs.forEach { it.cancel() }
                return@coroutineScope loc
            }
        }
        null
    }

    @SuppressLint("MissingPermission")
    private suspend fun fromProvider(context: Context, lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        runCatching {
            LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) { if (cont.isActive) cont.resume(it) }
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    /** 手機記得的上一個位置（Google 或系統，取最新的） */
    @SuppressLint("MissingPermission")
    suspend fun lastKnown(context: Context, maxAgeMs: Long = MAX_AGE_MS): Location? {
        if (!hasPermission(context)) return null
        val google = withTimeoutOrNull(3_000) { runCatching { LocationServices.getFusedLocationProviderClient(context).lastLocation.await() }.getOrNull() }
        val system = withContext(Dispatchers.IO) {
            val lm = context.getSystemService(LocationManager::class.java) ?: return@withContext emptyList()
            providers(context, lm).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        }
        val now = SystemClock.elapsedRealtimeNanos()
        return (listOfNotNull(google) + system)
            .filter { (now - it.elapsedRealtimeNanos) / 1_000_000 <= maxAgeMs }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    private fun providers(context: Context, lm: LocationManager): List<String> {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val wanted = if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER)
        return wanted.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }
}
