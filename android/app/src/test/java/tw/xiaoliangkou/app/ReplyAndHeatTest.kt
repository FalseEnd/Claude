package tw.xiaoliangkou.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.core.app.RemoteInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.core.PhoneHeat
import tw.xiaoliangkou.app.core.PhoneHeat.Level
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.service.DeviceProbe
import tw.xiaoliangkou.app.service.Notifier
import tw.xiaoliangkou.app.service.StatusActionReceiver
import tw.xiaoliangkou.app.ui.StatusText

/** 在通知上直接回覆安撫（打字、回抱）；手機溫度的等級和文字 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplyAndHeatTest {
    private val app = RuntimeEnvironment.getApplication() as TestXlkApp
    private val c get() = app.container
    private val nm get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    // ================================================================ 手機溫度

    @Test fun heatLevels() {
        assertNull(PhoneHeat.level(null, null))
        assertEquals(Level.NORMAL, PhoneHeat.level(33.0, null))
        assertEquals(Level.NORMAL, PhoneHeat.level(39.9, 1))
        assertEquals(Level.WARM, PhoneHeat.level(40.0, null))
        assertEquals(Level.HOT, PhoneHeat.level(45.0, 0))
        assertEquals(Level.COLD, PhoneHeat.level(4.0, 0))
        assertEquals("系統說在降速就聽系統的", Level.WARM, PhoneHeat.level(36.0, 2))
        assertEquals(Level.HOT, PhoneHeat.level(36.0, 4))
        assertEquals("冰的時候系統說在降速：算熱", Level.HOT, PhoneHeat.level(3.0, 3))
        assertEquals("只有系統的等級", Level.NORMAL, PhoneHeat.level(null, 0))
        assertEquals("36.5°C", PhoneHeat.text(36.54))
        assertEquals(36.5, PhoneHeat.fromBattery(365)!!, 0.0)
        assertNull("沒有回報", PhoneHeat.fromBattery(Int.MIN_VALUE))
        assertNull("不合理", PhoneHeat.fromBattery(2000))
        assertNull(PhoneHeat.fromBattery(null))
    }

    @Test fun probeReportsTheTemperature() {
        val r = DeviceProbe.Reading(80, false, "wifi", null, false, true, null, "Google Pixel 8", null, batteryTemp = 37.2, thermal = 1)
        val d = DeviceProbe.status("me", r, null)
        assertEquals(37.2, d.batteryTemp!!, 0.0)
        assertEquals(1, d.thermal)
    }

    @Test fun partnerStatusLine() {
        val s = demo()
        fun with(temp: Double?, thermal: Int?) = s.copy(partnerStatus = s.partnerStatus.copy(device = s.partnerStatus.device!!.copy(batteryTemp = temp, thermal = thermal)))
        assertEquals("🌡️ 34.2°C", StatusText.of(with(34.2, 0)).item(StatusText.TEMP).toString())
        assertEquals("🔥 46.1°C 手機很燙", StatusText.of(with(46.1, 3)).item(StatusText.TEMP).toString())
        assertEquals("🥵 手機有點熱", StatusText.of(with(null, 2)).item(StatusText.TEMP).toString())
        assertNull("舊的 App 沒回報", StatusText.of(with(null, null)).item(StatusText.TEMP))
        assertTrue("預設不放在常駐通知", StatusText.TEMP !in StatusText.DEFAULTS)
    }

    // ================================================================ 在通知上回覆

    /** 示範模式、設定都載入好（App 的協程跑在主執行緒上，要讓它跑一下） */
    private fun demo(): AppState {
        c.startDemo()
        idleUntil { c.prefs.value != null && c.repo.value?.state?.value?.loaded == true }
        return c.repo.value!!.state.value
    }

    private fun idleUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end && !cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(cond())
    }

    /** 通知的 id 是 outbox 的 id + 100（Notifier.androidId） */
    private fun notification(id: Long) = nm.getNotification((id + 100).toInt())!!

    /** 按通知上的按鈕：打字的話把字放進 RemoteInput 的結果 */
    private fun press(id: Long, index: Int, typed: String? = null) {
        val action = notification(id).actions[index]
        val intent = Intent(shadowOf(action.actionIntent).savedIntent)
        if (typed != null) {
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(StatusActionReceiver.KEY_REPLY).build()), intent,
                Bundle().apply { putCharSequence(StatusActionReceiver.KEY_REPLY, typed) },
            )
        }
        app.sendBroadcast(intent)
    }

    @Test fun comfortNotificationsHaveReplyButtons() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        c.notifier.show(7, "小雨 給了你一個大大的抱抱", "晚安", "comfort", buildJsonObject { put("comfort_id", 12) })
        val n = notification(7)
        assertEquals(listOf("💬 回覆", "🤗 回抱"), n.actions.map { it.title.toString() })
        val input = n.actions[0].remoteInputs.single()
        assertEquals(StatusActionReceiver.KEY_REPLY, input.resultKey)
        assertEquals("回一句悄悄話…", input.label.toString())
        val saved = shadowOf(n.actions[0].actionIntent).savedIntent
        assertEquals(StatusActionReceiver.REPLY, saved.action)
        assertEquals(12L, saved.getLongExtra(StatusActionReceiver.EXTRA_COMFORT, -1))
        assertTrue("打字的 PendingIntent 要讓系統放得進字", shadowOf(n.actions[0].actionIntent).flags and PendingIntent.FLAG_MUTABLE != 0)
        assertTrue("回抱不用", shadowOf(n.actions[1].actionIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        // 對方心情不好（要不要安撫一下？）也可以直接回；開心、抵達這些不用
        c.notifier.show(8, "小雨 現在覺得難過", "要不要安撫一下？", "mood", buildJsonObject { put("mood", "sad") })
        assertEquals(listOf("💬 回覆", "🤗 抱抱"), notification(8).actions.map { it.title.toString() })
        c.notifier.show(9, "小雨 現在覺得開心", "今天超順利", "mood", buildJsonObject { put("mood", "happy") })
        assertTrue(notification(9).actions.isNullOrEmpty())
        c.notifier.show(10, "小雨 已抵達 公司", "", "place")
        assertTrue(notification(10).actions.isNullOrEmpty())
    }

    @Test fun typingAReplySendsItAndMarksTheComfortRead() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val s = demo()
        val got = s.comforts.first { it.receiverId == s.myId && it.readAt == null }
        c.notifier.show(21, "小雨 說：我在，想聊隨時找我", got.message.orEmpty(), "comfort", buildJsonObject { put("comfort_id", got.id) })
        press(21, 0, "  我也想你，晚安 💕  ")
        val repo = c.repo.value!!
        idleUntil { repo.state.value.comforts.any { it.senderId == s.myId && it.message == "我也想你，晚安 💕" } }
        val sent = repo.state.value.comforts.first { it.senderId == s.myId && it.message == "我也想你，晚安 💕" }
        assertEquals(StatusActionReceiver.REPLY_KIND, sent.kind)
        assertNotNull("收到的那則標成看過了", repo.state.value.comforts.first { it.id == got.id }.readAt)
        // 通知換成「已回覆」，沒有按鈕，幾秒後自己消失
        idleUntil { shadowOf(notification(21)).contentText.toString().startsWith("已回覆") }
        val n = notification(21)
        assertEquals("已回覆 小雨：我也想你，晚安 💕 💬", shadowOf(n).contentText.toString())
        assertTrue(n.actions.isNullOrEmpty())
        assertEquals(Notifier.REPLIED_MS, n.timeoutAfter)
    }

    @Test fun hugBack() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val s = demo()
        val hugs = s.comforts.count { it.senderId == s.myId && it.kind == "hug" }
        c.notifier.show(22, "小雨 給了你一個大大的抱抱", "點開回應一下吧", "comfort")
        press(22, 1)
        idleUntil { c.repo.value!!.state.value.comforts.count { it.senderId == s.myId && it.kind == "hug" } == hugs + 1 }
        idleUntil { shadowOf(notification(22)).contentText.toString() == "已經抱抱 小雨 🤗" }
    }

    /** 沒送出去（例如傳太快被擋下來）：寫原因，按鈕留著可以再試 */
    @Test fun failedReplyKeepsTheButtons() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        demo()
        val demoRepo = c.repo.value!!
        @Suppress("UNCHECKED_CAST")
        val field = c.javaClass.getDeclaredField("_repo").apply { isAccessible = true }.get(c) as MutableStateFlow<CoupleRepository?>
        field.value = object : CoupleRepository by demoRepo {
            override suspend fun sendComfort(kind: String, message: String?, sticker: String?) = throw AppException("傳太快了，休息一下再傳", "rate_limited")
        }
        c.notifier.show(23, "小雨 給了你一個大大的抱抱", "點開回應一下吧", "comfort", buildJsonObject { put("comfort_id", 5) })
        press(23, 0, "好")
        idleUntil { shadowOf(notification(23)).contentText.toString().startsWith("沒有送出") }
        val n = notification(23)
        assertEquals("沒有送出：傳太快了，休息一下再傳", shadowOf(n).contentText.toString())
        assertEquals(listOf("💬 回覆", "🤗 回抱"), n.actions.map { it.title.toString() })
    }
}
