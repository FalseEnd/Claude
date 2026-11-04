package tw.xiaoliangkou.app

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.net.Uri
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaRecorder
import org.robolectric.shadows.ShadowSystemClock
import tw.xiaoliangkou.app.data.AppException
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DemoMedia
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.Limits
import tw.xiaoliangkou.app.data.MediaItem
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.components.MediaThumbs
import tw.xiaoliangkou.app.ui.screens.DayEntrySheet
import tw.xiaoliangkou.app.ui.screens.MemoSheet
import tw.xiaoliangkou.app.ui.screens.MemosScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import tw.xiaoliangkou.app.widget.MemoWidget
import java.io.File
import java.time.Duration
import java.time.LocalDate

/**
 * 錄音傳不上去時留著可以重新上傳、麥克風被拒絕到不再詢問時帶去設定、跑到背景時先停下錄音、
 * 叉叉的按鈕夠大、備忘錄面板上傳了沒存的會刪掉、資料庫還沒更新不能新增備忘錄、桌面上沒有備忘錄小工具時可以直接加。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests '*ReviewFix3ScreensTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1300dp-xhdpi", shadows = [ShadowWritingMediaRecorder::class])
class ReviewFix3ScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app get() = RuntimeEnvironment.getApplication()
    private val vm by lazy { AppViewModel(app) }
    private lateinit var demo: DemoRepository
    private val today = LocalDate.now()
    /** 打開時上傳都會失敗（像是在捷運上、電梯裡） */
    @Volatile private var offline = false
    /** 試著上傳了幾次 */
    @Volatile private var attempts = 0

    @Before fun setUp() {
        vm.container.startDemo()
        demo = vm.repo.value as DemoRepository
        demo.running = false
        val flaky = object : CoupleRepository by demo {
            override suspend fun uploadMedia(bytes: ByteArray, mime: String, kind: String, shared: Boolean, width: Int?, height: Int?, durationMs: Long?): MediaItem {
                attempts++
                if (offline) throw AppException("連不到 Supabase，請確認網路", "network")
                return demo.uploadMedia(bytes, mime, kind, shared, width, height, durationMs)
            }
        }
        @Suppress("UNCHECKED_CAST")
        val repo = vm.container.javaClass.getDeclaredField("_repo").apply { isAccessible = true }.get(vm.container)
            as kotlinx.coroutines.flow.MutableStateFlow<CoupleRepository?>
        repo.value = flaky
        voiceDir().deleteRecursively()
    }

    @After fun tearDown() = runBlocking { vm.container.media.clear() }

    private fun voiceDir() = File(app.cacheDir, "voice")
    private fun voiceFiles() = voiceDir().listFiles().orEmpty().toList()

    private fun waitFor(cond: () -> Boolean) = compose.waitUntil(10_000, cond)
    private fun has(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    /** 面板：DayEntrySheet（照片、語音都會上傳） */
    private fun daySheet(show: () -> Boolean = { true }, owner: LifecycleOwner? = null, registry: ActivityResultRegistryOwner? = null, view: (View) -> Unit = {}) {
        compose.setContent {
            XlkTheme {
                val content: @Composable () -> Unit = {
                    view(LocalView.current)
                    val s by vm.state.collectAsState()
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                            if (show()) DayEntrySheet(s, vm, null, today) {}
                        }
                    }
                }
                when {
                    owner != null -> CompositionLocalProvider(LocalLifecycleOwner provides owner) { content() }
                    registry != null -> CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) { content() }
                    else -> content()
                }
            }
        }
    }

    /** 按錄音、錄 [seconds] 秒，回傳錄音檔 */
    private fun record(seconds: Long = 2): File {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        compose.onNodeWithText("錄音").performClick()
        waitFor { has("⏹ 停止", substring = true) }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(seconds))
        return voiceFiles().single()
    }

    /** 紀錄裡加進一個照片或語音了（面板上會顯示「1 / 9」） */
    private fun oneAdded() = has("1 / ${Limits.MEDIA}")

    private fun stop() {
        compose.onNodeWithText("⏹ 停止", substring = true).performClick()
        waitFor { !vm.busy && !has("⏹ 停止", substring = true) }
        compose.waitForIdle()
    }

    // ------------------------------------------------------------ 錄音傳不上去

    /** 傳不上去：錄音留在手機裡，可以重新上傳；傳好了才刪掉 */
    @Test fun failedVoiceUploadCanBeRetried() {
        daySheet()
        val f = record()
        offline = true
        stop()
        waitFor { has("重新上傳") }
        assertTrue(f.exists())
        compose.onNodeWithText("錄音還沒傳上去").assertIsNotEnabled()
        compose.onNodeWithText("錄音").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/screens/70_voice_unsent.png")

        // 網路好了：重新上傳，加進這則紀錄、手機裡的檔案刪掉
        offline = false
        compose.onNodeWithText("重新上傳").performClick()
        waitFor { oneAdded() }
        waitFor { !f.exists() }
        assertFalse(has("重新上傳"))
        compose.onNodeWithText("儲存").assertIsEnabled()
    }

    /** 又失敗一次還是留著；按「丟掉這段」才刪 */
    @Test fun unsentVoiceCanBeDropped() {
        daySheet()
        val f = record()
        offline = true
        stop()
        waitFor { has("重新上傳") }
        compose.onNodeWithText("重新上傳").performClick()
        waitFor { attempts == 2 && !vm.busy && has("重新上傳") }
        assertTrue(f.exists())
        compose.onNodeWithText("丟掉這段").performClick()
        waitFor { !f.exists() }
        assertFalse(has("重新上傳"))
        assertTrue(has("儲存"))
    }

    /** 沒傳上去就把面板關掉（已經問過要不要放棄）：手機裡的檔案也刪掉 */
    @Test fun unsentVoiceIsDeletedWithTheSheet() {
        var show by mutableStateOf(true)
        daySheet({ show })
        val f = record()
        offline = true
        stop()
        waitFor { has("重新上傳") }
        show = false
        compose.waitForIdle()
        waitFor { !f.exists() }
    }

    // ------------------------------------------------------------ 跑到背景

    /** 按主畫面鍵（ON_STOP）：先停下來把錄到的上傳；錄音中螢幕不會自己關掉 */
    @Test fun leavingTheAppStopsAndKeepsTheRecording() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        var view: View? = null
        daySheet(owner = owner, view = { view = it })
        assertFalse(view!!.keepScreenOn)
        val f = record()
        compose.waitForIdle()
        assertTrue(view!!.keepScreenOn)
        owner.registry.currentState = Lifecycle.State.CREATED
        waitFor { !vm.busy && oneAdded() }
        assertFalse(has("⏹ 停止", substring = true))
        assertFalse(view!!.keepScreenOn)
        waitFor { !f.exists() }
    }

    // ------------------------------------------------------------ 麥克風權限

    /** 系統的權限詢問直接回「拒絕」 */
    private fun denyMic() = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, false as O)
            }
        }
    }

    /** 拒絕到系統不再詢問：問要不要去設定，按了打開這個 App 的設定頁 */
    @Test fun permanentlyDeniedMicOpensSettings() {
        daySheet(registry = denyMic())
        compose.onNodeWithText("錄音").performClick()
        waitFor { has("要打開麥克風權限") }
        compose.onRoot().captureRoboImage("build/screens/71_mic_blocked.png")
        compose.onNodeWithText("去設定").performClick()
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals(Uri.fromParts("package", app.packageName, null), started.data)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(has("要打開麥克風權限"))
    }

    /** 第一次拒絕（系統還會再問）：只提醒，不跳去設定 */
    @Test fun firstDenialOnlyReminds() {
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO, true)
        daySheet(registry = denyMic())
        compose.onNodeWithText("錄音").performClick()
        compose.waitForIdle()
        assertFalse(has("要打開麥克風權限"))
    }

    // ------------------------------------------------------------ 叉叉

    /** 移除的按鈕至少 40dp，按了是移除、不會打開大圖 */
    @Test fun removeDotIsBigEnough() {
        val photos = listOf(DemoMedia.PICNIC, DemoMedia.SUNSET)
        runBlocking { photos.forEach { vm.container.media.put(it.path, DemoMedia.sample(it.path)!!) } }
        var media by mutableStateOf(photos + DemoMedia.VOICE)
        compose.setContent { XlkTheme { Column(Modifier.padding(20.dp)) { MediaThumbs(media, vm, size = 76.dp) { m -> media = media - m } } } }
        val dots = compose.onAllNodesWithContentDescription("移除")
        assertEquals(3, dots.fetchSemanticsNodes().size)
        val density = compose.density.density
        dots.fetchSemanticsNodes().forEach { n ->
            assertTrue("${n.size}", n.size.width / density >= 40f - 0.5f)
            assertTrue("${n.size}", n.size.height / density >= 40f - 0.5f)
        }
        dots[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf(DemoMedia.SUNSET, DemoMedia.VOICE), media)
        // 沒有打開看大圖
        assertTrue(compose.onAllNodesWithContentDescription("關閉").fetchSemanticsNodes().isEmpty())
    }

    // ------------------------------------------------------------ 備忘錄面板上傳的檔案

    /** 選照片：不開系統的相簿，直接回傳這張圖 */
    private fun photoPicker(): ActivityResultRegistryOwner {
        val file = File(app.cacheDir, "pick-${System.nanoTime()}.png")
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(255, 182, 193)) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = Uri.fromFile(file)
        return object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, (if (contract is ActivityResultContracts.PickMultipleVisualMedia) listOf(uri) else uri) as O)
                }
            }
        }
    }

    /** 示範模式上傳的檔案都在記憶體裡 */
    @Suppress("UNCHECKED_CAST")
    private fun demoFiles(): Set<String> =
        (DemoRepository::class.java.getDeclaredField("files").apply { isAccessible = true }.get(demo) as Map<String, ByteArray>).keys.toSet()

    /** 打開新增備忘錄、上傳一張照片，回傳它（還沒存） */
    private fun uploadInMemoSheet(show: () -> Boolean, onClose: () -> Unit): String {
        val picker = photoPicker()
        compose.setContent {
            XlkTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    val s by vm.state.collectAsState()
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) { if (show()) MemoSheet(s, vm, null, onClose) }
                }
            }
        }
        val before = demoFiles()
        compose.onNodeWithText("照片").performScrollTo().performClick()
        waitFor { (demoFiles() - before).isNotEmpty() }
        waitFor { !vm.busy }
        return (demoFiles() - before).single()
    }

    /** 新增備忘錄、放了照片沒存就關掉：照片從 Storage 刪掉 */
    @Test fun unsavedMemoUploadsAreDiscarded() {
        var show by mutableStateOf(true)
        val path = uploadInMemoSheet({ show }) {}
        show = false
        compose.waitForIdle()
        waitFor { path !in demoFiles() }
    }

    /** 放了照片又移除、換一張再存：移除的那張刪掉，存起來的留著 */
    @Test fun removedMemoUploadIsDiscardedAfterSaving() {
        var show by mutableStateOf(true)
        val first = uploadInMemoSheet({ show }) { show = false }
        compose.onAllNodesWithContentDescription("移除")[0].performClick()
        val before = demoFiles()
        compose.onNodeWithText("照片").performScrollTo().performClick()
        waitFor { (demoFiles() - before).isNotEmpty() }
        waitFor { !vm.busy }
        val second = (demoFiles() - before).single()
        compose.onNodeWithText("儲存").performScrollTo().performClick()
        waitFor { !show }
        compose.waitForIdle()
        waitFor { first !in demoFiles() }
        assertTrue(second in demoFiles())
        assertTrue(vm.state.value.memos.any { m -> m.media.map { it.path } == listOf(second) })
    }

    // ------------------------------------------------------------ 備忘錄列表

    /** 資料庫還沒更新：不能新增備忘錄（跟日曆、備註一樣） */
    @Test fun cannotAddMemosOnAnOldDatabase() {
        compose.setContent { XlkTheme { MemosScreen(demo.state.value.copy(memos = emptyList(), schemaOutdated = true), vm, {}) {} } }
        compose.onNodeWithText("新增備忘錄").assertIsNotEnabled()
        compose.onNodeWithText("新增第一則備忘錄").assertIsNotEnabled()
        // 備忘錄還不能用：先不用提要加小工具
        assertFalse(has("把備忘錄小工具加到桌面"))
    }

    /** 桌面上還沒有備忘錄小工具：列表下面可以直接加 */
    @Test fun offersToAddTheMemoWidget() {
        compose.setContent {
            XlkTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { MemosScreen(demo.state.value, vm, {}) {} }
            }
        }
        compose.mainClock.advanceTimeBy(2500)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("把備忘錄小工具加到桌面"))
        compose.onRoot().captureRoboImage("build/screens/72_memos_add_widget.png")
        compose.onNodeWithText("把備忘錄小工具加到桌面").assertIsEnabled()
        compose.onNodeWithText("桌面上還沒有這個小工具", substring = true).assertExists()
    }

    /** 面板裡：打開「放在桌面」，桌面上又還沒有備忘錄小工具，才提醒可以加 */
    @Test fun memoSheetOffersTheWidgetWhenPinned() {
        compose.setContent {
            XlkTheme {
                val s by demo.state.collectAsState()
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) { MemoSheet(s, vm, null) {} }
                }
            }
        }
        assertFalse(has("把備忘錄小工具加到桌面"))
        compose.onNodeWithText("放在桌面小工具").performScrollTo().performClick()
        compose.onNodeWithText("把備忘錄小工具加到桌面").performScrollTo().assertIsEnabled()
    }

    /** 桌面上已經有備忘錄小工具：不用再提 */
    @Test fun noOfferWhenTheWidgetIsPlaced() {
        shadowOf(AppWidgetManager.getInstance(app)).bindAppWidgetId(42, ComponentName(app, MemoWidget::class.java))
        assertTrue(MemoWidget.isPlaced(app))
        compose.setContent { XlkTheme { MemosScreen(demo.state.value, vm, {}) {} } }
        compose.waitForIdle()
        assertFalse(has("把備忘錄小工具加到桌面"))
    }
}

/** Robolectric 的錄音機不會寫檔：開始錄音時寫一點內容進去，停止時才不會被當成「太短」 */
@Implements(MediaRecorder::class)
class ShadowWritingMediaRecorder : ShadowMediaRecorder() {
    @Implementation
    override fun start() {
        super.start()
        File(outputPath).writeBytes(ByteArray(4096) { 7 })
    }
}
