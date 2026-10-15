package tw.xiaoliangkou.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

interface Api {
    @GET("api/health") suspend fun health(): JsonObject
    @POST("api/auth/register") suspend fun register(@Body body: JsonObject): AuthResponse
    @POST("api/auth/login") suspend fun login(@Body body: JsonObject): AuthResponse
    @POST("api/auth/password") suspend fun changePassword(@Body body: JsonObject): AuthResponse

    @GET("api/me") suspend fun me(): MeResponse
    @PUT("api/profile") suspend fun updateProfile(@Body body: JsonObject): Profile
    @PUT("api/couple") suspend fun updateCouple(@Body body: JsonObject): Couple
    @POST("api/pair/invite") suspend fun invite(): Invite
    @POST("api/pair/accept") suspend fun accept(@Body body: JsonObject): JsonObject
    @POST("api/pair/unpair") suspend fun unpair(): JsonObject

    @PUT("api/sharing") suspend fun updateSharing(@Body body: JsonObject): Sharing
    @POST("api/sharing/pause") suspend fun pauseSharing(@Body body: JsonObject): JsonObject

    @PUT("api/status/device") suspend fun putDevice(@Body body: JsonObject): JsonObject
    @PUT("api/status/location") suspend fun putLocation(@Body body: JsonObject): JsonObject
    @GET("api/partner/status") suspend fun partnerStatus(): PartnerStatus
    @POST("api/partner/locate") suspend fun locatePartner(): JsonObject

    @GET("api/places") suspend fun places(): List<Place>
    @POST("api/places") suspend fun createPlace(@Body body: JsonObject): Place
    @PUT("api/places/{id}") suspend fun updatePlace(@Path("id") id: String, @Body body: JsonObject): Place
    @DELETE("api/places/{id}") suspend fun deletePlace(@Path("id") id: String): JsonObject
    @POST("api/places/{id}/events") suspend fun placeEvent(@Path("id") id: String, @Body body: JsonObject): JsonObject

    @GET("api/expenses") suspend fun expenses(@Query("from") from: String? = null, @Query("to") to: String? = null): List<Expense>
    @POST("api/expenses") suspend fun createExpense(@Body body: JsonObject): JsonObject
    @PUT("api/expenses/{id}") suspend fun updateExpense(@Path("id") id: String, @Body body: JsonObject): Expense
    @DELETE("api/expenses/{id}") suspend fun deleteExpense(@Path("id") id: String): JsonObject
    @GET("api/partner/expenses") suspend fun partnerExpenses(@Query("from") from: String? = null, @Query("to") to: String? = null): List<Expense>
    @POST("api/partner/expenses/{id}/guess") suspend fun guess(@Path("id") id: String, @Body body: JsonObject): GuessResult

    @GET("api/moods") suspend fun moods(@Query("days") days: Int = 14): List<Mood>
    @POST("api/moods") suspend fun postMood(@Body body: JsonObject): Mood
    @GET("api/comforts") suspend fun comforts(@Query("limit") limit: Int = 50): List<Comfort>
    @POST("api/comforts") suspend fun postComfort(@Body body: JsonObject): Comfort

    @GET("api/anniversaries") suspend fun anniversaries(): List<Anniversary>
    @POST("api/anniversaries") suspend fun createAnniversary(@Body body: JsonObject): Anniversary
    @PUT("api/anniversaries/{id}") suspend fun updateAnniversary(@Path("id") id: String, @Body body: JsonObject): Anniversary
    @DELETE("api/anniversaries/{id}") suspend fun deleteAnniversary(@Path("id") id: String): JsonObject

    @GET("api/events") suspend fun events(): List<DateEvent>
    @POST("api/events") suspend fun createEvent(@Body body: JsonObject): DateEvent
    @PUT("api/events/{id}") suspend fun updateEvent(@Path("id") id: String, @Body body: JsonObject): DateEvent
    @POST("api/events/{id}/answer") suspend fun answerEvent(@Path("id") id: String, @Body body: JsonObject): DateEvent
    @DELETE("api/events/{id}") suspend fun deleteEvent(@Path("id") id: String): JsonObject

    @GET("api/cycle") suspend fun cycle(): CycleResponse
    @PUT("api/cycle/settings") suspend fun saveCycleSettings(@Body body: JsonObject): CycleSettingsDto
    @DELETE("api/cycle/settings") suspend fun stopCycle(): JsonObject
    @POST("api/cycle/periods") suspend fun addPeriod(@Body body: JsonObject): PeriodDto
    @PUT("api/cycle/periods/{id}") suspend fun updatePeriod(@Path("id") id: String, @Body body: JsonObject): PeriodDto
    @DELETE("api/cycle/periods/{id}") suspend fun deletePeriod(@Path("id") id: String): JsonObject
    @PUT("api/cycle/logs/{date}") suspend fun saveCycleLog(@Path("date") date: String, @Body body: JsonObject): CycleLog

    @GET("api/history") suspend fun history(@Query("before") before: Long? = null, @Query("limit") limit: Int = 60): List<Activity>
    @GET("api/notification-prefs") suspend fun notificationPrefs(): Map<String, Boolean>
    @PUT("api/notification-prefs") suspend fun setNotificationPrefs(@Body body: JsonObject): JsonObject

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = true }

        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            return "$url/"
        }

        fun create(baseUrl: String, token: () -> String?): Api {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val t = token()
                    val req = chain.request().newBuilder().apply { if (t != null) header("Authorization", "Bearer $t") }.build()
                    chain.proceed(req)
                }
                .build()
            return Retrofit.Builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(Api::class.java)
        }
    }
}

/** 給畫面顯示的錯誤訊息 */
class AppException(message: String, val code: String? = null, val status: Int? = null) : Exception(message)

suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    val body = e.response()?.errorBody()?.string()
    val err = runCatching { Api.json.decodeFromString(ApiError.serializer(), body ?: "") }.getOrNull()
    throw AppException(err?.message ?: "伺服器回應 ${e.code()}", err?.error, e.code())
} catch (e: IOException) {
    throw AppException("連不到伺服器，請確認網址、Wi-Fi 或 Tailscale 是否連線", "offline")
} catch (e: kotlinx.serialization.SerializationException) {
    throw AppException("伺服器回傳的資料格式不對，App 和伺服器版本可能不一致", "bad_response")
}
