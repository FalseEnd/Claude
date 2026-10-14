package com.perppilot.cli

import com.perppilot.core.events.EventMarket
import com.perppilot.core.events.EventPricer
import com.perppilot.core.events.EventQuote
import com.perppilot.core.events.EventStrategy
import com.perppilot.core.events.EventStrategyConfig
import com.perppilot.core.events.Outcome
import com.perppilot.core.exchange.okx.OkxClient
import com.perppilot.core.model.Candle
import kotlinx.coroutines.delay
import java.io.File

/**
 * Replays expired OKX up/down event contracts minute by minute:
 * model probability (index volatility) vs. the contract's traded YES price, then simulates the
 * strategy buying whichever side the model says is underpriced.
 *
 * Caveat: only traded prices (1m candles) are available historically, not the order book, so a
 * half-spread is assumed on every fill.
 */
suspend fun runEventBacktest(opts: Map<String, String>, cache: File) {
    val series = (opts["series"] ?: "BTC-UPDOWN-15MIN,ETH-UPDOWN-15MIN,SOL-UPDOWN-15MIN").split(",")
    val days = (opts["days"] ?: "7").toInt()
    val halfSpread = (opts["spread"] ?: "0.02").toDouble()
    val cfg = EventStrategyConfig(minEdge = (opts["edge"] ?: "0.05").toDouble())
    // Minutes by which the model's index snapshot lags the traded price (1 = model only sees data
    // older than any trade in the candle, removing the "fresher information" advantage).
    val lag = (opts["lag"] ?: "1").toInt()
    val okx = OkxClient(demo = false)
    val since = System.currentTimeMillis() - days * 86_400_000L
    println("事件合約回測：最近 $days 天，假設半價差 $halfSpread，最低優勢 ${cfg.minEdge}，手續費 K=${cfg.feeK}，模型資訊延遲 $lag 分鐘")

    for (sid in series) {
        val periodMin = if (sid.endsWith("5MIN") && !sid.endsWith("15MIN")) 5 else 15
        val markets = loadExpired(okx, sid, since)
        if (markets.isEmpty()) { println("$sid：無資料"); continue }
        val underlying = sid.substringBefore("-") + "-USDT"
        val index = loadIndex(okx, cache, underlying, markets.minOf { it.expTime } - (periodMin + 90) * 60_000L)
        val idxByTime = index.associateBy { it.time }

        var brierModel = 0.0
        var brierMarket = 0.0
        var points = 0
        var trades = 0
        var withPrices = 0
        var wins = 0
        var pnl = 0.0
        var cost = 0.0
        val yesWins = markets.count { it.outcome == 1 }

        for ((n, m) in markets.withIndex()) {
            val strike = m.floorStrike ?: continue
            val winner = m.winner() ?: continue
            val start = m.expTime - periodMin * 60_000L
            val bars = loadEventCandles(okx, cache, m.instId)
            if (bars.isNotEmpty()) withPrices++
            var traded = false
            for (k in 1..periodMin) {
                val t = start + k * 60_000L
                val minutesLeft = (m.expTime - t) / 60_000.0
                if (minutesLeft < 1) break
                val bar = bars.firstOrNull { it.time == t - 60_000L } ?: continue
                if (bar.volume <= 0 || bar.close <= 0 || bar.close >= 1) continue
                val seen = t - 60_000L - lag * 60_000L
                val spot = idxByTime[seen]?.close ?: continue
                val hist = (60 downTo 0).mapNotNull { idxByTime[seen - it * 60_000L]?.close }
                if (hist.size < 30) continue
                val vol = EventPricer.perMinuteVol(hist)
                val q = EventPricer.probAbove(spot, strike, vol, minutesLeft)
                val y = if (winner == Outcome.YES) 1.0 else 0.0
                brierModel += (q - y) * (q - y)
                brierMarket += (bar.close - y) * (bar.close - y)
                points++
                if (traded) continue
                val quote = EventQuote(m.instId, (bar.close - halfSpread).coerceAtLeast(0.001), (bar.close + halfSpread).coerceAtMost(0.999), bar.close, t)
                val op = EventStrategy.evaluate(m.copy(state = "live"), quote, spot, vol, t, cfg) ?: continue
                val c = op.ask + cfg.slippage
                val fee = EventStrategy.fee(c, cfg.feeK)
                val win = op.outcome == winner
                pnl += (if (win) 1.0 else 0.0) - c - fee
                cost += c
                trades++
                if (win) wins++
                traded = true
            }
            if (n % 100 == 99) print(".")
        }
        println()
        println("== $sid：${markets.size} 個事件（YES 勝 ${yesWins}，${"%.1f".format(yesWins * 100.0 / markets.size)}%），其中 $withPrices 個有歷史成交價")
        if (points > 0) {
            println("   校準 Brier（越低越準）：模型 %.4f｜市場價格 %.4f（%d 個觀測點）".format(brierModel / points, brierMarket / points, points))
        }
        if (trades > 0) {
            println("   策略交易 %d 筆｜勝率 %.1f%%｜每張合約平均損益 %+.4f｜投入報酬率 %+.1f%%".format(
                trades, wins * 100.0 / trades, pnl / trades, pnl / cost * 100))
        } else {
            println("   策略未找到符合條件的機會")
        }
    }
}

