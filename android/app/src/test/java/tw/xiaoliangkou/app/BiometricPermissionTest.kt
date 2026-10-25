package tw.xiaoliangkou.app

import android.Manifest
import android.content.pm.PackageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.ui.SystemBiometrics

/** 系統的指紋、臉部辨識要宣告 USE_BIOMETRIC，不然一問「能不能用」App 就當掉（0.2.8 打開 App 安全性會閃退） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricPermissionTest {
    @Test fun manifestAsksForBiometrics() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions!!.contains(Manifest.permission.USE_BIOMETRIC))
    }

    @Test fun askingTheSystemNeverCrashes() {
        // 測試機上沒有指紋：回傳原因，不會丟例外
        val problem = SystemBiometrics.problem(RuntimeEnvironment.getApplication())
        assertTrue(problem == null || problem.isNotEmpty())
    }
}
