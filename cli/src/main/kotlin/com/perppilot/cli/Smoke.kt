package com.perppilot.cli

import com.perppilot.core.analysis.MarketAnalyzer
import com.perppilot.core.engine.EngineSettings
import com.perppilot.core.engine.InMemoryEngineStore
import com.perppilot.core.engine.TradingEngine
import com.perppilot.core.events.EventEngine
import com.perppilot.core.events.EventStrategyConfig
import com.perppilot.core.events.InMemoryEventStore
import com.perppilot.core.exchange.okx.OkxClient
import com.perppilot.core.signals.NtfyClient
import com.perppilot.core.signals.TradingViewParser
import com.perppilot.core.strategy.TrendStrategy

/** Exercises every public endpoint the app relies on against the live OKX API. */
suspend fun runSmoke() {
    val okx = OkxClient(demo = false)
    fun ok(name: String, detail: Any?) = println("✔ %-26s %s".format(name, detail.toString().take(150)))
    val inst = "BTC-USDT-SWAP"
    val tickers = okx.tickers(); ok("tickers", "${tickers.size} USDT swaps, top=${tickers.maxByOrNull { it.volQuote24h }?.instId}")
    val spec = okx.instrument(inst); ok("instrument", spec)
    val funding = okx.funding(inst); ok("funding", funding)
    ok("openInterestUsd", okx.openInterestUsd(inst))
    ok("longShortRatio", okx.longShortRatio(inst))
    val strategy = TrendStrategy()
    val ltf = okx.candles(inst, strategy.cfg.ltfFrame, 300)
    val htf = okx.candles(inst, strategy.cfg.htfFrame, 300)
    ok("candles", "${strategy.cfg.ltfFrame}=${ltf.size} ${strategy.cfg.htfFrame}=${htf.size} last=${ltf.last()}")
    val snap = MarketAnalyzer.analyze(inst, ltf, htf, strategy, okx.ticker(inst), funding, null, null)
    ok("analysis", "regime=${snap.regime} score=${snap.score} ${snap.bias} adx=${"%.1f".format(snap.htfAdx)} signal=${snap.signal}")

    val events = mutableListOf<String>()
    val report = TradingEngine(okx, null, InMemoryEngineStore(), { events += it.title }).runCycle(
        EngineSettings(watchlist = listOf("BTC-USDT-SWAP", "ETH-USDT-SWAP", "SOL-USDT-SWAP")),
    )
    ok("engine (signal-only)", "errors=${report.errors} signals=${report.signals.mapValues { it.value?.side }} events=$events")

    val series = okx.eventSeries(); ok("event series", series.map { it.seriesId }.filter { !it.startsWith("TEST", true) })
    val live = okx.eventMarkets("BTC-UPDOWN-15MIN", "live").filter { it.isLive }
    ok("event markets", live.map { "${it.instId} strike=${it.floorStrike}" })
    live.firstOrNull()?.let { ok("event quote", okx.eventQuote(it.instId)) }
    ok("index price", okx.indexPrice("BTC-USDT"))
    ok("index 1m closes", okx.indexCloses("BTC-USDT", "1m", 100).size)
    val st = EventEngine(okx, false, InMemoryEventStore(), {}).runCycle(EventStrategyConfig(), null)
    ok("event engine scan", "opportunities=${st.opportunities.size} errors=${st.events.filter { it.kind.name == "ERROR" }.map { it.message }}")

    val topic = "up" + System.currentTimeMillis().toString(36)
    val relay = NtfyClient("https://ntfy.sh", topic)
    runCatching {
        relay.publish("""{"secret":"x","action":"long","symbol":"BTCUSDT.P","price":1}""")
        relay.poll(null)
    }.onSuccess { got -> ok("ntfy round trip", got.map { TradingViewParser.parse(it.text, it.time, it.id) }) }
        .onFailure { println("✘ ntfy round trip          ${it.message?.take(120)}") }
}
