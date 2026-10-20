package tw.xiaoliangkou.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import tw.xiaoliangkou.app.core.StepCounter
import tw.xiaoliangkou.app.ui.StatusText

private val Context.dataStore by preferencesDataStore("xiaoliangkou")

/** App 的本機設定：模式、Supabase 連線、登入狀態、背景服務的小狀態 */
class SettingsStore(private val context: Context) {
    enum class Mode { NONE, DEMO, REMOTE }

    data class Prefs(
        val mode: Mode = Mode.NONE,
        val supabaseUrl: String = "",
        val supabaseKey: String = "",
        val session: Session? = null,
        /** 上次登入的 Email，登出後幫忙填好 */
        val lastEmail: String = "",
        val autoBookkeeping: Boolean = true,
        val onboarded: Boolean = false,
        /** 常駐通知顯示對方的狀態，以及要顯示哪些項目（見 [StatusText]） */
        val statusNotification: Boolean = true,
        val statusItems: Set<String> = StatusText.DEFAULTS,
        /** 最近用過的 GIPHY 貼圖 id，最近的在前（最多 [RECENT_STICKERS] 個，登出就清掉） */
        val recentStickers: List<String> = emptyList(),
    ) {
        val configured get() = supabaseUrl.isNotEmpty() && supabaseKey.isNotEmpty()
    }

    private object K {
        val mode = stringPreferencesKey("mode")
        val supabaseUrl = stringPreferencesKey("supabase_url")
        val supabaseKey = stringPreferencesKey("supabase_key")
        val accessToken = stringPreferencesKey("access_token")
        val refreshToken = stringPreferencesKey("refresh_token")
        val expiresAt = longPreferencesKey("expires_at")
        val userId = stringPreferencesKey("user_id")
        val email = stringPreferencesKey("email")
        val autoBookkeeping = booleanPreferencesKey("auto_bookkeeping")
        val onboarded = booleanPreferencesKey("onboarded")
        val statusNotification = booleanPreferencesKey("status_notification")
        val statusItems = stringSetPreferencesKey("status_items")
        val insidePlaces = stringSetPreferencesKey("inside_places")
        val stepDay = longPreferencesKey("step_day")
        val stepBase = longPreferencesKey("step_base")
        val stepLast = longPreferencesKey("step_last")
        val stepCarry = longPreferencesKey("step_carry")
        val stepLastAt = longPreferencesKey("step_last_at")
        val stepSession = stringPreferencesKey("step_session")
        // 有順序的清單：用逗號分開（GIPHY 的 id 只有英數字）
        val recentStickers = stringPreferencesKey("recent_stickers")
        // 舊版留下的設定，登出時順便清掉
        val legacy = listOf(stringPreferencesKey("server_url"), stringPreferencesKey("token"))
    }

    private fun read(p: Preferences): Prefs {
        val access = p[K.accessToken]
        val refresh = p[K.refreshToken]
        val uid = p[K.userId]
        val session = if (access != null && refresh != null && uid != null) Session(access, refresh, p[K.expiresAt] ?: 0L, uid, p[K.email]) else null
        val mode = runCatching { Mode.valueOf(p[K.mode] ?: "NONE") }.getOrDefault(Mode.NONE)
        return Prefs(
            // 舊版留下的 REMOTE 沒有 Supabase 登入狀態：當作還沒設定
            mode = if (mode == Mode.REMOTE && session == null) Mode.NONE else mode,
            // 還沒設定過就用預設的 Supabase 專案
            supabaseUrl = p[K.supabaseUrl] ?: Supabase.DEFAULT_URL,
            supabaseKey = p[K.supabaseKey] ?: Supabase.DEFAULT_KEY,
            session = session,
            lastEmail = p[K.email] ?: "",
            autoBookkeeping = p[K.autoBookkeeping] ?: true,
            onboarded = p[K.onboarded] ?: false,
            statusNotification = p[K.statusNotification] ?: true,
            statusItems = p[K.statusItems] ?: StatusText.DEFAULTS,
            recentStickers = stickers(p[K.recentStickers]),
        )
    }

