package tw.xiaoliangkou.app

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.osmdroid.config.Configuration
import tw.xiaoliangkou.app.data.Api
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.RealtimeClient
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.data.SettingsStore
import tw.xiaoliangkou.app.data.apiCall
import tw.xiaoliangkou.app.service.Notifier
import tw.xiaoliangkou.app.service.ShareService

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

/** 整個 App 共用的物件：設定、目前的資料來源（伺服器或示範）、即時連線、通知 */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app)
    val notifier = Notifier(app).also { it.createChannels() }
    val realtime = RealtimeClient(scope)

    private val _repo = MutableStateFlow<CoupleRepository?>(null)
    val repo: StateFlow<CoupleRepository?> = _repo
    private val _prefs = MutableStateFlow<SettingsStore.Prefs?>(null)
    val prefs: StateFlow<SettingsStore.Prefs?> = _prefs

    @Volatile private var token: String? = null
    private var api: Api? = null

    val isForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    init {
        scope.launch {
            val p = settings.load()
            when (p.mode) {
                SettingsStore.Mode.DEMO -> _repo.value = DemoRepository(scope)
                SettingsStore.Mode.REMOTE -> if (p.token != null) startRemote(p.serverUrl, p.token)
                SettingsStore.Mode.NONE -> Unit
            }
            _prefs.value = p
        }
    }

    private suspend fun reloadPrefs() { _prefs.value = settings.load() }

    suspend fun testServer(url: String) {
        apiCall { Api.create(url) { null }.health() }
        settings.setServerUrl(url)
    }

    suspend fun login(url: String, username: String, password: String) {
        val r = apiCall { Api.create(url) { null }.login(buildJsonObject { put("username", username); put("password", password) }) }
        settings.setAuth(url, r.token, r.userId ?: "")
        startRemote(url, r.token)
        reloadPrefs()
    }

    suspend fun register(url: String, username: String, password: String, displayName: String) {
        val r = apiCall {
            Api.create(url) { null }.register(buildJsonObject {
                put("username", username); put("password", password); put("displayName", displayName)
            })
        }
        settings.setAuth(url, r.token, r.userId ?: "")
        startRemote(url, r.token)
        reloadPrefs()
    }

    suspend fun changePassword(old: String, new: String) {
        val a = api ?: return
        val r = apiCall { a.changePassword(buildJsonObject { put("oldPassword", old); put("newPassword", new) }) }
        token = r.token
        settings.setToken(r.token)
        realtime.start(settings.load().serverUrl, r.token)
    }

    fun startDemo() {
        stopCurrent()
        _repo.value = DemoRepository(scope)
        scope.launch { settings.setDemo(); reloadPrefs() }
    }

    private fun startRemote(url: String, t: String) {
        stopCurrent()
        token = t
        val a = Api.create(url) { token }
        api = a
        realtime.start(url, t)
        val repo = RemoteRepository(a, realtime, scope) { n ->
            if (!isForeground) notifier.show(n.id, n.title, n.body, n.kind)
        }
        _repo.value = repo
        scope.launch { runCatching { repo.refreshAll() } }
        ShareService.start(app)
    }

    suspend fun logout() {
        ShareService.stop(app)
        stopCurrent()
        settings.clear()
        reloadPrefs()
    }

    suspend fun setOnboarded() { settings.setOnboarded(true); reloadPrefs() }
    suspend fun setAutoBookkeeping(v: Boolean) { settings.setAutoBookkeeping(v); reloadPrefs() }

    private fun stopCurrent() {
        _repo.value?.close()
        _repo.value = null
        realtime.stop()
        api = null
    }
}

val android.content.Context.container: AppContainer get() = (applicationContext as XlkApp).container
