package com.reelix.app.data.search

import com.reelix.app.data.model.MediaItem

/**
 * 搜索匹配逻辑 —— 纯函数，不碰 Android，PC 上直接单测。
 *
 * 为什么单独抽出来：
 *  电视上的搜索体验有一半是"输入法 + 焦点"，另一半就是"输入两三个字就要出结果"。
 *  后者完全是字符串算法，完全可以在 PC 上做到位、测到位，
 *  等到上电视时只剩输入法和焦点两件事要调。
 *
 * 匹配分三档，分越高越靠前：
 *  100 完整相等
 *   80 前缀命中
 *   60 子串命中
 *   40 拼音首字母命中（"SJ" → "三体"）
 *   20 模糊子序列命中（"三体" → "三体动画第二季"，字都按顺序出现）
 *    0 不匹配
 *
 * 为什么要拼音：TV 遥控器上打中文极其痛苦，用户真实行为是用首字母，
 * 这是电视端搜索和手机端最大的差别。
 */
object SearchMatcher {

    /** 一档匹配结果 */
    data class Hit(val item: MediaItem, val score: Int, val matchedBy: String)

    /**
     * 给单个条目打分。
     *
     * @param query 用户输入（会先做 trim + 小写）
     * @param item 待匹配的片子
     * @param pinyin 可选：预先算好的拼音首字母，没有就现算
     */
    fun score(query: String, item: MediaItem, pinyin: String? = null): Hit? {
        val q = normalize(query)
        if (q.isEmpty()) return null

        val fields = buildList {
            add("title" to item.title)
            item.originalTitle?.let { add("original" to it) }
            add("id" to item.id)
            item.sourceKey.takeIf { it.isNotBlank() }?.let { add("source" to it) }
        }

        var best: Hit? = null

        for ((field, raw) in fields) {
            val text = normalize(raw)
            if (text.isEmpty()) continue

            val candidate = when {
                text == q -> Hit(item, 100, field)
                text.startsWith(q) -> Hit(item, 80, field)
                text.contains(q) -> Hit(item, 60, field)
                // 拼音首字母：只在标题上做，避免 id/source 里的字母污染
                else -> if (field == "title") {
                    val py = pinyin ?: Pinyin.firstLetters(raw)
                    // 首字母"完整相等"要给到和中文完全相等同一档：
                    // 用户打 "st" 想找的就是"三体"，而"三体动画第二季"的首字母是 "stdhej"，
                    // 若只按 startsWith 给 40 分，完整命中的那条反而排不上第一。
                    if (py.isNotEmpty() && py == q) Hit(item, 95, "pinyin")
                    else if (py.isNotEmpty() && py.startsWith(q)) Hit(item, 40, "pinyin")
                    else if (py.isNotEmpty() && py.contains(q)) Hit(item, 35, "pinyin")
                    else null
                } else null
            }

            if (candidate != null && (best == null || candidate.score > best.score)) {
                best = candidate
            }
        }

        if (item.fromLibrary) {
            val resource = item.searchResources.mapNotNull { file ->
                val name = normalize(file.name)
                val score = when { name == q -> 100; name.startsWith(q) -> 80; name.contains(q) -> 60; else -> 0 }
                if (score > 0) file to score else null
            }.sortedWith(compareByDescending<Pair<com.reelix.app.data.model.MediaSearchResource, Int>> { it.second }
                .thenBy { it.first.path }).firstOrNull()
            if (resource != null && (best == null || resource.second > best.score)) {
                best = Hit(item.copy(matchedResourcePath = resource.first.path, matchedResourceName = resource.first.name), resource.second, "file")
            }
        }

        // 兜底：子序列匹配（"三体" 命中 "三体动画第二季" 已经在 contains 覆盖，
        // 这里覆盖错过连续的情况，比如 "流浪地球" 命中 "流浪的地球"）
        if (best == null) {
            val title = normalize(item.title)
            if (isSubsequence(q, title)) best = Hit(item, 20, "fuzzy")
        }

        return best
    }

    /**
     * 搜索主入口，返回按匹配度排序的结果。
     */
    fun search(
        query: String,
        items: List<MediaItem>,
        limit: Int = 60,
    ): List<Hit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()

        return items
            .mapNotNull { item -> score(query, item) }
            .sortedWith(
                compareByDescending<Hit> { it.score }
                    .thenByDescending { it.item.year ?: 0 }
                    .thenBy { it.item.title }
            )
            .take(limit)
    }

    /** 把结果拆成"精确命中"和"模糊命中"，UI 用来分组显示 */
    fun group(hits: List<Hit>): List<SearchGroup> {
        val exact = hits.filter { it.score >= 60 }
        val fuzzy = hits.filter { it.score < 60 }
        return buildList {
            if (exact.isNotEmpty()) add(SearchGroup("匹配", exact))
            if (fuzzy.isNotEmpty()) add(SearchGroup("可能相关", fuzzy))
        }
    }

    data class SearchGroup(val title: String, val hits: List<Hit>)

    // ---------- 内部 ----------

    /** 统一小写、去多余空白、全角转半角 */
    internal fun normalize(s: String): String {
        if (s.isEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (c in s) {
            val ch = when {
                c == '\u3000' -> ' '
                c in '\uFF01'..'\uFF5E' -> (c.code - 0xFEE0).toChar() // 全角 ASCII
                else -> c
            }
            sb.append(ch)
        }
        return sb.toString().lowercase().trim()
    }

    /** 子序列匹配：query 的字符按顺序出现在 text 里即可 */
    internal fun isSubsequence(query: String, text: String): Boolean {
        if (query.isEmpty()) return false
        if (query.length > text.length) return false
        var i = 0
        for (c in text) {
            if (c == query[i]) {
                i++
                if (i == query.length) return true
            }
        }
        return false
    }
}

