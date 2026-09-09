package com.example.controlfree.knowledge

/**
 * 随安装包交付的离线事实库。内容刻意避开时效性、主观性、医疗、法律和投资问题。
 */
internal object OfflineKnowledgeBank {
    val facts: List<KnowledgeFact> = listOf(
        fact(
            "science_water_formula", KnowledgeCategory.NATURAL_SCIENCE, KnowledgeDifficulty.EASY,
            "水的化学式是什么？", "H₂O", listOf("H2O"), listOf("CO₂", "O₂", "NaCl"),
            "一个水分子由两个氢原子和一个氧原子构成，因此化学式为 H₂O。"
        ),
        fact(
            "science_red_planet", KnowledgeCategory.NATURAL_SCIENCE, KnowledgeDifficulty.EASY,
            "太阳系中被称为“红色星球”的行星是哪一颗？", "火星", emptyList(),
            listOf("金星", "木星", "水星"), "火星表面富含氧化铁，远看呈红色，因此得名“红色星球”。"
        ),
        fact(
            "science_photosynthesis_gas", KnowledgeCategory.NATURAL_SCIENCE, KnowledgeDifficulty.STANDARD,
            "绿色植物进行光合作用时主要吸收哪种气体？", "二氧化碳", listOf("CO₂", "CO2"),
            listOf("氧气", "氮气", "氢气"), "植物利用光能、水和二氧化碳合成有机物，并释放氧气。"
        ),
        fact(
            "science_largest_organ", KnowledgeCategory.NATURAL_SCIENCE, KnowledgeDifficulty.STANDARD,
            "按覆盖面积计算，人体最大的器官是什么？", "皮肤", emptyList(),
            listOf("肝脏", "肺", "心脏"), "皮肤覆盖全身，是人体按面积和总体质量计最大的器官。"
        ),
        fact(
            "science_sound_vacuum", KnowledgeCategory.NATURAL_SCIENCE, KnowledgeDifficulty.CHALLENGING,
            "声音不能在哪种环境中传播？", "真空", emptyList(), listOf("空气", "水", "钢铁"),
            "声音是机械波，需要介质传递振动，真空中没有可传递振动的介质。"
        ),
        fact(
            "science_dna_name", KnowledgeCategory.NATURAL_SCIENCE, KnowledgeDifficulty.CHALLENGING,
            "DNA 的中文全称是什么？", "脱氧核糖核酸", emptyList(),
            listOf("核糖核酸", "脱氧核糖", "氨基核酸"), "DNA 是脱氧核糖核酸，主要承担生物遗传信息的储存和传递。"
        ),

        fact(
            "history_movable_type", KnowledgeCategory.HISTORY_CULTURE, KnowledgeDifficulty.EASY,
            "北宋时期发明活字印刷术的工匠是谁？", "毕昇", listOf("毕升"),
            listOf("蔡伦", "张衡", "沈括"), "北宋工匠毕昇发明胶泥活字，沈括在《梦溪笔谈》中记录了这项技术。"
        ),
        fact(
            "history_shiji_author", KnowledgeCategory.HISTORY_CULTURE, KnowledgeDifficulty.EASY,
            "《史记》的作者是谁？", "司马迁", emptyList(), listOf("班固", "司马光", "陈寿"),
            "西汉史学家司马迁撰写《史记》，开创了纪传体通史的体例。"
        ),
        fact(
            "history_qingming_painter", KnowledgeCategory.HISTORY_CULTURE, KnowledgeDifficulty.STANDARD,
            "《清明上河图》通常认为出自哪位北宋画家？", "张择端", emptyList(),
            listOf("顾恺之", "吴道子", "阎立本"), "《清明上河图》是北宋画家张择端的代表作，描绘了汴京的城市生活。"
        ),
        fact(
            "history_qimin_author", KnowledgeCategory.HISTORY_CULTURE, KnowledgeDifficulty.STANDARD,
            "中国古代农学著作《齐民要术》的作者是谁？", "贾思勰", emptyList(),
            listOf("徐光启", "宋应星", "郦道元"), "北魏贾思勰撰写《齐民要术》，系统总结了当时的农业生产知识。"
        ),
        fact(
            "history_oracle_dynasty", KnowledgeCategory.HISTORY_CULTURE, KnowledgeDifficulty.CHALLENGING,
            "现存甲骨文材料主要属于中国古代哪个王朝？", "商朝", listOf("商代"),
            listOf("夏朝", "周朝", "秦朝"), "已发现的甲骨文大多是商代晚期王室占卜记录。"
        ),
        fact(
            "history_yongle_encyclopedia", KnowledgeCategory.HISTORY_CULTURE, KnowledgeDifficulty.CHALLENGING,
            "大型类书《永乐大典》编纂于哪个朝代？", "明朝", listOf("明代"),
            listOf("唐朝", "宋朝", "清朝"), "《永乐大典》奉明成祖之命编纂，是中国古代规模宏大的类书。"
        ),

        fact(
            "geography_largest_ocean", KnowledgeCategory.GEOGRAPHY, KnowledgeDifficulty.EASY,
            "世界上面积最大的海洋是哪一个？", "太平洋", emptyList(),
            listOf("大西洋", "印度洋", "北冰洋"), "太平洋面积约占世界海洋总面积的一半，是面积最大的海洋。"
        ),
        fact(
            "geography_china_longest_river", KnowledgeCategory.GEOGRAPHY, KnowledgeDifficulty.EASY,
            "中国长度最长的河流是哪一条？", "长江", emptyList(), listOf("黄河", "珠江", "黑龙江"),
            "长江干流全长六千多千米，是中国第一长河。"
        ),
        fact(
            "geography_australia_capital", KnowledgeCategory.GEOGRAPHY, KnowledgeDifficulty.STANDARD,
            "澳大利亚的首都是哪座城市？", "堪培拉", emptyList(),
            listOf("悉尼", "墨尔本", "珀斯"), "堪培拉是澳大利亚首都，悉尼和墨尔本虽然更知名但都不是首都。"
        ),
        fact(
            "geography_sahara_continent", KnowledgeCategory.GEOGRAPHY, KnowledgeDifficulty.STANDARD,
            "撒哈拉沙漠主要位于哪个大洲？", "非洲", emptyList(), listOf("亚洲", "南美洲", "大洋洲"),
            "撒哈拉沙漠横跨北非多个国家，是世界上面积最大的热沙漠。"
        ),
        fact(
            "geography_prime_meridian", KnowledgeCategory.GEOGRAPHY, KnowledgeDifficulty.CHALLENGING,
            "国际上通常把经过英国哪一地点的经线定为本初子午线？", "格林尼治", listOf("格林威治"),
            listOf("剑桥", "牛津", "多佛"), "经过格林尼治天文台旧址的经线被定为零度经线，即本初子午线。"
        ),
        fact(
            "geography_everest_border", KnowledgeCategory.GEOGRAPHY, KnowledgeDifficulty.CHALLENGING,
            "珠穆朗玛峰位于中国与哪个国家的边界？", "尼泊尔", emptyList(),
            listOf("不丹", "印度", "巴基斯坦"), "珠穆朗玛峰位于喜马拉雅山脉中段的中国与尼泊尔边界。"
        ),

        fact(
            "art_red_chamber_author", KnowledgeCategory.LITERATURE_ART, KnowledgeDifficulty.EASY,
            "中国古典小说《红楼梦》通常署名的作者是谁？", "曹雪芹", emptyList(),
            listOf("施耐庵", "罗贯中", "吴承恩"), "《红楼梦》通常认为由清代作家曹雪芹创作。"
        ),
        fact(
            "art_hamlet_author", KnowledgeCategory.LITERATURE_ART, KnowledgeDifficulty.EASY,
            "戏剧《哈姆雷特》的作者是谁？", "莎士比亚", listOf("威廉·莎士比亚"),
            listOf("歌德", "雨果", "狄更斯"), "《哈姆雷特》是英国剧作家威廉·莎士比亚创作的悲剧。"
        ),
        fact(
            "art_starry_night_painter", KnowledgeCategory.LITERATURE_ART, KnowledgeDifficulty.STANDARD,
            "油画《星月夜》的作者是谁？", "梵高", listOf("文森特·梵高"),
            listOf("莫奈", "塞尚", "高更"), "《星月夜》是荷兰画家文森特·梵高的代表作之一。"
        ),
        fact(
            "art_ninth_symphony", KnowledgeCategory.LITERATURE_ART, KnowledgeDifficulty.STANDARD,
            "著名的《第九交响曲“合唱”》由哪位作曲家创作？", "贝多芬", listOf("路德维希·凡·贝多芬"),
            listOf("莫扎特", "舒伯特", "肖邦"), "贝多芬的第九交响曲末乐章加入合唱，因此常被称为“合唱交响曲”。"
        ),
        fact(
            "art_don_quixote_author", KnowledgeCategory.LITERATURE_ART, KnowledgeDifficulty.CHALLENGING,
            "小说《堂吉诃德》的作者是谁？", "塞万提斯", listOf("米格尔·德·塞万提斯"),
            listOf("博尔赫斯", "马尔克斯", "洛尔迦"), "《堂吉诃德》是西班牙作家米格尔·德·塞万提斯的代表作。"
        ),
        fact(
            "art_guernica_painter", KnowledgeCategory.LITERATURE_ART, KnowledgeDifficulty.CHALLENGING,
            "大型画作《格尔尼卡》的作者是谁？", "毕加索", listOf("巴勃罗·毕加索"),
            listOf("达利", "米罗", "马蒂斯"), "西班牙画家巴勃罗·毕加索创作《格尔尼卡》，表达对战争灾难的反思。"
        ),

        fact(
            "life_fire_alarm", KnowledgeCategory.LIFE_KNOWLEDGE, KnowledgeDifficulty.EASY,
            "在中国大陆发现火灾时，应拨打哪个火警电话？", "119", emptyList(),
            listOf("110", "120", "122"), "中国大陆统一火警电话号码是 119，报警时应说明地点和火情。"
        ),
        fact(
            "life_oil_fire", KnowledgeCategory.LIFE_KNOWLEDGE, KnowledgeDifficulty.EASY,
            "炒菜时油锅起火，较合适的第一步处理是什么？", "关闭火源并盖上锅盖", listOf("关火并盖锅盖"),
            listOf("立即向锅内泼水", "端着油锅跑出屋外", "用嘴用力吹灭"), "关闭火源并盖紧锅盖能隔绝空气；向热油中泼水会使火势飞溅。"
        ),
        fact(
            "life_electrical_fire", KnowledgeCategory.LIFE_KNOWLEDGE, KnowledgeDifficulty.STANDARD,
            "电器冒烟起火且能够安全操作时，首先应做什么？", "切断电源", listOf("关闭电源"),
            listOf("直接泼水", "徒手搬走电器", "继续通电观察"), "能够安全操作时先切断电源，避免触电和持续短路，再使用合适方式灭火。"
        ),
        fact(
            "life_carbon_monoxide", KnowledgeCategory.LIFE_KNOWLEDGE, KnowledgeDifficulty.STANDARD,
            "怀疑室内一氧化碳泄漏时，首要做法是什么？", "立即通风并转移到室外", listOf("开窗通风并离开现场"),
            listOf("打开明火查看", "继续留在室内休息", "关闭门窗等待"), "应在保证自身安全的前提下迅速通风、离开现场，并及时求助。"
        ),
        fact(
            "life_cutting_boards", KnowledgeCategory.LIFE_KNOWLEDGE, KnowledgeDifficulty.CHALLENGING,
            "处理生肉和即食食品时，砧板和刀具最好怎样使用？", "生熟分开", listOf("分开使用"),
            listOf("只用清水冲一下后混用", "先切熟食再切生肉即可", "长期使用同一块木板无需清洗"),
            "生熟食品使用不同砧板和刀具，可以降低微生物交叉污染的风险。"
        ),
        fact(
            "life_password", KnowledgeCategory.LIFE_KNOWLEDGE, KnowledgeDifficulty.CHALLENGING,
            "下列哪种做法更有利于保护网络账号？", "为不同账号使用不同的长密码", listOf("每个账号使用唯一长密码"),
            listOf("所有账号共用生日密码", "把密码发给朋友保管", "长期使用六位纯数字密码"),
            "不同账号使用唯一长密码，并配合密码管理器和多因素验证，可减少撞库风险。"
        ),

        fact(
            "tech_binary_digits", KnowledgeCategory.TECHNOLOGY, KnowledgeDifficulty.EASY,
            "二进制计数系统使用哪两个数字？", "0 和 1", listOf("0与1", "0、1"),
            listOf("1 和 2", "0 到 9", "A 和 B"), "二进制以二为基数，只使用 0 和 1 两个数字表示信息。"
        ),
        fact(
            "tech_cpu_name", KnowledgeCategory.TECHNOLOGY, KnowledgeDifficulty.EASY,
            "计算机中的 CPU 中文通常称为什么？", "中央处理器", emptyList(),
            listOf("图形处理器", "随机存储器", "固态硬盘"), "CPU 是中央处理器，负责解释和执行计算机程序中的主要指令。"
        ),
        fact(
            "tech_qr_dimension", KnowledgeCategory.TECHNOLOGY, KnowledgeDifficulty.STANDARD,
            "常见二维码属于哪类条码？", "二维条码", listOf("二维条形码"),
            listOf("一维条码", "磁性条码", "声音条码"), "二维码在横向和纵向两个方向编码信息，因此属于二维条码。"
        ),
        fact(
            "tech_https_tls", KnowledgeCategory.TECHNOLOGY, KnowledgeDifficulty.STANDARD,
            "HTTPS 通常使用哪种协议保护传输过程？", "TLS", listOf("传输层安全协议"),
            listOf("FTP", "SMTP", "DHCP"), "HTTPS 通常在 HTTP 与传输层之间使用 TLS 提供加密、完整性和身份验证。"
        ),
        fact(
            "tech_ipv6_bits", KnowledgeCategory.TECHNOLOGY, KnowledgeDifficulty.CHALLENGING,
            "一个 IPv6 地址的长度是多少位？", "128 位", listOf("128bit", "128 bits"),
            listOf("32 位", "64 位", "256 位"), "IPv6 地址长度为 128 位，远大于 IPv4 的 32 位地址空间。"
        ),
        fact(
            "tech_open_source", KnowledgeCategory.TECHNOLOGY, KnowledgeDifficulty.CHALLENGING,
            "开源软件中的“开源”主要指什么？", "源代码按开源许可证公开", listOf("源代码依照开源许可证提供"),
            listOf("软件一定完全免费", "软件不受任何版权保护", "任何人都可删除原作者署名"),
            "开源是按开源许可证提供源代码及相应权利，并不等同于没有版权或必然免费。"
        )
    )

    private val factsById = facts.associateBy(KnowledgeFact::id)

    init {
        require(factsById.size == facts.size) { "离线百科事实 ID 不得重复" }
        KnowledgeCategory.entries.forEach { category ->
            require(facts.count { it.category == category } >= MIN_FACTS_PER_CATEGORY) {
                "${category.displayName}题库数量不足"
            }
        }
    }

    fun get(factId: String): KnowledgeFact? = factsById[factId]

    private fun fact(
        id: String,
        category: KnowledgeCategory,
        difficulty: KnowledgeDifficulty,
        stem: String,
        answer: String,
        aliases: List<String>,
        distractors: List<String>,
        explanation: String
    ) = KnowledgeFact(
        id = id,
        category = category,
        difficulty = difficulty,
        localStem = stem,
        canonicalAnswer = answer,
        acceptedAliases = aliases.toSet(),
        localDistractors = distractors,
        localExplanation = explanation
    )

    private const val MIN_FACTS_PER_CATEGORY = 6
}
