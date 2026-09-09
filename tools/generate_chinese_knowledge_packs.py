"""Generate and verify the six bundled Chinese knowledge packs."""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PACK_ROOT = ROOT / "app/src/main/assets/knowledge_packs/local"
QUESTION_COUNT = 500
PACK_VERSION = 2
PUBLISHED_AT_EPOCH_SECONDS = 1785024000
SIGNING_KEY_ID = "bundled_local_v1"
SIGNATURE = "CONTROLFREE_BUNDLED_LOCAL_V1"


@dataclass(frozen=True)
class PackSpec:
    category: str
    prefix: str
    title: str

    @property
    def package_id(self) -> str:
        return f"local_{self.category}_v1"

    @property
    def output(self) -> Path:
        return PACK_ROOT / self.category / "pack.json"


@dataclass(frozen=True)
class Fact:
    id: str
    category: str
    difficulty: int
    stem: str
    answer: str
    distractors: tuple[str, str, str]
    explanation: str


SPECS = (
    PackSpec("natural_science", "sci", "自然科学百科 500 题"),
    PackSpec("history_culture", "his", "历史文化百科 500 题"),
    PackSpec("geography", "geo", "地理百科 500 题"),
    PackSpec("literature_art", "art", "文学艺术百科 500 题"),
    PackSpec("life_knowledge", "life", "生活常识百科 500 题"),
    PackSpec("technology", "tech", "科技百科 500 题"),
)


class FactCollector:
    def __init__(self, spec: PackSpec) -> None:
        self.spec = spec
        self.facts: list[Fact] = []

    def add(
        self,
        stem: str,
        answer: str,
        distractors: list[str] | tuple[str, str, str],
        explanation: str,
    ) -> None:
        if len(self.facts) >= QUESTION_COUNT:
            return
        normalized = [str(value).strip() for value in distractors]
        if len(normalized) != 3:
            raise ValueError(f"题目必须有三个干扰项：{stem}")
        index = len(self.facts) + 1
        self.facts.append(
            Fact(
                id=f"zh_{self.spec.prefix}_{index:04d}",
                category=self.spec.category,
                difficulty=(index - 1) % 3 + 1,
                stem=stem.strip(),
                answer=str(answer).strip(),
                distractors=tuple(normalized),
                explanation=explanation.strip(),
            )
        )


def choices(answer: str, pool: list[str], seed: int) -> list[str]:
    candidates = list(dict.fromkeys(value for value in pool if value != answer))
    if len(candidates) < 3:
        raise ValueError(f"选项池不足：{answer}")
    start = (seed * 17 + 7) % len(candidates)
    rotated = candidates[start:] + candidates[:start]
    return rotated[:3]


def numeric_choices(answer: int, step: int = 1) -> list[str]:
    values = (answer + step, max(0, answer - step), answer + step * 2)
    result: list[str] = []
    for value in values:
        text = str(value)
        if text != str(answer) and text not in result:
            result.append(text)
    candidate = answer + step * 3
    while len(result) < 3:
        text = str(candidate)
        if text != str(answer) and text not in result:
            result.append(text)
        candidate += step
    return result


def add_relation_pairs(
    collector: FactCollector,
    records: list[tuple[str, str]],
    left_label: str,
    right_label: str,
) -> None:
    left_pool = [left for left, _ in records]
    right_pool = [right for _, right in records]
    right_counts = {right: right_pool.count(right) for right in set(right_pool)}
    for index, (left, right) in enumerate(records):
        collector.add(
            f"{left_label}“{left}”对应的{right_label}是什么？",
            right,
            choices(right, right_pool, index),
            f"{left}与{right}是这组百科关系中的正确对应项。",
        )
        if right_counts[right] == 1:
            collector.add(
                f"下列哪一项的{right_label}是“{right}”？",
                left,
                choices(left, left_pool, index + 31),
                f"在给出的选项中，{left}与{right}正确对应。",
            )


def add_cycle_questions(collector: FactCollector, label: str, values: list[str]) -> None:
    position_pool = [f"第{index + 1}个" for index in range(len(values))]
    for index, value in enumerate(values):
        collector.add(
            f"按{label}顺序，“{value}”之后是哪一项？",
            values[(index + 1) % len(values)],
            choices(values[(index + 1) % len(values)], values, index),
            f"{label}按固定顺序循环排列，{value}之后是{values[(index + 1) % len(values)]}。",
        )
        collector.add(
            f"按{label}顺序，“{value}”之前是哪一项？",
            values[(index - 1) % len(values)],
            choices(values[(index - 1) % len(values)], values, index + 13),
            f"{label}按固定顺序循环排列，{value}之前是{values[(index - 1) % len(values)]}。",
        )
        collector.add(
            f"“{value}”在{label}中排在什么位置？",
            position_pool[index],
            choices(position_pool[index], position_pool, index + 29),
            f"从该顺序的第一项开始计数，{value}排在{position_pool[index]}。",
        )


