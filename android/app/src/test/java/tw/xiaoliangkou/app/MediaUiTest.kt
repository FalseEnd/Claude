package tw.xiaoliangkou.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import tw.xiaoliangkou.app.data.DemoMedia
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.components.MediaPickerRow
import tw.xiaoliangkou.app.ui.components.MediaThumbs
import tw.xiaoliangkou.app.ui.components.VoiceChip
import tw.xiaoliangkou.app.ui.theme.XlkTheme

/**
 * 照片縮圖、看大圖、語音膠囊、選照片／錄音按鈕：畫得出來、照片從快取讀得到。
 * 截圖：./gradlew recordRoborazziDebug --tests '*MediaUiTest*'，圖片在 app/build/screens/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class MediaUiTest {
    @get:Rule val compose = createComposeRule()
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }
    private val photos = listOf(DemoMedia.PICNIC, DemoMedia.DINNER, DemoMedia.TRIP)

    /** 示範照片直接放進快取（不用登入、不用網路） */
    private fun primeCache() = runBlocking {
        vm.container.media.clear()
        photos.forEach { vm.container.media.put(it.path, DemoMedia.sample(it.path)!!) }
    }

    @After fun tearDown() = runBlocking { vm.container.media.clear() }

    private fun photoCount() = compose.onAllNodesWithContentDescription("照片").fetchSemanticsNodes().size

    @Test fun thumbnailsVoiceAndPicker() {
        primeCache()
        compose.setContent {
            XlkTheme {
                var media by remember { mutableStateOf(photos + DemoMedia.VOICE) }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MediaThumbs(media, vm, onRemove = { m -> media = media - m })
                    MediaThumbs(listOf(MediaItem("c/x/gone.jpg", MediaItem.PHOTO)), vm, size = 64.dp)
                    VoiceChip(MediaItem("c/x/v.m4a", MediaItem.VOICE, durationMs = 65_000), vm)
                    MediaPickerRow(vm, shared = true, onAdded = { media = media + it })
                }
            }
        }
        compose.waitUntil(WAIT) { photoCount() == 3 }
        compose.onNodeWithText("🎙️ 0:03").assertExists()
        compose.onNodeWithText("🎙️ 1:05").assertExists()
        compose.onNodeWithText("📷 照片").assertExists()
        compose.onNodeWithText("🎙️ 錄音").assertExists()
        compose.onRoot().captureRoboImage("build/screens/media_thumbs.png")

        // 移除一張
        compose.onAllNodesWithContentDescription("移除")[0].performClick()
        compose.waitUntil(WAIT) { photoCount() == 2 }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun fullScreenViewer() {
        primeCache()
        compose.setContent { XlkTheme { MediaThumbs(photos, vm) } }
        compose.waitUntil(WAIT) { photoCount() == 3 }
        compose.onAllNodesWithContentDescription("照片")[1].performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithContentDescription("照片 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2 / 3").assertExists()
        captureScreenRoboImage("build/screens/media_viewer.png")
        compose.onNodeWithContentDescription("關閉").performClick()
        compose.waitUntil(WAIT) { compose.onAllNodesWithContentDescription("照片 2").fetchSemanticsNodes().isEmpty() }
        assertEquals(3, photoCount())
    }

    @Test fun voiceChipFollowsThePlayer() {
        val file = runBlocking {
            vm.container.media.clear()
            vm.container.media.put(DemoMedia.VOICE.path, DemoMedia.sample(DemoMedia.VOICE.path)!!)
            vm.container.media.file(DemoMedia.VOICE.path)!!
        }
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), ShadowMediaPlayer.MediaInfo(3200, 0))
        compose.setContent { XlkTheme { VoiceChip(DemoMedia.VOICE, vm) } }
        fun showing(desc: String) = compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
        compose.onNodeWithText("🎙️ 0:03").performClick()
        compose.waitUntil(WAIT) { showing("暫停") }
        // 播完、被別段語音打斷：不管是誰停的，膠囊都變回可以播放
        vm.container.voice.stop()
        compose.waitUntil(WAIT) { showing("播放") }
        compose.onNodeWithContentDescription("播放").performClick()
        compose.waitUntil(WAIT) { showing("暫停") }
        compose.onNodeWithContentDescription("暫停").performClick()
        compose.waitUntil(WAIT) { showing("播放") }
        assertEquals(null, vm.container.voice.playing.value)
    }

    companion object {
        /** 其他建置同時在跑時 Robolectric 會比較慢，等久一點 */
        const val WAIT = 30_000L
    }
}
