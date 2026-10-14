package com.perppilot.core.carry

import com.perppilot.core.engine.EngineEvent
import com.perppilot.core.engine.EventKind
import com.perppilot.core.engine.Notifier
import com.perppilot.core.exchange.MarketData
import com.perppilot.core.exchange.TradingApi
import com.perppilot.core.model.Side
import com.perppilot.core.risk.RiskManager
import kotlin.math.abs
import kotlin.math.floor

interface CarryStore {
    fun load(): CarryState
    fun save(state: CarryState)
}

class InMemoryCarryStore(private var s: CarryState = CarryState()) : CarryStore {
    override fun load() = s
    override fun save(state: CarryState) { s = state }
}

/**
 * Runs the funding carry book: opens spot-long / perp-short pairs while funding is positive,
 * keeps the two legs equal, trims them after big rallies so the short never runs out of margin,
 * and unwinds when funding turns negative.
 */
class CarryEngine(
    private val market: MarketData,
    private val trading: TradingApi?,
    private val carry: CarryApi,
    private val store: CarryStore,
    private val notifier: Notifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private lateinit var state: CarryState

    /** [blocked]: perpetuals that hold a directional position and must not be touched. */
    suspend fun runCycle(cfg: CarryConfig, blocked: Set<String> = emptySet(), closeAll: Boolean = false): CarryState {
        state = store.load()
        val now = clock()
        val api = trading
        val statuses = ArrayList<CarryCoinStatus>()
        val coins = (cfg.coins + state.positions.keys).distinct()
        var equity: Double? = null
        var hedgeMode = false
        if (api != null && (cfg.enabled || state.positions.isNotEmpty())) {
            runCatching {
                equity = api.balance().totalEquity
                hedgeMode = api.accountConfig().hedgeMode
            }.onFailure { emit(EventKind.ERROR, "套利帳戶讀取失敗", it.message ?: "") }
        }
        for (coin in coins) {
            val perp = "$coin-USDT-SWAP"
            val spot = "$coin-USDT"
            try {
                val history = carry.fundingHistory(perp, cfg.lookback)
                val rates = history.map { it.second }
                val avg = if (rates.isEmpty()) 0.0 else rates.average() * 100
                val last = (rates.firstOrNull() ?: 0.0) * 100
                var pos = state.positions[coin]
                var note = ""

                // Book the funding of settlements since the last check (estimate for display).
                if (pos != null) {
                    val mark = market.ticker(perp).last
                    val spec = market.instrument(perp)
                    val notional = pos.contracts * spec.baseQtyPerContract * mark
                    val newer = history.filter { it.first > pos!!.lastFundingTime && it.first > pos!!.entryTime }
                    if (newer.isNotEmpty()) {
                        pos = pos.copy(
                            fundingEarned = pos.fundingEarned + newer.sumOf { it.second } * notional,
                            lastFundingTime = newer.maxOf { it.first },
                        )
                        state = state.copy(positions = state.positions + (coin to pos))
                    }
                }

                if (api != null && equity != null) {
                    val eq = equity!!
                    when {
                        pos != null && (closeAll || avg < cfg.exitAvgPct || coin !in cfg.coins) -> {
                            close(api, coin, pos, hedgeMode, if (closeAll) "手動全部平倉" else if (coin !in cfg.coins) "已從清單移除" else "資金費轉負（7日均 %.4f%%）".format(avg))
                            pos = null
                        }
                        pos == null && cfg.enabled && !closeAll && coin in cfg.coins && avg >= cfg.entryAvgPct -> {
                            if (perp in blocked) {
                                note = "該合約有方向性持倉，暫不套利"
                            } else {
                                pos = open(api, coin, spot, perp, eq, cfg, hedgeMode)
                            }
                        }
                        pos != null -> pos = maintain(api, coin, spot, perp, pos, eq, cfg, hedgeMode)
                    }
                }
                statuses += CarryCoinStatus(coin, avg, last, avg * 3 * 365, pos != null, note)
            } catch (e: Exception) {
                emit(EventKind.ERROR, "$coin 套利處理失敗", e.message ?: e.javaClass.simpleName)
                statuses += CarryCoinStatus(coin, 0.0, 0.0, 0.0, state.positions.containsKey(coin), "錯誤：${e.message}")
            }
        }
        state = state.copy(status = statuses, lastRun = now)
        store.save(state)
        return state
    }

    private fun targetNotional(equity: Double, cfg: CarryConfig): Double {
        val perCoin = equity * cfg.capitalPct / 100.0 / cfg.coins.size.coerceAtLeast(1)
        // Capital per unit of notional: 1 for the spot leg + 1/leverage margin for the short.
        return perCoin / (1.0 + 1.0 / cfg.perpLeverage.coerceAtLeast(1))
    }

    private suspend fun open(api: TradingApi, coin: String, spot: String, perp: String, equity: Double, cfg: CarryConfig, hedge: Boolean): CarryPosition? {
        // In one-way mode a new short would merge with whatever is already there.
        if (api.positions().any { it.instId == perp }) {
            emit(EventKind.INFO, "$coin 套利未開倉", "$perp 已有其他持倉，為避免合併部位暫不套利")
            return null
        }
        val price = market.ticker(perp).last
        val spec = market.instrument(perp)
        val (spotLot, spotMin) = carry.spotLotSize(spot)
        val target = targetNotional(equity, cfg)
        // Size in whole perp lots so both legs can match exactly.
        val contracts = RiskManager.roundToStep(floor(target / price / spec.baseQtyPerContract / spec.lotSz) * spec.lotSz, spec.lotSz)
        if (contracts < spec.minSz) {
            emit(EventKind.INFO, "$coin 套利未開倉", "配置資金 %.0f USDT 不足最小合約張數".format(target))
            return null
        }
        val qty = RiskManager.roundToStep(contracts * spec.baseQtyPerContract, spotLot)
        if (qty < spotMin) return null
        val id = "cy" + clock().toString(36)
        val bought = carry.spotMarket(spot, buy = true, baseQty = qty, clientOrderId = id + "s")
        try {
            api.setLeverage(perp, cfg.perpLeverage, "cross", Side.SHORT, hedge)
            carry.perpMarket(perp, sell = true, contracts = contracts, marginMode = "cross", hedgeMode = hedge, reduceOnly = false, clientOrderId = id + "p")
        } catch (e: Exception) {
            // Never leave a naked spot position: unwind the first leg.
            runCatching { carry.spotMarket(spot, buy = false, baseQty = bought.coerceAtMost(carry.assetBalance(coin)), clientOrderId = id + "u") }
            throw IllegalStateException("合約空單失敗，已賣回現貨：${e.message}")
        }
        val pos = CarryPosition(coin, bought, contracts, price, clock(), lastFundingTime = clock())
        state = state.copy(positions = state.positions + (coin to pos))
        emit(EventKind.ORDER, "$coin 資金費套利開倉", "現貨買入 ${fmt(bought)} $coin＋合約做空 ${fmt(contracts)} 張（約 %.0f USDT 名目），價格方向已對沖".format(contracts * spec.baseQtyPerContract * price))
        return pos
    }

    private suspend fun maintain(api: TradingApi, coin: String, spot: String, perp: String, pos: CarryPosition, equity: Double, cfg: CarryConfig, hedge: Boolean): CarryPosition {
        val price = market.ticker(perp).last
        val spec = market.instrument(perp)
        val (spotLot, _) = carry.spotLotSize(spot)
        val short = api.positions().firstOrNull { it.instId == perp && it.side == Side.SHORT }
        var p = pos
        if (short == null) {
            emit(EventKind.RISK, "$coin 套利空單消失", "交易所上找不到 $perp 空單（可能被強平或手動平倉），已賣出現貨以免暴露價格風險")
            val bal = carry.assetBalance(coin)
            runCatching { carry.spotMarket(spot, buy = false, baseQty = RiskManager.roundToStep(minOf(bal, pos.spotQty), spotLot), clientOrderId = "cy" + clock().toString(36) + "x") }
            state = state.copy(positions = state.positions - coin, closedFunding = state.closedFunding + pos.fundingEarned)
            throw IllegalStateException("$coin 套利已解除")
        }
        p = p.copy(contracts = short.contracts)
        // Trim both legs after a big rally so the short keeps a healthy margin buffer.
        val notional = p.contracts * spec.baseQtyPerContract * price
        val target = targetNotional(equity, cfg)
        if (notional > target * (1 + cfg.rebalanceGrowthPct / 100)) {
            val cut = RiskManager.roundToStep(floor((notional - target) / price / spec.baseQtyPerContract / spec.lotSz) * spec.lotSz, spec.lotSz)
            if (cut >= spec.minSz) {
                val id = "cy" + clock().toString(36)
                carry.perpMarket(perp, sell = false, contracts = cut, marginMode = "cross", hedgeMode = hedge, reduceOnly = true, clientOrderId = id + "p")
                val sold = carry.spotMarket(spot, buy = false, baseQty = RiskManager.roundToStep(cut * spec.baseQtyPerContract, spotLot), clientOrderId = id + "s")
                p = p.copy(contracts = RiskManager.roundToStep(p.contracts - cut, spec.lotSz), spotQty = p.spotQty - sold)
                emit(EventKind.STOP, "$coin 套利再平衡", "價格上漲使名目增加，兩腿同步減少 ${fmt(cut)} 張以維持保證金安全")
            }
        }
        // Re-hedge if the legs drifted apart (fees deducted in coin, partial fills, manual changes).
        val perpQty = p.contracts * spec.baseQtyPerContract
        val spotQty = minOf(carry.assetBalance(coin), p.spotQty)
        val diff = spotQty - perpQty
        if (perpQty > 0 && abs(diff) / perpQty * 100 > cfg.hedgeTolerancePct) {
            val q = RiskManager.roundToStep(abs(diff), spotLot)
            if (q > 0) {
                carry.spotMarket(spot, buy = diff < 0, baseQty = q, clientOrderId = "cy" + clock().toString(36) + "h")
                p = p.copy(spotQty = if (diff < 0) p.spotQty + q else p.spotQty - q)
                emit(EventKind.INFO, "$coin 套利對沖校正", "現貨 ${fmt(spotQty)} vs 合約 ${fmt(perpQty)}，已${if (diff < 0) "買入" else "賣出"} ${fmt(q)} $coin")
            }
        }
        state = state.copy(positions = state.positions + (coin to p))
        return p
    }

    private suspend fun close(api: TradingApi, coin: String, pos: CarryPosition, hedge: Boolean, reason: String) {
        val perp = "$coin-USDT-SWAP"
        val spot = "$coin-USDT"
        val (spotLot, spotMin) = carry.spotLotSize(spot)
        if (api.positions().any { it.instId == perp && it.side == Side.SHORT }) {
            api.closePosition(perp, Side.SHORT, "cross", hedge)
        }
        val qty = RiskManager.roundToStep(floor(minOf(carry.assetBalance(coin), pos.spotQty) / spotLot) * spotLot, spotLot)
        if (qty >= spotMin) carry.spotMarket(spot, buy = false, baseQty = qty, clientOrderId = "cy" + clock().toString(36) + "c")
        state = state.copy(positions = state.positions - coin, closedFunding = state.closedFunding + pos.fundingEarned)
        emit(EventKind.CLOSE, "$coin 資金費套利平倉（$reason）", "持有期間估計收取資金費 %+.2f USDT（未扣手續費）".format(pos.fundingEarned))
    }

    private fun emit(kind: EventKind, title: String, msg: String) {
        runCatching { notifier.notify(EngineEvent(kind, title, msg, null, clock())) }
    }

    private fun fmt(v: Double) = java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
