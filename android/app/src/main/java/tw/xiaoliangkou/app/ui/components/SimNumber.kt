package tw.xiaoliangkou.app.ui.components

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import tw.xiaoliangkou.app.data.PhoneNumbers
import tw.xiaoliangkou.app.ui.AppViewModel

/**
 * 「從 SIM 卡帶入」電話號碼：
 * 1. 先用 Google 的 Phone Number Hint：不用權限，跳出這支手機的號碼讓你點一下
 * 2. 不行（沒有 Google 服務、手機不知道號碼）再問「電話號碼」權限，直接讀 SIM 卡；雙卡讓你選一個
 * 帶入後只是填進欄位，按「儲存」才會存（台灣很多 SIM 卡沒存號碼，讀不到就請使用者自己打）
 */
@Composable
fun SimNumberButton(vm: AppViewModel, onNumber: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<String>>(emptyList()) }

    fun deliver(raw: String?) {
        val n = PhoneNumbers.normalize(raw)
        if (n == null) {
            vm.toast("讀不到電話號碼，請自己輸入一下 🙏")
            return
        }
        onNumber(n)
        vm.toast("帶入 $n 了，記得按「儲存」")
    }

    fun fromSim() {
        scope.launch {
            val numbers = withContext(Dispatchers.IO) { PhoneNumbers.readFromSim(context) }
            when (val p = PhoneNumbers.pick(numbers)) {
                PhoneNumbers.Pick.None -> vm.toast("SIM 卡裡沒有存電話號碼（台灣很多 SIM 卡都沒存），請自己輸入一下 🙏")
                is PhoneNumbers.Pick.One -> deliver(p.number)
                is PhoneNumbers.Pick.Many -> choices = p.numbers
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fromSim() else vm.toast("沒有允許讀電話號碼，請自己輸入一下")
    }

    fun fallback() {
        if (PhoneNumbers.canReadSim(context)) fromSim() else permission.launch(Manifest.permission.READ_PHONE_NUMBERS)
    }

    val hint = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        // 自己關掉選單就算了，不要接著跳權限
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val number = runCatching { Identity.getSignInClient(context).getPhoneNumberFromIntent(res.data) }.getOrNull()
        if (number != null) deliver(number) else fallback()
    }

    SoftButton("從 SIM 卡帶入", modifier, icon = Icons.Rounded.SimCard, small = true) {
        // 在畫面的 scope 裡等 Google 回應：等的時候離開畫面就一起取消，不會對已經關掉的畫面跳視窗
        scope.launch {
            val pi = runCatching {
                Identity.getSignInClient(context).getPhoneNumberHintIntent(GetPhoneNumberHintIntentRequest.builder().build()).await()
            }.getOrNull()
            ensureActive()
            if (pi == null || runCatching { hint.launch(IntentSenderRequest.Builder(pi).build()) }.isFailure) fallback()
        }
    }

    if (choices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { choices = emptyList() },
            title = { Text("要用哪一個號碼？") },
            text = {
                Column {
                    choices.forEach { n ->
                        Text(
                            n, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().clickable { choices = emptyList(); deliver(n) }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choices = emptyList() }) { Text("取消") } },
        )
    }
}
