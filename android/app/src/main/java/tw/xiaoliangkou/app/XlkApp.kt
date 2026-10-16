package tw.xiaoliangkou.app

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.osmdroid.config.Configuration
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.data.InviteLink
import tw.xiaoliangkou.app.data.MediaCache
import tw.xiaoliangkou.app.data.PlaceSearch
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.data.SessionManager
import tw.xiaoliangkou.app.data.SettingsStore
import tw.xiaoliangkou.app.data.Supabase
import tw.xiaoliangkou.app.data.SupabaseClient
import tw.xiaoliangkou.app.data.VoicePlayer
import tw.xiaoliangkou.app.data.apiCall
import tw.xiaoliangkou.app.service.AutoBookkeeping
import tw.xiaoliangkou.app.service.Notifier
import tw.xiaoliangkou.app.service.ShareService
import tw.xiaoliangkou.app.widget.MemoWidget
import tw.xiaoliangkou.app.widget.MemoWidgetSnapshot
import tw.xiaoliangkou.app.widget.WidgetSnapshot
import tw.xiaoliangkou.app.widget.Widgets

class XlkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // OpenStreetMap 圖磚：需要識別用的 User-Agent，快取放在 App 自己的資料夾
        Configuration.getInstance().apply {
            userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir.resolve("tiles")
        }
        container = AppContainer(this)
    }
}

