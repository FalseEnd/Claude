package tw.xiaoliangkou.app.ui

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/** 指紋、臉部辨識；畫面測試時換成假的 */
interface BiometricAuth {
    /** 這支手機能不能用：null＝可以，不然是原因 */
    fun problem(context: Context): String?

    /** 跳出系統的辨識視窗；成功呼叫 [onSuccess]，按「用密碼」或取消呼叫 [onCancel] */
    fun prompt(activity: Activity, title: String, onSuccess: () -> Unit, onCancel: () -> Unit)
}

/**
 * 用系統內建的 BiometricPrompt（Android 10 以上），不用另外裝套件。
 * 允許「弱」的生物辨識：很多手機的臉部辨識屬於這一級；指紋都可以
 */
object SystemBiometrics : BiometricAuth {
    override fun problem(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Android 10 以上才能用指紋或臉部解鎖"
        val bm = context.getSystemService(BiometricManager::class.java) ?: return "這支手機不支援指紋或臉部辨識"
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } else {
            @Suppress("DEPRECATION")
            bm.canAuthenticate()
        }
        return when (r) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "這支手機還沒設定指紋或臉部辨識，先到系統設定新增"
            else -> "這支手機不支援指紋或臉部辨識"
        }
    }

    override fun prompt(activity: Activity, title: String, onSuccess: () -> Unit, onCancel: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return onCancel()
        val b = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("用指紋或臉部解鎖")
            .setConfirmationRequired(false)
            .setNegativeButton("用密碼", activity.mainExecutor) { _, _ -> onCancel() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        runCatching {
            b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onCancel()
            })
        }.onFailure { onCancel() }
    }
}

