package com.reelix.app.data.pan

/**
 * 识别「网盘配置类」条目。
 *
 * ## 这批条目是什么
 *
 * `饭太硬` 这类源的「配置中心」站点，分类里的每一项都不是影视内容，
 * 而是**网盘授权入口**：
 * ```
 * vod_id          vod_remarks
 * ─────────────────────────────────────────
 * quarkcookie     点击设置cookie      ← 夸克
 * ucpancookie     svip设置这一个      ← UC
 * 115pandiy       点击设置            ← 115
 * aliyuntoken     点击设置token       ← 阿里
 * tianyilogin     点击设置账号        ← 天翼
 * pan123login     点击设置账号        ← 123
 * bilicookie      点击设置cookie      ← B站
 * ydylogincookie  点击设置cookie      ← 移动云盘
 * panxunleiuser   点击设置账号        ← 迅雷
 * editembyserver  点击填写emby服务器  ← Emby
 * danmubtn        点击设置            ← 弹幕
 * ```
 *
 * ## 为什么不进详情页
 *
 * jar 对这类条目的 `detailContent()` 返回值里**没有 vod_play_url**，
 * 而且它的设计意图是"点开就打开一个内嵌网页/弹窗做授权"。
 * 我们直接进通用详情页会：
 * 1. 显示空白页（没有线路）
 * 2. 实测在小米电视上**点一下就闪退**（jar 内部在 UI 线程做了非 UI 操作）
 *
 * 所以识别出来后，直接跳我们自己的扫码授权页，绕开 jar 的详情逻辑。
 */
object PanAuthItem {

    /** 识别结果 */
    data class Match(
        /** 原始 vod_id */
        val vodId: String,
        /** 对应哪个网盘（null 表示未知/其他类） */
        val provider: PanCookieStore.Provider?,
        /** 展示名 */
        val label: String,
        /** 点开之后该做什么 */
        val action: Action = Action.OPEN_AUTH,
    )

    enum class Action {
        /** 进入凭据管理页 */
        OPEN_AUTH,

        /** 直接清除网盘登录态 */
        CLEAR,
    }

    /**
     * vod_id 关键词 → 网盘映射。
     *
     * ## 顺序很关键
     *
     * 必须**从长到短、从具体到宽泛**：
     * ```
     * "baidu" 要排在 "uc" 前面 —— 否则 baiducookie 里的 "uc" 会先命中
     * "aliyun" 要排在 "ali" 前面 —— 同理
     * "pan123" 要排在 "115" 前面 —— 避免误命中
     * ```
     * 用 `contains` 匹配，所以短词很容易抢走长词的匹配。
     */
    private val RULES: List<Triple<String, PanCookieStore.Provider?, String>> = listOf(
        // —— 长词在前 ——
        Triple("aliyun", PanCookieStore.Provider.ALI, "阿里云盘"),
        Triple("alipan", PanCookieStore.Provider.ALI, "阿里云盘"),
        Triple("baidu", PanCookieStore.Provider.BAIDU, "百度网盘"),
        Triple("pan123", null, "123 网盘"),
        Triple("quark", PanCookieStore.Provider.QUARK, "夸克网盘"),
        Triple("tianyi", null, "天翼云盘"),
        Triple("xunlei", null, "迅雷"),
        Triple("pan115", PanCookieStore.Provider.PAN115, "115 网盘"),
        // —— 短词在后 ——
        Triple("115", PanCookieStore.Provider.PAN115, "115 网盘"),
        Triple("uc", PanCookieStore.Provider.UC, "UC 网盘"),
        Triple("ali", PanCookieStore.Provider.ALI, "阿里云盘"),
        Triple("bili", null, "B 站"),
        Triple("ydy", null, "移动云盘"),
        Triple("emby", null, "Emby 服务器"),
        Triple("danmu", null, "弹幕设置"),
    )

