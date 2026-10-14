package com.perppilot.core.events

import com.perppilot.core.engine.EngineEvent
import com.perppilot.core.engine.EventKind
import com.perppilot.core.engine.Notifier
import kotlinx.serialization.Serializable

@Serializable
data class EventBet(
    val instId: String,
    val seriesId: String,
    val method: EventMethod,
    val outcome: Outcome,
    val contracts: Double,
    val limitPrice: Double,
    val fairProb: Double,
    val placedAt: Long,
    val expTime: Long,
    val orderId: String,
    /** Contracts actually seen in the position (limit orders may fill partially or not at all). */
    val filled: Double = 0.0,
    val avgPrice: Double = 0.0,
    val settled: Boolean = false,
    val won: Boolean? = null,
    val pnl: Double? = null,
)

@Serializable
data class EventState(
    val bets: List<EventBet> = emptyList(),
    val notified: Map<String, Long> = emptyMap(),
    val opportunities: List<EventOpportunity> = emptyList(),
    val events: List<EngineEvent> = emptyList(),
    val lastRun: Long = 0,
) {
    fun withEvent(e: EngineEvent) = copy(events = (listOf(e) + events).take(200))
    fun pnlOnDay(dayKey: Long): Double =
        bets.filter { it.settled && Math.floorDiv(it.expTime, DAY_MS) == dayKey }.sumOf { it.pnl ?: 0.0 }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}

interface EventStore {
    fun load(): EventState
    fun save(state: EventState)
}

class InMemoryEventStore(private var s: EventState = EventState()) : EventStore {
    override fun load() = s
    override fun save(state: EventState) { s = state }
}

/**
 * One scan of the event-contract markets: settle finished bets, price every live market with the
 * volatility model, notify on mispricings and (optionally) place limit orders within risk limits.
 */
