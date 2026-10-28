package tw.xiaoliangkou.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import tw.xiaoliangkou.app.core.Pet
import tw.xiaoliangkou.app.data.AppState
import tw.xiaoliangkou.app.data.CoupleRepository
import tw.xiaoliangkou.app.data.DemoRepository
import tw.xiaoliangkou.app.data.PetDto
import tw.xiaoliangkou.app.data.PetEvent
import tw.xiaoliangkou.app.data.PetResponse
import tw.xiaoliangkou.app.data.PetToday
import tw.xiaoliangkou.app.data.RemoteRepository
import tw.xiaoliangkou.app.ui.AppViewModel
import tw.xiaoliangkou.app.ui.components.CatFace
import tw.xiaoliangkou.app.ui.components.PetCat
import tw.xiaoliangkou.app.ui.screens.HomePetCard
import tw.xiaoliangkou.app.ui.screens.LocalPetClock
import tw.xiaoliangkou.app.ui.screens.PetScreen
import tw.xiaoliangkou.app.ui.theme.XlkTheme
import java.time.Instant

/**
 * 一起養的小貓：毛色、長大、表情、照顧的樣子，首頁的卡片、牠的房間（白天、晚上）、領養。
 * 截圖：./gradlew testDebugUnitTest -Proborazzi.test.record=true --tests 'tw.xiaoliangkou.app.PetScreensTest'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp-xhdpi")
class PetScreensTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob())
    private val demo = DemoRepository(scope).also { it.running = false }
    private val vm by lazy { AppViewModel(RuntimeEnvironment.getApplication()) }

    /** 台北下午 3 點、晚上 11 點半 */
    private val afternoon = Instant.parse("2026-10-28T07:00:00Z")
    private val night = Instant.parse("2026-10-28T15:30:00Z")

    @After fun tearDown() = scope.cancel()

    private fun pet(at: Instant, fullness: Double = 66.0, happiness: Double = 72.0, cleanliness: Double = 61.0, xp: Int = 168) = PetDto(
        name = "麻糬", coat = "orange", fullness = fullness, happiness = happiness, cleanliness = cleanliness, xp = xp, streak = 12,
        lastFedOn = Pet.localDay(at).toString(), adoptedBy = DemoRepository.PARTNER, adoptedAt = at.minusSeconds(11 * 86400L).toString(),
        updatedAt = at.toString(),
    )

    private fun events(at: Instant) = listOf(
        PetEvent(5, DemoRepository.ME, "pet", null, 2, at.minusSeconds(600).toString()),
        PetEvent(4, DemoRepository.ME, "pet", null, 2, at.minusSeconds(660).toString()),
        PetEvent(3, DemoRepository.PARTNER, "feed", "can", 12, at.minusSeconds(7200).toString()),
        PetEvent(2, DemoRepository.ME, "grow", "幼貓", 0, at.minusSeconds(3 * 86400L).toString()),
        PetEvent(1, DemoRepository.PARTNER, "adopt", "orange", 0, at.minusSeconds(11 * 86400L).toString()),
    )

    private fun state(at: Instant = afternoon, p: PetDto? = pet(at), today: PetToday = PetToday(feeds = 1, pets = 2)): AppState =
        demo.state.value.copy(pet = PetResponse(p, today, if (p == null) emptyList() else events(at)))

    private fun show(at: Instant, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalPetClock provides { at }) {
                XlkTheme { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } }
            }
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()
    }

    private fun capture(name: String, large: Boolean = false) =
        compose.onRoot().captureRoboImage(if (large) "build/screens-large/$name.png" else "build/screens/$name.png")

    private fun has(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun idleUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!cond() && System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); compose.mainClock.advanceTimeBy(50); Thread.sleep(10) }
        assertTrue(cond())
    }

    private fun setRepo(repo: CoupleRepository) {
        @Suppress("UNCHECKED_CAST")
        val field = vm.container.javaClass.getDeclaredField("_repo").apply { isAccessible = true }.get(vm.container) as MutableStateFlow<CoupleRepository?>
        field.value = repo
    }

    /** 六種毛色、五個階段、每種表情和照顧的樣子 */
    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun catGallery() {
        show(afternoon) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("毛色（小奶貓／大胖貓）")
                Row { Pet.coats.forEach { c -> PetCat(c.key, 0, CatFace.OK, Modifier.weight(1f).height(80.dp)) } }
                Row { Pet.coats.forEach { c -> PetCat(c.key, 4, CatFace.HAPPY, Modifier.weight(1f).height(80.dp)) } }
                Text("長大")
                Row { (0..4).forEach { st -> PetCat("gray", st, CatFace.OK, Modifier.weight(1f).height(110.dp)) } }
                Text("表情")
                Row { listOf(CatFace.OK, CatFace.HAPPY, CatFace.HUNGRY, CatFace.SAD, CatFace.DIRTY).forEach { f -> PetCat("orange", 2, f, Modifier.weight(1f).height(110.dp)) } }
                Row { listOf(CatFace.SLEEPING, CatFace.EATING, CatFace.PURRING, CatFace.PLAYING).forEach { f -> PetCat("black", 2, f, Modifier.weight(1f).height(110.dp)) } }
                Text("照顧")
                Row { listOf("kibble", "can", "fish", "wand").forEach { p -> PetCat("calico", 1, CatFace.EATING, Modifier.weight(1f).height(120.dp), prop = p) } }
                Row { listOf("laser", "yarn", "bath", "brush").forEach { p -> PetCat("tuxedo", 3, CatFace.PLAYING, Modifier.weight(1f).height(120.dp), prop = p) } }
                Row { PetCat("white", 3, CatFace.OK, Modifier.size(200.dp)); PetCat("calico", 4, CatFace.PURRING, Modifier.size(200.dp)) }
            }
        }
        capture("100_pet_gallery")
    }

    @Test fun petRoomInTheAfternoon() {
        show(afternoon) { PetScreen(state(), vm, back = {}, open = {}) }
        assertTrue(has("麻糬"))
        assertTrue(has("幼貓 🐾"))
        assertTrue(has("🔥 連續照顧 12 天"))
        assertTrue("小雨領養的第 12 天", has("橘貓 · 小雨 領養的 · 第 12 天"))
        assertTrue(has("再 232 長成少年貓 🎀"))
        assertTrue(has("今天的任務"))
        assertTrue(has("2/3"))
        assertTrue("連續的摸摸合成一行", has("阿哲 摸摸牠 🤚 ×2"))
        assertTrue(has("小雨 餵了罐罐 🥫"))
        assertTrue(has("長大成幼貓了！🎉"))
        assertTrue(has("今天也很乖 😺"))
        capture("101_pet_room")
    }

    @Test fun petRoomAtNightAndHungry() {
        // 晚上肚子餓了：餓了半夜也會叫
        show(night) { PetScreen(state(night, pet(night, fullness = 12.0, happiness = 20.0, cleanliness = 18.0), PetToday()), vm, back = {}, open = {}) }
        assertTrue(has("肚子好餓… 🥺"))
        capture("102_pet_room_night")
    }

    @Test fun sleepingAtNight() {
        show(night) { PetScreen(state(night), vm, back = {}, open = {}) }
        assertTrue(has("呼嚕呼嚕…睡著了 💤"))
    }

    @Test fun feedingTheCat() {
        setRepo(demo)
        val before = demo.state.value.pet!!
        show(afternoon) { PetScreen(demo.state.collectAsStateValue(), vm, back = {}, open = {}) }
        compose.onNodeWithText("餵食").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertTrue(has("飽足 +45 · 開心 +8"))
        assertTrue(has("今天還能給 3 次"))
        compose.onNodeWithText("罐罐").performClick()
        idleUntil { demo.state.value.pet!!.today.feeds == before.today.feeds + 1 }
        assertEquals("can", demo.state.value.pet!!.events.first().item)
        assertEquals(DemoRepository.ME, demo.state.value.pet!!.events.first().userId)
    }

    @Test fun pettingFromTheHomeCard() {
        setRepo(demo)
        val pets = demo.state.value.pet!!.today.pets
        show(afternoon) { HomePetCard(demo.state.collectAsStateValue(), vm, open = {}) }
        compose.onNodeWithContentDescription("摸摸麻糬").performClick()
        idleUntil { demo.state.value.pet!!.today.pets == pets + 1 }
    }

    @Test fun homeCard() {
        show(afternoon) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HomePetCard(state(), vm, open = {})
                HomePetCard(state(p = pet(afternoon, fullness = 20.0), today = PetToday()), vm, open = {})
                HomePetCard(state(p = null), vm, open = {})
                HomePetCard(state().copy(dbSchema = RemoteRepository.PET_SCHEMA - 1), vm, open = {})
            }
        }
        assertTrue(has("今天 ✅ 餵飯 ⬜ 陪玩 ⬜ 摸摸 2/3"))
        assertTrue(has("今天還沒有人餵麻糬"))
        assertTrue(has("🍚 去餵牠"))
        assertTrue(has("領養一隻小貓吧 🐾"))
        assertTrue(has("再更新一次資料庫就能養了"))
        capture("103_pet_home")
    }

    @Test fun nothingBeforePairing() {
        show(afternoon) { HomePetCard(state().copy(couple = null, partner = null), vm, open = {}) }
        assertFalse(has("麻糬"))
    }

    @Test fun adopting() {
        setRepo(demo)
        demo.demoAction("petReset")
        idleUntil { demo.state.value.pet?.pet == null }
        show(afternoon) { PetScreen(demo.state.collectAsStateValue(), vm, back = {}, open = {}) }
        assertTrue(has("領養一隻小貓 🐾"))
        capture("104_pet_adopt")
        compose.onNodeWithText("黑貓").performClick()
        compose.onNodeWithText("幫牠取名字").performTextInput("煤球")
        compose.onNodeWithText("🐾 領養黑貓").performClick()
        idleUntil { demo.state.value.pet?.pet != null }
        val p = demo.state.value.pet!!.pet!!
        assertEquals("煤球", p.name)
        assertEquals("black", p.coat)
        assertEquals(0, p.xp)
        assertEquals("adopt", demo.state.value.pet!!.events.single().action)
    }

    @Test fun outdatedDatabase() {
        show(afternoon) { PetScreen(state().copy(dbSchema = 6), vm, back = {}, open = {}) }
        assertTrue(has("資料庫還沒有小貓"))
        assertNull(compose.onAllNodesWithText("麻糬").fetchSemanticsNodes().firstOrNull())
    }

    @Config(qualifiers = "zh-rTW-w360dp-h2000dp-xhdpi", fontScale = 1.3f)
    @Test fun largeText() {
        show(afternoon) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.padding(16.dp)) { HomePetCard(state(), vm, open = {}) }
                PetScreen(state(), vm, back = {}, open = {})
            }
        }
        compose.onNodeWithText("餵食").performClick()
        compose.mainClock.advanceTimeBy(500)
        capture("pet", large = true)
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateValue(): T = collectAsState().value
