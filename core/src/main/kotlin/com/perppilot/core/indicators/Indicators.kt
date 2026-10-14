package com.perppilot.core.indicators

import com.perppilot.core.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Classic technical indicators. Every function returns an array aligned with its input;
 * values that are not yet defined during the warm-up period are [Double.NaN].
 */
object Indicators {

    fun sma(values: DoubleArray, period: Int): DoubleArray {
        require(period > 0)
        val out = DoubleArray(values.size) { Double.NaN }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) out[i] = sum / period
        }
        return out
    }

    /** Exponential moving average seeded with the SMA of the first [period] values. */
    fun ema(values: DoubleArray, period: Int): DoubleArray {
        require(period > 0)
        val out = DoubleArray(values.size) { Double.NaN }
        if (values.size < period) return out
        val k = 2.0 / (period + 1)
        var prev = values.take(period).average()
        out[period - 1] = prev
        for (i in period until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out[i] = prev
        }
        return out
    }

    /** Wilder's smoothing (RMA), seeded with the SMA of the first [period] values. */
    fun rma(values: DoubleArray, period: Int, start: Int = 0): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (values.size - start < period) return out
        var prev = 0.0
        for (i in start until start + period) prev += values[i]
        prev /= period
        out[start + period - 1] = prev
        for (i in start + period until values.size) {
            prev = (prev * (period - 1) + values[i]) / period
            out[i] = prev
        }
        return out
    }

    /** Wilder RSI. */
    fun rsi(closes: DoubleArray, period: Int = 14): DoubleArray {
        val out = DoubleArray(closes.size) { Double.NaN }
        if (closes.size <= period) return out
        val gains = DoubleArray(closes.size)
        val losses = DoubleArray(closes.size)
        for (i in 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            gains[i] = max(d, 0.0)
            losses[i] = max(-d, 0.0)
        }
        val avgGain = rma(gains, period, start = 1)
        val avgLoss = rma(losses, period, start = 1)
        for (i in closes.indices) {
            val g = avgGain[i]
            val l = avgLoss[i]
            if (g.isNaN() || l.isNaN()) continue
            out[i] = when {
                l == 0.0 && g == 0.0 -> 50.0
                l == 0.0 -> 100.0
                else -> 100.0 - 100.0 / (1.0 + g / l)
            }
        }
        return out
    }

    fun trueRange(candles: List<Candle>): DoubleArray {
        val out = DoubleArray(candles.size)
        for (i in candles.indices) {
            val c = candles[i]
            out[i] = if (i == 0) {
                c.high - c.low
            } else {
                val pc = candles[i - 1].close
                max(c.high - c.low, max(abs(c.high - pc), abs(c.low - pc)))
            }
        }
        return out
    }

    /** Wilder ATR. */
    fun atr(candles: List<Candle>, period: Int = 14): DoubleArray = rma(trueRange(candles), period)

    data class AdxResult(val adx: DoubleArray, val plusDi: DoubleArray, val minusDi: DoubleArray)

    /** Wilder ADX / DMI. */
    fun adx(candles: List<Candle>, period: Int = 14): AdxResult {
        val n = candles.size
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)
        for (i in 1 until n) {
            val up = candles[i].high - candles[i - 1].high
            val down = candles[i - 1].low - candles[i].low
            plusDm[i] = if (up > down && up > 0) up else 0.0
            minusDm[i] = if (down > up && down > 0) down else 0.0
        }
        val tr = trueRange(candles)
        val trS = rma(tr, period, start = 1)
        val pS = rma(plusDm, period, start = 1)
        val mS = rma(minusDm, period, start = 1)
        val plusDi = DoubleArray(n) { Double.NaN }
        val minusDi = DoubleArray(n) { Double.NaN }
        val dx = DoubleArray(n) { Double.NaN }
        var firstDx = -1
        for (i in 0 until n) {
            if (trS[i].isNaN() || trS[i] == 0.0) continue
            plusDi[i] = 100.0 * pS[i] / trS[i]
            minusDi[i] = 100.0 * mS[i] / trS[i]
            val s = plusDi[i] + minusDi[i]
            dx[i] = if (s == 0.0) 0.0 else 100.0 * abs(plusDi[i] - minusDi[i]) / s
            if (firstDx < 0) firstDx = i
        }
        val adx = if (firstDx < 0) DoubleArray(n) { Double.NaN } else rma(dx, period, start = firstDx)
        return AdxResult(adx, plusDi, minusDi)
    }

    data class MacdResult(val macd: DoubleArray, val signal: DoubleArray, val histogram: DoubleArray)

    fun macd(closes: DoubleArray, fast: Int = 12, slow: Int = 26, signal: Int = 9): MacdResult {
        val f = ema(closes, fast)
        val s = ema(closes, slow)
        val macd = DoubleArray(closes.size) { if (f[it].isNaN() || s[it].isNaN()) Double.NaN else f[it] - s[it] }
        val firstValid = macd.indexOfFirst { !it.isNaN() }
        val sig = DoubleArray(closes.size) { Double.NaN }
        if (firstValid >= 0) {
            val sub = ema(macd.copyOfRange(firstValid, macd.size), signal)
            sub.copyInto(sig, firstValid)
        }
        val hist = DoubleArray(closes.size) { if (macd[it].isNaN() || sig[it].isNaN()) Double.NaN else macd[it] - sig[it] }
        return MacdResult(macd, sig, hist)
    }

    data class BollingerResult(val middle: DoubleArray, val upper: DoubleArray, val lower: DoubleArray) {
        /** Band width relative to the middle band. */
        fun width(i: Int): Double = if (middle[i].isNaN() || middle[i] == 0.0) Double.NaN else (upper[i] - lower[i]) / middle[i]
    }

    fun bollinger(closes: DoubleArray, period: Int = 20, mult: Double = 2.0): BollingerResult {
        val mid = sma(closes, period)
        val up = DoubleArray(closes.size) { Double.NaN }
        val lo = DoubleArray(closes.size) { Double.NaN }
        for (i in period - 1 until closes.size) {
            var ss = 0.0
            for (j in i - period + 1..i) {
                val d = closes[j] - mid[i]
                ss += d * d
            }
            val sd = sqrt(ss / period)
            up[i] = mid[i] + mult * sd
            lo[i] = mid[i] - mult * sd
        }
        return BollingerResult(mid, up, lo)
    }

    /** Highest value of the [period] bars ending at (and including) index i. */
    fun highest(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        for (i in period - 1 until values.size) {
            var m = Double.NEGATIVE_INFINITY
            for (j in i - period + 1..i) m = max(m, values[j])
            out[i] = m
        }
        return out
    }

    /** Lowest value of the [period] bars ending at (and including) index i. */
    fun lowest(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        for (i in period - 1 until values.size) {
            var m = Double.POSITIVE_INFINITY
            for (j in i - period + 1..i) m = min(m, values[j])
            out[i] = m
        }
        return out
    }
}

fun List<Candle>.closes(): DoubleArray = DoubleArray(size) { this[it].close }
fun List<Candle>.highs(): DoubleArray = DoubleArray(size) { this[it].high }
fun List<Candle>.lows(): DoubleArray = DoubleArray(size) { this[it].low }
fun List<Candle>.volumes(): DoubleArray = DoubleArray(size) { this[it].volume }
