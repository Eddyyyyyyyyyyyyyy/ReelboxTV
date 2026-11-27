package com.reelix.app.data.source.jar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * **WebView 嗅探器** —— 把播放页 HTML 里的真实媒体地址抓出来。
 *
 * ## 为什么必须有它
 *
 * 采集站/网盘源给的大量线路是**页面地址**而不是媒体地址：
 * ```
 * https://vip.xxx-plays.com/share/<hash>   ← ArtPlayer 分享页
 * https://xxx.com/vodplay/123-1-1.html     ← 播放页，真地址在 JS 里
 * ```
 * 直接喂给 ExoPlayer 只会报 `ERROR_CODE_IO_UNSPECIFIED`。
 *
 * TVBox 的做法：开一个**看不见的 WebView** 加载页面，拦截所有网络请求，
 * 一旦出现 `.m3u8` / `.mp4` 就当作结果。
 *
 * ## 三个必须注意的点（都是踩出来的）
 *
 * ```
 * ① 必须 attach 到视图树（不可见但已 attach），否则部分页面不触发 JS
 * ② 必须 evaluateJavascript 点一下播放按钮 —— 很多页面要点击才加载视频
 * ③ 必须在主线程创建 WebView，而 await 要能阻塞在 IO 线程
 * ```
 *
 * ## 实现方式
 *
 * 用 `suspendCancellableCoroutine` 把回调式 WebView 包成 suspend 函数，
 * 内部靠三重触发结束：
 * ```
 * · 拦截到媒体请求         → 立刻返回（最快）
 * · JavaScript 探测到 src  → 返回
 * · 超时 / 页面加载完      → 返回已收集的候选
 * ```
 */
