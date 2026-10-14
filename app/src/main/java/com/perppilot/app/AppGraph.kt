package com.perppilot.app

import android.content.Context
import com.perppilot.app.data.AppSettings
import com.perppilot.app.data.FileEngineStore
import com.perppilot.app.data.FileEventStore
import com.perppilot.app.data.SecretKey
import com.perppilot.app.data.SecureStore
import com.perppilot.app.data.SettingsRepository
import com.perppilot.app.notify.AppNotifier
import com.perppilot.core.engine.CycleReport
import com.perppilot.core.engine.TradingEngine
import com.perppilot.core.events.EventEngine
import com.perppilot.core.events.EventState
import com.perppilot.core.exchange.okx.OkxClient
import com.perppilot.core.exchange.okx.OkxCredentials
import com.perppilot.core.risk.RiskManager
import com.perppilot.core.signals.NtfyClient
import com.perppilot.core.signals.TradingViewParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Hand-rolled dependency container shared by the UI, the worker and the foreground service. */
class AppGraph(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val settings = SettingsRepository(context)
    val secure = SecureStore(context)
    val engineStore = FileEngineStore(File(context.filesDir, "engine_state.json"))
    val eventStore = FileEventStore(File(context.filesDir, "event_state.json"))
    val notifier = AppNotifier(context, this)
    private val http = OkxClient.defaultHttpClient()
    private val engineLock = Mutex()
    private val eventLock = Mutex()

    /** Public, unauthenticated live market data (analysis screens, backtests). */
    fun publicClient(s: AppSettings = settings.current): OkxClient =
        OkxClient(credentials = null, demo = false, baseUrl = s.baseUrl, http = http)

    fun credentials(demo: Boolean): OkxCredentials? {
        val c = if (demo) {
            OkxCredentials(secure.get(SecretKey.DEMO_API_KEY).orEmpty(), secure.get(SecretKey.DEMO_SECRET).orEmpty(), secure.get(SecretKey.DEMO_PASSPHRASE).orEmpty())
        } else {
            OkxCredentials(secure.get(SecretKey.LIVE_API_KEY).orEmpty(), secure.get(SecretKey.LIVE_SECRET).orEmpty(), secure.get(SecretKey.LIVE_PASSPHRASE).orEmpty())
        }
        return c.takeIf { it.isComplete }
    }

    /** Authenticated client for the selected mode, or null when no keys are configured. */
    fun tradingClient(s: AppSettings = settings.current): OkxClient? {
        if (!s.demoMode && !s.liveRiskAccepted) return null
        val creds = credentials(s.demoMode) ?: return null
        return OkxClient(credentials = creds, demo = s.demoMode, baseUrl = s.baseUrl, http = http)
    }

    suspend fun runTradingCycle(): CycleReport = withContext(Dispatchers.IO) { engineLock.withLock {
        val s = settings.current
        val trading = tradingClient(s)
        // In demo mode orders fill against demo prices, so read market data from the same environment.
        val market = trading ?: publicClient(s)
        TradingEngine(market, trading, engineStore, notifier).runCycle(s.engine)
    } }

    suspend fun runEventCycle(): EventState = withContext(Dispatchers.IO) { eventLock.withLock {
        val s = settings.current
        val trading = tradingClient(s)
        val equity = trading?.let { runCatching { it.balance().availableEquity }.getOrNull() }
        EventEngine(trading ?: publicClient(s), trading != null, eventStore, notifier).runCycle(s.events, equity)
    } }

    fun relayClient(s: AppSettings = settings.current): NtfyClient? =
        s.signals.ntfyTopic.takeIf { it.isNotBlank() }?.let { NtfyClient(s.signals.ntfyServer, it, http) }

    /** Pulls new TradingView alerts from the relay and executes them. Returns how many were new. */
    suspend fun pollRelay(): Int = withContext(Dispatchers.IO) { engineLock.withLock { pollRelayLocked() } }

    private suspend fun pollRelayLocked(): Int {
        val s = settings.current
        if (!s.signals.enabled) return 0
        val relay = relayClient(s) ?: return 0
        val cursor = engineStore.get().relayCursor
        val messages = relay.poll(cursor)
        if (messages.isEmpty()) return 0
        val parsed = messages.mapNotNull { TradingViewParser.parse(it.text, it.time, it.id) }
        val trading = tradingClient(s)
        TradingEngine(trading ?: publicClient(s), trading, engineStore, notifier)
            .handleExternal(parsed, s.engine, s.signals, relayCursor = messages.last().id)
        return parsed.size
    }

    fun resetHalt() {
        val st = engineStore.get()
        engineStore.put(st.copy(risk = RiskManager.resetHalt(st.risk, st.lastEquity)))
    }
}
