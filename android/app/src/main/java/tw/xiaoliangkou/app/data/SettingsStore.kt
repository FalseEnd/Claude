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
        val insidePlaces = stringSetPreferencesKey("inside_places")
        val stepDay = longPreferencesKey("step_day")
        val stepBase = longPreferencesKey("step_base")
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
            supabaseUrl = p[K.supabaseUrl] ?: "",
            supabaseKey = p[K.supabaseKey] ?: "",
            session = session,
            lastEmail = p[K.email] ?: "",
            autoBookkeeping = p[K.autoBookkeeping] ?: true,
            onboarded = p[K.onboarded] ?: false,
        )
    }

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
        it.remove(K.expiresAt); it.remove(K.insidePlaces); it.remove(K.onboarded)
        K.legacy.forEach { k -> it.remove(k) }
    }

    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[K.onboarded] = v }
    suspend fun setAutoBookkeeping(v: Boolean) = context.dataStore.edit { it[K.autoBookkeeping] = v }

    suspend fun insidePlaces(): Set<String> = context.dataStore.data.first()[K.insidePlaces] ?: emptySet()
    suspend fun setInsidePlaces(ids: Set<String>) = context.dataStore.edit { it[K.insidePlaces] = ids }

    /** 計步感測器是開機後的累計值：記住每天第一次讀到的數字當作基準 */
    suspend fun stepsToday(dayNumber: Long, sensorTotal: Long): Int {
        var base = sensorTotal
        context.dataStore.edit {
            val day = it[K.stepDay]
            val saved = it[K.stepBase]
            if (day == dayNumber && saved != null && saved <= sensorTotal) {
                base = saved
            } else {
                it[K.stepDay] = dayNumber
                it[K.stepBase] = sensorTotal
            }
        }
        return (sensorTotal - base).toInt()
    }
}