    /**
     * 判断一个 vod_id 是不是配置类条目。
     *
     * 判定依据（组合，避免误判）：
     * ```
     * · id 里含 cookie / login / token / user / server 这类词
     * · 且能匹配到上面某个网盘关键词
     * ```
     * 单看关键词不够 —— 比如 `alicookie` 是阿里，但 `ali` 也出现在普通片名里。
     * 所以要求同时命中"配置类词"和"网盘词"。
     */
    fun match(vodId: String): Match? {
        if (vodId.isBlank()) return null

        val id = vodId.lowercase()

        // ① 必须像配置项。
        //
        // 这里刻意**不收 `user` / `server` 这种泛词** —— 实测会把
        // `movie_user_story` 这类普通片名误判成配置项，导致打不开。
        // 只认真正只出现在配置项里的词：
        val looksLikeConfig = id.contains("cookie") ||
            id.contains("login") ||
            id.contains("token") ||
            id.contains("pandiy") ||      // 115pandiy
            id.contains("emby") ||
            id.contains("danmu")
        if (!looksLikeConfig) return null

        // ② 再匹配网盘
        for ((key, provider, label) in RULES) {
            if (id.contains(key)) {
                return Match(vodId, provider, label)
            }
        }

        // ③ 像配置项但认不出是哪个网盘 —— 仍然拦下来，避免进详情页闪退
        return Match(vodId, null, "网盘配置")
    }

    /**
     * 按显示标题识别配置项。
     *
     * ★ 只按 `vod_id` 判断不够：实测某些源把 vod_id 写成序号（1/2/3），
     *   标题却是「清除嘟嘟Cookie」「登入自己网盘」这类中文别名，
     *   于是全部掉进通用详情页（0 集、播放无反应）。
     *
     * 标题识别比 ID 更可靠，但必须保守：只有同时出现「清除/登录/登入」这类
     * 动作词和「Cookie/token/网盘」这类对象词才认，避免误伤普通片名。
     */
    fun matchTitle(title: String): Match? {
        if (title.isBlank()) return null
        val text = title.trim()
        val lower = text.lowercase()

        val looksLikeAuthConfig = lower.contains("cookie") ||
            lower.contains("token") ||
            text.contains("网盘") ||
            text.contains("云盘") ||
            lower.contains("115") ||
            text.contains("夸克") ||
            text.contains("阿里") ||
            text.contains("百度") ||
            text.contains("优汐") ||
            text.contains("夸父") ||
            text.contains("嘟嘟") ||
            text.contains("阿狸")
        if (!looksLikeAuthConfig) return null

        // 动作词决定是「清除」还是「登录」。清除优先判断，
        // 因为「清除嘟嘟Cookie」里也含网盘别名。
        val isClear = text.contains("清除") || text.contains("删除") ||
            lower.contains("clear") || lower.contains("logout") || lower.contains("退出")
        val isLogin = !isClear && (text.contains("登入") || text.contains("登录") ||
            lower.contains("login"))
        if (!isClear && !isLogin) return null

        val provider = providerFor(lower + text)
        return Match(
            vodId = "",
            provider = provider,
            label = text,
            action = if (isClear) Action.CLEAR else Action.OPEN_AUTH,
        )
    }

    /**
     * 从 ID 或标题里猜是哪个网盘。
     *
     * 源站常用花名（优汐=UC、夸父=夸克、嘟嘟=百度、阿狸=阿里），
     * 所以除了官方名还要认这些别名，否则清除时定位不到正确的凭据。
     */
    fun providerFor(text: String): PanCookieStore.Provider? {
        val lower = text.lowercase()
        return when {
            lower.contains("quark") || text.contains("夸克") || text.contains("夸父") ->
                PanCookieStore.Provider.QUARK
            lower.contains("uc") || text.contains("优汐") -> PanCookieStore.Provider.UC
            lower.contains("115") -> PanCookieStore.Provider.PAN115
            lower.contains("aliyun") || lower.contains("alipan") || lower.contains("ali") ||
                text.contains("阿里") || text.contains("阿狸") -> PanCookieStore.Provider.ALI
            lower.contains("baidu") || text.contains("百度") || text.contains("嘟嘟") ->
                PanCookieStore.Provider.BAIDU
            else -> null
        }
    }

    /** 一组条目里有没有配置类（用于"这个站点是配置中心"的判定） */
    fun anyConfigItem(ids: List<String>): Boolean = ids.any { match(it) != null }
}