class WebViewSniffer(
    private val context: Context,
    /** 超时。太长用户等不了，太短抓不到（实测 8~12 秒够）。 */
    private val timeoutMs: Long = 12_000,
) {

    data class Result(
        val url: String?,
        val candidates: List<String>,
        val elapsedMs: Long,
        val error: String? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    private val main = Handler(Looper.getMainLooper())

    /**
     * 分阶段进度回调 —— 让 UI 能显示"正在嗅探(第几秒)"。
     *
     * 为什么需要：嗅探最长要等 12 秒，期间界面完全静止，
     * 用户会以为卡死了。
     */
    var onProgress: ((String) -> Unit)? = null
    var onCandidate: ((String) -> Unit)? = null

    /**
     * 嗅探一个页面地址。
     *
     * 调用方可以在任意线程 await —— 内部会切到主线程创建 WebView。
     */
    suspend fun sniff(pageUrl: String, headers: Map<String, String> = emptyMap()): Result = withContext(Dispatchers.Main) {
        val started = System.currentTimeMillis()

        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val candidates = Collections.synchronizedList(mutableListOf<String>())
            var webView: WebView? = null
            var holder: android.widget.FrameLayout? = null
            val mediaHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

            fun finish(reason: String) {
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    main.post { finish(reason) }
                    return
                }
                if (!done.compareAndSet(false, true)) return
                main.removeCallbacksAndMessages(null)
                val best = SniffRules.pickBest(candidates)
                val elapsed = System.currentTimeMillis() - started

                Log.i(
                    TAG,
                    "嗅探结束($reason) 耗时=${elapsed}ms 候选=${candidates.size} 选中=${best?.take(90)}"
                )

                // 清理：detach + destroy，否则 WebView 泄漏会拖垮电视
                runCatching {
                    (webView?.parent as? ViewGroup)?.removeView(webView)
                    webView?.stopLoading()
                    webView?.destroy()
                    (holder?.parent as? ViewGroup)?.removeView(holder)
                }
                webView = null
                holder = null

                if (cont.isActive) {
                    cont.resume(
                        Result(
                            url = best,
                            candidates = candidates.toList(),
                            elapsedMs = elapsed,
                            error = if (best == null) reason else null,
                            headers = best?.let { candidate ->
                                val cookies = CookieManager.getInstance().getCookie(candidate)
                                    ?.takeIf { it.isNotBlank() }?.let { mapOf("Cookie" to it) }.orEmpty()
                                val fallback = headers.filterKeys { it.equals("User-Agent", true) || it.equals("Referer", true) }
                                combinePlaybackHeaders(fallback, mediaHeaders[candidate].orEmpty(), cookies)
                            }.orEmpty(),
                        )
                    )
                }
            }

            runCatching {
                val wv = WebView(context)
                wv.isFocusable = false
                wv.isFocusableInTouchMode = false
                webView = wv

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    @SuppressLint("SetJavaScriptEnabled")
                    mediaPlaybackRequiresUserGesture = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: UA
                }

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val u = request?.url?.toString().orEmpty()
                        if (SniffRules.looksLikeMedia(u)) {
                            candidates.add(u)
                            mediaHeaders[u] = request?.requestHeaders.orEmpty()
                            onCandidate?.invoke(u)
                            Log.i(TAG, "拦截到媒体请求: " + u.take(120))
                            // m3u8 优先立刻返回；mp4 等其它格式先继续收集一会儿，
                            // 因为部分站点会先发一个低码率占位再发正片。
                            if (SniffRules.isHls(u)) {
                                finish("拦截到 m3u8")
                            }
                        }
                        return null   // 不代理内容，正常加载
                    }

                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        // 页面开始加载后，延迟探测 <video>/<source> 标签
                        // 分阶段推进：先等页面渲染，再点，再探测。
                        // 单次点击命中率有限，这里点 3 轮（间隔 1.5s），
                        // 有些站点要等广告/遮罩层消失后按钮才可点。
                        onProgress?.invoke("正在分析页面…")
                        main.postDelayed({ probeVideoTags(v ?: return@postDelayed, candidates, ::finish) }, 1000)
                        main.postDelayed({ clickPlayButton(v ?: return@postDelayed) }, 1500)
                        main.postDelayed({ probeVideoTags(v ?: return@postDelayed, candidates, ::finish) }, 2500)
                        main.postDelayed({ clickPlayButton(v ?: return@postDelayed) }, 3500)
                        main.postDelayed({ probeVideoTags(v ?: return@postDelayed, candidates, ::finish) }, 4500)
                        main.postDelayed({ clickPlayButton(v ?: return@postDelayed) }, 6000)
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        probeVideoTags(v ?: return, candidates, ::finish)
                        // 有些 SPA 加载完后才有 video 标签
                        main.postDelayed({ probeVideoTags(v ?: return@postDelayed, candidates, ::finish) }, 1500)
                    }
                }

                // ★ 必须 attach 到窗口（用 1×1 的容器），否则部分页面 JS 不跑
                // FrameLayout 是 ViewGroup 的具体实现，不能直接 new ViewGroup（抽象类）
                val container = android.widget.FrameLayout(context).apply {
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
                holder = container
                container.addView(
                    wv,
                    ViewGroup.LayoutParams(1, 1)
                )
                (context as? android.app.Activity)?.addContentView(
                    container,
                    ViewGroup.LayoutParams(1, 1)
                )
                wv.visibility = View.INVISIBLE

                wv.loadUrl(pageUrl, headers)

                // 超时兜底
                main.postDelayed({ finish("超时") }, timeoutMs)

                cont.invokeOnCancellation {
                    main.post { finish("取消") }
                }
            }.onFailure { e ->
                Log.e(TAG, "创建 WebView 失败", e)
                finish("WebView 初始化失败：${e.message}")
            }
        }
    }

    /**
     * 探测页面里的 `<video src>` / `<source src>` 以及常见播放器变量。
     *
     * 为什么不能只靠请求拦截：有些播放器**先拿到 m3u8 再决定要不要播**
     * （比如需要用户交互、或者要等解析脚本），此时不会有媒体请求发出，
     * 但地址已经写在 DOM 里了。
     */
    private fun probeVideoTags(
        wv: WebView,
        sink: MutableList<String>,
        finish: (String) -> Unit,
    ) {
        val js = """
            (function(){
              var out=[];
              try{
                document.querySelectorAll('video,source').forEach(function(e){
                  if(e.src) out.push(e.src);
                  if(e.currentSrc) out.push(e.currentSrc);
                });
                // 常见播放器全局变量
                ['videoUrl','url','src','m3u8','playUrl','videoSrc'].forEach(function(k){
                  try{ if(window[k] && typeof window[k]==='string') out.push(window[k]); }catch(e){}
                });
                // player 对象
                try{
                  if(window.player){
                    if(window.player.config && window.player.config.url) out.push(window.player.config.url);
                    if(window.player.url) out.push(window.player.url);
                  }
                }catch(e){}
              }catch(e){}
              return out.join('\n');
            })()
        """.trimIndent()

        wv.evaluateJavascript(js) { value ->
            // 返回值是 JSON 字符串，形如 "url1\nurl2"
            val raw = value?.trim()?.removeSurrounding("\"") ?: return@evaluateJavascript
            val found = raw.split("\\n", "\n")
                .map { it.trim().replace("\\/", "/") }
                .filter { it.startsWith("http") && SniffRules.looksLikeMedia(it) }

            if (found.isNotEmpty()) {
                sink.addAll(found)
                finish("DOM 命中")
            }
        }
    }

    /**
     * 尝试点一下页面里的播放按钮。
     *
     * 很多采集站页面是"点击才加载视频"的，不点永远拦不到媒体请求。
     * 这里用启发式选择器，不保证命中，但成本低。
     */
    /**
     * 尝试点击页面里的播放按钮。
     *
     * ## 为什么选择器要这么多
     *
     * 采集站的前端五花八门，实测常见的几类：
     * ```
     * ArtPlayer      .artplayer-container / .art-video-player
     * DPlayer        .dplayer
     * video.js       .vjs-big-play-button
     * CKPlayer       .ckplayer
     * 自定义          #play / .play-btn / .btn-play
     * 遮罩层          .poster / .cover-mask
     * ```
     * 之前只试了 5 个选择器，命中率低 —— 点不到播放按钮就永远拦不到媒体请求。
     *
     * ## 为什么按尺寸过滤
     *
     * 页面上叫 `.play` 的往往是**推荐位的小图**，点了会跳转到别的片子。
     * 所以只点**面积足够大**的元素（>= 80x60），那才是真正的播放按钮。
     */
    private fun clickPlayButton(wv: WebView) {
        val js = """
            (function(){
              try{
                var sels=[
                  '.art-video-player .art-video-player-click',
                  '.artplayer-container','.art-video-player',
                  '.vjs-big-play-button','.dplayer-play-icon','.dplayer',
                  '.ckplayer','#play','.play-btn','.btn-play','.playbutton',
                  '[class*=play][class*=btn]','[class*=btn][class*=play]',
                  '.poster','.cover-mask','.vjs-poster','video'
                ];
                for(var i=0;i<sels.length;i++){
                  var list=document.querySelectorAll(sels[i]);
                  for(var j=0;j<list.length;j++){
                    var el=list[j];
                    if(!el || !el.click) continue;
                    var r=el.getBoundingClientRect();
                    // 只点足够大的元素，避免点到推荐位小图
                    if(r.width>=80 && r.height>=60){
                      el.click();
                      return sels[i];
                    }
                  }
                }
                // 兜底：直接对 video 标签调 play()
                var v=document.querySelector('video');
                if(v){ if(v.paused) v.play(); return 'video.play'; }
              }catch(e){}
              return false;
            })()
        """.trimIndent()
        wv.evaluateJavascript(js) { r ->
            Log.d(TAG, "点击播放按钮: " + r)
        }
    }

    private companion object {
        const val TAG = "WebViewSniffer"
        const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
