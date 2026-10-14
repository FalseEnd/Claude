package com.perppilot.app.data

import com.perppilot.core.engine.EngineSettings
import com.perppilot.core.events.EventStrategyConfig
import com.perppilot.core.exchange.okx.OkxClient
import com.perppilot.core.signals.ExternalSignalConfig
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val engine: EngineSettings = EngineSettings(),
    val events: EventStrategyConfig = EventStrategyConfig(),
    /** Scan event contracts every 30 s in the foreground service. */
    val eventMonitor: Boolean = false,
    /** TradingView alerts relayed through ntfy. */
    val signals: ExternalSignalConfig = ExternalSignalConfig(),
    /** true = OKX demo trading (x-simulated-trading), false = real money. */
    val demoMode: Boolean = true,
    val baseUrl: String = OkxClient.DEFAULT_BASE_URL,
    /** Periodic background scan (signals + position management) every 15 minutes. */
    val backgroundScan: Boolean = true,
    val notifyStops: Boolean = true,
    val telegramEnabled: Boolean = false,
    val telegramChatId: String = "",
    /** Chinese convention: red = up, green = down. */
    val redUp: Boolean = false,
    /** The user acknowledged the risk disclosure for live trading. */
    val liveRiskAccepted: Boolean = false,
)

enum class SecretKey(val id: String) {
    DEMO_API_KEY("okx_demo_key"),
    DEMO_SECRET("okx_demo_secret"),
    DEMO_PASSPHRASE("okx_demo_pass"),
    LIVE_API_KEY("okx_live_key"),
    LIVE_SECRET("okx_live_secret"),
    LIVE_PASSPHRASE("okx_live_pass"),
    TELEGRAM_TOKEN("tg_token"),
}
