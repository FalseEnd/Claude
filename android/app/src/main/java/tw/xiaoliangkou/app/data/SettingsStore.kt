package tw.xiaoliangkou.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("xiaoliangkou")

/** App 的本機設定：模式、伺服器網址、登入權杖、背景服務的小狀態 */
class SettingsStore(private val context: Context) {
    enum class Mode { NONE, DEMO, REMOTE }

    data class Prefs(
        val mode: Mode = Mode.NONE,
        val serverUrl: String = "",
        val token: String? = null,
        val userId: String? = null,
        val autoBookkeeping: Boolean = true,
        val onboarded: Boolean = false,
    )

    private object K {
        val mode = stringPreferencesKey("mode")
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val autoBookkeeping = booleanPreferencesKey("auto_bookkeeping")
        val onboarded = booleanPreferencesKey("onboarded")
        val insidePlaces = stringSetPreferencesKey("inside_places")
        val stepDay = longPreferencesKey("step_day")
        val stepBase = longPreferencesKey("step_base")
    }

    private fun read(p: Preferences) = Prefs(
        mode = runCatching { Mode.valueOf(p[K.mode] ?: "NONE") }.getOrDefault(Mode.NONE),
        serverUrl = p[K.serverUrl] ?: "",
        token = p[K.token],
        userId = p[K.userId],
        autoBookkeeping = p[K.autoBookkeeping] ?: true,
        onboarded = p[K.onboarded] ?: false,
    )

    suspend fun load(): Prefs = read(context.dataStore.data.first())

    suspend fun setDemo() = context.dataStore.edit { it[K.mode] = Mode.DEMO.name }

    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[K.serverUrl] = url }

    suspend fun setAuth(url: String, token: String, userId: String) = context.dataStore.edit {
        it[K.mode] = Mode.REMOTE.name
        it[K.serverUrl] = url
        it[K.token] = token
        it[K.userId] = userId
    }

    suspend fun setToken(token: String) = context.dataStore.edit { it[K.token] = token }

    suspend fun clear() = context.dataStore.edit {
        it.remove(K.mode); it.remove(K.token); it.remove(K.userId); it.remove(K.insidePlaces); it.remove(K.onboarded)
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
