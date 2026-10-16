package tw.xiaoliangkou.app.ui.screens

import androidx.compose.runtime.Composable
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.SheetSpec

// 骨架：B3 會實作

@Composable
fun MemosScreen(s: AppState, vm: AppViewModel, back: () -> Unit, sheet: (SheetSpec) -> Unit) = Placeholder("備忘錄", back)

@Composable
fun MemoSheet(s: AppState, vm: AppViewModel, id: String?, close: () -> Unit) = Unit