def build_natural_science(spec: PackSpec) -> list[Fact]:
    collector = FactCollector(spec)
    elements = [
        ("氢", "H"), ("氦", "He"), ("锂", "Li"), ("铍", "Be"),
        ("硼", "B"), ("碳", "C"), ("氮", "N"), ("氧", "O"),
        ("氟", "F"), ("氖", "Ne"), ("钠", "Na"), ("镁", "Mg"),
        ("铝", "Al"), ("硅", "Si"), ("磷", "P"), ("硫", "S"),
        ("氯", "Cl"), ("氩", "Ar"), ("钾", "K"), ("钙", "Ca"),
        ("钪", "Sc"), ("钛", "Ti"), ("钒", "V"), ("铬", "Cr"),
        ("锰", "Mn"), ("铁", "Fe"), ("钴", "Co"), ("镍", "Ni"),
        ("铜", "Cu"), ("锌", "Zn"), ("镓", "Ga"), ("锗", "Ge"),
        ("砷", "As"), ("硒", "Se"), ("溴", "Br"), ("氪", "Kr"),
        ("铷", "Rb"), ("锶", "Sr"), ("钇", "Y"), ("锆", "Zr"),
        ("铌", "Nb"), ("钼", "Mo"), ("锝", "Tc"), ("钌", "Ru"),
        ("铑", "Rh"), ("钯", "Pd"), ("银", "Ag"), ("镉", "Cd"),
        ("铟", "In"), ("锡", "Sn"), ("锑", "Sb"), ("碲", "Te"),
        ("碘", "I"), ("氙", "Xe"),
    ]
    names = [name for name, _ in elements]
    symbols = [symbol for _, symbol in elements]
    atomic_numbers = [str(index + 1) for index in range(len(elements))]
    for index, (name, symbol) in enumerate(elements):
        number = index + 1
        collector.add(
            f"元素“{name}”的化学符号是什么？",
            symbol,
            choices(symbol, symbols, index),
            f"{name}的国际通用化学符号是 {symbol}。",
        )
        collector.add(
            f"化学符号“{symbol}”代表哪种元素？",
            name,
            choices(name, names, index + 11),
            f"元素符号 {symbol} 对应的中文名称是{name}。",
        )
        collector.add(
            f"{name}元素的原子序数是多少？",
            str(number),
            choices(str(number), atomic_numbers, index + 19),
            f"元素周期表中{name}的原子序数是{number}。",
        )
        collector.add(
            f"原子序数为{number}的元素是哪一种？",
            name,
            choices(name, names, index + 23),
            f"原子序数{number}在元素周期表中对应{name}。",
        )

    planets = ["水星", "金星", "地球", "火星", "木星", "土星", "天王星", "海王星"]
    for index, planet in enumerate(planets):
        collector.add(
            f"从太阳向外数，{planet}是第几颗行星？",
            str(index + 1),
            choices(str(index + 1), [str(i) for i in range(1, 9)], index),
            f"太阳系八大行星按距离太阳由近到远排列时，{planet}位列第{index + 1}。",
        )
        collector.add(
            f"太阳系第{index + 1}颗行星是哪一颗？",
            planet,
            choices(planet, planets, index + 17),
            f"按距离太阳由近到远的顺序，第{index + 1}颗行星是{planet}。",
        )

    relations = [
        ("水的化学式", "H₂O"), ("二氧化碳的化学式", "CO₂"),
        ("氧气的化学式", "O₂"), ("氮气的化学式", "N₂"),
        ("食盐主要成分的化学式", "NaCl"), ("甲烷的化学式", "CH₄"),
        ("太阳系最大行星", "木星"), ("太阳系最小行星", "水星"),
        ("地球唯一的天然卫星", "月球"), ("红色星球", "火星"),
        ("人体最大的器官", "皮肤"), ("植物光合作用主要吸收的气体", "二氧化碳"),
        ("遗传信息的主要载体", "DNA"), ("血液中运输氧的细胞", "红细胞"),
        ("细胞控制中心", "细胞核"), ("植物细胞进行光合作用的结构", "叶绿体"),
        ("力的国际单位", "牛顿"), ("功率的国际单位", "瓦特"),
        ("电流的国际单位", "安培"), ("温度的国际单位", "开尔文"),
    ]
    add_relation_pairs(collector, relations, "科学概念", "对应答案")

    value = 1
    while len(collector.facts) < QUESTION_COUNT:
        family = len(collector.facts) % 4
        if family == 0:
            answer = value * 1000
            stem = f"{value}千米等于多少米？"
            explanation = f"1千米等于1000米，所以{value}千米等于{answer}米。"
        elif family == 1:
            answer = value * 100
            stem = f"{value}米等于多少厘米？"
            explanation = f"1米等于100厘米，所以{value}米等于{answer}厘米。"
        elif family == 2:
            answer = value * 1000
            stem = f"{value}千克等于多少克？"
            explanation = f"1千克等于1000克，所以{value}千克等于{answer}克。"
        else:
            answer = value * 1000
            stem = f"{value}升等于多少毫升？"
            explanation = f"1升等于1000毫升，所以{value}升等于{answer}毫升。"
            value += 1
        collector.add(stem, str(answer), numeric_choices(answer, max(1, answer // 10)), explanation)
    return collector.facts


def build_history_culture(spec: PackSpec) -> list[Fact]:
    collector = FactCollector(spec)
    add_cycle_questions(
        collector,
        "二十四节气",
        [
            "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
            "立夏", "小满", "芒种", "夏至", "小暑", "大暑",
            "立秋", "处暑", "白露", "秋分", "寒露", "霜降",
            "立冬", "小雪", "大雪", "冬至", "小寒", "大寒",
        ],
    )
    add_cycle_questions(collector, "十天干", list("甲乙丙丁戊己庚辛壬癸"))
    add_cycle_questions(collector, "十二地支", ["子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"])
    add_cycle_questions(collector, "十二生肖", ["鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"])

    works = [
        ("《史记》", "司马迁"), ("《汉书》", "班固"), ("《资治通鉴》", "司马光"),
        ("《三国志》", "陈寿"), ("《后汉书》", "范晔"), ("《齐民要术》", "贾思勰"),
        ("《水经注》", "郦道元"), ("《梦溪笔谈》", "沈括"), ("《天工开物》", "宋应星"),
        ("《农政全书》", "徐光启"), ("《本草纲目》", "李时珍"), ("《九章算术注》", "刘徽"),
        ("《营造法式》", "李诫"), ("《文心雕龙》", "刘勰"), ("《诗品》", "钟嵘"),
        ("《洛阳伽蓝记》", "杨衒之"), ("《东京梦华录》", "孟元老"), ("《徐霞客游记》", "徐霞客"),
        ("《海国图志》", "魏源"), ("《大同书》", "康有为"), ("《日知录》", "顾炎武"),
        ("《明夷待访录》", "黄宗羲"), ("《读通鉴论》", "王夫之"), ("《茶经》", "陆羽"),
        ("《考工记》", "佚名"), ("《说文解字》", "许慎"), ("《伤寒杂病论》", "张仲景"),
        ("《千金方》", "孙思邈"), ("《缀术》", "祖冲之"), ("《授时历》", "郭守敬"),
    ]
    add_relation_pairs(collector, works, "典籍", "作者")

    festivals = [
        ("春节", "农历正月初一"), ("元宵节", "农历正月十五"),
        ("龙抬头", "农历二月初二"), ("上巳节", "农历三月初三"),
        ("端午节", "农历五月初五"), ("七夕节", "农历七月初七"),
        ("中元节", "农历七月十五"), ("中秋节", "农历八月十五"),
        ("重阳节", "农历九月初九"), ("腊八节", "农历腊月初八"),
        ("小年", "农历腊月二十三或二十四"), ("除夕", "农历一年最后一夜"),
    ]
    add_relation_pairs(collector, festivals, "传统节日", "通常日期")

    events = [
        ("秦完成统一", "公元前221年"), ("西汉建立", "公元前202年"),
        ("东汉建立", "公元25年"), ("隋朝建立", "公元581年"),
        ("唐朝建立", "公元618年"), ("北宋建立", "公元960年"),
        ("元朝定国号", "公元1271年"), ("明朝建立", "公元1368年"),
        ("郑和首次下西洋", "公元1405年"), ("清朝入关", "公元1644年"),
        ("虎门销烟", "公元1839年"), ("辛亥革命爆发", "公元1911年"),
        ("五四运动", "公元1919年"), ("中国共产党成立", "公元1921年"),
        ("中华人民共和国成立", "公元1949年"),
    ]
    add_relation_pairs(collector, events, "历史事件", "发生年份")

    year = 1
    while len(collector.facts) < QUESTION_COUNT:
        century = (year - 1) // 100 + 1
        answer = f"第{century}世纪"
        pool = [f"第{value}世纪" for value in range(1, 22)]
        collector.add(
            f"公元{year}年属于哪个世纪？",
            answer,
            choices(answer, pool, year),
            f"公元年份每一百年划分为一个世纪，因此公元{year}年属于{answer}。",
        )
        year += 4
    return collector.facts


def parse_table(raw: str, width: int) -> list[tuple[str, ...]]:
    records = [tuple(part.strip() for part in line.split("|")) for line in raw.strip().splitlines()]
    if any(len(record) != width or any(not part for part in record) for record in records):
        raise ValueError("百科关系表存在空字段或列数错误")
    return records


def build_geography(spec: PackSpec) -> list[Fact]:
    collector = FactCollector(spec)
    provinces = parse_table(
        """
北京|北京|京
天津|天津|津
河北|石家庄|冀
山西|太原|晋
内蒙古|呼和浩特|内蒙古
辽宁|沈阳|辽
吉林|长春|吉
黑龙江|哈尔滨|黑
上海|上海|沪
江苏|南京|苏
浙江|杭州|浙
安徽|合肥|皖
福建|福州|闽
江西|南昌|赣
山东|济南|鲁
河南|郑州|豫
湖北|武汉|鄂
湖南|长沙|湘
广东|广州|粤
广西|南宁|桂
海南|海口|琼
重庆|重庆|渝
四川|成都|川
贵州|贵阳|贵
云南|昆明|云
西藏|拉萨|藏
陕西|西安|陕
甘肃|兰州|甘
青海|西宁|青
宁夏|银川|宁
新疆|乌鲁木齐|新
香港|香港|港
澳门|澳门|澳
台湾|台北|台
""",
        3,
    )
    province_names = [record[0] for record in provinces]
    capitals = [record[1] for record in provinces]
    abbreviations = [record[2] for record in provinces]
    for index, (province, capital, abbreviation) in enumerate(provinces):
        collector.add(
            f"中国省级行政区“{province}”的行政中心是哪里？",
            capital,
            choices(capital, capitals, index),
            f"{province}的行政中心是{capital}。",
        )
        collector.add(
            f"{capital}是哪一个中国省级行政区的行政中心？",
            province,
            choices(province, province_names, index + 7),
            f"{capital}是{province}的行政中心。",
        )
        collector.add(
            f"中国省级行政区“{province}”常用简称是什么？",
            abbreviation,
            choices(abbreviation, abbreviations, index + 13),
            f"{province}常用的规范简称是“{abbreviation}”。",
        )
        collector.add(
            f"省级行政区简称“{abbreviation}”通常指哪里？",
            province,
            choices(province, province_names, index + 19),
            f"简称“{abbreviation}”通常对应{province}。",
        )

    countries = parse_table(
        """
中国|北京|亚洲
日本|东京|亚洲
韩国|首尔|亚洲
朝鲜|平壤|亚洲
蒙古|乌兰巴托|亚洲
印度|新德里|亚洲
巴基斯坦|伊斯兰堡|亚洲
孟加拉国|达卡|亚洲
尼泊尔|加德满都|亚洲
不丹|廷布|亚洲
缅甸|内比都|亚洲
泰国|曼谷|亚洲
越南|河内|亚洲
老挝|万象|亚洲
柬埔寨|金边|亚洲
马来西亚|吉隆坡|亚洲
新加坡|新加坡|亚洲
菲律宾|马尼拉|亚洲
文莱|斯里巴加湾市|亚洲
东帝汶|帝力|亚洲
哈萨克斯坦|阿斯塔纳|亚洲
乌兹别克斯坦|塔什干|亚洲
吉尔吉斯斯坦|比什凯克|亚洲
塔吉克斯坦|杜尚别|亚洲
伊朗|德黑兰|亚洲
伊拉克|巴格达|亚洲
沙特阿拉伯|利雅得|亚洲
阿联酋|阿布扎比|亚洲
卡塔尔|多哈|亚洲
科威特|科威特城|亚洲
英国|伦敦|欧洲
法国|巴黎|欧洲
德国|柏林|欧洲
意大利|罗马|欧洲
西班牙|马德里|欧洲
葡萄牙|里斯本|欧洲
荷兰|阿姆斯特丹|欧洲
比利时|布鲁塞尔|欧洲
瑞士|伯尔尼|欧洲
奥地利|维也纳|欧洲
波兰|华沙|欧洲
捷克|布拉格|欧洲
斯洛伐克|布拉迪斯拉发|欧洲
匈牙利|布达佩斯|欧洲
罗马尼亚|布加勒斯特|欧洲
保加利亚|索非亚|欧洲
希腊|雅典|欧洲
瑞典|斯德哥尔摩|欧洲
挪威|奥斯陆|欧洲
芬兰|赫尔辛基|欧洲
丹麦|哥本哈根|欧洲
冰岛|雷克雅未克|欧洲
爱尔兰|都柏林|欧洲
乌克兰|基辅|欧洲
白俄罗斯|明斯克|欧洲
立陶宛|维尔纽斯|欧洲
拉脱维亚|里加|欧洲
爱沙尼亚|塔林|欧洲
克罗地亚|萨格勒布|欧洲
塞尔维亚|贝尔格莱德|欧洲
埃及|开罗|非洲
阿尔及利亚|阿尔及尔|非洲
摩洛哥|拉巴特|非洲
突尼斯|突尼斯|非洲
利比亚|的黎波里|非洲
埃塞俄比亚|亚的斯亚贝巴|非洲
肯尼亚|内罗毕|非洲
坦桑尼亚|多多马|非洲
乌干达|坎帕拉|非洲
卢旺达|基加利|非洲
加纳|阿克拉|非洲
尼日利亚|阿布贾|非洲
塞内加尔|达喀尔|非洲
安哥拉|罗安达|非洲
纳米比亚|温得和克|非洲
博茨瓦纳|哈博罗内|非洲
津巴布韦|哈拉雷|非洲
赞比亚|卢萨卡|非洲
莫桑比克|马普托|非洲
马达加斯加|塔那那利佛|非洲
加拿大|渥太华|北美洲
美国|华盛顿|北美洲
墨西哥|墨西哥城|北美洲
古巴|哈瓦那|北美洲
牙买加|金斯敦|北美洲
巴拿马|巴拿马城|北美洲
哥斯达黎加|圣何塞|北美洲
危地马拉|危地马拉城|北美洲
巴西|巴西利亚|南美洲
阿根廷|布宜诺斯艾利斯|南美洲
智利|圣地亚哥|南美洲
秘鲁|利马|南美洲
哥伦比亚|波哥大|南美洲
厄瓜多尔|基多|南美洲
乌拉圭|蒙得维的亚|南美洲
巴拉圭|亚松森|南美洲
委内瑞拉|加拉加斯|南美洲
澳大利亚|堪培拉|大洋洲
新西兰|惠灵顿|大洋洲
斐济|苏瓦|大洋洲
巴布亚新几内亚|莫尔兹比港|大洋洲
萨摩亚|阿皮亚|大洋洲
汤加|努库阿洛法|大洋洲
""",
        3,
    )
    country_names = [record[0] for record in countries]
    country_capitals = [record[1] for record in countries]
    continents = ["亚洲", "欧洲", "非洲", "北美洲", "南美洲", "大洋洲"]
    for index, (country, capital, continent) in enumerate(countries):
        collector.add(
            f"国家“{country}”的首都是哪座城市？",
            capital,
            choices(capital, country_capitals, index),
            f"{country}的首都是{capital}。",
        )
        collector.add(
            f"{capital}是哪一个国家的首都？",
            country,
            choices(country, country_names, index + 23),
            f"{capital}是{country}的首都。",
        )
        collector.add(
            f"国家“{country}”主要位于哪个大洲？",
            continent,
            choices(continent, continents, index + 37),
            f"按通常的洲际划分，{country}主要位于{continent}。",
        )

    physical = [
        ("世界面积最大的海洋", "太平洋"), ("世界面积最小的海洋", "北冰洋"),
        ("世界面积最大的大洲", "亚洲"), ("世界面积最小的大洲", "大洋洲"),
        ("世界最高峰", "珠穆朗玛峰"), ("世界最深的海沟", "马里亚纳海沟"),
        ("世界面积最大的热沙漠", "撒哈拉沙漠"), ("世界水量最大的河流", "亚马孙河"),
        ("中国最长的河流", "长江"), ("中国面积最大的淡水湖", "鄱阳湖"),
        ("中国面积最大的咸水湖", "青海湖"), ("本初子午线经过的英国地点", "格林尼治"),
        ("赤道纬度", "0度"), ("北极点纬度", "北纬90度"),
        ("南极点纬度", "南纬90度"), ("地球自转方向", "自西向东"),
    ]
    add_relation_pairs(collector, physical, "地理概念", "对应答案")

    coordinate_index = 0
    while len(collector.facts) < QUESTION_COUNT:
        family = coordinate_index % 8
        coordinate = coordinate_index // 8 % 89 + 1
        if family == 0:
            stem = f"纬度标注为北纬{coordinate}度的地点位于哪个纬度半球？"
            answer = "北半球"
            distractors = choices(answer, ["东半球", "西半球", "南半球", "北半球"], coordinate)
            explanation = "北纬表示位于赤道以北的纬度范围，因此该地点位于北半球。"
        elif family == 1:
            stem = f"纬度标注为南纬{coordinate}度的地点位于哪个纬度半球？"
            answer = "南半球"
            distractors = choices(answer, ["东半球", "西半球", "南半球", "北半球"], coordinate)
            explanation = "南纬表示位于赤道以南的纬度范围，因此该地点位于南半球。"
        elif family == 2:
            stem = f"东经{coordinate}度表示该经线位于本初子午线的哪一侧？"
            answer = "以东"
            distractors = ["以西", "以北", "以南"]
            explanation = "东经用于标记本初子午线以东的经度。"
        elif family == 3:
            stem = f"西经{coordinate}度表示该经线位于本初子午线的哪一侧？"
            answer = "以西"
            distractors = ["以东", "以北", "以南"]
            explanation = "西经用于标记本初子午线以西的经度。"
        elif family == 4:
            stem = f"北纬{coordinate}度与赤道相差多少个纬度？"
            answer = f"{coordinate}度"
            distractors = [f"{value}度" for value in numeric_choices(coordinate)]
            explanation = f"赤道纬度是0度，因此北纬{coordinate}度与赤道相差{coordinate}度。"
        elif family == 5:
            stem = f"南纬{coordinate}度与赤道相差多少个纬度？"
            answer = f"{coordinate}度"
            distractors = [f"{value}度" for value in numeric_choices(coordinate)]
            explanation = f"赤道纬度是0度，因此南纬{coordinate}度与赤道相差{coordinate}度。"
        elif family == 6:
            difference = coordinate * 2
            stem = f"北纬{coordinate}度与南纬{coordinate}度相差多少个纬度？"
            answer = f"{difference}度"
            distractors = [f"{value}度" for value in numeric_choices(difference)]
            explanation = f"两地分处赤道两侧，纬度差为{coordinate}加{coordinate}，即{difference}度。"
        else:
            difference = coordinate * 2
            stem = f"东经{coordinate}度与西经{coordinate}度的经度差是多少？"
            answer = f"{difference}度"
            distractors = [f"{value}度" for value in numeric_choices(difference)]
            explanation = f"两条经线分处本初子午线两侧，经度差为{coordinate}加{coordinate}，即{difference}度。"
        collector.add(stem, answer, distractors, explanation)
        coordinate_index += 1
    return collector.facts


def build_literature_art(spec: PackSpec) -> list[Fact]:
    collector = FactCollector(spec)
    works = parse_table(
        """
《红楼梦》|曹雪芹
《三国演义》|罗贯中
《水浒传》|施耐庵
《西游记》|吴承恩
《儒林外史》|吴敬梓
《聊斋志异》|蒲松龄
《呐喊》|鲁迅
《边城》|沈从文
《围城》|钱钟书
《骆驼祥子》|老舍
《家》|巴金
《雷雨》|曹禺
《女神》|郭沫若
《子夜》|茅盾
《平凡的世界》|路遥
《白鹿原》|陈忠实
《尘埃落定》|阿来
《繁星》|冰心
《荷花淀》|孙犁
《青春之歌》|杨沫
《林海雪原》|曲波
《红岩》|罗广斌、杨益言
《创业史》|柳青
《呼兰河传》|萧红
《沉沦》|郁达夫
《金粉世家》|张恨水
《太阳照在桑干河上》|丁玲
《暴风骤雨》|周立波
《保卫延安》|杜鹏程
《哈姆雷特》|莎士比亚
《浮士德》|歌德
《巴黎圣母院》|雨果
《双城记》|狄更斯
《战争与和平》|托尔斯泰
《罪与罚》|陀思妥耶夫斯基
《老人与海》|海明威
《百年孤独》|马尔克斯
《堂吉诃德》|塞万提斯
《神曲》|但丁
《伊利亚特》|荷马
《玩偶之家》|易卜生
《变形记》|卡夫卡
《包法利夫人》|福楼拜
《红与黑》|司汤达
《简爱》|夏洛蒂·勃朗特
《呼啸山庄》|艾米莉·勃朗特
《傲慢与偏见》|简·奥斯汀
《鲁滨逊漂流记》|笛福
《格列佛游记》|斯威夫特
《叶甫盖尼·奥涅金》|普希金
《静静的顿河》|肖洛霍夫
《母亲》|高尔基
《局外人》|加缪
《追忆似水年华》|普鲁斯特
《尤利西斯》|乔伊斯
《瓦尔登湖》|梭罗
《草叶集》|惠特曼
《恶之花》|波德莱尔
《等待戈多》|贝克特
《秃头歌女》|尤奈斯库
《铁皮鼓》|君特·格拉斯
《雪国》|川端康成
《源氏物语》|紫式部
《我是猫》|夏目漱石
《罗生门》|芥川龙之介
《挪威的森林》|村上春树
《飞鸟集》|泰戈尔
""",
        2,
    )
    add_relation_pairs(collector, works, "文学作品", "作者")

    paintings = parse_table(
        """
《清明上河图》|张择端
《洛神赋图》|顾恺之
《步辇图》|阎立本
《韩熙载夜宴图》|顾闳中
《千里江山图》|王希孟
《富春山居图》|黄公望
《墨葡萄图》|徐渭
《虾》|齐白石
《奔马图》|徐悲鸿
《开国大典》|董希文
《蒙娜丽莎》|达·芬奇
《星月夜》|梵高
《印象·日出》|莫奈
《格尔尼卡》|毕加索
《记忆的永恒》|达利
《呐喊》|蒙克
《拾穗者》|米勒
《自由引导人民》|德拉克洛瓦
《夜巡》|伦勃朗
《宫娥》|委拉斯开兹
《雅典学院》|拉斐尔
《维纳斯的诞生》|波提切利
《大碗岛的星期日下午》|修拉
《舞蹈》|马蒂斯
《戴珍珠耳环的少女》|维米尔
《美国哥特式》|格兰特·伍德
《坎贝尔汤罐头》|安迪·沃霍尔
""",
        2,
    )
    add_relation_pairs(collector, paintings, "绘画作品", "画家")

    music = parse_table(
        """
《命运交响曲》|贝多芬
《魔笛》|莫扎特
《未完成交响曲》|舒伯特
《蓝色多瑙河》|小约翰·施特劳斯
《四季》|维瓦尔第
《卡门》|比才
《天鹅湖》|柴可夫斯基
《月光》|克洛德·德彪西
《波莱罗舞曲》|拉威尔
《新世界交响曲》|德沃夏克
《匈牙利舞曲》|勃拉姆斯
《芬兰颂》|西贝柳斯
《威廉·退尔序曲》|罗西尼
《图兰朵》|普契尼
《茶花女》|威尔第
《春江花月夜》|柳尧章整理
《二泉映月》|华彦钧
《梁山伯与祝英台》|何占豪、陈钢
《黄河大合唱》|冼星海
《义勇军进行曲》|聂耳
《春节序曲》|李焕之
《彩云追月》|任光
《赛马》|黄海怀
《瑶族舞曲》|刘铁山、茅沅
《牧童短笛》|贺绿汀
《长征交响曲》|丁善德
《红旗颂》|吕其明
""",
        2,
    )
    add_relation_pairs(collector, music, "音乐作品", "作曲者")

    concepts = [
        ("十四行诗通常包含的诗行数", "14行"), ("五言绝句通常包含的句数", "4句"),
        ("七言律诗通常包含的句数", "8句"), ("交响曲通常由什么团体演奏", "管弦乐队"),
        ("芭蕾舞主要使用的艺术媒介", "舞蹈动作"), ("版画创作的重要步骤", "制版"),
        ("书法中的楷书特点", "字形端正规整"), ("中国画常用的主要材料", "笔墨纸砚"),
        ("透视法主要表现的视觉效果", "空间深度"), ("戏剧表演的核心载体", "演员行动"),
    ]
    add_relation_pairs(collector, concepts, "艺术知识", "对应答案")

    midi = 21
    note_names = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]
    while len(collector.facts) < QUESTION_COUNT and midi <= 127:
        octave = midi // 12 - 1
        note = f"{note_names[midi % 12]}{octave}"
        pool = [f"{name}{octave}" for name in note_names]
        collector.add(
            f"按国际通用MIDI编号，音高{midi}对应哪个音名？",
            note,
            choices(note, pool, midi),
            f"MIDI音高编号以半音递增，编号{midi}对应音名{note}。",
        )
        midi += 1

    art_relations = (
        [("文学作品", item, creator) for item, creator in works] +
        [("绘画作品", item, creator) for item, creator in paintings] +
        [("音乐作品", item, creator) for item, creator in music]
    )
    creator_pool = [creator for _, _, creator in art_relations]
    templates = [
        "文学艺术知识：{kind}{item}的创作者是谁？",
        "考察{kind}{item}：应选择哪位创作者？",
        "下列人物中，谁创作了{kind}{item}？",
        "{kind}{item}出自哪位创作者？",
        "为{kind}{item}匹配创作者，正确的是谁？",
        "{kind}{item}的创作者姓名是什么？",
        "关于{kind}{item}的归属，哪位创作者正确？",
        "识别{kind}{item}，其创作者是谁？",
    ]
    relation_index = 0
    while len(collector.facts) < QUESTION_COUNT:
        kind, item, creator = art_relations[relation_index % len(art_relations)]
        template = templates[relation_index // len(art_relations) % len(templates)]
        collector.add(
            template.format(kind=kind, item=item),
            creator,
            choices(creator, creator_pool, relation_index + 41),
            f"{kind}{item}的创作者是{creator}。",
        )
        relation_index += 1
    return collector.facts


def build_life_knowledge(spec: PackSpec) -> list[Fact]:
    collector = FactCollector(spec)
    safety = [
        ("发现油锅起火", "关火并盖上锅盖"),
        ("电器冒烟且能够安全操作", "先切断电源"),
        ("闻到室内燃气异味", "关阀通风并离开"),
        ("怀疑一氧化碳泄漏", "通风并转移到室外"),
        ("发现有人触电", "先切断电源"),
        ("身上衣物着火", "就地打滚压灭火焰"),
        ("火灾中穿过有烟区域", "低姿前进并尽快撤离"),
        ("高层建筑发生火灾", "走疏散楼梯撤离"),
        ("灭火器压力表指针在绿色区域", "通常表示压力正常"),
        ("乘车时保护自己的基本做法", "全程系好安全带"),
        ("骑电动自行车出行", "规范佩戴安全头盔"),
        ("夜间步行经过无灯路段", "穿戴醒目反光物品"),
        ("雷雨时身处空旷地带", "远离孤立高物并降低身体"),
        ("遇到地震强烈晃动", "就近避险并护住头部"),
        ("电梯突然停止运行", "使用报警装置求助"),
        ("发现未成年人落水", "呼救并使用漂浮物施救"),
        ("在陌生水域游泳", "先确认安全条件和救生设施"),
        ("长时间离家前", "检查水电燃气是否关闭"),
        ("使用插线板", "避免超过额定功率"),
        ("更换灯泡前", "先断开电源"),
        ("湿手接触电器开关", "应先擦干双手"),
        ("手机电池明显鼓包", "停止使用并规范处理"),
        ("收到陌生验证码请求", "不要向他人提供验证码"),
        ("多个网站需要设置密码", "使用不同的高强度密码"),
        ("公共电脑登录个人账号后", "退出账号并清除登录状态"),
        ("收到来源不明的文件链接", "核实来源后再处理"),
        ("银行卡突然出现异常交易", "立即联系银行并冻结风险"),
        ("快递面单准备丢弃", "先遮盖个人信息"),
        ("在公共无线网络处理敏感业务", "优先改用可信网络"),
        ("发现身份证件遗失", "及时挂失并补办"),
        ("切生肉和即食食品", "使用不同刀具和砧板"),
        ("熟食在室温放置过久", "不要仅凭加热判断安全"),
        ("冰箱储存生熟食品", "分层密封并避免交叉污染"),
        ("食品包装已经明显胀袋", "停止食用并妥善处理"),
        ("购买预包装食品", "检查保质期和储存条件"),
        ("清洁剂需要存放", "远离食品并保留原标签"),
        ("使用含氯清洁剂", "不要与酸性清洁剂混用"),
        ("玻璃杯刚盛过热水", "避免立刻加入冰水"),
        ("厨房刀具暂时不用", "放在稳定且不易触碰处"),
        ("搬动较重物品", "屈膝并让重物贴近身体"),
    ]
    add_relation_pairs(collector, safety, "生活场景", "较合适的做法")

    household = [
        ("标准大气压下水的沸点", "100摄氏度"),
        ("标准大气压下水的冰点", "0摄氏度"),
        ("一打物品通常包含", "12个"),
        ("一年通常包含的月份数", "12个月"),
        ("平年通常包含的天数", "365天"),
        ("闰年通常包含的天数", "366天"),
        ("一周包含的天数", "7天"),
        ("一天包含的小时数", "24小时"),
        ("一小时包含的分钟数", "60分钟"),
        ("一分钟包含的秒数", "60秒"),
        ("人民币一元等于", "10角"),
        ("人民币一角等于", "10分"),
        ("长度一米等于", "100厘米"),
        ("质量一千克等于", "1000克"),
        ("容积一升等于", "1000毫升"),
        ("直角的角度", "90度"),
        ("平角的角度", "180度"),
        ("圆周的角度", "360度"),
        ("中国大陆火警电话", "119"),
        ("中国大陆急救电话", "120"),
        ("中国大陆报警电话", "110"),
        ("中国大陆交通事故报警电话", "122"),
        ("绿色交通信号灯通常表示", "允许通行"),
        ("黄色交通信号灯通常表示", "警示并准备停止"),
        ("红色交通信号灯通常表示", "禁止通行"),
        ("可回收物常用标志颜色", "蓝色"),
        ("有害垃圾常用标志颜色", "红色"),
        ("厨余垃圾常用标志颜色", "绿色"),
        ("干电池正极常见标记", "+号"),
        ("干电池负极常见标记", "-号"),
    ]
    add_relation_pairs(collector, household, "生活常识", "对应答案")

    value = 1
    while len(collector.facts) < QUESTION_COUNT:
        family = len(collector.facts) % 8
        if family == 0:
            answer, stem, unit = value * 1000, f"日常换算：{value}千米等于多少米？", "千米与米"
        elif family == 1:
            answer, stem, unit = value * 100, f"日常换算：{value}米等于多少厘米？", "米与厘米"
        elif family == 2:
            answer, stem, unit = value * 1000, f"日常换算：{value}千克等于多少克？", "千克与克"
        elif family == 3:
            answer, stem, unit = value * 1000, f"日常换算：{value}升等于多少毫升？", "升与毫升"
        elif family == 4:
            answer, stem, unit = value * 60, f"日常换算：{value}小时等于多少分钟？", "小时与分钟"
        elif family == 5:
            answer, stem, unit = value * 24, f"日常换算：{value}天等于多少小时？", "天与小时"
        elif family == 6:
            answer, stem, unit = value * 7, f"日常换算：{value}周等于多少天？", "周与天"
        else:
            answer, stem, unit = value * 10, f"日常换算：人民币{value}元等于多少角？", "元与角"
            value += 1
        collector.add(
            stem,
            str(answer),
            numeric_choices(answer, max(1, answer // 10)),
            f"按照{unit}的固定换算关系，计算结果是{answer}。",
        )
    return collector.facts


def build_technology(spec: PackSpec) -> list[Fact]:
    collector = FactCollector(spec)
    acronyms = parse_table(
        """
CPU|中央处理器
GPU|图形处理器
RAM|随机存取存储器
ROM|只读存储器
SSD|固态硬盘
HDD|机械硬盘
USB|通用串行总线
HDMI|高清多媒体接口
PDF|便携式文档格式
HTML|超文本标记语言
CSS|层叠样式表
HTTP|超文本传输协议
HTTPS|安全超文本传输协议
URL|统一资源定位符
URI|统一资源标识符
DNS|域名系统
IP|网际协议
TCP|传输控制协议
UDP|用户数据报协议
LAN|局域网
WAN|广域网
VPN|虚拟专用网络
API|应用程序编程接口
SDK|软件开发工具包
IDE|集成开发环境
GUI|图形用户界面
CLI|命令行界面
SQL|结构化查询语言
DBMS|数据库管理系统
JSON|JavaScript对象表示法
XML|可扩展标记语言
CSV|逗号分隔值
ASCII|美国信息交换标准代码
Unicode|统一码
AI|人工智能
ML|机器学习
NLP|自然语言处理
OCR|光学字符识别
GPS|全球定位系统
QR|二维码
RFID|射频识别
NFC|近场通信
IoT|物联网
CDN|内容分发网络
BIOS|基本输入输出系统
UEFI|统一可扩展固件接口
OS|操作系统
VM|虚拟机
JVM|Java虚拟机
TLS|传输层安全协议
""",
        2,
    )
    add_relation_pairs(collector, acronyms, "技术缩写", "中文名称")

    ports = parse_table(
        """
HTTP|80
HTTPS|443
FTP|21
SSH|22
Telnet|23
SMTP|25
DNS|53
DHCP|67
TFTP|69
POP3|110
NTP|123
IMAP|143
SNMP|161
LDAP|389
SMB|445
SMTPS|465
Syslog|514
LDAPS|636
IMAPS|993
POP3S|995
""",
        2,
    )
    add_relation_pairs(collector, ports, "常见网络协议", "默认端口")

    concepts = [
        ("二进制使用的基本数字", "0和1"), ("十六进制使用的数字和字母范围", "0到9及A到F"),
        ("一个字节通常包含的位数", "8位"), ("IPv4地址的长度", "32位"),
        ("IPv6地址的长度", "128位"), ("HTTPS保护传输常用的协议", "TLS"),
        ("网页结构主要使用的语言", "HTML"), ("网页样式主要使用的语言", "CSS"),
        ("关系数据库常用查询语言", "SQL"), ("版本控制系统Git的主要用途", "跟踪代码变更"),
        ("操作系统管理的核心资源", "硬件与软件资源"), ("编译器的主要作用", "翻译程序源代码"),
        ("路由器的主要网络功能", "转发不同网络的数据包"), ("交换机的常见局域网功能", "转发以太网帧"),
        ("防火墙的主要作用", "控制网络访问流量"), ("备份的主要目的", "降低数据丢失风险"),
        ("哈希函数的典型输出", "固定长度摘要"), ("对称加密的密钥特点", "加解密使用同一密钥"),
        ("公钥加密使用的密钥数量", "一对密钥"), ("数据库主键的主要作用", "唯一标识记录"),
        ("缓存的主要作用", "减少重复读取延迟"), ("虚拟机运行依赖的抽象层", "虚拟化平台"),
        ("开源软件公开的核心内容", "许可证约束下的源代码"), ("二维码属于的条码类型", "二维条码"),
        ("触摸屏常见的输入方式", "手指或触控笔"), ("光纤传输信息使用的载体", "光信号"),
        ("卫星导航定位需要的信号来源", "多颗导航卫星"), ("云计算按需提供的核心资源", "计算存储与网络资源"),
        ("机器学习模型训练所使用的样本集合", "训练数据"), ("软件补丁的主要用途", "修复缺陷或安全问题"),
    ]
    add_relation_pairs(collector, concepts, "科技概念", "对应答案")

    number = 1
    while len(collector.facts) < QUESTION_COUNT:
        binary = format(number, "b")
        hex_value = format(number, "X")
        binary_pool = [format(value, "b") for value in range(max(0, number - 6), number + 7)]
        hex_pool = [format(value, "X") for value in range(max(0, number - 6), number + 7)]
        collector.add(
            f"十进制整数{number}转换为二进制是多少？",
            binary,
            choices(binary, binary_pool, number),
            f"按二进制位权展开，十进制{number}写作二进制{binary}。",
        )
        collector.add(
            f"十进制整数{number}转换为十六进制是多少？",
            hex_value,
            choices(hex_value, hex_pool, number + 9),
            f"按十六进制位权换算，十进制{number}写作十六进制{hex_value}。",
        )
        number += 1
    return collector.facts


BUILDERS = {
    "natural_science": build_natural_science,
    "history_culture": build_history_culture,
    "geography": build_geography,
    "literature_art": build_literature_art,
    "life_knowledge": build_life_knowledge,
    "technology": build_technology,
}


def append_field(parts: list[str], name: str, value: str) -> None:
    parts.append(f"{len(name)}:{name}={len(value.encode('utf-8'))}:{value}\n")


def canonical_hash(metadata: dict[str, object], facts: list[dict[str, object]]) -> str:
    parts: list[str] = []
    append_field(parts, "schema", "1")
    append_field(parts, "package_id", str(metadata["package_id"]))
    append_field(parts, "title", str(metadata["title"]))
    append_field(parts, "version", str(metadata["version"]))
    append_field(parts, "locale", str(metadata["locale"]))
    append_field(parts, "published_at", str(metadata["published_at_epoch_seconds"]))
    append_field(parts, "minimum_app_version", str(metadata["minimum_app_version_code"]))
    for content_type in sorted(metadata["content_types"]):
        append_field(parts, "content_type", content_type)
    for fact in sorted(facts, key=lambda item: item["id"]):
        append_field(parts, "id", fact["id"])
        append_field(parts, "category", fact["category"])
        append_field(parts, "difficulty", str(fact["difficulty"]))
        append_field(parts, "stem", fact["stem"])
        append_field(parts, "answer", fact["answer"])
        for alias in sorted(fact["accepted_aliases"]):
            append_field(parts, "alias", alias)
        for distractor in fact["distractors"]:
            append_field(parts, "distractor", distractor)
        append_field(parts, "explanation", fact["explanation"])
    return hashlib.sha256("".join(parts).encode("utf-8")).hexdigest()


def fact_dict(fact: Fact) -> dict[str, object]:
    return {
        "id": fact.id,
        "category": fact.category,
        "difficulty": fact.difficulty,
        "stem": fact.stem,
        "answer": fact.answer,
        "accepted_aliases": [],
        "distractors": list(fact.distractors),
        "explanation": fact.explanation,
    }


def normalized(value: str) -> str:
    return "".join(value.casefold().split())


def validate_packs(packs: list[tuple[PackSpec, dict[str, object], str]]) -> None:
    ids: set[str] = set()
    stems: set[str] = set()
    for spec, pack, _ in packs:
        facts = pack["facts"]
        if len(facts) != QUESTION_COUNT:
            raise ValueError(f"{spec.title}题数不是{QUESTION_COUNT}")
        for fact in facts:
            if fact["category"] != spec.category:
                raise ValueError(f"题目分类错误：{fact['id']}")
            if fact["id"] in ids or fact["stem"] in stems:
                raise ValueError(f"跨包题目重复：{fact['id']} {fact['stem']}")
            if "英文单词" in fact["stem"] or not any("\u3400" <= char <= "\u9fff" for char in fact["stem"]):
                raise ValueError(f"中文百科题型错误：{fact['id']}")
            if not 6 <= len(fact["stem"]) <= 80:
                raise ValueError(f"题干长度错误：{fact['id']}")
            if not 1 <= len(fact["answer"]) <= 32 or not 6 <= len(fact["explanation"]) <= 120:
                raise ValueError(f"答案或解释长度错误：{fact['id']}")
            options = [normalized(fact["answer"])] + [normalized(value) for value in fact["distractors"]]
            if len(options) != 4 or len(set(options)) != 4:
                raise ValueError(f"选项重复：{fact['id']}")
            if any(not 1 <= len(value) <= 32 for value in fact["distractors"]):
                raise ValueError(f"干扰项长度错误：{fact['id']}")
            ids.add(fact["id"])
            stems.add(fact["stem"])
    if len(ids) != len(SPECS) * QUESTION_COUNT:
        raise ValueError("六领域题目总数错误")


def build_pack(spec: PackSpec) -> tuple[dict[str, object], str]:
    facts = [fact_dict(fact) for fact in BUILDERS[spec.category](spec)]
    metadata = {
        "package_id": spec.package_id,
        "title": spec.title,
        "version": PACK_VERSION,
        "locale": "zh-CN",
        "published_at_epoch_seconds": PUBLISHED_AT_EPOCH_SECONDS,
        "minimum_app_version_code": 17,
        "question_count": len(facts),
        "content_types": ["knowledge_quiz", f"category_{spec.category}"],
        "signing_key_id": SIGNING_KEY_ID,
    }
    pack = {
        "schema_version": 1,
        "metadata": metadata,
        "facts": facts,
        "signature": SIGNATURE,
    }
    return pack, canonical_hash(metadata, facts)


def serialized_pack(pack: dict[str, object]) -> str:
    return json.dumps(pack, ensure_ascii=False, indent=2) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    packs = [(spec, *build_pack(spec)) for spec in SPECS]
    validate_packs(packs)
    for spec, pack, content_hash in packs:
        rendered = serialized_pack(pack)
        if args.check:
            if not spec.output.exists() or spec.output.read_text(encoding="utf-8") != rendered:
                raise SystemExit(f"{spec.title}与生成器输出不一致")
        else:
            spec.output.parent.mkdir(parents=True, exist_ok=True)
            spec.output.write_text(rendered, encoding="utf-8", newline="\n")
        print(
            f"category={spec.category} questions={len(pack['facts'])} "
            f"sha256={content_hash} bytes={len(rendered.encode('utf-8'))}"
        )


if __name__ == "__main__":
    main()
