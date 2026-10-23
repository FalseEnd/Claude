package tw.xiaoliangkou.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.gif.AnimatedImageDecoder
import coil3.request.ErrorResult
import coil3.test.FakeImageLoaderEngine
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetworkCapabilities
import tw.xiaoliangkou.app.data.Giphy
import tw.xiaoliangkou.app.data.GiphyMediaCache
import tw.xiaoliangkou.app.data.giphyHttp
import tw.xiaoliangkou.app.data.giphyMediaHttp
import tw.xiaoliangkou.app.ui.components.StickerImage
import tw.xiaoliangkou.app.ui.components.animationsOff
import tw.xiaoliangkou.app.ui.components.stickerImageLoader
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/** 選好的貼圖：用 id 組出 GIPHY 的網址（WebP、不會動的），id 不對不載；圖片是假的（[TestXlkApp]），不連網路 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StickerImageTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as TestXlkApp
    private val requests get() = app.stickers.requests.toList()
    private val sticker = "L4c8BNDK4STL7B5LdV"
    private val png by lazy { ByteArrayOutputStream().also { FakeStickers.bitmap(sticker).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
    private var shown by mutableStateOf(false)

    @After fun tearDown() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun show(id: String?) = compose.setContent { XlkTheme { StickerImage(id, Modifier.size(120.dp)) } }

    private fun waitForSticker() = compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("貼圖").fetchSemanticsNodes().isNotEmpty() }

    /** 每個 Robolectric 測試都用 TestXlkApp：貼圖不會真的連到 GIPHY */
    @Test fun robolectricUsesTheTestApp() {
        assertEquals(TestXlkApp::class.java, RuntimeEnvironment.getApplication().javaClass)
        assertSame(app.stickers.loader, app.container.stickerImages)
    }

    @Test fun validStickerLoadsThenDescribesItself() {
        show("L4c8BNDK4STL7B5LdV")
        waitForSticker()
        // Android 9 以上：會動的 WebP
        assertEquals(listOf("https://media.giphy.com/media/L4c8BNDK4STL7B5LdV/200w.webp"), requests.distinct())
    }

    @Test fun invalidIdNeverLoads() {
        show("../../evil?x=1")
        compose.waitForIdle()
        compose.onNodeWithText("🖼️").assertExists()
        compose.onAllNodesWithContentDescription("貼圖").assertCountEquals(0)
        assertTrue(requests.isEmpty())
    }

    @Test fun animationsOffAsksForTheStill() {
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(animationsOff(app))
        show("L4c8BNDK4STL7B5LdV")
        waitForSticker()
        assertEquals(listOf("https://media.giphy.com/media/L4c8BNDK4STL7B5LdV/200w_s.gif"), requests.distinct())
    }

    @Test fun failedLoadShowsAQuietPlaceholder() {
        app.container.stickerImages = ImageLoader.Builder(app)
            .components { add(FakeImageLoaderEngine.Builder().intercept({ true }) { chain -> ErrorResult(null, chain.request, IOException("404")) }.build()) }
            .build()
        show("L4c8BNDK4STL7B5LdV")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("🖼️").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("貼圖").assertCountEquals(0)
    }

    /** 真的載入器：只有一個連網路的方式（不會自動再加一份預設連線的）、不存檔；圖片的連線從 GIPHY 的那條長出來，多了記憶體裡的快取 */
    @Test fun realLoaderUsesOnlyTheGiphyConnection() {
        val loader = stickerImageLoader(app)
        val network = loader.components.fetcherFactories.map { it.first.javaClass.name }.filter { "network" in it.lowercase() }
        assertEquals(network.toString(), 1, network.size)
        assertNull(loader.diskCache)
        assertTrue(loader.components.decoderFactories.any { it is AnimatedImageDecoder.Factory })
        assertTrue(giphyMediaHttp.interceptors.single() is GiphyMediaCache)
        assertTrue(giphyMediaHttp.networkInterceptors.isEmpty())
        assertSame("共用連線池", giphyHttp.connectionPool, giphyMediaHttp.connectionPool)
        assertSame(Authenticator.NONE, giphyMediaHttp.authenticator)
    }

    /** 真的載入器送出去的請求：沒有 Supabase 的 apikey、登入的 token（在本機開一個假的伺服器看它收到什麼） */
    @Test fun realLoaderSendsNoSupabaseHeaders() {
        online()
        val headers = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            server.accept().use { sock ->
                val input = sock.getInputStream().bufferedReader()
                generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.forEach { headers += it }
                sock.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(png)
                    flush()
                }
            }
        }
        val loader = stickerImageLoader(app)
        var state: AsyncImagePainter.State? = null
        compose.setContent {
            AsyncImage("http://127.0.0.1:${server.localPort}/media/$sticker/200w.webp", null, loader, Modifier.size(50.dp), onState = { state = it })
        }
        compose.waitUntil(10_000) { state is AsyncImagePainter.State.Success || state is AsyncImagePainter.State.Error }
        server.close()
        assertTrue("$state", state is AsyncImagePainter.State.Success)
        assertEquals("GET /media/$sticker/200w.webp HTTP/1.1", headers.first())
        assertTrue(headers.toString(), headers.none { it.lowercase().startsWith("apikey:") || it.lowercase().startsWith("authorization:") })
    }

    /**
     * 會動的貼圖 Coil 不放進它的記憶體快取（這裡把 Coil 的記憶體快取整個關掉來模擬）：
     * 同一張貼圖再出現時，從 [GiphyMediaCache] 拿，不用再向 GIPHY 下載一次；沒有它的話每次都要重新下載
     */
    @Test fun stickerIsDownloadedOnce() {
        val url = Giphy.stickerUrl(sticker)!!
        fun downloads(cache: Boolean): List<String> {
            val hits = Collections.synchronizedList(mutableListOf<String>())
            val http = giphyHttp.newBuilder().apply { if (cache) addInterceptor(GiphyMediaCache()) }.addInterceptor { chain ->
                hits += chain.request().url.toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "image/png").body(png.toResponseBody("image/png".toMediaType())).build()
            }.build()
            app.container.stickerImages = stickerImageLoader(app, http).newBuilder().memoryCache(null).build()
            shown = true
            waitForSticker()
            shown = false
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("貼圖").fetchSemanticsNodes().isEmpty() }
            shown = true
            waitForSticker()
            shown = false
            compose.waitForIdle()
            return hits.toList()
        }
        compose.setContent { XlkTheme { if (shown) StickerImage(sticker, Modifier.size(120.dp)) } }
        assertEquals(listOf(url, url), downloads(cache = false))
        assertEquals(listOf(url), downloads(cache = true))
    }

    /** 「Powered by GIPHY」用官方標誌包的大張（641×71）：16 dp 高在高解析度的手機上也清楚 */
    @Test fun attributionMarksAreTheLargeOnes() {
        for (res in listOf(R.drawable.giphy_attribution_light, R.drawable.giphy_attribution_dark)) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(app.resources, res, o)
            assertEquals(641 to 71, o.outWidth to o.outHeight)
        }
    }

    /** Robolectric 的網路預設沒有「可以上網」：Coil 會當成離線，不送出請求 */
    private fun online() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }
}