    private fun stickers(raw: String?) = raw?.split(',')?.filter(Giphy::isValidId).orEmpty()

    suspend fun load(): Prefs = read(context.dataStore.data.first())

    suspend fun setDemo() = context.dataStore.edit { it[K.mode] = Mode.DEMO.name }

    /** 測試連線成功後存起來 */
    suspend fun setSupabase(url: String, key: String) = context.dataStore.edit {
        it[K.supabaseUrl] = url
        it[K.supabaseKey] = key
    }

    /** 登入或註冊成功 */
    suspend fun setSession(s: Session) = context.dataStore.edit {
        it[K.mode] = Mode.REMOTE.name
        writeSession(it, s)
    }

    /** token 換新：只在還是登入狀態時更新，避免登出後又被寫回去 */
    suspend fun updateSession(s: Session) = context.dataStore.edit {
        if (it[K.mode] == Mode.REMOTE.name && it[K.userId] == s.userId) writeSession(it, s)
    }

    private fun writeSession(p: MutablePreferences, s: Session) {
        p[K.accessToken] = s.accessToken
        p[K.refreshToken] = s.refreshToken
        p[K.expiresAt] = s.expiresAt
        p[K.userId] = s.userId
        if (s.email != null) p[K.email] = s.email
    }

    /** 登出：清掉登入狀態，保留 Supabase 網址、金鑰與 Email，方便重新登入 */
    suspend fun clear() = context.dataStore.edit {
        listOf(K.mode, K.accessToken, K.refreshToken, K.userId).forEach { k -> it.remove(k) }
        it.remove(K.expiresAt); it.remove(K.insidePlaces); it.remove(K.onboarded); it.remove(K.recentStickers)
        K.legacy.forEach { k -> it.remove(k) }
    }

    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[K.onboarded] = v }
    suspend fun setAutoBookkeeping(v: Boolean) = context.dataStore.edit { it[K.autoBookkeeping] = v }
    suspend fun setStatusNotification(v: Boolean) = context.dataStore.edit { it[K.statusNotification] = v }
    suspend fun setStatusItems(items: Set<String>) = context.dataStore.edit { it[K.statusItems] = items }

    /** 選了一張貼圖：放到「最近用過」的最前面 */
    suspend fun addRecentSticker(id: String) = context.dataStore.edit {
        if (Giphy.isValidId(id)) it[K.recentStickers] = (listOf(id) + stickers(it[K.recentStickers])).distinct().take(RECENT_STICKERS).joinToString(",")
    }

    suspend fun insidePlaces(): Set<String> = context.dataStore.data.first()[K.insidePlaces] ?: emptySet()
    suspend fun setInsidePlaces(ids: Set<String>) = context.dataStore.edit { it[K.insidePlaces] = ids }

    /** 今天到目前為止的步數（背景服務最後一次讀到的）；今天還沒讀到是 null */
    suspend fun stepsSoFar(day: Long): Int? {
        val p = context.dataStore.data.first()
        if (p[K.stepDay] != day) return null
        val base = p[K.stepBase] ?: return null
        val last = p[K.stepLast] ?: return null
        return ((p[K.stepCarry] ?: 0) + (last - base).coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** 今日步數（換算方式見 [StepCounter]） */
    suspend fun stepsToday(r: StepCounter.Reading): Int {
        var steps = 0L
        context.dataStore.edit {
            val day = it[K.stepDay]
            val prev = if (day == null) null else StepCounter.State(
                day = day,
                base = it[K.stepBase] ?: 0,
                carry = it[K.stepCarry] ?: 0,
                // 舊版只存了 base
                last = it[K.stepLast] ?: it[K.stepBase] ?: 0,
                lastAt = it[K.stepLastAt] ?: 0,
                session = it[K.stepSession],
            )
            val (next, n) = StepCounter.next(prev, r)
            it[K.stepDay] = next.day
            it[K.stepBase] = next.base
            it[K.stepCarry] = next.carry
            it[K.stepLast] = next.last
            it[K.stepLastAt] = next.lastAt
            next.session?.let { v -> it[K.stepSession] = v }
            steps = n
        }
        return steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        const val RECENT_STICKERS = 24
    }
}