/** 整個 App 共用的物件：設定、目前的資料來源（Supabase 或示範）、即時連線、通知 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app)
    val notifier = Notifier(app).also { it.createChannels() }
    val realtime = RealtimeClient(scope)
    /** 自動記帳（付款通知）與最近處理過的付款通知 */
    val autoBookkeeping = AutoBookkeeping(app, ledger = { (_repo.value as? RemoteRepository)?.let(::RemoteLedger) }, enabled = { _prefs.value?.autoBookkeeping != false })

    private class RemoteLedger(private val r: RemoteRepository) : AutoBookkeeping.Ledger {
        override suspend fun add(p: PaymentParser.Parsed, dedupeKey: String) = r.addAutoExpense(p, dedupeKey)
        override suspend fun rename(id: String, merchant: String, category: String) =
            r.saveExpense(id, buildJsonObject { put("merchant", merchant); put("category", category) })
    }
    /** 地圖的地址搜尋 */
    val placeSearch = PlaceSearch(app, "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")

    private val _repo = MutableStateFlow<CoupleRepository?>(null)
    val repo: StateFlow<CoupleRepository?> = _repo
    /** 照片、語音：下載過的檔案與縮圖 */
    val media = MediaCache(app) { _repo.value }
    /** 播放語音（同時只播一段） */
    val voice = VoicePlayer()
    private val _prefs = MutableStateFlow<SettingsStore.Prefs?>(null)
    val prefs: StateFlow<SettingsStore.Prefs?> = _prefs

    /** 給畫面顯示的提示（登入過期、邀請連結等） */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices

    /**
     * 邀請連結帶進來、使用者還沒確認的網址與金鑰。
     * 連結誰都能做，所以只顯示確認視窗：不會自動測試、不會存起來，也不會動到示範模式或登入狀態。
     */
    private val _pendingInvite = MutableStateFlow<Pair<String, String>?>(null)
    val pendingInvite: StateFlow<Pair<String, String>?> = _pendingInvite

    /** 使用者確認邀請後，預先填進「連接 Supabase」的網址與金鑰（還沒測試、還沒存；按「測試連線」成功才存） */
    private val _prefill = MutableStateFlow<Pair<String, String>?>(null)
    val prefill: StateFlow<Pair<String, String>?> = _prefill

    private var supabase: SupabaseClient? = null

    val isForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    init {
        scope.launch {
            val p = settings.load()
            when (p.mode) {
                SettingsStore.Mode.DEMO -> _repo.value = DemoRepository(scope)
                SettingsStore.Mode.REMOTE -> if (p.session != null && p.configured) startRemote(p.supabaseUrl, p.supabaseKey, p.session)
                SettingsStore.Mode.NONE -> Unit
            }
            _prefs.value = p
        }
        // 桌面小工具：資料一變就重畫（登出後就不再顯示對方的資料）
        scope.launch {
            _prefs.first { it != null } // 設定載入前還不知道有沒有登入，先不要把小工具改成「請登入」
            _repo.flatMapLatest { r -> r?.state ?: flowOf(null) }
                .filter { it == null || it.loaded }
                .map { WidgetSnapshot.of(it) }
                .distinctUntilChanged()
                .collectLatest { snap ->
                    delay(800) // 一次來好幾個變動時，等一下再一起畫
                    withContext(Dispatchers.IO) { Widgets.publish(app, snap) }
                }
        }
        // 備忘錄小工具：放在桌面的備忘錄（照片縮圖從 media 讀；登出後就清掉）
        scope.launch {
            _prefs.first { it != null }
            _repo.flatMapLatest { r -> r?.state ?: flowOf(null) }
                .filter { it == null || it.loaded }
                .map { MemoWidgetSnapshot.of(it) }
                .distinctUntilChanged()
                .collectLatest { snap ->
                    delay(800)
                    // 有縮圖沒抓到（網路不穩、對方的照片還沒上傳好）：等一下再試一次
                    if (!withContext(Dispatchers.IO) { MemoWidget.publish(app, snap, media) }) {
                        delay(60_000)
                        withContext(Dispatchers.IO) { MemoWidget.refresh(app, media) }
                    }
                }
        }
        // 回到 App：補抓還沒抓到的縮圖；系統字型大小改了也重新排版
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) scope.launch(Dispatchers.IO) { MemoWidget.refresh(app, media) }
        })
        // 回到前景：即時連線斷了的話馬上重連，也先用 RPC 補抓通知
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START && supabase != null) {
                realtime.reconnectNow()
                realtime.pollIfDisconnected()
            }
        })
    }

    suspend fun reloadPrefs() { _prefs.value = settings.load() }

    /** 「測試連線」：呼叫 app_health，成功才存起來 */
    suspend fun testConnection(rawUrl: String, rawKey: String) {
        val url = Supabase.normalizeUrl(rawUrl)
        val key = Supabase.normalizeKey(rawKey)
        // 先在手機上檢查，有問題的網址、金鑰不送出去
        Supabase.urlProblem(url)?.let { throw AppException(it, "wrong_url") }
        Supabase.keyProblem(key)?.let { throw AppException(it, if (it == Supabase.SECRET_KEY) "secret_key" else "bad_key") }
        try {
            apiCall { SupabaseClient(url, key, null).api.health() }
        } catch (e: AppException) {
            throw Supabase.setupHint(e)
        }
        settings.setSupabase(url, key)
        _prefill.value = null
        reloadPrefs()
    }

    suspend fun login(email: String, password: String) = signedIn { it.signIn(email.trim(), password) }

    suspend fun register(email: String, password: String, displayName: String) =
        signedIn { it.signUp(email.trim(), password, displayName.trim()) }

    private suspend fun signedIn(block: suspend (SupabaseClient) -> Session) {
        val p = settings.load()
        if (!p.configured) throw AppException("請先連接 Supabase", "not_configured")
        val s = apiCall { block(SupabaseClient(p.supabaseUrl, p.supabaseKey, null)) }
        settings.setSession(s)
        startRemote(p.supabaseUrl, p.supabaseKey, s)
        reloadPrefs()
    }

    /** 改密碼：先用舊密碼登入一次確認，再改成新密碼（其他裝置的登入會失效） */
    suspend fun changePassword(old: String, new: String) {
        val c = supabase ?: return
        val sm = c.session ?: return
        val email = sm.current?.email ?: throw AppException(Errors.RELOGIN, "not_authenticated")
        val fresh = try {
            apiCall { c.signIn(email, old) }
        } catch (e: AppException) {
            if (e.code == "invalid_login") throw AppException("目前的密碼不正確", "invalid_login") else throw e
        }
        sm.replace(fresh)
        apiCall { c.updatePassword(fresh.accessToken, new) }
    }

    /** 分享給另一半的邀請（網址與金鑰） */
    fun inviteText(): String? = _prefs.value?.takeIf { it.configured }?.let { InviteLink.shareText(it.supabaseUrl, it.supabaseKey) }

    /** 打開 xiaoliangkou://connect 邀請連結：只請使用者確認，不會自動連線或存起來 */
    fun openInvite(link: String) {
        scope.launch {
            val parsed = InviteLink.parse(link)
            if (parsed == null) { _notices.emit("這個邀請連結看不懂，請另一半重新分享一次"); return@launch }
            val p = prefs.filterNotNull().first()
            when (p.mode) {
                // 已經登入：完全不動登入狀態
                SettingsStore.Mode.REMOTE -> _notices.emit(
                    if (p.supabaseUrl == parsed.first) "你們已經連在同一個小倆口了 💞"
                    else "要換到另一半的 Supabase，請先到「我的 › Supabase 與帳號」登出",
                )
                SettingsStore.Mode.DEMO, SettingsStore.Mode.NONE -> _pendingInvite.value = parsed
            }
        }
    }

    /** 使用者在確認視窗按了「要」：示範模式的話這時才離開，然後把網址、金鑰填進設定頁（還沒存） */
    fun confirmInvite() {
        val inv = _pendingInvite.value ?: return
        _pendingInvite.value = null
        scope.launch {
            when (prefs.value?.mode) {
                SettingsStore.Mode.REMOTE -> { _notices.emit("要換到另一半的 Supabase，請先到「我的 › Supabase 與帳號」登出"); return@launch }
                SettingsStore.Mode.DEMO -> logout()
                else -> Unit
            }
            _prefill.value = inv
        }
    }

    /** 使用者不要這個邀請 */
    fun dismissInvite() { _pendingInvite.value = null }

    /** 使用者在設定頁改回原本的設定（或離開設定頁） */
    fun clearPrefill() { _prefill.value = null }

    fun startDemo() {
        stopCurrent()
        _repo.value = DemoRepository(scope)
        scope.launch { settings.setDemo(); reloadPrefs() }
    }

    private fun startRemote(url: String, key: String, s: Session) {
        stopCurrent()
        val session = SessionManager(url, key, s)
        val c = SupabaseClient(url, key, session)
        session.onChange = { fresh ->
            if (supabase === c) {
                realtime.updateToken(fresh.accessToken)
                scope.launch { settings.updateSession(fresh) }
            }
        }
        session.onExpired = {
            scope.launch {
                if (supabase === c) {
                    _notices.emit(Errors.RELOGIN)
                    logout()
                }
            }
        }
        supabase = c
        realtime.start(url, key, s.userId, { session.freshToken() }, c.api)
        val repo = RemoteRepository(c.api, realtime, scope) { n ->
            if (!isForeground) notifier.show(n.id, n.title, n.body, n.kind, n.data)
        }
        _repo.value = repo
        scope.launch { runCatching { repo.refreshAll() } }
        ShareService.start(app)
    }

    suspend fun logout() {
        val c = supabase
        ShareService.stop(app)
        stopCurrent()
        settings.clear()
        reloadPrefs()
        media.clear()
        // 告訴 Supabase 這支手機登出了：token 過期的話先換新（最多等 5 秒），沒網路也沒關係
        if (c != null) scope.launch { runCatching { withTimeout(15_000) { c.logout(refreshTimeoutMs = 5_000) } } }
    }

    suspend fun setOnboarded() { settings.setOnboarded(true); reloadPrefs() }
    suspend fun setAutoBookkeeping(v: Boolean) { settings.setAutoBookkeeping(v); reloadPrefs() }
    suspend fun setStatusNotification(v: Boolean) { settings.setStatusNotification(v); reloadPrefs() }
    suspend fun setStatusItems(items: Set<String>) { settings.setStatusItems(items); reloadPrefs() }

    private fun stopCurrent() {
        voice.stop()
        _repo.value?.close()
        _repo.value = null
        realtime.stop()
        supabase = null
    }
}

val android.content.Context.container: AppContainer get() = (applicationContext as XlkApp).container
