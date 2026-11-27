package com.reelix.app.data.library

/**
 * 文件名解析器 —— 私有片库（爆米花/VidHub 的核心竞争力）最重要的一块。
 *
 * 需求背景：
 *  用户丢进来的文件叫
 *    "白莲花度假村.The.White.Lotus.S02E03.2160p.WEB-DL.H265.DDP5.1.mkv"
 *    "流浪地球2.The.Wandering.Earth.II.2023.1080p.BluRay.x264.mkv"
 *    "三体 第一季 第07集.mp4"
 *  要能稳定解析出：片名 / 季 / 集 / 年份 / 分辨率 / 编码 / 字幕信息。
 *
 * 策略（按优先级，命中即返回）：
 *  1. 剧集正则：SxxExx / 第X集 / EPxx / xXx 数字集
 *  2. 电影正则：片名 + 年份（4 位数字，19xx/20xx）
 *  3. 兜底：去掉所有技术标记后的剩余部分就是片名
 */
object FileNameParser {

    /** 旧版逐词白名单（保留给兼容/调试；真正过滤用 [isTag]） */
    private val TECH_TOKENS = setOf(
        // 分辨率
        "2160p", "1080p", "720p", "480p", "4k", "8k", "uhd", "hdr", "hdr10", "hdr10+", "dolbyvision", "dv", "sdr",
        // 来源
        "bluray", "blu-ray", "bdrip", "brrip", "webrip", "web-dl", "webdl", "hdtv", "dvdrip", "remux", "hdcam", "hdrip",
        // 编码
        "x264", "x265", "h264", "h265", "hevc", "avc", "av1", "10bit", "8bit",
        // 音频
        "aac", "ac3", "eac3", "dts", "dts-hd", "truehd", "ddp", "dd5", "atmos", "flac", "mp3",
        // 其他
        "proper", "repack", "extended", "uncut", "internal", "remastered", "complete", "imax",
        "chinese", "english", "chs", "cht", "gb", "big5", "sub", "subs", "hardsub", "softsub",
    )

    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "ts", "m2ts", "mov", "wmv", "flv", "rmvb", "webm", "iso")

    /**
     * 技术标记的形态族 —— 覆盖资源站的各种等价写法。
     * 例：WEB-DL 会被写成 WEB.DL / WEBDL；H.265 写成 H265 / x265；DDP5.1 写成 DD5.1。
     * 直接枚举不够用，所以用一条正则统一识别"这一坨词是不是技术标记"。
     *
     * 【务必写成单行】用 \s 做分隔符时，多行 """ 写法会把换行也变成可匹配字符，
     * 于是 `^((web|blu...)\s?(dl|rip|ray))$` 匹配 "WEB\n            (DL..." 而不是 "WEB"，
     * 整条正则永远返回 false。这个坑调试了一整轮。
     */
    private val TAG_PATTERN = Regex(
        "(?i)^((web|blu|bd|br|hd|dvd)[._-]?(dl|rip|ray)|(remux|hdtv|hr|hq|uhd)|" +
            "(x|h)\\.?26[45]|(hevc|avc|av1|vp9)|(\\d{3,4}[pi])|([48]k)|" +
            "((hdr|sdr|dv|10bit)(10\\+?)?)|dolby\\.?(vision|atmos)|" +
            "(ddp?\\.?[257](\\.[01])?|dd\\+|dts([._-]?hd)?|truehd|atmos|aac|ac3|eac3|flac|opus|lpcm)|" +
            "(\\d{1,2}bit)|(chs|cht|zho|gb|big5|eng)|(proper|repack|extended|uncut|remastered|complete|imax|internal))$"
    )

    /** 中文标签（"中英双字""简中"），单独一条，避免和上面的英文族混在一起 */
    private val CN_TAG_PATTERN = Regex("""^(中英双字|中英字幕|中字|简中|繁中|国语|粤语|内封字幕|双语字幕)$""")

    /** 纯集号/季号残留（"第93话"、"第2期上"）——不是片名 */
    private val RE_CN_EP_FULL = Regex("""^第\s*\d{1,4}\s*[集话話期回季]\s*[上下]?$""")

    /** 仅供单测/调试：某个词是不是技术标记 */
    internal fun debugIsTag(word: String): Boolean = isTag(word)

    /** 仅供单测/调试：TAG_PATTERN 的原始匹配（不带白名单） */
    internal fun debugRegexMatch(word: String): Boolean = TAG_PATTERN.matches(word)

    /** 一个词是不是技术标记：白名单命中，或符合形态族 */
    internal fun isTag(word: String): Boolean {
        val w = word.trim().trim(',', ';', '|').lowercase()
        if (w.isEmpty()) return true
        if (w in TECH_TOKENS) return true
        if (TAG_PATTERN.matches(w)) return true
        return false
    }

    /**
     * 整串是不是"纯技术标记拼装"（"WEB DL" / "2160p H265"）。
     * 用来判断过滤后的残渣能不能直接丢弃（只有非中文片名才用这条判断）。
     */
    internal fun isAllTags(text: String): Boolean {
        val words = text.split(Regex("""[\s._\-/]+""")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        return words.all { isTag(it) }
    }

    /** S01E02 / S01.E02 / S1E2 / S01E02E03 / S01EP02 */
    private val RE_SE = Regex(
        """[\.\s_\-]*[Ss](\d{1,2})[\.\s_\-]*[Ee][Pp]?(\d{1,4})(?:[\.\s_\-]*[Ee][Pp]?(\d{1,4}))?"""
    )
    /** 第X集 / 第X话 */
    private val RE_CN_EP = Regex("""第\s*(\d{1,4})\s*[集话話回]""")
    /** 第X期（综艺；"第2期上/下"里的上下是分段后缀，一并吃掉） */
    private val RE_CN_EP_QI = Regex("""第\s*(\d{1,4})\s*期\s*[上下]?""")
    /** 第X季 */
    private val RE_CN_SEASON = Regex("""第\s*([0-9一二三四五六七八九十]+)\s*季""")
    /** EP12 / E12 / Ep.12 */
    private val RE_EP = Regex("""(?:^|[\.\s_\-])[Ee][Pp]?[\.\s_\-]*(\d{1,4})(?:[\.\s_\-]|$)""")
    /** Season 2 / Season.2 */
    private val RE_SEASON_EN = Regex("""[Ss]eason[\.\s_\-]*(\d{1,2})""")
    /** 全集 / 完结 */
    private val RE_COMPLETE = Regex("""全集|完结|全(\d+)[集话]""")
    /** 年份 */
    private val RE_YEAR = Regex("""(?:^|[\.\s_\-\(\[])((?:19|20)\d{2})(?:[\.\s_\-\)\]]|$)""")
    /** 分辨率 */
    private val RE_RES = Regex("""(2160p|1080p|720p|480p|4[kK]|8[kK])""", RegexOption.IGNORE_CASE)
    /** 方括号/圆括号整段（通常放字幕组、技术标记） */
    private val RE_BRACKET = Regex("""[\[【\(（][^\]】\)）]{0,40}[\]】\)）]""")
    /**
     * 常见字幕组前缀（"高清影视之家 " "XXX字幕组 "）。
     *
     * 【不要写成 `^[A-Za-z0-9...]+[\s-_]+`】那是把"任意英文单词"都当成字幕组，
     * 实测会直接把 "Oppenheimer 2023..." 的片名整块吃掉，只剩 "2160p WEB-DL"。
     * 现在只认带明确后缀（组/字幕组/工作室/影视/制片/压制）的前缀，或者中文的常见组名。
     */
    private val RE_GROUP_PREFIX = Regex(
        "^(?:" +
            "[A-Za-z0-9@.\\-_]{2,24}(?:组|組|字幕组|字幕組|工作室|影视|影視|压制|壓制|制片|字幕社)" +
            "|(?:高清影视|电影天堂|本站|官方发布|最新影视|某某字幕)" +
            ")[\\s\\-_]+"
    )

    /**
     * 字幕组/发布组名 —— 只出现在**词的边界**上，且形态非常有限。
     *
     * 例：`高清影视之家 三体` / `三体 某某字幕组 2160p`
     * 早期版本把"任意英文单词+空格"当组名，结果把 Oppenheimer 当成组名吃掉了。
     * 现在只认这几种明确形态，且要求前后有空格隔离。
     */
    private val RT_PREFIX = Regex(
        "^(高清影视之家|高清影视|[A-Za-z0-9]{2,20}(?:字幕组|字幕組|組|组|工作室))\\s+"
    )
    private val RT_SUFFIX = Regex(
        "\\s+(高清影视之家|[A-Za-z0-9]{2,20}(?:字幕组|字幕組|組|组|工作室))$"
    )

    /**
     * 解析结果。
     * [episode] 和 [endEpisode] 用于合集文件（S01E01-E03 一个文件）。
     */
    data class Parsed(
        val title: String,
        val season: Int? = null,
        val episode: Int? = null,
        val endEpisode: Int? = null,
        val year: Int? = null,
        val resolution: String? = null,
        val isComplete: Boolean = false,
        /** 置信度 0..1，低于 0.5 建议让用户手动改 */
        val confidence: Float = 0f,
    ) {
        val isSeries: Boolean get() = episode != null || season != null
    }

    /**
     * 纯数字文件名（"01.mkv"、"2023.mp4"）不可能是片名，显式判掉。
     */
    private val RE_PURE_NUMERIC = Regex("""^\d{1,8}$""")

    /**
     * 主入口。
     * @param fileName 文件名（可带路径，会自动取 basename 并去扩展名）
     */
    fun parse(fileName: String): Parsed {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val noExt = base.substringBeforeLast('.', base).let {
            if (base.substringAfterLast('.', "").lowercase() in VIDEO_EXT) it else base
        }

        // 先把方括号整段抠掉（字幕组 [XXX] 通常不是片名）
        var work = noExt
        val bracketHits = RE_BRACKET.findAll(work).map { it.value }.toList()
        work = RE_BRACKET.replace(work, " ")

        // 记录技术信息（从被抠掉的部分里也能捞）
        val haystack = (noExt + " " + bracketHits.joinToString(" ")).replace('.', ' ')
        val resolution = RE_RES.find(haystack)?.groupValues?.get(1)

        // 0) 片名可能只存在于方括号里（"[高清影视之家][三体][01][2160p].mkv"）。
        //    上面为了干净把 [] 整段抠掉了，这里若再找不到片名原料，
        //    就把括号还原成空格重来一遍，否则这部片子永远拿不到片名。
        //    判断条件是"抠完之后剩下的非空格字符不足 2 个"。
        if (work.count { !it.isWhitespace() } < 2) {
            work = noExt.replace(Regex("""[\[\]【】\(\)（）]"""), " ")
        }

        // 1) 季集
        var season: Int? = null
        var episode: Int? = null
        var endEpisode: Int? = null
        var hitSeries = false

        // 注意：下面每一处 work.replace 都必须**优先**用工作串，
        // 早期版本一律 replace(noExt)，会把方括号里的内容（`[01]`、`[高清影视之家]`）
        // 又匹配一次却替换不掉，导致片名里残留垃圾。新增分支请同时补测试。
        RE_SE.find(work)?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            episode = m.groupValues[2].toIntOrNull()
            endEpisode = m.groupValues.getOrNull(3)?.toIntOrNull()
            hitSeries = true
            work = work.replace(m.value, " ")
        }

        if (!hitSeries) {
            RE_CN_SEASON.find(work)?.let {
                season = cnNumber(it.groupValues[1]) ?: it.groupValues[1].toIntOrNull()
                work = work.replace(it.value, " ")
            }
            RE_SEASON_EN.find(work)?.let {
                season = season ?: it.groupValues[1].toIntOrNull()
                work = work.replace(it.value, " ")
            }
            RE_CN_EP.find(work)?.let {
                episode = it.groupValues[1].toIntOrNull()
                hitSeries = true
                work = work.replace(it.value, " ")
            }
            if (!hitSeries) {
                RE_CN_EP_QI.find(work)?.let {
                    episode = it.groupValues[1].toIntOrNull()
                    hitSeries = true
                    work = work.replace(it.value, " ")
                }
            }
            if (!hitSeries) {
                RE_EP.find(work)?.let {
                    episode = it.groupValues[1].toIntOrNull()
                    hitSeries = true
                    work = work.replace(it.value, " ")
                }
            }
        }

        val isComplete = RE_COMPLETE.containsMatchIn(work)

        // 2) 年份
        val year = RE_YEAR.find(work)?.groupValues?.get(1)?.toIntOrNull()
        year?.let { work = work.replace(it.toString(), " ") }

        // 3) 干净片名
        // 点/下划线/连字符 → 空格，这样 "Oppenheimer.2023.2160p.WEB-DL" 变成
        // "Oppenheimer 2023 2160p WEB DL"，每个技术标记都变成独立词，白名单才认得出来。
        var title = work
            .replace(Regex("""[\._\[\]\(\)【】（）]"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '_', ' ')

        title = RE_GROUP_PREFIX.replace(title, "").trim()

        // 剔除残留技术标记。
        //
        // 这里只按空白切词，**绝不按点号/连字符切**：
        // 上面的 title 规范化已经把点号换成空格了，还会只剩下"连字符形态"的技术词
        // （"WEB-DL"）。若按连字符切，会得到 "WEB" 和 "DL" 两个断片，
        // 两个都不在形态族里，于是片名变成 "Oppenheimer 2023 2160p WEB-DL"。
        // 整词交给 isTag 判断才对。
        val beforeFilter = title
        val words = beforeFilter.split(Regex("""\s+""")).filter { it.isNotBlank() }

        // 连写形态（"WEB-DL"）先整体判一次；拆开的形态（["WEB","DL"]）再判一次。
        // 两条都过才算片名，避免 "Oppenheimer 2023 2160p WEB-DL" 这种残留。
        val kept = ArrayList<String>()
        for (w in words) {
            if (isTag(w)) continue
            val segments = w.split('-', '.', '_').filter { it.isNotBlank() }
            if (segments.size > 1 && segments.all { isTag(it) }) continue
            kept += w
        }
        var cleaned = kept.joinToString(" ").trim()

        // ④ 整串都是标记（"2160p H265"）说明压根没有片名，直接清空交给上层兜底
        if (isAllTags(beforeFilter)) cleaned = ""

        // ⑤ 集号/季号残留（"第93话"）不是片名
        if (cleaned.isNotBlank()) {
            cleaned = cleaned.split(" ").filterNot { w ->
                CN_TAG_PATTERN.matches(w) || RE_CN_EP_FULL.matches(w)
            }.joinToString(" ").trim()
        }

        // ⑤.5 字幕组/发布组名：只在这段是纯 ASCII 来源时才动手。
        // RT 只认 "英文名+组/字幕组/工作室" 这类形态，不会吃中文片名。
        cleaned = RT_PREFIX.replace(cleaned, "").trim()
        cleaned = RT_SUFFIX.replace(cleaned, "").trim()

        // ⑥ 最后一道：剩余词逐个过 isTag。
        // 前面的过滤只覆盖"词边界"，年份/集号被替换后可能拼出新的边界，
        // 这一步保证输出里绝不会有技术标记（Oppenheimer 2023 2160p WEB-DL → Oppenheimer）。
        //
        // 【坑】必须写 split(" ") 而不是 split(' ')：Kotlin 的 Char 单参重载是
        // "按分隔符字面量切每个字符"，会把一个空格切成空串集合，filterNot 后什么都不剩。
        if (cleaned.isNotBlank()) {
            cleaned = cleaned.split(" ").filterNot { w ->
                w.isBlank() || isTag(w)
            }.joinToString(" ").trim()
        }

        // 丢弃纯数字词：年份/集号被替换后常留下孤立数字（"白莲花... DDP5.1" → "1"）。
        // 片名里出现孤立数字没有意义，而"流浪地球2"这种词是连写的，不会被误伤。
        cleaned = cleaned.split(" ").filterNot { w ->
            w.isBlank() || w.all { c -> c.isDigit() }
        }.joinToString(" ").trim()

        val finalTitle = cleaned.ifBlank {
            // 最后的兜底：什么都没解析出来时，用原始文件名的前 40 个字符，
            // 至少不会出现"空片名"这种必须在 UI 层特殊处理的毒数据。
            // 只有已知是垃圾（纯数字、技术标记）时才认输返回空串。
            val fallback = base.substringBeforeLast('.').replace(Regex("""[\._\[\]【】]"""), " ")
                .replace(Regex("""\s{2,}"""), " ").trim().take(40)
            if (RE_PURE_NUMERIC.matches(fallback) || isAllTags(fallback)) "" else fallback
        }

        // 4) 置信度
        var conf = 0f
        if (finalTitle.isNotBlank()) conf += 0.4f
        if (year != null) conf += 0.2f
        if (hitSeries && episode != null) conf += 0.3f
        if (resolution != null) conf += 0.1f
        // 片名全是数字/符号，说明解析失败
        if (finalTitle.none { it.isLetter() || it.code > 0x4E00 }) conf -= 0.5f
        // 根本就没解析出片名（只剩集号），上层应当用目录名兜底
        if (finalTitle.isBlank()) conf -= 0.3f

        return Parsed(
            title = finalTitle.trim(),
            season = season,
            episode = episode,
            endEpisode = endEpisode,
            year = year,
            resolution = resolution,
            isComplete = isComplete,
            confidence = conf.coerceIn(0f, 1f),
        )
    }

    /**
     * 从一堆文件名推导出整部剧的结构：
     * 片名（取众数）+ 季 + 集列表。
     */
    fun parseBatch(fileNames: List<String>): BatchParsed {
        val parsed: List<Pair<String, Parsed>> = fileNames.map { name -> name to parse(name) }
        if (parsed.isEmpty()) return BatchParsed(title = "", files = emptyList())

        // 片名取出现次数最多的
        val titleCount = parsed.groupingBy { pair -> pair.second.title }.eachCount()
        val title = titleCount.maxByOrNull { entry -> entry.value }?.key
            ?: parsed.first().second.title

        // 年份同理
        val year = parsed.mapNotNull { pair -> pair.second.year }
            .groupingBy { y -> y }.eachCount()
            .maxByOrNull { entry -> entry.value }?.key

        val seasons = parsed.mapNotNull { pair -> pair.second.season }
            .groupingBy { s -> s }.eachCount()
            .maxByOrNull { entry -> entry.value }?.key

        val episodes = parsed
            .filter { pair -> pair.second.episode != null }
            .sortedBy { pair -> pair.second.episode }

        return BatchParsed(
            title = title,
            year = year,
            season = seasons,
            files = episodes.map { pair ->
                EpisodeFile(pair.first, pair.second.episode!!, pair.second.endEpisode)
            }
        )
    }

    data class BatchParsed(
        val title: String,
        val year: Int? = null,
        val season: Int? = null,
        val files: List<EpisodeFile> = emptyList(),
    )

    data class EpisodeFile(val fileName: String, val episode: Int, val endEpisode: Int? = null)

    /** 中文数字转阿拉伯，支持 1..99 */
    private fun cnNumber(s: String): Int? {
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        var result = 0
        var section = 0
        for (c in s) {
            when {
                c in digits -> section = digits[c]!!
                c == '十' -> {
                    result += (if (section == 0) 1 else section) * 10
                    section = 0
                }
                c == '百' -> {
                    result += (if (section == 0) 1 else section) * 100
                    section = 0
                }
                else -> return null
            }
        }
        return result + section
    }
}
