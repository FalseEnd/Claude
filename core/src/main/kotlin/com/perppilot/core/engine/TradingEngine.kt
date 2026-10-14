package com.perppilot.core.engine

import com.perppilot.core.exchange.MarketData
import com.perppilot.core.exchange.TradingApi
import com.perppilot.core.model.AccountConfig
import com.perppilot.core.model.ExchangePosition
import com.perppilot.core.model.Side
import com.perppilot.core.risk.RiskManager
import com.perppilot.core.signals.ExternalSignal
import com.perppilot.core.signals.ExternalSignalConfig
import com.perppilot.core.signals.SignalAction
import com.perppilot.core.strategy.EntrySignal
import com.perppilot.core.strategy.EntryType
import com.perppilot.core.strategy.PositionTrack
import com.perppilot.core.strategy.PreparedSeries
import com.perppilot.core.strategy.Strategy
import com.perppilot.core.strategy.TrendStrategy
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

/**
 * Runs one decision cycle: sync account → reconcile positions → manage stops → look for entries.
 *
 * Designed to be invoked periodically (e.g. every 15 minutes by WorkManager). Every open position
 * always carries an exchange-side stop loss, so the account stays protected even if the phone is
 * offline between cycles.
 */
class TradingEngine(
    private val market: MarketData,
    private val trading: TradingApi?,
    private val store: EngineStore,
    private val notifier: Notifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fillWaitMs: Long = 600,
) {
    private lateinit var state: EngineState
    private val events = mutableListOf<EngineEvent>()
    private val errors = mutableListOf<String>()

    /** [reserved]: perpetuals held by the funding-carry book; never traded or counted here. */
    suspend fun runCycle(settings: EngineSettings, reserved: Set<String> = emptySet()): CycleReport {
        state = store.load()
        events.clear()
        errors.clear()
        val now = clock()
        val strategy = settings.activeStrategy()
        // Existing positions keep being managed even when auto-trading is switched off;
        // only new entries require [EngineSettings.autoTrade].
        val tradingApi = trading.takeIf { settings.autoTrade || state.positions.isNotEmpty() }
        val signals = LinkedHashMap<String, EntrySignal?>()

        var account: AccountConfig? = null
        var exPositions: List<ExchangePosition> = emptyList()
        var equity: Double? = null
        var available = 0.0

        if (tradingApi != null) {
            try {
                account = tradingApi.accountConfig()
                if (!account.supportsDerivatives) {
                    emit(EventKind.ERROR, "帳戶模式不支援合約", "OKX 帳戶目前為「簡單模式」，請在 OKX 設定切換為「單幣種保證金」或以上模式。")
                    return finish(now, null, signals)
                }
                val bal = tradingApi.balance()
                equity = bal.totalEquity
                available = bal.availableEquity
                exPositions = tradingApi.positions().filter { it.instId !in reserved }
                val before = state.risk
                val risk = RiskManager.onEquity(before, bal.totalEquity, now, settings.risk)
                state = state.copy(risk = risk, lastEquity = bal.totalEquity, lastAvailable = available)
                if (risk.haltReason != null && risk.haltReason != before.haltReason) {
                    emit(EventKind.RISK, "風控熔斷", risk.haltReason)
                }
                reconcileClosed(tradingApi, exPositions, settings, now)
            } catch (e: Exception) {
                error("讀取帳戶失敗", e)
                return finish(now, equity, signals)
            }
        }

        val symbols = (settings.watchlist.filter { it !in reserved } + state.positions.keys).distinct()
        for (instId in symbols) {
            try {
                val track = state.positions[instId]
                // A position is always managed by the strategy that opened it.
                val strat = if (track != null) settings.strategyFor(track.strategy) else strategy
                val ltf = market.candles(instId, strat.ltfFrame, 300)
                val htf = market.candles(instId, strat.htfFrame, 300)
                if (ltf.size < strat.ltfWarmup || htf.size < strat.htfWarmup) {
                    signals[instId] = null
                    continue
                }
                val p = strat.prepare(ltf, htf)
                val last = p.size - 1
                if (track != null) {
                    if (tradingApi != null && account != null) {
                        managePosition(tradingApi, account, strat, p, track, exPositions, settings)
                    }
                    continue
                }
                if (!settings.useBuiltInStrategy) continue
                val funding = runCatching { market.funding(instId)?.rate }.getOrNull()
                val signal = strategy.entrySignal(p, last, funding)
                signals[instId] = signal
                if (signal == null) continue
                if (state.lastSignalBar[instId] == signal.barTime) continue
                state = state.copy(
                    lastSignalBar = state.lastSignalBar + (instId to signal.barTime),
                    latestSignals = state.latestSignals + (instId to signal),
                )
                if (settings.notifySignals) {
                    emit(
                        EventKind.SIGNAL,
                        "${shortName(instId)} ${signal.side.label}訊號",
                        "${signal.reason}\n參考價 ${px(signal.price)}，建議止損 ${px(signal.suggestedStop)}",
                        instId,
                    )
                }
                if (settings.autoTrade && tradingApi != null && account != null && equity != null) {
                    if (exPositions.any { it.instId == instId }) {
                        log("$instId 已有非本程式管理的持倉，略過自動開倉")
                        continue
                    }
                    openPosition(tradingApi, account, strategy, signal, instId, equity, available, exPositions, settings)
                }
            } catch (e: Exception) {
                error("$instId 處理失敗", e, instId)
            }
        }
        return finish(now, equity, signals)
    }

    /** Positions we managed that are gone from the exchange were stopped out, hit TP, or closed manually. */
    private suspend fun reconcileClosed(api: TradingApi, exPositions: List<ExchangePosition>, settings: EngineSettings, now: Long) {
        for ((instId, track) in state.positions) {
            if (exPositions.any { it.instId == instId && it.side == track.side }) continue
            val record = runCatching { api.closedPositions(instId, track.entryTime - 60_000) }.getOrNull()
                ?.lastOrNull { it.side == track.side }
            val spec = runCatching { market.instrument(instId) }.getOrNull()
            val exitPrice = record?.closePrice?.takeIf { it > 0 } ?: track.stop
            val pnl = record?.realizedPnl
                ?: ((exitPrice - track.entryPrice) * track.side.sign * track.size * (spec?.baseQtyPerContract ?: 0.0))
            val reason = when {
                record == null -> "已於交易所平倉（查無紀錄，損益為估算）"
                record.closeType == "liquidation" -> "強制平倉"
                record.closeType == "adl" -> "自動減倉(ADL)"
                track.takeProfit != null && abs(exitPrice - track.takeProfit) < abs(exitPrice - track.stop) -> "止盈"
                track.breakEvenDone -> "移動止損"
                else -> "止損"
            }
            recordClose(track, exitPrice, pnl, reason, record?.closeTime ?: now, spec?.baseQtyPerContract, settings)
        }
    }

    private suspend fun managePosition(
        api: TradingApi,
        account: AccountConfig,
        strategy: Strategy,
        p: PreparedSeries,
        original: PositionTrack,
        exPositions: List<ExchangePosition>,
        settings: EngineSettings,
    ) {
        val instId = original.instId
        val exPos = exPositions.firstOrNull { it.instId == instId && it.side == original.side } ?: return
        var track = original.copy(size = exPos.contracts)
        var exitReason: String? = null
        for (k in 0 until p.size) {
            if (!track.manageExits) break
            if (p.ltf[k].time <= track.lastBarTime) continue
            val r = strategy.manage(track, p, k)
            track = r.position
            if (r.exitReason != null) {
                exitReason = r.exitReason
                break
            }
        }
        val spec = market.instrument(instId)
        val lastPrice = market.ticker(instId).last
        if (exitReason == null) {
            val stopCrossed = if (track.side == Side.LONG) lastPrice <= track.stop else lastPrice >= track.stop
            if (stopCrossed) exitReason = "價格觸及止損"
        }
        if (exitReason != null) {
            api.closePosition(instId, track.side, exPos.marginMode, account.hedgeMode)
            delay(fillWaitMs)
            val record = runCatching { api.closedPositions(instId, track.entryTime - 60_000) }.getOrNull()
                ?.lastOrNull { it.side == track.side }
            val exitPrice = record?.closePrice?.takeIf { it > 0 } ?: lastPrice
            val pnl = record?.realizedPnl ?: ((exitPrice - track.entryPrice) * track.side.sign * track.size * spec.baseQtyPerContract)
            recordClose(track, exitPrice, pnl, exitReason, clock(), spec.baseQtyPerContract, settings)
            return
        }

        // Keep the exchange-side stop in sync with the strategy's stop (only ever tightening).
        val desired = RiskManager.roundPrice(track.stop, spec.tickSz, up = track.side == Side.SHORT)
        val stops = api.stopOrders(instId).filter { it.stopLoss != null }
        val current = stops.firstOrNull()
        if (current == null) {
            api.placeStop(instId, track.side, exPos.marginMode, account.hedgeMode, desired)
            emit(EventKind.STOP, "${shortName(instId)} 補掛止損", "交易所上找不到止損單，已重新掛出止損 ${px(desired)}", instId)
        } else {
            val cur = current.stopLoss!!
            val better = if (track.side == Side.LONG) desired > cur + spec.tickSz / 2 else desired < cur - spec.tickSz / 2
            if (better) {
                try {
                    api.amendStop(instId, current.algoId, desired)
                } catch (e: Exception) {
                    // Some order types cannot be amended: replace instead (place first, then cancel).
                    api.placeStop(instId, track.side, exPos.marginMode, account.hedgeMode, desired)
                    api.cancelStop(instId, current.algoId)
                }
                emit(
                    EventKind.STOP,
                    "${shortName(instId)} 移動止損",
                    "止損 ${px(cur)} → ${px(desired)}（已實現保護 %.2fR）".format(track.rMultiple(desired)),
                    instId,
                )
            }
        }
        state = state.copy(positions = state.positions + (instId to track))
    }

    private suspend fun openPosition(
        api: TradingApi,
        account: AccountConfig,
        strategy: Strategy,
        signal: EntrySignal,
        instId: String,
        equity: Double,
        available: Double,
        exPositions: List<ExchangePosition>,
        settings: EngineSettings,
        stopOverride: Double? = null,
        tpOverride: Double? = null,
        riskPctOverride: Double? = null,
        origin: String = "策略",
        manageExits: Boolean = true,
    ) {
        val now = clock()
        val cfg = settings.risk.let { r -> riskPctOverride?.let { r.copy(riskPerTradePct = it.coerceIn(0.05, r.riskPerTradePct * 2)) } ?: r }
        val openRisk = state.positions.values.sumOf { t ->
            val spec = runCatching { market.instrument(t.instId) }.getOrNull()
            max(0.0, (t.entryPrice - t.stop) * t.side.sign) * t.size * (spec?.baseQtyPerContract ?: 0.0)
        }
        val openCount = (state.positions.keys + exPositions.map { it.instId }).distinct().size
        val decision = RiskManager.canOpen(state.risk, now, cfg, openCount, openRisk / equity * 100.0)
        if (!decision.allowed) {
            emit(EventKind.INFO, "${shortName(instId)} 訊號未執行", decision.reason, instId)
            return
        }
        val spec = market.instrument(instId)
        val price = market.ticker(instId).last
        if (abs(price - signal.price) > settings.maxChaseAtr * signal.atr) {
            emit(EventKind.INFO, "${shortName(instId)} 訊號未執行", "價格已偏離訊號價超過 ${settings.maxChaseAtr} ATR，不追價", instId)
            return
        }
        val rawStop = stopOverride ?: strategy.initialStop(signal, price)
        if ((rawStop - price) * signal.side.sign >= 0) {
            emit(EventKind.INFO, "${shortName(instId)} 訊號未執行", "止損價 ${px(rawStop)} 不在${signal.side.label}方向的正確一側", instId)
            return
        }
        val stop = RiskManager.roundPrice(rawStop, spec.tickSz, up = signal.side == Side.SHORT)
        val tp = (tpOverride ?: strategy.takeProfit(signal, price, stop))?.let { RiskManager.roundPrice(it, spec.tickSz) }
        val leverage = minOf(cfg.leverage, spec.maxLeverage)
        val size = RiskManager.size(equity, price, stop, cfg.copy(leverage = leverage), spec, available)
        if (size == null) {
            emit(EventKind.INFO, "${shortName(instId)} 訊號未執行", "資金不足以達到最小下單量，或止損距離相對槓桿過寬", instId)
            return
        }
        api.setLeverage(instId, leverage, cfg.marginMode, signal.side, account.hedgeMode)
        val clientId = "pp" + now.toString(36) + instId.filter { it.isLetterOrDigit() }.take(8)
        val order = api.openMarket(instId, signal.side, size.contracts, cfg.marginMode, account.hedgeMode, stop, tp, clientId)
        delay(fillWaitMs)
        val status = runCatching { api.order(instId, order.orderId) }.getOrNull()
        val filled = status?.filledContracts
        if (filled != null && filled <= 0.0) {
            emit(EventKind.ERROR, "${shortName(instId)} 訂單未成交", "市價單 ${order.orderId} 未成交，未建立倉位紀錄", instId)
            return
        }
        val fill = status?.avgPrice ?: price
        val track = PositionTrack(
            instId = instId,
            side = signal.side,
            entryTime = now,
            entryPrice = fill,
            initialStop = stop,
            stop = stop,
            takeProfit = tp,
            size = filled ?: size.contracts,
            extreme = fill,
            entryType = signal.type,
            lastBarTime = signal.barTime,
            strategy = strategy.type,
            origin = origin,
            manageExits = manageExits,
        )
        state = state.copy(positions = state.positions + (instId to track))
        val typeLabel = runCatching { EntryType.valueOf(signal.type).label }.getOrNull()
            ?: runCatching { com.perppilot.core.strategy.StrategyType.valueOf(signal.type).label }.getOrDefault(origin)
        emit(
            EventKind.ORDER,
            "${shortName(instId)} 已${signal.side.label}開倉",
            "$typeLabel｜成交 ${px(fill)}｜${RiskManager.roundToStep(size.contracts, spec.lotSz)} 張｜止損 ${px(stop)}" +
                (tp?.let { "｜止盈 ${px(it)}" } ?: "") + "｜風險 %.2f USDT｜${leverage}x".format(size.riskAmount),
            instId,
        )
    }

    /**
     * Executes external (TradingView) signals through the same risk pipeline as built-in signals:
     * secret check → freshness → symbol allow-list → risk limits → sizing → order with exchange stop.
     */
    suspend fun handleExternal(
        signals: List<ExternalSignal>,
        settings: EngineSettings,
        cfg: ExternalSignalConfig,
        relayCursor: String? = null,
    ): CycleReport {
        state = store.load()
        events.clear()
        errors.clear()
        val now = clock()
        val strategy = TrendStrategy(settings.strategy)
        val fresh = signals.filter { it.id !in state.processedSignals }
        state = state.copy(
            processedSignals = (state.processedSignals + fresh.map { it.id }).takeLast(300),
            relayCursor = relayCursor ?: state.relayCursor,
        )
        var account: AccountConfig? = null
        var equity = 0.0
        var available = 0.0
        var exPositions: List<ExchangePosition> = emptyList()

        for (sig in fresh) {
            val action = sig.signalAction
            val name = shortName(sig.instId)
            when {
                action == null -> { log("無法辨識的訊號動作：${sig.action}"); continue }
                cfg.secret.isNotEmpty() && sig.secret != cfg.secret -> {
                    emit(EventKind.ERROR, "TradingView 訊號被拒絕", "$name 訊號密鑰不符，已忽略（請檢查警報訊息中的 secret）", sig.instId)
                    continue
                }
                now - sig.time > cfg.maxAgeSeconds * 1000L -> {
                    emit(EventKind.INFO, "TradingView 訊號過期", "$name ${action.label}訊號已超過 ${cfg.maxAgeSeconds} 秒，不執行", sig.instId)
                    continue
                }
                cfg.requireWatchlist && sig.instId !in settings.watchlist && sig.instId !in state.positions -> {
                    emit(EventKind.INFO, "TradingView 訊號未執行", "$name 不在觀察清單中", sig.instId)
                    continue
                }
            }
            emit(
                EventKind.SIGNAL,
                "TradingView：$name ${action!!.label}",
                listOfNotNull(sig.price?.let { "價格 ${px(it)}" }, sig.stop?.let { "止損 ${px(it)}" }, sig.note).joinToString("｜").ifEmpty { "收到警報" },
                sig.instId,
            )
            val api = trading
            if (!settings.autoTrade || api == null) continue
            try {
                if (account == null) {
                    account = api.accountConfig()
                    val bal = api.balance()
                    equity = bal.totalEquity
                    available = bal.availableEquity
                    exPositions = api.positions()
                    state = state.copy(risk = RiskManager.onEquity(state.risk, equity, now, settings.risk), lastEquity = equity)
                    reconcileClosed(api, exPositions, settings, now)
                }
                val acct = account!!
                val existing = state.positions[sig.instId]
                val closeSide: Side? = when (action) {
                    SignalAction.CLOSE -> existing?.side
                    SignalAction.CLOSE_LONG -> Side.LONG.takeIf { existing?.side == it }
                    SignalAction.CLOSE_SHORT -> Side.SHORT.takeIf { existing?.side == it }
                    SignalAction.LONG -> Side.SHORT.takeIf { existing?.side == it && cfg.allowReverse }
                    SignalAction.SHORT -> Side.LONG.takeIf { existing?.side == it && cfg.allowReverse }
                }
                if (existing != null && closeSide != null) {
                    val exPos = exPositions.firstOrNull { it.instId == sig.instId && it.side == closeSide }
                    if (exPos != null) {
                        api.closePosition(sig.instId, closeSide, exPos.marginMode, acct.hedgeMode)
                        delay(fillWaitMs)
                        val spec = market.instrument(sig.instId)
                        val last = market.ticker(sig.instId).last
                        val record = runCatching { api.closedPositions(sig.instId, existing.entryTime - 60_000) }.getOrNull()?.lastOrNull { it.side == closeSide }
                        val exitPrice = record?.closePrice?.takeIf { it > 0 } ?: last
                        val pnl = record?.realizedPnl ?: ((exitPrice - existing.entryPrice) * closeSide.sign * existing.size * spec.baseQtyPerContract)
                        recordClose(existing, exitPrice, pnl, "TradingView 平倉訊號", clock(), spec.baseQtyPerContract, settings)
                        exPositions = exPositions - exPos
                    }
                }
                val side = when (action) {
                    SignalAction.LONG -> Side.LONG
                    SignalAction.SHORT -> Side.SHORT
                    else -> continue
                }
                if (state.positions[sig.instId]?.side == side) {
                    log("$name 已持有${side.label}倉位，忽略重複訊號")
                    continue
                }
                if (exPositions.any { it.instId == sig.instId }) {
                    emit(EventKind.INFO, "$name 訊號未執行", "已有非本程式管理的持倉", sig.instId)
                    continue
                }
                val ltf = market.candles(sig.instId, settings.ltf, 300)
                val htf = market.candles(sig.instId, settings.htf, 300)
                val p = strategy.prepare(ltf, htf, settings.ltf, settings.htf)
                val atr = p.atr.lastOrNull()?.takeIf { !it.isNaN() } ?: throw IllegalStateException("K 線不足，無法計算 ATR")
                val ref = sig.price ?: market.ticker(sig.instId).last
                val entry = EntrySignal(
                    side = side,
                    type = "TRADINGVIEW",
                    barTime = ltf.last().time,
                    price = ref,
                    atr = atr,
                    suggestedStop = sig.stop ?: strategy.initialStop(side, ref, atr),
                    strength = 0.0,
                    reason = "TradingView 警報",
                )
                openPosition(
                    api, acct, strategy, entry, sig.instId, equity, available, exPositions, settings,
                    stopOverride = sig.stop,
                    tpOverride = sig.takeProfit,
                    riskPctOverride = sig.riskPct,
                    origin = "TradingView",
                    manageExits = cfg.appManagesExits,
                )
            } catch (e: Exception) {
                error("$name TradingView 訊號執行失敗", e, sig.instId)
            }
        }
        return finish(now, equity.takeIf { it > 0 }, emptyMap())
    }

    private fun recordClose(
        track: PositionTrack,
        exitPrice: Double,
        pnl: Double,
        reason: String,
        time: Long,
        baseQtyPerContract: Double?,
        settings: EngineSettings,
    ) {
        val riskAmount = track.riskPerUnit * track.size * (baseQtyPerContract ?: 0.0)
        val r = if (riskAmount > 0) pnl / riskAmount else 0.0
        val entry = JournalEntry(
            instId = track.instId,
            side = track.side,
            entryType = track.entryType,
            entryTime = track.entryTime,
            exitTime = time,
            entryPrice = track.entryPrice,
            exitPrice = exitPrice,
            contracts = track.size,
            realizedPnl = pnl,
            rMultiple = r,
            exitReason = reason,
        )
        state = state.withJournal(entry).copy(
            positions = state.positions - track.instId,
            risk = RiskManager.onTradeClosed(state.risk, pnl, time, settings.risk),
        )
        emit(
            EventKind.CLOSE,
            "${shortName(track.instId)} 平倉（$reason）",
            "${track.side.label} ${px(track.entryPrice)} → ${px(exitPrice)}｜盈虧 %+.2f USDT（%+.2fR）".format(pnl, r),
            track.instId,
        )
    }

    private fun finish(now: Long, equity: Double?, signals: Map<String, EntrySignal?>): CycleReport {
        state = state.copy(lastRun = now)
        store.save(state)
        return CycleReport(now, equity, events.toList(), signals, errors.toList())
    }

    private fun emit(kind: EventKind, title: String, message: String, instId: String? = null) {
        val e = EngineEvent(kind, title, message, instId, clock())
        events += e
        state = state.withEvent(e)
        runCatching { notifier.notify(e) }
    }

    private fun log(message: String) {
        state = state.withEvent(EngineEvent(EventKind.INFO, "記錄", message, null, clock()))
    }

    private fun error(context: String, e: Exception, instId: String? = null) {
        val msg = "$context：${e.message ?: e.javaClass.simpleName}"
        errors += msg
        emit(EventKind.ERROR, "執行錯誤", msg, instId)
    }

    companion object {
        fun shortName(instId: String): String = instId.substringBefore("-USDT")
        fun px(v: Double): String = when {
            v >= 1000 -> "%.1f".format(v)
            v >= 1 -> "%.3f".format(v)
            else -> "%.6f".format(v)
        }
    }
}
