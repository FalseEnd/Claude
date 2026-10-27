package tw.xiaoliangkou.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.BitmapFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.osmdroid.config.Configuration
import tw.xiaoliangkou.app.core.PaymentParser
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.AppLock
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.Errors
import tw.xiaoliangkou.app.data.Giphy
import tw.xiaoliangkou.app.data.InviteLink
import tw.xiaoliangkou.app.data.MediaCache
import tw.xiaoliangkou.app.data.MerchantMemory
import tw.xiaoliangkou.app.data.OpenMeteo
import tw.xiaoliangkou.app.data.PlaceSearch
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.Session
import tw.xiaoliangkou.app.data.SessionManager
import tw.xiaoliangkou.app.data.SettingsStore
import tw.xiaoliangkou.app.data.Supabase
import tw.xiaoliangkou.app.data.SupabaseClient
import tw.xiaoliangkou.app.data.VoicePlayer
import tw.xiaoliangkou.app.data.WeatherSource
import tw.xiaoliangkou.app.data.apiCall
import tw.xiaoliangkou.app.data.giphyMediaCache
import tw.xiaoliangkou.app.service.AutoBookkeeping
import tw.xiaoliangkou.app.service.MorningWeather
import tw.xiaoliangkou.app.service.Notifier
import tw.xiaoliangkou.app.service.ShareService
import tw.xiaoliangkou.app.ui.BiometricAuth
import tw.xiaoliangkou.app.ui.SystemBiometrics
import tw.xiaoliangkou.app.ui.components.stickerImageLoader
import tw.xiaoliangkou.app.widget.MemoWidget
import tw.xiaoliangkou.app.widget.MemoWidgetSnapshot
import tw.xiaoliangkou.app.widget.WidgetSnapshot
import tw.xiaoliangkou.app.widget.Widgets

// open：測試用 TestXlkApp 換掉會連網路的東西（Robolectric 會自動用它）
open class XlkApp : Application() {
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

