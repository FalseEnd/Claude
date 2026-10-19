package tw.xiaoliangkou.app

import android.Manifest
import android.provider.Settings
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.DeviceStatus
import tw.xiaoliangkou.app.data.PartnerStatus
import tw.xiaoliangkou.app.data.PhoneNumbers
import tw.xiaoliangkou.app.data.Sharing
import tw.xiaoliangkou.app.service.DeviceProbe
import tw.xiaoliangkou.app.ui.StatusText

/** 手機名稱、電信、從 SIM 卡帶入電話號碼 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneInfoTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun modelDoesNotRepeatTheBrand() {
        assertEquals("OPPO CPH2689", DeviceProbe.model("OPPO", "CPH2689"))
        assertEquals("asus ASUSAI2501C", DeviceProbe.model("asus", "ASUSAI2501C"))
        assertEquals("samsung SM-S921B", DeviceProbe.model("samsung", "SM-S921B"))
        assertEquals("Google Pixel 8", DeviceProbe.model("Google", "Pixel 8"))
        assertEquals("Samsung Galaxy S24", DeviceProbe.model("samsung", "Samsung Galaxy S24"))
    }

    @Test fun deviceNameOnlyWhenItSaysSomethingNew() {
        assertEquals("梓梓的 ROG Phone 9", DeviceProbe.deviceName(" 梓梓的 ROG Phone 9 ", "asus ASUSAI2501C"))
        assertEquals("OPPO Find X8 Pro", DeviceProbe.deviceName("OPPO Find X8 Pro", "OPPO CPH2689"))
        // 名稱就是型號：不要同樣的字寫兩次
        assertNull(DeviceProbe.deviceName("ASUS_AI2501C", "asus ASUSAI2501C"))
        assertNull(DeviceProbe.deviceName("CPH2689", "OPPO CPH2689"))
        assertNull(DeviceProbe.deviceName("Pixel 8", "Google Pixel 8"))
        assertNull(DeviceProbe.deviceName("  ", "OPPO CPH2689"))
        assertNull(DeviceProbe.deviceName(null, "OPPO CPH2689"))
    }

    @Test fun deviceNameFallsBackToTheBluetoothName() {
        val cr = app.contentResolver
        Settings.Global.putString(cr, Settings.Global.DEVICE_NAME, "梓梓的 ROG Phone 9")
        Settings.Secure.putString(cr, "bluetooth_name", "藍牙名稱")
        assertEquals("梓梓的 ROG Phone 9", DeviceProbe.read(app).deviceName)
        // 有些廠牌只改了藍牙名稱
        Settings.Global.putString(cr, Settings.Global.DEVICE_NAME, " ")
        assertEquals("藍牙名稱", DeviceProbe.read(app).deviceName)
        Settings.Secure.putString(cr, "bluetooth_name", null)
        assertNull(DeviceProbe.read(app).deviceName)
    }

    @Test fun taiwanCarriersInChinese() {
        assertEquals("中華電信", DeviceProbe.carrierName("46692", "Chunghwa Telecom", "Chunghwa"))
        assertEquals("台灣大哥大", DeviceProbe.carrierName("46697", "", ""))
        assertEquals("遠傳電信", DeviceProbe.carrierName("46601", "FET", "Far EasTone"))
        // 代碼不認得：看名字
        assertEquals("台灣大哥大", DeviceProbe.carrierName("46600", "Taiwan Mobile", null))
        assertEquals("遠傳電信", DeviceProbe.carrierName(null, null, "FarEasTone"))
        assertEquals("亞太電信", DeviceProbe.carrierName("", "APTG", null))
        // 國外的卡：照 SIM 卡上的名字
        assertEquals("SoftBank", DeviceProbe.carrierName("44020", "SoftBank", null))
        assertNull(DeviceProbe.carrierName(null, " ", ""))
    }

    @Test fun carrierFromTheSim() {
        val tm = app.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setSimState(TelephonyManager.SIM_STATE_READY)
        shadowOf(tm).setSimOperator("46692")
        shadowOf(tm).setSimOperatorName("Chunghwa Telecom")
        assertEquals("中華電信", DeviceProbe.carrier(app))
        shadowOf(tm).setSimState(TelephonyManager.SIM_STATE_ABSENT)
        assertNull("沒有 SIM 卡", DeviceProbe.carrier(app))
    }

    @Test fun readingThisPhoneNeverCrashes() {
        val r = DeviceProbe.read(app)
        assertTrue(r.network in setOf("wifi", "cellular", "offline"))
        assertTrue(r.deviceModel.isNotBlank())
        val d = DeviceProbe.status("me", r, steps = 1_234)
        assertEquals(1_234, d.stepsToday)
        assertEquals(r.deviceModel, d.deviceModel)
    }

    @Test fun taiwanMobileNumbers() {
        assertEquals("0912-345-678", PhoneNumbers.normalize("+886912345678"))
        assertEquals("0912-345-678", PhoneNumbers.normalize("+886 912 345 678"))
        assertEquals("0912-345-678", PhoneNumbers.normalize("886912345678"))
        assertEquals("0912-345-678", PhoneNumbers.normalize("00886912345678"))
        assertEquals("0912-345-678", PhoneNumbers.normalize("0912345678"))
        assertEquals("0912-345-678", PhoneNumbers.normalize("+8860912345678"))
        // 市話、國外號碼：只整理掉空白和符號
        assertEquals("0223456789", PhoneNumbers.normalize("+886 2 2345 6789"))
        assertEquals("+819012345678", PhoneNumbers.normalize("+81 90-1234-5678"))
        // 沒存號碼時有些手機會給空字串或「0」
        assertNull(PhoneNumbers.normalize(""))
        assertNull(PhoneNumbers.normalize("0"))
        assertNull(PhoneNumbers.normalize(null))
    }

    @Test fun pickingFromDualSim() {
        assertEquals(PhoneNumbers.Pick.None, PhoneNumbers.pick(listOf(null, "", "0")))
        assertEquals(PhoneNumbers.Pick.One("0912-345-678"), PhoneNumbers.pick(listOf("+886912345678", "0912345678", null)))
        assertEquals(
            PhoneNumbers.Pick.Many(listOf("0912-345-678", "0987-654-321")),
            PhoneNumbers.pick(listOf("+886912345678", "+886987654321")),
        )
    }

    @Test fun simNumberNeedsPermission() {
        val tm = app.getSystemService(TelephonyManager::class.java)
        shadowOf(tm).setLine1Number("+886912345678")
        assertTrue("沒有權限就不讀", PhoneNumbers.readFromSim(app).isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        assertEquals(PhoneNumbers.Pick.One("0912-345-678"), PhoneNumbers.pick(PhoneNumbers.readFromSim(app)))
    }

    @Test fun statusLineShowsTheCarrier() {
        val now = java.time.Instant.now().toString()
        val s = AppState(
            loaded = true,
            me = tw.xiaoliangkou.app.data.Profile("me", "阿哲"),
            partner = tw.xiaoliangkou.app.data.Profile("p", "小雨"),
            couple = tw.xiaoliangkou.app.data.Couple("c", "2025-01-01"),
            partnerSharing = Sharing("p", shareDevice = true),
            partnerStatus = PartnerStatus(DeviceStatus("p", 50, false, "cellular", "約 30 Mbps", false, null, now, "OPPO CPH2689", now, carrier = "中華電信"), null),
        )
        assertTrue(StatusText.of(s).items.any { it.text == "中華電信（約 30 Mbps）" })
        // 舊版 App 沒有回報電信
        val old = s.copy(partnerStatus = PartnerStatus(s.partnerStatus.device!!.copy(carrier = null), null))
        assertTrue(StatusText.of(old).items.any { it.text == "行動網路（約 30 Mbps）" })
    }
}
