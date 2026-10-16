package tw.xiaoliangkou.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.components.Note
import tw.xiaoliangkou.app.ui.components.ScreenHeader
import java.time.LocalDate

// 骨架：B2 會實作

@Composable
fun DayEntrySheet(s: AppState, vm: AppViewModel, id: String?, date: LocalDate, close: () -> Unit) = Unit

/** 還沒做好的畫面 */
@Composable
internal fun Placeholder(title: String, back: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader(title, onBack = back)
        Note("做到一半，下一版就會出現 🛠️", "🐣")
        Text("")
    }
}
