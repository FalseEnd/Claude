package com.perppilot.cli

import com.perppilot.core.backtest.BacktestConfig
import com.perppilot.core.backtest.BacktestMetrics
import com.perppilot.core.backtest.BacktestResult
import com.perppilot.core.backtest.Backtester
import com.perppilot.core.backtest.SymbolData
import com.perppilot.core.exchange.okx.OkxClient
import com.perppilot.core.model.Candle
import com.perppilot.core.model.Timeframe
import com.perppilot.core.risk.RiskConfig
import com.perppilot.core.strategy.MeanRevConfig
import com.perppilot.core.strategy.MeanReversionStrategy
import com.perppilot.core.strategy.OrbConfig
import com.perppilot.core.strategy.OrbStrategy
import com.perppilot.core.strategy.Strategy
import com.perppilot.core.strategy.StrategyConfig
import com.perppilot.core.strategy.TradingSession
import com.perppilot.core.strategy.TrendStrategy
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/**
 * Backtests the live strategy on real OKX history.
 *
 *   ./gradlew :cli:run --args="--symbols BTC-USDT-SWAP,ETH-USDT-SWAP --days 730"
 *
 * Options: --symbols, --days, --risk <pct>, --equity, --cache <dir>, --split <0..1> (walk-forward
 * report: first part vs. rest), --sweep (robustness grid over key parameters), --trades.
 */
fun main(args: Array<String>) = runBlocking {
    val opts = parseArgs(args)
    val symbols = (opts["symbols"] ?: "BTC-USDT-SWAP,ETH-USDT-SWAP,SOL-USDT-SWAP").split(",").map { it.trim() }
    val days = (opts["days"] ?: "730").toInt()
    val cache = File(opts["cache"] ?: "data-cache").apply { mkdirs() }
    offline = opts.containsKey("offline")
    if (opts.containsKey("download")) {
        // Parallel bulk download into the cache: --download --symbols A,B --days N --frames 5m,15m
        val frames = (opts["frames"] ?: "5m").split(",").map { f -> Timeframe.entries.first { it.okx == f } }
        val syms = (opts["symbols"] ?: "BTC-USDT-SWAP").split(",")
        val d = (opts["days"] ?: "365").toInt()
        val okx = OkxClient(demo = false)
        kotlinx.coroutines.coroutineScope {
            syms.map { inst ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    for (f in frames) {
                        val st = System.currentTimeMillis() - d * 86_400_000L - 260 * f.millis
                        val c = load(okx, cache, inst, f, st)
                        println("✔ $inst ${f.okx} ${c.size} 根")
                    }
                }
            }.forEach { it.await() }
        }
        return@runBlocking
    }
    if (opts.containsKey("smoke")) {
        runSmoke()
        return@runBlocking
    }
    if (opts.containsKey("events")) {
        runEventBacktest(opts, cache)
        return@runBlocking
    }
    val risk = RiskConfig(riskPerTradePct = (opts["risk"] ?: "1.0").toDouble())
    val equity = (opts["equity"] ?: "10000").toDouble()
    val now = System.currentTimeMillis()
    val start = now - days * 86_400_000L
    // Extra history so the higher-timeframe EMA200 is warmed up before the first evaluated bar.

    val ltfFrame = Timeframe.entries.first { it.okx == (opts["ltf"] ?: "1H") }
    val htfFrame = Timeframe.entries.first { it.okx == (opts["htf"] ?: "4H") }
    val baseCfg = StrategyConfig(ltfFrame = ltfFrame, htfFrame = htfFrame)
    val warmStart = start - 260 * htfFrame.millis
    val okx = OkxClient(demo = false)
    val data = symbols.map { inst ->
        val ltf = load(okx, cache, inst, ltfFrame, warmStart)
        val htf = load(okx, cache, inst, htfFrame, warmStart)
        println("· $inst  ${ltfFrame.okx}=${ltf.size}  ${htfFrame.okx}=${htf.size}  ${date(ltf.first().time)} → ${date(ltf.last().time)}")
        SymbolData(inst, ltf, htf)
    }

    if (opts.containsKey("sweep")) {
        val variants = when (opts["strategy"] ?: "trend") {
            "orb" -> orbVariants(ltfFrame, htfFrame)
            "mr" -> mrVariants(ltfFrame, htfFrame)
            else -> trendVariants(baseCfg)
        }
        sweep(data, risk, feeConfig(opts, equity, start), variants)
        return@runBlocking
    }

    val strategy: Strategy = when (opts["strategy"]) {
        "orb" -> OrbStrategy(OrbConfig(ltfFrame = ltfFrame, htfFrame = htfFrame))
        "mr" -> MeanReversionStrategy(MeanRevConfig(ltfFrame = ltfFrame, htfFrame = htfFrame))
        else -> TrendStrategy(baseCfg)
    }
    val base = feeConfig(opts, equity, start)
    println("\n=== 組合回測（${symbols.size} 個合約，${days} 天，單筆風險 ${risk.riskPerTradePct}%）===")
    val full = Backtester(strategy, risk, base).run(data)
    printMetrics(full.metrics)
    printBreakdown(full)
    if (opts.containsKey("trades")) printTrades(full)

    opts["split"]?.toDouble()?.let { f ->
        val cut = start + ((now - start) * f).toLong()
        println("\n=== 前段 ${date(start)} → ${date(cut)} ===")
        printMetrics(Backtester(strategy, risk, base.copy(endTime = cut)).run(data.map { it.until(cut) }).metrics)
        println("\n=== 後段 ${date(cut)} → ${date(now)} ===")
        printMetrics(Backtester(strategy, risk, base.copy(startTime = cut)).run(data).metrics)
    }

    println("\n=== 單一合約 ===")
    for (d in data) {
        val m = Backtester(strategy, risk, base).run(listOf(d)).metrics
        println("%-16s 報酬 %+7.1f%%  最大回撤 %5.1f%%  交易 %3d  勝率 %4.1f%%  PF %4.2f  期望 %+.2fR".format(
            d.instId, m.totalReturnPct, m.maxDrawdownPct, m.trades, m.winRatePct, m.profitFactor, m.expectancyR))
    }
}