    // 畫面收起來、系統記憶體不夠：下載過的貼圖檔不用留著（背景服務讓 App 一直活著，不要一直佔著記憶體），回來再載
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) giphyMediaCache.clear()
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
    /** 記住改過的商家名字和類別（自動記帳、自己記帳都會用） */
    val merchants = MerchantMemory(app)
    val autoBookkeeping = AutoBookkeeping(app, ledger = { (_repo.value as? RemoteRepository)?.let(::RemoteLedger) }, enabled = { _prefs.value?.autoBookkeeping != false }, memory = merchants)

    private class RemoteLedger(private val r: RemoteRepository) : AutoBookkeeping.Ledger {
        override suspend fun add(p: PaymentParser.Parsed, dedupeKey: String) = r.addAutoExpense(p, dedupeKey)
        override suspend fun rename(id: String, merchant: String, category: String) =
            r.saveExpense(id, buildJsonObject { put("merchant", merchant); put("category", category) })
    }
    /** 地圖的地址搜尋 */
    val placeSearch = PlaceSearch(app, "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
    /** GIPHY：搜尋貼圖（查過的頁面記在記憶體裡）、通知裡的貼圖；測試時換成假的 */
    var giphy = Giphy()
    /** App 鎖（密碼、指紋或臉部）；辨識在測試時換成假的 */
    val lock = AppLock(app)
    var biometrics: BiometricAuth = SystemBiometrics
    /** 雙方所在地的天氣（Open-Meteo）；測試時換成假的 */
    var weather: WeatherSource = OpenMeteo(app)

    /**
     * 顯示 GIPHY 貼圖的圖片載入器（用到才建立）：只放記憶體、不存檔。測試時換成假的。
     * 不用 Coil 全域的那個：測試之間會互相影響
     */
    var stickerImages: ImageLoader
        get() = images ?: stickerImageLoader(app).also { images = it }
        set(value) { images = value }
    private var images: ImageLoader? = null

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
                // 備忘錄還沒載入（或載入失敗）時不要畫：不然小工具會先變成「沒有放在桌面的備忘錄」、縮圖也被刪掉
                .filter { it == null || (it.loaded && it.memosLoaded) }
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
        // App 鎖：離開 App 超過設定的時間再回來就鎖上（相片挑選器、權限視窗也算離開，所以預設等 1 分鐘）
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> lock.onBackground()
                Lifecycle.Event.ON_START -> lock.onForeground()
                else -> Unit
            }
        })
        // 早上的天氣通知：登入後依設定排好（開機、更新 App 後 BootReceiver 叫起 App 時也會重排）
        scope.launch {
            _prefs.filterNotNull()
                .map { (it.morningWeather && it.mode == SettingsStore.Mode.REMOTE) to it.morningMinutes }
                .distinctUntilChanged()
                .collect { (on, minutes) -> MorningWeather.schedule(app, on, minutes) }
        }
        // 回到 App：補抓還沒抓到的縮圖；系統字型大小改了也重新排版
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) scope.launch(Dispatchers.IO) { MemoWidget.refresh(app, media) }
        })
        // 回到前景：即時連線斷了的話馬上重連，也先用 RPC 補抓通知。
        // 資料庫還是舊的話再檢查一次：使用者常常是到電腦跑完 upgrade-v2.sql 再回來（背景服務讓 App 一直活著，不會重新載入）
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START && supabase != null) {
                // 定位權限可能是在系統設定裡剛打開的：讓背景服務重新檢查、開始定位
                ShareService.start(app)
                realtime.reconnectNow()
                realtime.pollIfDisconnected()
                (_repo.value as? RemoteRepository)?.recheckSchema()
            }
        })
    }

    suspend fun reloadPrefs() { _prefs.value = settings.load() }

    suspend fun setMorningWeather(on: Boolean, minutes: Int) { settings.setMorningWeather(on, minutes); reloadPrefs() }

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
        // 還是同一個登入（沒登出、沒換帳號）才補上通知的貼圖
        val repo = RemoteRepository(c.api, realtime, scope) { n ->
            if (!isForeground) postNotification(n) { supabase === c }
        }
        _repo.value = repo
        scope.launch { runCatching { repo.refreshAll() } }
        ShareService.start(app)
    }

    /**
     * 背景收到的通知：先發文字的。附了 GIPHY 貼圖的話，再到背景抓不會動的那張（最多等 5 秒），
     * 抓到時通知還在、[current] 也還是對的，才把同一則換成有大圖的（不會再響一次）；抓不到就留著文字的
     */
    internal fun postNotification(n: RealtimeClient.Event.Notification, current: () -> Boolean): Job? {
        notifier.show(n.id, n.title, n.body, n.kind, n.data)
        val sticker = (n.data?.get("sticker") as? JsonPrimitive)?.contentOrNull?.takeIf(Giphy::isValidId) ?: return null
        // 看不到的通知（沒有權限、App 的通知關掉了、這個頻道被封鎖）就不用向 GIPHY 抓圖
        if (!notifier.canShow(n.kind)) return null
        val client = giphy
        return scope.launch {
            val picture = withContext(Dispatchers.IO) {
                withTimeoutOrNull(5_000) { client.still(sticker)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            } ?: return@launch
            if (current() && notifier.isShowing(n.id)) notifier.show(n.id, n.title, n.body, n.kind, n.data, picture)
        }
    }

    suspend fun logout() {
        val c = supabase
        ShareService.stop(app)
        stopCurrent()
        settings.clear()
        // 忘記 App 鎖的密碼就是靠登出再登入：登出時一起關掉
        lock.disable()
        merchants.clear()
        reloadPrefs()
        media.clear()
        // 告訴 Supabase 這支手機登出了：token 過期的話先換新（最多等 5 秒），沒網路也沒關係
        if (c != null) scope.launch { runCatching { withTimeout(15_000) { c.logout(refreshTimeoutMs = 5_000) } } }
    }

    suspend fun setOnboarded() { settings.setOnboarded(true); reloadPrefs() }
    suspend fun setAutoBookkeeping(v: Boolean) { settings.setAutoBookkeeping(v); reloadPrefs() }
    suspend fun setStatusNotification(v: Boolean) { settings.setStatusNotification(v); reloadPrefs() }
    suspend fun setStatusItems(items: Set<String>) { settings.setStatusItems(items); reloadPrefs() }
    /** 選了一張貼圖：記到「最近用過」 */
    suspend fun addRecentSticker(id: String) { settings.addRecentSticker(id); reloadPrefs() }

    private fun stopCurrent() {
        voice.stop()
        _repo.value?.close()
        _repo.value = null
        realtime.stop()
        supabase = null
    }
}

val android.content.Context.container: AppContainer get() = (applicationContext as XlkApp).container
