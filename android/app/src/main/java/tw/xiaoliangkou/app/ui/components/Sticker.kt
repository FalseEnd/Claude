package tw.xiaoliangkou.app.ui.components

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.serviceLoaderEnabled
import okhttp3.OkHttpClient
import tw.xiaoliangkou.app.container
import tw.xiaoliangkou.app.data.Giphy
import tw.xiaoliangkou.app.data.giphyMediaHttp
import tw.xiaoliangkou.app.ui.theme.Cute

/**
 * 貼圖的底色：淺淺的粉紅（深色模式也一樣），透明背景的貼圖才看得清楚（黑色線條、黑字的貼圖在深色的卡片上幾乎看不見）。
 * 選貼圖的格子和選好的貼圖都用這個，選的時候看到的就是送出去的樣子
 */
internal val STICKER_CELL = Color(0xFFFCEAF0)

/** 貼圖載入的狀態 */
private enum class Phase { LOADING, READY, FAILED }

/**
 * 選好的 GIPHY 貼圖（資料庫只存 id）：每個畫面都用這一個顯示，載好了放在淺粉紅的底上（[STICKER_CELL]）。
 * id 不對或載不到時顯示插畫佔位圖，不會當掉；載入中是淡淡的圓角方塊。
 * 載好了才有 [contentDescription]（測試等的就是它，跟照片一樣）
 */
@Composable
fun StickerImage(id: String?, modifier: Modifier = Modifier, contentDescription: String = "貼圖") {
    val context = LocalContext.current
    val still = rememberAnimationsOff()
    val url = remember(id, still) { Giphy.stickerUrl(id, still) }
    var phase by remember(url) { mutableStateOf(if (url == null) Phase.FAILED else Phase.LOADING) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.clip(shape).background(if (phase == Phase.READY) STICKER_CELL else Cute.colors.peach.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = remember(url) { ImageRequest.Builder(context).data(url).build() },
                contentDescription = if (phase == Phase.READY) contentDescription else null,
                imageLoader = context.container.stickerImages,
                modifier = Modifier.fillMaxSize().padding(6.dp),
                contentScale = ContentScale.Fit,
                onState = { s ->
                    phase = when (s) {
                        is AsyncImagePainter.State.Success -> Phase.READY
                        is AsyncImagePainter.State.Error -> Phase.FAILED
                        else -> Phase.LOADING
                    }
                },
            )
        }
        if (phase == Phase.FAILED) IllustratedIcon("🖼️", Modifier.alpha(0.35f), "貼圖載入失敗")
    }
}

/** 系統把動畫關掉了（協助工具的「移除動畫」、開發人員選項）：貼圖改用不會動的那張 */
fun animationsOff(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

@Composable
fun rememberAnimationsOff(): Boolean {
    val context = LocalContext.current
    return remember(context) { animationsOff(context) }
}

/**
 * 顯示 GIPHY 貼圖的圖片載入器（放在 [tw.xiaoliangkou.app.AppContainer.stickerImages]）：
 * 只放記憶體、不存檔（GIPHY 的規定）。連線用 [giphyMediaHttp]，不會帶 Supabase 的金鑰；
 * 會動的貼圖 Coil 不放進它的記憶體快取，下載過的圖記在那裡（[tw.xiaoliangkou.app.data.GiphyMediaCache]），
 * 回到首頁、捲回來的格子不用再下載一次
 */
fun stickerImageLoader(context: Context, http: OkHttpClient = giphyMediaHttp): ImageLoader = ImageLoader.Builder(context)
    .components {
        add(OkHttpNetworkFetcherFactory(callFactory = { http }))
        // 會動的 WebP 要 Android 9 以上；舊的手機只播 GIF
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
    }
    // 上面的元件就是全部了：不要再自動加一份用預設連線的
    .serviceLoaderEnabled(false)
    .diskCache(null)
    .build()
