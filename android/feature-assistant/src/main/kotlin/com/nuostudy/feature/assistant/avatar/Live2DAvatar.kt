package com.nuostudy.feature.assistant.avatar

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
// ⚠️ 本文件同时用到**两个同名 Color**：android.graphics.Color（给 WebView 设透明底，
//    见 setBackgroundColor(Color.TRANSPARENT)）和 Compose 的 Color（给渐变）。
//    后者必须起别名，否则两个 import 撞名编译不过；而 Brush 收的是 Compose 那个。
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nuostudy.feature.assistant.AssistantState
import com.nuostudy.feature.assistant.audio.MouthShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * 小智的 Live2D 形象 —— 用 WebView 承载 Cubism 模型，跟着说话动嘴、按会话状态换动作。
 *
 * ## 为什么是 WebView，而不是原生渲染
 * Cubism 的运行时（moc3 解析、变形、物理、贴图合成）只有官方 SDK，
 * Android 原生版是 **C++ NDK 库**，要自己写 JNI 胶水、自己管 GL 上下文与生命周期。
 * 而浏览器那套（pixi + pixi-live2d-display）是现成可用、已经在 PoC 里跑通的。
 * 代价是多一个 WebView 常驻（约 40MB 峰值）—— 在一台只跑这一个 App 的平板上可接受。
 *
 * ## 两个方向的数据流
 *  - **App → 页面**：`evaluateJavascript("l2d.setMouthShape(open, form)")` / `l2d.setState(name)`。
 *    只在「值真的变了」时发（口型约 16.7 次/秒、状态切换偶发），不是每帧。
 *  - **页面 → App**：`L2DBridge.onEvent('ready' | 'error')`。宿主据此决定
 *    用 WebView 还是退回静态头像。
 *
 * ## 资源怎么进 WebView（这一节是坑的集中地）
 * 页面、JS、模型全部打包在 APK 的 assets 里。但**不能**用 `file:///android_asset/...`：
 *  1. 现代 WebView 里 `fetch`/XHR 打 `file:` 源会被直接拒（"URL scheme must be http or https"），
 *     而 pixi 正是用 fetch 去拉 model3.json 和 moc3 的 —— 结果是**白屏**，
 *     而且控制台里只有一句 CORS 报错，看着像模型文件坏了；
 *  2. 靠 `setAllowFileAccessFromFileURLs(true)` 能绕，但那是在关掉浏览器的同源保护，
 *     且这些开关在新版本里默认关闭、随时可能失效。
 *
 * 所以这里走 [shouldInterceptRequest]：伪造一个 `https://live2d.local/` 源，
 * 把它的请求映射到 assets 目录。对页面来说它就是一次正常的 https 同源请求，
 * fetch 畅通、同源策略也还在。
 *
 * ## 与 Compose 的层叠关系（一个反直觉的点）
 * `AndroidView` 里的 WebView 是**真的 View 子节点**，它**永远画在 Compose 内容之上**。
 * 所以角色画面上**不能**再叠任何 Compose 控件（通话按钮、关闭按钮都不行）——
 * 会被 WebView 盖住。本文件的用法是：画面归 WebView，控件全放在它外面。
 */
