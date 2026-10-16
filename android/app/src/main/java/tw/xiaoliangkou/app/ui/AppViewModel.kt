package tw.xiaoliangkou.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.xiaoliangkou.app.container
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.Banner
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.SettingsStore

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val container = app.container
    val prefs: StateFlow<SettingsStore.Prefs?> = container.prefs
    val repo: StateFlow<CoupleRepository?> = container.repo

    val state: StateFlow<AppState> = repo
        .flatMapLatest { it?.state ?: flowOf(AppState()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppState())

    val banners: Flow<Banner> = repo.flatMapLatest { it?.banners ?: emptyFlow() }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** 送出安撫等動作後要飄愛心 */
    private val _hearts = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val hearts: SharedFlow<Unit> = _hearts

    private var working by mutableStateOf(false)
    /** 正在上傳的照片、語音有幾個 */
    var uploads by mutableIntStateOf(0)
        private set
    /** 正在存資料或上傳檔案：按鈕先停用（上傳完才能按儲存） */
    val busy: Boolean get() = working || uploads > 0

    /** 上傳照片、語音：上傳期間 [busy] */
    suspend fun <T> uploading(block: suspend () -> T): T {
        uploads++
        try {
            return block()
        } finally {
            uploads--
        }
    }

    val demo: DemoRepository? get() = repo.value as? DemoRepository

    fun toast(text: String) { _messages.tryEmit(text) }
    fun hearts() { _hearts.tryEmit(Unit) }

    /** 執行資料操作：處理忙碌狀態與錯誤訊息 */
    fun act(success: String? = null, hearts: Boolean = false, onDone: () -> Unit = {}, block: suspend (CoupleRepository) -> Unit) {
        val r = repo.value ?: return
        viewModelScope.launch {
            working = true
            try {
                block(r)
                if (hearts) _hearts.emit(Unit)
                success?.let { _messages.emit(it) }
                onDone()
            } catch (e: AppException) {
                _messages.emit(e.message ?: "發生錯誤")
            } catch (e: Exception) {
                _messages.emit("發生錯誤：${e.message ?: e.javaClass.simpleName}")
            } finally {
                working = false
            }
        }
    }

    /** 帳號相關（登入、註冊）：不需要已登入的資料來源 */
    fun account(onDone: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            working = true
            try {
                block()
                onDone()
            } catch (e: AppException) {
                _messages.emit(e.message ?: "發生錯誤")
            } catch (e: Exception) {
                _messages.emit("發生錯誤：${e.message ?: e.javaClass.simpleName}")
            } finally {
                working = false
            }
        }
    }

    fun refresh() = act { it.refreshAll() }
}