/**
 * 极简拼音首字母表。
 *
 * 为什么自己写而不引依赖：
 *  1. pinyin4j 之类的库带 1MB+ 词库，对一个电视 App 不值；
 *  2. 搜索首字母只需要**汉字 → 声母首字母**的映射，
 *     常用汉字 6000 多个，但片名里 95% 落在 3500 常用字里；
 *  3. 真要覆盖全，可以后续换成全量表，接口不变（[firstLetters]）。
 *
 * 现在覆盖：GB2312 一级常用字（3755 字）中用到的常见片名字。
 * 未收录的字返回 '#'，会被 [SearchMatcher] 跳过，不会误命中。
 */
object Pinyin {

    /**
     * 取字符串里每个汉字的首字母，非汉字原样保留（转小写）。
     * "三体" → "st"，"流浪地球2" → "lldq2"
     */
    fun firstLetters(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c.code < 0x4E00 || c.code > 0x9FFF) {
                if (!c.isWhitespace()) sb.append(c.lowercaseChar())
                continue
            }
            val initial = initialOf(c)
            if (initial != '#') sb.append(initial)
        }
        return sb.toString()
    }

    /** 常用片名字的声母，未收录返回 '#' */
    fun initialOf(c: Char): Char = TABLE[c] ?: '#'

    /**
     * 常用字 → 声母。
     * 只收片名高频字，未收录的返回 '#'（不参与拼音匹配，但中文子串匹配照常工作）。
     */
    private val TABLE: Map<Char, Char> = buildMap {
        fun put(initial: Char, chars: String) {
            for (c in chars) this[c] = initial
        }
        put('a', "阿啊爱安按暗岸案昂奥澳")
        put('b', "八巴白百败班板版半办帮包宝保报抱北背背贝备本比笔必毕闭边变便表别宾冰并病波播博不布步部")
        // 注意：多音字 / 易错字单独补，顺序无关（后写覆盖先写，值相同无副作用）
        put('c', "才材财采彩参餐残藏操草册侧层查茶察差产长常场唱超朝潮车彻尘沉陈称成城乘程吃池迟持赤冲虫重抽丑出初除厨楚处触川穿传船窗创春纯词此刺次从粗促村存寸错")
        put('d', "答达打大代带待单但淡当挡刀导岛倒到道德得的灯登等低敌底地第弟点电店垫调跌迭叠丁顶订定丢东冬懂动都斗豆毒独读赌杜度端短段断堆队对吨顿多夺朵")
        put('e', "俄额恶儿而耳二")
        put('f', "发法翻反返范犯饭方防房仿访放飞非肥废费分纷粉份风封疯峰锋逢讽否夫服浮符福父付负富复副凡帆番繁")
        put('g', "该改概干甘敢感刚钢港高搞告哥歌格个各给根跟更工公功攻供宫共沟狗构姑孤古股故顾瓜关观官管冠惯光广归规鬼贵国果过")
        put('h', "哈孩海害含汉汗行航好号合河和黑很恨横红后候呼胡湖户花华划画怀坏欢环换黄灰回会婚活火或货")
        put('j', "击机积基激及吉级极即急集几己挤计记纪技既济继加夹家甲假价架尖坚间肩监减简见建剑健江将讲交郊角脚叫教接街节结姐解介戒界借今金近进京经惊精井景警净静境九久酒旧就居局举巨句剧卷觉绝军君")
        put('k', "开看康抗考科咳可克刻客课肯空孔恐控口扣哭苦库块快款狂况亏昆")
        put('l', "拉啦来赖兰蓝篮烂狼浪劳老乐雷累类冷李里理力立利历例连联脸练炼恋良两量亮谅聊了料列烈林临灵零领另令溜流留六龙楼露陆录路旅绿乱略论罗落莲炼")
        put('m', "妈麻马吗买卖满慢忙猫毛贸么没美妹门们梦迷米密秘面苗灭民名明命摸模魔末没默某母木目牧幕")
        put('n', "拿哪那内南难男脑闹呢能你年念娘鸟您宁牛农弄女暖努")
        put('o', "哦")
        put('p', "怕拍排牌派判盘旁跑朋碰批皮片票漂贫品平评凭瓶破迫普")
        put('q', "七其奇期骑起气汽前钱强抢桥巧切且亲侵轻清情请穷秋球区取去全权劝群")
        put('r', "然染让热人认任日容如入软瑞若")
        put('s', "三色杀沙山商上赏少蛇社设身深神审生声胜晟十时实识使世事市式试收手守首受舒输书熟数属术树数双谁水睡顺说思死四速素算虽随岁碎孙所索")
        put('t', "他她它台太态谈弹探堂唐躺逃桃讨特疼提题体天田条跳听通同童头透图土突团推退")
        put('v', "女吕旅律绿")
        put('w', "挖外完玩万王网往望危为围唯委卫未位温文闻问我卧握乌无五午舞务物武")
        put('x', "西希习喜系细下先仙鲜显现相想向巷像消小效笑些写谢心新信星行醒性兄雄休修秀需许序续选学雪寻迅")
        put('y', "压呀牙亚烟严言颜眼演样阳要药也野业叶夜一依已以义艺议因阴引饮英应影硬映用有友又右于余鱼娱雨语遇元员园远愿约月越云运与羽宇")
        put('z', "杂灾再在咱赞早造择怎增扎摘窄展站张章长找照者这真阵振政之只知直值止只纸指至治中钟终众种周州舟昼主住注祝专转装状追准资子字自宗总走足族组祖最罪左作做")
    }
}
