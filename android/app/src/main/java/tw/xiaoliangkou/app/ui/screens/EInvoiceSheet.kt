package tw.xiaoliangkou.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.xiaoliangkou.app.core.EInvoice
import tw.xiaoliangkou.app.data.EInvoiceApi
import tw.xiaoliangkou.app.data.EInvoiceException
import tw.xiaoliangkou.app.service.EInvoiceSync
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.components.ButtonRow
import tw.xiaoliangkou.app.ui.components.CuteButton
import tw.xiaoliangkou.app.ui.components.CuteCard
import tw.xiaoliangkou.app.ui.components.CuteField
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.SoftButton
import tw.xiaoliangkou.app.ui.components.TidyText
import tw.xiaoliangkou.app.ui.components.ToggleRow
import java.nio.charset.Charset

/**
 * 發票載具自動記帳：填手機條碼、驗證碼、自己申請的 AppID，測試通過就每 6 小時自動查新的雲端發票；
 * 沒有 AppID 的話，可以匯入電子發票平台下載的消費明細 CSV
 */
@Composable
fun EInvoiceSheet(vm: AppViewModel, close: () -> Unit, sync: EInvoiceSync = vm.container.einvoice) {
    val store = sync.store
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var card by remember { mutableStateOf(store.card) }
    var verify by remember { mutableStateOf(store.verifyCode) }
    var appId by remember { mutableStateOf(store.appId) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // 存檔、同步後重畫（設定存在 SharedPreferences）
    var version by remember { mutableIntStateOf(0) }
    val saved = remember(version) { store.account != null }
    val enabled = remember(version) { store.enabled }
    val lastResult = remember(version) { store.lastResult }

    val cardOk = EInvoice.CARD.matches(card)
    fun run(block: suspend () -> String) {
        busy = true
        problem = null
        scope.launch {
            try {
                vm.toast(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: EInvoiceException) {
                problem = e.message
            } catch (e: Exception) {
                problem = "發生錯誤：${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
                version++
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked: Uri? ->
        if (picked != null) run {
            val text = withContext(Dispatchers.IO) { readText(context.contentResolver.openInputStream(picked)?.use { it.readBytes() } ?: ByteArray(0)) }
            "匯入完成：${sync.importCsv(text).text}"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("發票載具自動記帳 🧾", style = MaterialTheme.typography.titleLarge)
        TidyText(
            "用手機條碼結帳的雲端發票，消費後 1～2 天會上傳到財政部。打開後每 6 小時查一次，新的發票自動記一筆（來源：發票載具）；" +
                "付款通知已經記過的同一筆不會重複記，沒有店名的會補上發票上的店名。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CuteField(card, { card = it.uppercase().filter { c -> !c.isWhitespace() }.take(8) }, "手機條碼", placeholder = "/ABC1234")
        if (card.isNotEmpty() && !cardOk) Text("手機條碼是斜線開頭再 7 碼，例如 /ABC1234", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        CuteField(verify, { verify = it.trim().take(16) }, "驗證碼（申請手機條碼時設定的）", password = true)
        CuteField(appId, { appId = it.trim().take(40) }, "AppID（向財政部申請的）")
        ButtonRow {
            CuteButton(if (busy) "連線中…" else "儲存並測試", enabled = cardOk && verify.isNotEmpty() && appId.isNotEmpty() && !busy, onClick = {
                store.save(card, verify, appId)
                run {
                    val o = sync.sync()
                    // 查得到才打開自動同步（查不到的話一直重試也沒用）
                    store.enabled = true
                    "連線成功，自動同步打開了：${o.text}"
                }
            })
            if (saved) SoftButton("立即同步", icon = Icons.Rounded.Sync, enabled = !busy) { run { sync.sync().text } }
        }
        problem?.let { Note(it, "😥", MaterialTheme.colorScheme.errorContainer) }
        if (saved) {
            ToggleRow("自動同步", enabled, "每 6 小時查一次新的雲端發票", "🔄") { v -> store.enabled = v; version++ }
        }
        if (lastResult.isNotEmpty()) Text(lastResult, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        CuteCard(color = MaterialTheme.colorScheme.surfaceVariant) {
            Text("怎麼拿到 AppID", style = MaterialTheme.typography.titleSmall)
            TidyText(
                "1. 到財政部電子發票整合服務平台登入\n" +
                    "2. 進「電子發票 API 申請」填申請表（可以用自然人憑證驗證身分）\n" +
                    "3. 財政部審查通過後（大約 1～2 週）會寄 AppID 給你，填到上面就好\n" +
                    "只需要 AppID，不用填 API Key。",
                style = MaterialTheme.typography.bodySmall,
            )
            SoftButton("打開電子發票平台", Modifier.fillMaxWidth(), icon = Icons.Rounded.OpenInNew, small = true) { runCatching { uri.openUri(EInvoiceApi.PORTAL) } }
        }
        CuteCard(color = MaterialTheme.colorScheme.surfaceVariant) {
            Text("還沒有 AppID？", style = MaterialTheme.typography.titleSmall)
            TidyText(
                "到電子發票平台用手機條碼登入，在「消費明細」把發票匯出成 CSV，下載到手機後從這裡匯入；一樣不會重複記。",
                style = MaterialTheme.typography.bodySmall,
            )
            SoftButton("匯入消費明細 CSV", Modifier.fillMaxWidth(), icon = Icons.Rounded.FileOpen, small = true, enabled = !busy) {
                runCatching { pick.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }
            }
        }
        Note("手機條碼和驗證碼只存在這支手機（驗證碼加密保存），只會傳給財政部的電子發票平台；登出時會一起刪掉。", "🔒")
        if (saved) SoftButton("刪除載具設定", Modifier.fillMaxWidth(), enabled = !busy) {
            store.clear(); card = ""; verify = ""; appId = ""; problem = null; version++
        }
    }
}

/** CSV 可能是 UTF-8 或 Big5：UTF-8 讀起來有亂碼就改用 Big5 */
internal fun readText(bytes: ByteArray): String {
    val utf8 = bytes.toString(Charsets.UTF_8)
    if ('�' !in utf8) return utf8
    return runCatching { String(bytes, Charset.forName("Big5")) }.getOrDefault(utf8)
}
