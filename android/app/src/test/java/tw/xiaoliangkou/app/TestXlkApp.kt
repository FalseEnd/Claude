package tw.xiaoliangkou.app

/**
 * Robolectric 測試用的 App：同一個套件、叫做 Test + XlkApp，Robolectric 會自動拿它取代 AndroidManifest 裡的 XlkApp。
 * GIPHY 的圖片換成假的（[FakeStickers]）、天氣換成假的（[FakeWeather]）：畫面測試不會連網路，每次畫出來都一樣
 */
class TestXlkApp : XlkApp() {
    val stickers by lazy { FakeStickers(this) }
    val weather = FakeWeather()

    override fun onCreate() {
        super.onCreate()
        container.stickerImages = stickers.loader
        container.weather = weather
    }
}