@Composable
fun Live2DAvatar(
    state: AssistantState,
    mouthShape: StateFlow<MouthShape>,
    modifier: Modifier = Modifier,
) {
    var web: WebView? by remember { mutableStateOf(null) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Box(modifier, contentAlignment = Alignment.Center) {
        // 兜底：WebView 还没画出来、或模型起不来时露在下面。
        // 底色刻意跟页面里的 studio 渐变取同一组色，衔接处看不出切换。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        // 用 ComposeColor(0xFF…) 字面量，不走 android.graphics.Color.parseColor：
                        // 后者返回 Int，而 Brush 要的是 List<Color>，写错会报
                        // 「actual type is 'List<Int>', but 'List<Color>' was expected」。
                        listOf(ComposeColor(0xFF2E3A50), ComposeColor(0xFF12161E))
                    )
                )
        )

        if (failed) {
            Text(
                text = "形象加载失败\n先用简版头像陪你",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp),
            )
        }

        AndroidView(
            // failed 之后把 WebView 隐藏掉，不然它照样是块不透明的渐变，
            // 盖在「加载失败」那几个字上面，等于什么都没提示。
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (failed) 0f else 1f),
            factory = { ctx ->
                val bridge = Bridge(
                    onReady = { ready = true },
                    onError = { failed = true },
                )
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // 透明底：HTML 自己的 studio 渐变就是画面底色，
                    // WebView 再刷一层白底会在圆角边缘漏出来。
                    setBackgroundColor(Color.TRANSPARENT)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    settings.apply {
                        javaScriptEnabled = true          // 整个模型是靠 JS 跑的
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        // 我们走 asset 代理，不需要 file:// 通道；关掉更安全
                        allowFileAccess = false
                        allowContentAccess = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    webViewClient = AssetHostClient()
                    addJavascriptInterface(bridge, BRIDGE_NAME)
                    loadUrl(PAGE_URL)
                }
            },
            // ⚠️ 把实例交回给 Compose 的**状态**要在 update 里做，不能写在 factory 里：
            //    factory 是在组合过程中被调用的，在那里面写 mutableState 属于
            //    「组合期写状态」，会多触发一轮重组（遇到严格模式还会报警告）。
            //    update 在布局之后调用，且重复赋同一个实例不会引起重组（结构化相等）。
            update = { view -> web = view },
        )
    }

    // ⚠️ **必须销毁 WebView**。它持有 Activity Context 与整套 Chromium 渲染资源
    //    （峰值约 40MB）。面板关闭、或状态切到 AwaitingActivation（那条分支不渲染
    //    本组件）时它会从组合树移除，但对象不会自己释放 —— 反复开关面板，
    //    内存阶梯式上涨，低内存平板上表现为主进程被回收 / 卡顿。
    DisposableEffect(Unit) {
        onDispose {
            web?.apply {
                // 顺序有讲究：先摘掉桥、再停加载、最后 destroy()。
                // destroy() 之后这个实例不能再被碰，所以紧接着把 `web` 置 null ——
                // 否则 [LaunchedEffect] 里那两条推 JS 的协程会打到已销毁实例上。
                runCatching { removeJavascriptInterface(BRIDGE_NAME) }
                runCatching { stopLoading() }
                runCatching { destroy() }
            }
            web = null
        }
    }

    // ⚠️ **兜底通道**：`L2DBridge` 那条路一旦不通，JS 的 `notify('ready')` 会被它自己的
    //    try/catch 悄悄吞掉 —— App 侧一片安静，Android 也不会抛任何异常。
    //    表现极具迷惑性：形象在画面里好好地待着（待机动作是页面自己播的，不经过桥），
    //    可 App 推的口型和状态永远送不到 → 「形象出来了，说话时嘴不动」。
    //    所以除了等回调，这里再直接问页面本身：加载完成时它会把自己标成 ready
    //    （见 app.js 的 `window.l2d.ready = true`）。只在没就绪时轮询，就绪即停。
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        var tries = 0
        while (!ready && tries < 60) {      // 上限 30 秒（模型冷加载只要 1~3 秒）
            delay(500)
            tries++
            w.safeJs("!!(window.l2d&&window.l2d.ready)") { r ->
                if (r == "true") {
                    Log.d(TAG, "轮询确认模型已就绪（桥那条路没报上来）")
                    ready = true
                }
            }
        }
    }

    // 口型：每来一个新嘴形就推一次。约 16.7 次/秒（跟 TTS 帧率一致）。
    // ⚠️ `ready` 必须进 key：页面没就绪时推的值会被丢掉（window.l2d 还不存在），
    //    不进 key 就会出现「模型好了但口型一直不动」。
    LaunchedEffect(web, ready) {
        val w = web ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        Log.d(TAG, "口型通道打开，开始跟随 TTS 播放电平")
        var n = 0
        mouthShape.collect { s ->
            // 约 16.7 次/秒，全打会把 logcat 刷满 → 只报前几次和每 60 次一条
            if (n < 3 || n % 60 == 0) Log.d(TAG, "推口型 #$n open=${s.open} form=${s.form}")
            n++
            // ⚠️ 两个分量必须**在同一次调用里**推过去。分成两次 evaluateJavascript
            //    的话，JS 的 ticker 可能在两条之间插进来渲染，渲染用的是
            //    「新开合 + 旧圆扁」的组合 —— 那一帧嘴形是错的，而且因为两条
            //    是异步的，这种错帧会以不定的频率闪现。
            w.safeJs(
                "window.l2d&&l2d.setMouthShape(" +
                    "${s.open.coerceIn(0f, 1f)},${s.form.coerceIn(-1f, 1f)})"
            )
        }
    }

    // 状态 → 动作。key 里带 ready，是为了「就绪前先播了动作」也能补上。
    LaunchedEffect(web, ready, state) {
        val w = web ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val name = state.toL2dState()
        Log.d(TAG, "切状态 → $name")
        w.safeJs("window.l2d&&l2d.setState('$name')")
    }
}