class EventEngine(
    private val api: EventApi,
    private val canTrade: Boolean,
    private val store: EventStore,
    private val notifier: Notifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun runCycle(cfg: EventStrategyConfig, equity: Double?): EventState {
        var state = store.load()
        val now = clock()
        fun emit(kind: EventKind, title: String, msg: String, instId: String? = null) {
            val e = EngineEvent(kind, title, msg, instId, now)
            state = state.withEvent(e)
            runCatching { notifier.notify(e) }
        }

        // 1) Track fills and settle expired bets.
        val open = state.bets.filter { !it.settled }
        if (open.isNotEmpty()) {
            val positions = if (canTrade) runCatching { api.eventPositions() }.getOrDefault(emptyList()) else emptyList()
            val expiredBySeries = HashMap<String, List<EventMarket>>()
            state = state.copy(bets = state.bets.map { bet ->
                if (bet.settled) return@map bet
                val pos = positions.firstOrNull { it.instId == bet.instId && it.outcome == bet.outcome }
                var b = if (pos != null) bet.copy(filled = pos.contracts, avgPrice = pos.avgPrice) else bet
                if (now < b.expTime + 30_000) return@map b
                val expired = expiredBySeries.getOrPut(b.seriesId) {
                    runCatching { api.eventMarkets(b.seriesId, "expired") }.getOrDefault(emptyList())
                }
                val market = expired.firstOrNull { it.instId == b.instId } ?: return@map b
                val winner = market.winner() ?: return@map b
                val won = winner == b.outcome
                val qty = b.filled
                val price = if (b.avgPrice > 0) b.avgPrice else b.limitPrice
                val fee = EventStrategy.fee(price, cfg.feeK) * qty
                val pnl = if (qty <= 0) 0.0 else (if (won) qty * (1 - price) else -qty * price) - fee
                b = b.copy(settled = true, won = won, pnl = pnl)
                val title = if (qty <= 0) "事件合約未成交" else if (won) "事件合約獲勝" else "事件合約落空"
                emit(
                    EventKind.CLOSE,
                    title,
                    "${b.instId} ${b.outcome.label(b.method)}｜結算 ${market.settleValue ?: "-"}｜損益 %+.2f USDT".format(pnl),
                    b.instId,
                )
                b
            })
        }

        // 2) Price live markets.
        val opportunities = ArrayList<EventOpportunity>()
        for (seriesId in cfg.series) {
            try {
                val markets = api.eventMarkets(seriesId, "live").filter { it.isLive }
                if (markets.isEmpty()) continue
                val underlying = seriesId.substringBefore("-") + "-USDT"
                val spot = api.indexPrice(underlying)
                val nearest = markets.minOf { it.expTime }
                val minutesToNearest = (nearest - now) / 60_000.0
                val vol = if (minutesToNearest <= 90) {
                    EventPricer.perMinuteVol(api.indexCloses(underlying, "1m", 100))
                } else {
                    EventPricer.perMinuteVol(api.indexCloses(underlying, "1H", 100), minutesPerBar = 60.0)
                }
                // Only the nearest expiry, and for strike ladders only strikes within ±4% of spot.
                val candidates = markets.filter { it.expTime == nearest }
                    .filter { m -> m.method == EventMethod.PRICE_UP_DOWN || kotlin.math.abs((m.floorStrike ?: 0.0) / spot - 1) < 0.04 }
                for (m in candidates) {
                    val quote = runCatching { api.eventQuote(m.instId) }.getOrNull() ?: continue
                    EventStrategy.evaluate(m, quote, spot, vol, now, cfg)?.let { opportunities += it }
                }
            } catch (e: Exception) {
                emit(EventKind.ERROR, "事件合約掃描失敗", "$seriesId：${e.message}")
            }
        }
        opportunities.sortByDescending { it.edge }
        state = state.copy(opportunities = opportunities, lastRun = now)

        // 3) Notify and trade.
        val dayKey = Math.floorDiv(now, EventState.DAY_MS)
        for (op in opportunities) {
            val key = op.instId + ":" + op.outcome
            val fresh = state.notified[key] == null
            if (fresh && cfg.notify) {
                emit(
                    EventKind.SIGNAL,
                    "事件合約機會：${op.instId}",
                    "${op.outcome.label(op.method)}｜模型機率 %.0f%% vs 市價 %.2f｜每張期望 %+.3f｜剩 %.0f 分鐘".format(
                        op.fairProb * 100, op.ask, op.edge, op.minutesLeft,
                    ),
                    op.instId,
                )
            }
            if (fresh) state = state.copy(notified = state.notified + (key to now))
            if (!cfg.autoTrade || !canTrade || equity == null || equity <= 0) continue
            if (state.bets.any { it.instId == op.instId }) continue
            if (state.bets.count { !it.settled } >= cfg.maxOpenBets) continue
            if (state.pnlOnDay(dayKey) <= -equity * cfg.dailyLossLimitPct / 100.0) {
                if (fresh) emit(EventKind.RISK, "事件合約暫停", "今日事件合約虧損已達上限 ${cfg.dailyLossLimitPct}%")
                continue
            }
            val qty = EventStrategy.contracts(op, equity, cfg)
            if (qty <= 0) continue
            try {
                val orderId = api.buyEvent(op.instId, op.outcome, qty, op.limitPrice, "ev" + now.toString(36))
                state = state.copy(bets = state.bets + EventBet(
                    instId = op.instId,
                    seriesId = op.seriesId,
                    method = op.method,
                    outcome = op.outcome,
                    contracts = qty,
                    limitPrice = op.limitPrice,
                    fairProb = op.fairProb,
                    placedAt = now,
                    expTime = now + (op.minutesLeft * 60_000).toLong(),
                    orderId = orderId,
                ))
                emit(
                    EventKind.ORDER,
                    "事件合約下單",
                    "${op.instId} ${op.outcome.label(op.method)}｜${qty} 張｜限價 ${op.limitPrice}｜最多投入 %.2f USDT".format(qty * op.limitPrice),
                    op.instId,
                )
            } catch (e: Exception) {
                emit(EventKind.ERROR, "事件合約下單失敗", "${op.instId}：${e.message}", op.instId)
            }
        }
        // Forget old notification keys.
        state = state.copy(
            notified = state.notified.filterValues { now - it < 2 * EventState.DAY_MS },
            bets = state.bets.sortedByDescending { it.placedAt }.take(300),
        )
        store.save(state)
        return state
    }
}