private suspend fun loadExpired(okx: OkxClient, sid: String, since: Long): List<EventMarket> {
    val out = ArrayList<EventMarket>()
    var after: Long? = null
    while (true) {
        val page = okx.eventMarkets(sid, "expired", after)
        if (page.isEmpty()) break
        out += page
        val oldest = page.minOf { it.expTime }
        if (oldest <= since || page.size < 100) break
        after = oldest
        delay(120)
    }
    return out.filter { it.expTime >= since && it.instId.isNotEmpty() }.distinctBy { it.instId }.sortedBy { it.expTime }
}

private suspend fun loadIndex(okx: OkxClient, cache: File, underlying: String, start: Long): List<Candle> {
    val f = File(cache, "index-$underlying-1m.csv")
    val cached = if (f.exists()) f.readLines().map { it.split(",") }.map { Candle(it[0].toLong(), 0.0, 0.0, 0.0, it[1].toDouble(), 0.0) } else emptyList()
    if (cached.isNotEmpty() && cached.first().time <= start && System.currentTimeMillis() - cached.last().time < 3_600_000L) return cached
    print("  下載 $underlying 1m 指數 …")
    val out = HashMap<Long, Candle>()
    var after: String? = null
    while (true) {
        val rows = okx.rawGet("/api/v5/market/history-index-candles", mapOf("instId" to underlying, "bar" to "1m", "limit" to "100", "after" to after))
        if (rows.isEmpty()) break
        for (r in rows) out[r[0].toLong()] = Candle(r[0].toLong(), 0.0, 0.0, 0.0, r[4].toDouble(), 0.0)
        val oldest = rows.minOf { it[0].toLong() }
        if (oldest <= start) break
        after = oldest.toString()
        delay(110)
    }
    val all = out.values.sortedBy { it.time }
    println(" ${all.size} 根")
    f.writeText(all.joinToString("\n") { "${it.time},${it.close}" })
    return all
}

private suspend fun loadEventCandles(okx: OkxClient, cache: File, instId: String): List<Candle> {
    val dir = File(cache, "events").apply { mkdirs() }
    val f = File(dir, "$instId.csv")
    if (f.exists()) return f.readLines().filter { it.isNotBlank() }.map { it.split(",") }
        .map { Candle(it[0].toLong(), 0.0, 0.0, 0.0, it[1].toDouble(), it[2].toDouble()) }
    // Candles of long-expired contracts are purged by OKX (51001); treat them as unavailable.
    val rows = try {
        okx.rawGet("/api/v5/market/history-candles", mapOf("instId" to instId, "bar" to "1m", "limit" to "100"))
    } catch (e: com.perppilot.core.exchange.ExchangeException) {
        if (e.code != "51001") throw e
        emptyList()
    }
    val candles = rows.map { Candle(it[0].toLong(), 0.0, 0.0, 0.0, it[4].toDouble(), it[5].toDouble()) }.sortedBy { it.time }
    f.writeText(candles.joinToString("\n") { "${it.time},${it.close},${it.volume}" })
    delay(110)
    return candles
}