/**
 * 往页面推一段 JS，并**吞掉 WebView 已销毁**这一类异常。
 *
 * 为什么需要它：面板关闭的那一瞬间，[DisposableEffect] 会 `destroy()` WebView，
 * 而上面几条 [LaunchedEffect] 的协程是**协作式**取消的 —— 恰好卡在 `delay` 之后、
 * 调用之前时，仍会对已销毁的实例发一次 `evaluateJavascript`（内部可能 NPE）。
 * 这类竞态很难复现，但代价只有一层 try/catch。
 */
private fun WebView.safeJs(script: String, cb: ValueCallback<String>? = null) {
    runCatching { evaluateJavascript(script, cb) }
}

/**
 * 会话状态 → 页面里注册的动作档位（见 dist/live2d-poc/app.js 的 MODELS[..].states）。
 *
 * 日和没有表情文件，所以「情绪」全靠**动作组**表达：
 *   Idle[0] 待机 / Idle[1] 侧头听 / Idle[2] 说话时的小幅摆动 /
 *   FlickUp 抬头（高兴）/ Flick 甩头（不高兴）/ FlickDown 垂头（思考）
 */
private fun AssistantState.toL2dState(): String = when (this) {
    is AssistantState.Idle, is AssistantState.Ready -> "idle"
    is AssistantState.Listening -> "listen"
    is AssistantState.Thinking -> "think"
    is AssistantState.Speaking -> "speak"
    // 告别中她真的在说话（口型也照常动），动作档位跟 Speaking 一样 —— 只有按钮的
    // 视觉不同（见 TalkButton）。动作组不区分这两者，也不需要区分。
    is AssistantState.Farewell -> "speak"
    is AssistantState.Connecting, is AssistantState.AwaitingActivation -> "idle"
    is AssistantState.Error -> "sad"
}

/** 页面 → App 的回调口。名字必须与 JS 里的 `window.L2DBridge` 一致。 */
private const val BRIDGE_NAME = "L2DBridge"

/**
 * 日志 tag。
 *
 * 这个组件出问题几乎全是**静默失败**（桥不通、WebView 源被拒、模型 fetch 失败都不会崩），
 * 日志是唯一的抓手。所以 ready/error/状态切换这几个关键节点刻意保留 Log。
 */
private const val TAG = "Live2DAvatar"

/**
 * 伪源域名。不需要真实存在 —— [AssetHostClient] 会把它的每个请求都截下来。
 *
 * 用 `https` 而不是 `http`，是为了让页面处在**安全上下文**里：
 * 浏览器对 http 源有一堆额外限制（"insecure origin"），而且哪天项目把
 * `cleartextTrafficPermitted` 收紧回 false，http 就会当场失效。
 * 这里走 https 则与那个开关完全无关（请求根本不出设备）。
 */
private const val ASSET_HOST = "live2d.local"

/** assets 下页面所在的目录 */
private const val ASSET_DIR = "live2d"

/**
 * 页面 URL。
 * - `fit=panel`：按「人物总高的 span 比例」取景，取到的身体与容器**高度**绑定，
 *   容器尺寸变了还是同一截身体
 * - `span=0.62`：**取景到裙摆**（人物总高的 62%）。刻意显式写死，不吃 app.js 的默认值：
 *   默认是 0.38 = 只到肩膀/胸口，而日和的手在**总高约 47%** 处（裙摆高度）——
 *   0.38 < 0.47，**手天生在取景框下面**，所以面板里一直只看得见一颗头。
 *   0.62 是照面板真实尺寸 280×408dp 实拍对比选的（.debug-shots/l2d/panel_span_*.png）：
 *   手进画了，人物仍占画面宽约 75%，再放大人就明显变小。
 * - `bg=studio`：页面自带深蓝渐变底（比透出面板色更好看，也保证任何主题下都够对比）
 * - `mock=0`：**关掉内置的假口型包络** —— 真实口型由 App 推 RMS，两个来源并存会打架
 * - `hud=0`：不要调试面板（帧率照常每 5 秒打进 logcat，见 app.js）
 *
 * 画质/性能开关（`?res=` `?aa=` `?phys=`）**刻意不加在这里**：平板实测三者
 * 对帧率都没有可测影响（见 app.js 的 PHYS_EVERY 注释），加了只会白降画质。
 */
