package tw.xiaoliangkou.app

import android.app.Activity
import android.content.Context
import tw.xiaoliangkou.app.ui.BiometricAuth

/** 假的指紋、臉部辨識：[problem] 是不能用的原因，[succeed] 決定辨識成功還是按了「用密碼」 */
class FakeBiometrics(var problem: String? = null, var succeed: Boolean = true) : BiometricAuth {
    var asked = 0

    override fun problem(context: Context) = problem

    override fun prompt(activity: Activity, title: String, onSuccess: () -> Unit, onCancel: () -> Unit) {
        asked++
        if (succeed) onSuccess() else onCancel()
    }
}
