package tw.xiaoliangkou.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import coil3.ColorImage
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.request.SuccessResult
import coil3.test.FakeImageLoaderEngine
import java.util.Collections
import kotlin.random.Random

/**
 * 假的 GIPHY 圖片：giphy.com 的網址都畫一張小熊臉（透明背景，顏色、位置由網址裡的 id 決定，同一個 id 一定一樣），
 * 不連網路。[requests] 記下要過的網址，測試看要的是 WebP、GIF 還是不會動的那張
 */
class FakeStickers(context: Context) {
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val loader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(
                FakeImageLoaderEngine.Builder()
                    .intercept({ it is String && "giphy.com/" in it }) { chain ->
                        val url = chain.request.data as String
                        requests += url
                        SuccessResult(bitmap(idOf(url)).asImage(), chain.request, DataSource.MEMORY)
                    }
                    .default(ColorImage(0xFFFFC2D6.toInt()))
                    .build(),
            )
        }
        .build()

    companion object {
        private val ID = Regex("""/media/(?:v1\.[^/]+/)?([A-Za-z0-9]+)/""")

        /** 網址裡的貼圖 id（https://media.giphy.com/media/<id>/200w.webp，API 回傳的還會多一段 v1.…） */
        fun idOf(url: String) = ID.find(url)?.groupValues?.get(1) ?: url

        /** 同一個 id 一定畫出一樣的圖 */
        fun bitmap(id: String, size: Int = 200): Bitmap {
            val rnd = Random(id.hashCode())
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val s = size.toFloat()
            val cx = s * (0.4f + rnd.nextFloat() * 0.2f)
            val cy = s * (0.45f + rnd.nextFloat() * 0.15f)
            val r = s * (0.24f + rnd.nextFloat() * 0.08f)
            p.color = Color.rgb(rnd.nextInt(150, 256), rnd.nextInt(90, 200), rnd.nextInt(120, 230))
            c.drawCircle(cx - r * 0.8f, cy - r * 0.8f, r * 0.38f, p)
            c.drawCircle(cx + r * 0.8f, cy - r * 0.8f, r * 0.38f, p)
            c.drawCircle(cx, cy, r, p)
            p.color = Color.rgb(60, 40, 50)
            c.drawCircle(cx - r * 0.38f, cy - r * 0.1f, r * 0.11f, p)
            c.drawCircle(cx + r * 0.38f, cy - r * 0.1f, r * 0.11f, p)
            p.color = Color.WHITE
            c.drawCircle(cx, cy + r * 0.35f, r * 0.2f, p)
            return bmp
        }
    }
}
