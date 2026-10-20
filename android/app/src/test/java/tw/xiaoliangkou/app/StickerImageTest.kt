package tw.xiaoliangkou.app

import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.request.ErrorResult
import coil3.test.FakeImageLoaderEngine
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
import org.robolectric.annotation.Config
import tw.xiaoliangkou.app.ui.components.StickerImage
import tw.xiaoliangkou.app.ui.components.animationsOff
import tw.xiaoliangkou.app.ui.components.stickerImageLoader
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.io.IOException

/** 選好的貼圖：用 id 組出 GIPHY 的網址（WebP、不會動的），id 不對不載；圖片是假的（[TestXlkApp]），不連網路 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StickerImageTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as TestXlkApp
    private val requests get() = app.stickers.requests.toList()

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

    /** 真的載入器：只用 GIPHY 的連線（不會自動再加一份預設連線的）、不存檔 */
    @Test fun realLoaderUsesOnlyTheGiphyConnection() {
        val loader = stickerImageLoader(app)
        val network = loader.components.fetcherFactories.map { it.first.javaClass.name }.filter { "network" in it.lowercase() }
        assertEquals(network.toString(), 1, network.size)
        assertNull(loader.diskCache)
        assertTrue(loader.components.decoderFactories.any { it is AnimatedImageDecoder.Factory })
    }
}
