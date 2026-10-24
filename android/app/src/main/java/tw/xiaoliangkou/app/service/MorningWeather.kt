package tw.xiaoliangkou.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tw.xiaoliangkou.app.AppContainer
import tw.xiaoliangkou.app.container
import tw.xiaoliangkou.app.core.Weather
import tw.xiaoliangkou.app.core.WeatherNow
import tw.xiaoliangkou.app.data.SettingsStore
import tw.xiaoliangkou.app.data.WeatherException
import tw.xiaoliangkou.app.ui.screens.partnerSpot
import tw.xiaoliangkou.app.ui.screens.reminderKindFor
import java.time.ZonedDateTime

/**
 * 每天早上的天氣通知：兩邊今天的天氣和要注意的事，按鈕可以直接提醒對方。
 * 用 AlarmManager 排在設定的時間（系統允許精準鬧鐘時準時，不然在省電模式下可能晚幾分鐘），送出時再排隔天的
 */
object MorningWeather {
    const val ACTION = "tw.xiaoliangkou.app.action.MORNING_WEATHER"
    private const val REQUEST = 7730
    /** 自己的位置太舊（超過這麼久）就不用 */
    private const val MY_LOCATION_MAX_AGE_MS = 12 * 60 * 60 * 1000L

    /** 下一次送的時間：今天的 [minutes] 還沒到就是今天，過了（或剛好是現在）就是明天 */
    fun nextAt(now: ZonedDateTime, minutes: Int): ZonedDateTime {
        val today = now.toLocalDate().atTime(minutes / 60, minutes % 60).atZone(now.zone)
        // 鬧鐘可能早幾秒響：一分鐘內的當作已經送過
        return if (today.isAfter(now.plusMinutes(1))) today else now.toLocalDate().plusDays(1).atTime(minutes / 60, minutes % 60).atZone(now.zone)
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST,
        Intent(context, MorningWeatherReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 排下一次（[on] 是 false 就取消） */
    fun schedule(context: Context, on: Boolean, minutes: Int, now: ZonedDateTime = ZonedDateTime.now()) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context)
        if (!on) {
            am.cancel(pi)
            return
        }
        val at = nextAt(now, minutes).toInstant().toEpochMilli()
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** 抓兩邊的天氣、發通知；回傳一句結果（「現在試試看」跳出來的） */
    suspend fun post(context: Context, c: AppContainer): String {
        val repo = withTimeoutOrNull(8_000) {
            c.prefs.first { it != null }
            c.repo.value?.also { r -> r.state.first { it.loaded } }
        } ?: return "請先打開小倆口登入"
        val st = repo.state.value
        if (!c.notifier.canPost()) return "通知權限還沒打開"

        val mine = st.myLocation?.let { it.lat to it.lng }
            ?: Locator.lastKnown(context, MY_LOCATION_MAX_AGE_MS)?.let { it.latitude to it.longitude }
        val partner = if (st.paired) partnerSpot(st).first else null
        var problem: String? = null
        suspend fun fetch(spot: Pair<Double, Double>?): WeatherNow? = spot?.let { (lat, lng) ->
            try {
                c.weather.now(lat, lng)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WeatherException) {
                problem = e.message
                null
            } catch (e: Exception) {
                problem = "抓不到天氣"
                null
            }
        }
        val mw = fetch(mine)
        val pw = fetch(partner?.let { it.lat to it.lng })
        val m = Weather.morning(mw, pw, st.partnerName)
            ?: return problem ?: "還不知道兩邊的位置：打開定位或請對方分享位置"
        val remind = pw?.let { Weather.advice(it) }?.let { a -> StatusActionReceiver.weatherReminder(context, reminderKindFor(st, a), Weather.reminder(a)) }
        c.notifier.showMorning(m, remind, st.partnerName)
        return "已經送出今天的天氣提醒 🌤️"
    }
}

/** 早上的鬧鐘：先排好明天的，再抓天氣、發通知 */
class MorningWeatherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MorningWeather.ACTION) return
        val pending = goAsync()
        val c = context.applicationContext.container
        c.scope.launch {
            try {
                val p = withTimeoutOrNull(10_000) { c.prefs.first { it != null } } ?: return@launch
                val on = p.morningWeather && p.mode == SettingsStore.Mode.REMOTE
                MorningWeather.schedule(context, on, p.morningMinutes)
                if (on) withTimeoutOrNull(45_000) { MorningWeather.post(context, c) }
            } finally {
                pending.finish()
            }
        }
    }
}