private const val PAGE_URL =
    "https://$ASSET_HOST/index.html?model=hiyori_pro&fit=panel&span=0.62&hud=0&bg=studio&mock=0"

/**
 * JS ↔ Kotlin 的桥。
 *
 * ⚠️⚠️ **这个类必须是 public，不能像同文件其它辅助类那样标 private**。
 *    WebView 注入的 Java 对象是被 `android.webkit` **跨包反射调用**的，
 *    package-private 的类会让 JS 侧的调用**静默失败** —— JS 那边包了 try/catch，
 *    控制台一片安静，Android 侧也没有任何异常。
 *    表现极具迷惑性：模型照常加载、页面自己的待机动作/眨眼照常跑（那些不经过桥），
 *    但 App 推的**口型和状态永远送不到** → 「形象出来了，可说话时嘴不动」。
 *    （踩过一次，就是栽在这里。）
 *
 * ⚠️ 另外两点：
 *  1. 被 JS 调到的方法**必须**标 [JavascriptInterface]，否则（R8 混淆后尤其）
 *     在 JS 侧会静默变成 undefined；
 *  2. 这些方法的调用线程**不是主线程**（WebView 自己的 JavaBridge 线程），
 *     所以任何要在 Compose 里改状态的写操作都得 post 回主线程 —— 见 [main]。
 */
class Bridge(
    private val onReady: () -> Unit,
    private val onError: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onEvent(name: String) {
        // 这行日志刻意留着：桥一旦不通是全静默的，没有它就没法自查
        Log.d(TAG, "bridge event: $name")
        when (name) {
            "ready" -> main.post { onReady() }
            "error" -> main.post { onError() }
            else -> Unit   // state:xxx 之类的诊断事件，App 侧不消费
        }
    }
}

/**
 * 把 `https://live2d.local/` 下的所有路径映射到 `assets/live2d/` 下的同名文件。
 *
 * ⚠️ 这段注释本身踩过一次坑，写文档时注意：原来写的是 `https://live2d.local/` 后面接两个
 *    星号当通配符，看着无害，但 Kotlin 的**块注释可以嵌套** —— 斜杠紧跟星号会开一个内层注释，
 *    于是这个 KDoc 永远闭合不了，把下面整个 [AssetHostClient] 类都吞进了注释里。
 *    症状极具迷惑性：报错在**文件末尾**的 `Syntax error: Unclosed comment`，
 *    外加一句 `Unresolved reference 'AssetHostClient'`（其实类就在同一文件里）。
 *    规矩：注释和文档里永远别让斜杠贴着星号。
 *
 * 这是 WebView 里读本地资源的**唯一**干净做法（理由见 [Live2DAvatar] 的注释）。
 *
 * ⚠️ 这个回调在**后台 IO 线程**上被调用，而且会被并发调用（贴图、moc3、json 同时来），
 *    所以实现里不能有共享可变状态。每次都是现开一个 InputStream，交给 WebView 去关。
 */
private class AssetHostClient : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url
        if (url.host != ASSET_HOST) return null

        val rel = (url.path ?: return notFound()).trimStart('/')
        // 路径穿越防护：assets.open 本身不允许 ".."，但显式挡掉更清楚
        if (rel.isEmpty() || rel.contains("..")) return notFound()

        val assetPath = "$ASSET_DIR/$rel"
        return try {
            WebResourceResponse(mimeOf(rel), encodingOf(rel), view.context.assets.open(assetPath))
        } catch (e: Exception) {
            notFound()
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null)

    /**
     * MIME 必须给对：
     *  - `.moc3` 不是标准类型，给 `application/octet-stream` 走 XHR 没问题；
     *  - `.webp` 要给 `image/webp`，否则 Chromium 可能按文本处理、贴图直接不显示；
     *  - 其余按常规。给错 MIME 的症状是「模型加载了但一片空白」，很难往这上面想。
     */
    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "webp" -> "image/webp"
        "png" -> "image/png"
        "moc3" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    /** 二进制资源不能声明字符集，否则 Chromium 会按文本解码把它毁了。 */
    private fun encodingOf(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm", "js", "mjs", "css", "json" -> "utf-8"
        else -> null
    }
}