private fun SymbolData.until(t: Long) = SymbolData(instId, ltf.filter { it.time < t }, htf.filter { it.time < t })

/** Set by --offline: use the cached files as-is (reproducible research runs). */
private var offline = false

private suspend fun load(okx: OkxClient, dir: File, inst: String, tf: Timeframe, start: Long): List<Candle> {
    val file = File(dir, "$inst-${tf.okx}.csv")
    val cached = if (file.exists()) file.readLines().mapNotNull { line ->
        val p = line.split(",")
        if (p.size < 6) null else Candle(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
    } else emptyList()
    val haveFrom = cached.firstOrNull()?.time ?: Long.MAX_VALUE
    val haveTo = cached.lastOrNull()?.time ?: 0L
    val coversStart = haveFrom <= start + tf.millis
    val fresh = System.currentTimeMillis() - haveTo < 2 * tf.millis
    val merged = when {
        cached.isNotEmpty() && (offline || coversStart && fresh) -> cached
        coversStart -> {
            // Only the bars after the cached range are missing.
            val fetched = okx.historyCandles(inst, tf, haveTo)
            (cached + fetched).associateBy { it.time }.values.sortedBy { it.time }
        }
        else -> {
            print("  下載 $inst ${tf.okx} …")
            val fetched = okx.historyCandles(inst, tf, start) { n -> if (n % 2000 == 0) print(" $n") }
            println(" ${fetched.size} 根")
            (cached + fetched).associateBy { it.time }.values.sortedBy { it.time }
        }
    }
    if (merged !== cached) file.writeText(merged.joinToString("\n") { "${it.time},${it.open},${it.high},${it.low},${it.close},${it.volume}" })
    return merged.filter { it.time >= start }
}

private fun feeConfig(opts: Map<String, String>, equity: Double, start: Long): BacktestConfig =
    if (opts["fees"] == "maker") {
        // Limit orders on both sides: OKX VIP0 maker 0.02%, no slippage (optimistic).
        BacktestConfig(initialEquity = equity, startTime = start, takerFeePct = 0.02, slippagePct = 0.0)
    } else {
        BacktestConfig(initialEquity = equity, startTime = start)
    }

private fun trendVariants(base: StrategyConfig): List<Pair<String, Strategy>> =
    if (System.getenv("PP_SESSIONS") != null) {
        TradingSession.entries.map { it.label to TrendStrategy(base.copy(session = it)) }
    } else listOf(
        "預設" to base,
        "ADX>25" to base.copy(adxMin = 25.0),
        "ADX>20" to base.copy(adxMin = 20.0),
        "追蹤3ATR" to base.copy(trailAtrMult = 3.0),
        "追蹤5ATR" to base.copy(trailAtrMult = 5.0),
        "止損3ATR" to base.copy(stopAtrMult = 3.0),
        "僅回踩" to base.copy(useBreakout = false),
        "僅突破" to base.copy(usePullback = false),
        "只做多" to base.copy(allowShorts = false),
        "無保本" to base.copy(breakEvenR = 0.0),
    ).map { (n, c) -> n to TrendStrategy(c) }

private fun orbVariants(ltf: Timeframe, htf: Timeframe): List<Pair<String, Strategy>> {
    val b = OrbConfig(ltfFrame = ltf, htfFrame = htf)
    return listOf(
        "ORB 預設(15分區間,2R)" to b,
        "區間30分" to b.copy(rangeMinutes = 30),
        "區間5分" to b.copy(rangeMinutes = 5),
        "無趨勢過濾" to b.copy(trendFilter = false),
        "止盈1R" to b.copy(takeProfitR = 1.0),
        "止盈3R" to b.copy(takeProfitR = 3.0),
        "不設止盈(收盤平)" to b.copy(takeProfitR = 0.0),
        "止損在中線" to b.copy(stopAtMid = true),
        "放量1.5倍" to b.copy(volumeMult = 1.5),
        "保本1R" to b.copy(breakEvenR = 1.0),
        "08:30數據時段" to b.copy(openMinute = 8 * 60 + 30, entryUntilMinute = 11 * 60),
    ).map { (n, c) -> n to OrbStrategy(c) }
}

private fun mrVariants(ltf: Timeframe, htf: Timeframe): List<Pair<String, Strategy>> {
    val b = MeanRevConfig(ltfFrame = ltf, htfFrame = htf)
    return listOf(
        "均值回歸 預設" to b,
        "無ADX過濾" to b.copy(maxHtfAdx = 0.0),
        "布林2.5" to b.copy(bbMult = 2.5),
        "RSI<5" to b.copy(rsiExtreme = 5.0),
        "止損2.5ATR" to b.copy(stopAtrMult = 2.5),
        "持有24根" to b.copy(maxHoldBars = 24),
        "美股盤中" to b.copy(session = TradingSession.US_REGULAR),
        "美股開盤" to b.copy(session = TradingSession.US_OPEN),
        "亞洲盤" to b.copy(session = TradingSession.ASIA),
    ).map { (n, c) -> n to MeanReversionStrategy(c) }
}

private fun sweep(data: List<SymbolData>, risk: RiskConfig, base: BacktestConfig, variants: List<Pair<String, Strategy>>) {
    val start = base.startTime
    val now = System.currentTimeMillis()
    val mid = start + (now - start) / 2
    println("\n成本：手續費 ${base.takerFeePct}%／邊，滑價 ${base.slippagePct}%／邊｜前段 ${date(start)}→${date(mid)}｜後段 ${date(mid)}→${date(now)}")
    println("%-18s %8s %6s %6s | %8s %6s | %8s %6s %5s %6s %7s".format(
        "變體", "全期%", "PF", "回撤%", "前段%", "PF", "後段%", "PF", "交易", "勝率%", "成本%"))
    for ((name, strat) in variants) {
        val bt = { from: Long, to: Long ->
            Backtester(strat, risk, base.copy(startTime = from, endTime = to)).run(data.map { it.until(to + 1) }).metrics
        }
        val a = bt(start, now)
        val b = bt(start, mid)
        val c = bt(mid, now)
        println("%-18s %+8.1f %6.2f %6.1f | %+8.1f %6.2f | %+8.1f %6.2f %5d %6.1f %7.1f".format(
            name, a.totalReturnPct, a.profitFactor, a.maxDrawdownPct,
            b.totalReturnPct, b.profitFactor, c.totalReturnPct, c.profitFactor, a.trades, a.winRatePct,
            a.totalFees / a.initialEquity * 100))
    }
}

private fun printMetrics(m: BacktestMetrics) {
    println("期間 %.0f 天｜初始 %.0f → 最終 %.0f USDT".format(m.days, m.initialEquity, m.finalEquity))
    println("總報酬 %+.1f%%｜年化 %+.1f%%｜最大回撤 %.1f%%｜Sharpe %.2f｜Calmar %.2f".format(
        m.totalReturnPct, m.cagrPct, m.maxDrawdownPct, m.sharpe, m.calmar))
    println("交易 %d 筆（多 %d／空 %d）｜勝率 %.1f%%｜盈虧比 PF %.2f｜期望值 %+.2fR｜平均贏 %+.2fR／輸 %+.2fR".format(
        m.trades, m.longTrades, m.shortTrades, m.winRatePct, m.profitFactor, m.expectancyR, m.avgWinR, m.avgLossR))
    println("最長連虧 %d｜平均持倉 %.1f 根K｜持倉時間占比 %.1f%%｜手續費+資金費 %.0f USDT｜熔斷 %d 次".format(
        m.maxConsecutiveLosses, m.avgBarsHeld, m.exposurePct, m.totalFees, m.killSwitchTriggers))
}

private fun printBreakdown(r: BacktestResult) {
    println("\n年度/季度損益：")
    val byQuarter = r.trades.groupBy {
        val d = Instant.ofEpochMilli(it.exitTime).atZone(ZoneOffset.UTC)
        "${d.year}Q${(d.monthValue - 1) / 3 + 1}"
    }.toSortedMap()
    for ((q, ts) in byQuarter) {
        println("  %s  交易 %3d  損益 %+9.1f USDT  合計 %+6.1fR".format(q, ts.size, ts.sumOf { it.pnl }, ts.sumOf { it.rMultiple }))
    }
    println("出場原因：" + r.trades.groupBy { it.exitReason }.entries.joinToString("，") { (k, v) ->
        "%s %d 筆 %+.1fR".format(k, v.size, v.sumOf { it.rMultiple })
    })
    println("進場類型：" + r.trades.groupBy { it.entryType }.entries.joinToString("，") { (k, v) ->
        "%s %d 筆 勝率 %.0f%% %+.1fR".format(k, v.size, v.count { it.pnl > 0 } * 100.0 / v.size, v.sumOf { it.rMultiple })
    })
}

private fun printTrades(r: BacktestResult) {
    println("\n交易明細：")
    for (t in r.trades) {
        println("%s %-14s %s %-8s %10.4f → %10.4f  %+7.2fR  %+8.1f  %s".format(
            date(t.entryTime), t.instId, t.side.label, t.entryType, t.entryPrice, t.exitPrice, t.rMultiple, t.pnl, t.exitReason))
    }
}

private fun date(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate().toString()

private fun parseArgs(args: Array<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val k = args[i].removePrefix("--")
        if (i + 1 < args.size && !args[i + 1].startsWith("--")) { out[k] = args[i + 1]; i += 2 } else { out[k] = "true"; i++ }
    }
    return out
}
