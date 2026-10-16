package tw.xiaoliangkou.app.ui.screens

import androidx.compose.runtime.Composable
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.SheetSpec

// 骨架：B1 會實作

@Composable
fun MyProfileScreen(s: AppState, vm: AppViewModel, back: () -> Unit) = Placeholder("我的資料", back)

@Composable
fun PartnerProfileScreen(s: AppState, vm: AppViewModel, back: () -> Unit, sheet: (SheetSpec) -> Unit) = Placeholder("${s.partnerName} 的資料", back)

@Composable
fun NoteSheet(s: AppState, vm: AppViewModel, id: String?, close: () -> Unit) = Unit
