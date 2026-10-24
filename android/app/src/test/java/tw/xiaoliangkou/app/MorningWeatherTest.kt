package tw.xiaoliangkou.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.core.Weather
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.service.MorningWeather
import tw.xiaoliangkou.app.service.MorningWeatherReceiver
import tw.xiaoliangkou.app.service.Notifier
import tw.xiaoliangkou.app.service.StatusActionReceiver
import java.time.ZoneId
import java.time.ZonedDateTime

/** 每天早上的天氣通知：幾點送、通知的內容、提醒對方的按鈕、鬧鐘 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MorningWeatherTest {
    private val app = RuntimeEnvironment.getApplication() as TestXlkApp
    private val c get() = app.container
    private val tz = ZoneId.of("Asia/Taipei")

    private fun at(h: Int, m: Int, day: Int = 24) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, tz)

    @Test fun nextTime() {
        val m = 7 * 60 + 30
        assertEquals(at(7, 30), MorningWeather.nextAt(at(6, 0), m))
        assertEquals("過了就是明天", at(7, 30, 25), MorningWeather.nextAt(at(9, 0), m))
        assertEquals("鬧鐘剛響（或早幾秒）也是明天", at(7, 30, 25), MorningWeather.nextAt(at(7, 30), m))
        assertEquals(at(7, 30, 25), MorningWeather.nextAt(at(7, 29).plusSeconds(30), m))
        assertEquals(at(0, 0, 25), MorningWeather.nextAt(at(23, 59), 0))
    }

    @Test fun morningText() {
        val m = Weather.morning(FakeWeather.SUNNY_HOT, FakeWeather.RAINY_COOL, "小雨")!!
        assertEquals("早安 ☀️ 今天晴天 27°～34°", m.title)
        assertEquals(
            listOf(
                "你這邊 33° 晴天（27°～34°）：擦防曬、戴帽子或撐陽傘、多喝水，小心中暑",
                "小雨 那邊 14° 下雨（12°～17°）：記得帶傘、戴口罩，別在戶外運動",
            ),
            m.lines,
        )
        val nice = Weather.morning(FakeWeather.NICE, null, "小雨")!!
        assertEquals(listOf("你這邊 24° 大致晴朗（21°～26°）：很適合出門 😊"), nice.lines)
        assertEquals("早安！看看 小雨 那邊的天氣", Weather.morning(null, FakeWeather.NICE, "小雨")!!.title)
        assertNull(Weather.morning(null, null, "小雨"))
    }

    /** 示範模式、設定都載入好（App 的協程跑在主執行緒上，要讓它跑一下） */
    private fun demo(): AppState {
        c.startDemo()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && (c.prefs.value == null || c.repo.value?.state?.value?.loaded != true)) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        return c.repo.value!!.state.value
    }

    private fun morningNotification() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        .firstOrNull { it.channelId == Notifier.CH_WEATHER }

    @Test fun postsBothSidesWithARemindButton() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        demo()
        val result = runBlocking { MorningWeather.post(app, c) }
        assertEquals("已經送出今天的天氣提醒 🌤️", result)
        val n = morningNotification()!!
        assertEquals("早安 ☀️ 今天晴天 27°～34°", n.extras.getString("android.title"))
        assertTrue(n.extras.getCharSequence("android.bigText").toString().contains("小雨 那邊 14° 下雨"))
        assertEquals("💌 提醒 小雨", n.actions.single().title.toString())
        val remind = shadowOf(n.actions.single().actionIntent).savedIntent
        assertEquals(StatusActionReceiver.COMFORT, remind.action)
        assertEquals("safe", remind.getStringExtra(StatusActionReceiver.EXTRA_KIND))
        assertEquals(Weather.reminder(Weather.advice(FakeWeather.RAINY_COOL)), remind.getStringExtra(StatusActionReceiver.EXTRA_MESSAGE))
        // 點通知打開天氣頁
        assertEquals("weather", shadowOf(n.contentIntent).savedIntent.getStringExtra(Notifier.EXTRA_ROUTE))
    }

    @Test fun remindButtonSendsTheAdviceAsAComfort() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val me = demo().myId
        runBlocking { MorningWeather.post(app, c) }
        val remind = shadowOf(morningNotification()!!.actions.single().actionIntent).savedIntent
        app.sendBroadcast(Intent(remind))
        val message = Weather.reminder(Weather.advice(FakeWeather.RAINY_COOL))
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && c.repo.value!!.state.value.comforts.none { it.senderId == me && it.message == message }) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(c.repo.value!!.state.value.comforts.any { it.senderId == me && it.kind == "safe" && it.message == message })
    }

    @Test fun nothingWithoutPermissionOrWeather() {
        demo()
        assertEquals("通知權限還沒打開", runBlocking { MorningWeather.post(app, c) })
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.weather.fail = "連不到天氣資料"
        assertEquals("連不到天氣資料", runBlocking { MorningWeather.post(app, c) })
        assertNull(morningNotification())
    }

    @Test fun alarmIsScheduledAndCancelled() {
        val am = app.getSystemService(AlarmManager::class.java)
        MorningWeather.schedule(app, true, 7 * 60 + 30, at(6, 0))
        val alarm = shadowOf(am).scheduledAlarms.single()
        assertEquals(at(7, 30).toInstant().toEpochMilli(), alarm.triggerAtTime)
        assertEquals(MorningWeather.ACTION, shadowOf(alarm.operation).savedIntent.action)
        assertEquals(MorningWeatherReceiver::class.java.name, shadowOf(alarm.operation).savedIntent.component!!.className)
        // 換時間：同一個鬧鐘換掉
        MorningWeather.schedule(app, true, 8 * 60, at(6, 0))
        assertEquals(at(8, 0).toInstant().toEpochMilli(), shadowOf(am).scheduledAlarms.single().triggerAtTime)
        MorningWeather.schedule(app, false, 8 * 60)
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }
}
