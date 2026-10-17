package tw.xiaoliangkou.app.core

/**
 * 今日步數：手機的計步器是「開機以來」的累計值（只有偵測器的手機是我們自己數的，服務重開就歸零），
 * 這裡把一次次讀到的數字換算成今天走了幾步。
 * - 換手機開機、服務重新啟動（[Reading.session] 不同）或數字變小：今天已經走的先存起來，不會不見
 * - 新的一天：昨天很晚還有讀到數字，就從那個數字算起；計步器是今天才開始數的（今天開機），就全部算今天的
 */
object StepCounter {
    /** 存在手機裡的狀態 */
    data class State(
        val day: Long,
        val base: Long,
        val carry: Long,
        val last: Long,
        /** 上一次讀到的時間（epoch 毫秒），0 是不知道 */
        val lastAt: Long,
        /** 上一次讀到時的計步器（開機時間或服務），null 是舊版存的 */
        val session: String?,
    )

    data class Reading(
        val day: Long,
        val total: Long,
        val session: String,
        /** 這個計步器從什麼時候開始數（開機或服務啟動的 epoch 毫秒） */
        val sessionStart: Long,
        val now: Long,
        /** 今天 0 點的 epoch 毫秒 */
        val dayStart: Long,
    )

    /** 昨天最後一次讀到的數字，要在午夜前幾小時內才拿來當今天的起點 */
    const val NIGHT_MS = 3 * 60 * 60_000L

    fun next(prev: State?, r: Reading): Pair<State, Long> {
        val countingSinceToday = r.sessionStart >= r.dayStart
        val reset = prev != null && ((prev.session != null && prev.session != r.session) || r.total < prev.last)
        val base: Long
        val carry: Long
        when {
            prev == null -> {
                base = if (countingSinceToday) 0 else r.total
                carry = 0
            }
            prev.day != r.day -> {
                base = when {
                    reset -> if (countingSinceToday) 0 else r.total
                    prev.day == r.day - 1 && prev.lastAt >= r.dayStart - NIGHT_MS -> prev.last
                    else -> r.total
                }
                carry = 0
            }
            reset -> {
                carry = prev.carry + (prev.last - prev.base).coerceAtLeast(0)
                base = 0
            }
            else -> {
                base = prev.base
                carry = prev.carry
            }
        }
        val steps = carry + (r.total - base).coerceAtLeast(0)
        return State(r.day, base, carry, r.total, r.now, r.session) to steps
    }
}
