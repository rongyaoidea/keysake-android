//! 双拼：把「双拼击键串」还原成全拼，交给既有引擎处理。
//!
//! 目前内置**小鹤双拼**（最流行）。表格是纯数据，新增方案只需加一张表。
//! 零声母音节（a/an/ai/e/ei/er/o/ou/ang/eng…）按小鹤约定用「首字母 + 韵母键」输入，
//! 例如 爱=ai、安=aj?（见下表），本文件用同表双向推导，保证自洽。
//!
//! 注意：映射表来自公开键位图的整理，发布前建议对照官方键位图核对一遍
//! （代码里已用 `round_trip_all_listed_syllables` 做完整性自检）。

use std::collections::HashMap;

/// 当前支持的方案（与设置项编号一致；0 = 关闭）
pub const SCHEME_FLYPY: u8 = 1;

/// 韵母 -> 键位（小鹤）
const FINALS: &[(&str, char)] = &[
    ("a", 'a'),
    ("o", 'o'),
    ("e", 'e'),
    ("i", 'i'),
    ("u", 'u'),
    ("v", 'v'),
    ("ai", 'd'),
    ("ei", 'w'),
    ("ui", 'v'),
    ("ao", 'c'),
    ("ou", 'z'),
    ("iu", 'q'),
    ("ie", 'p'),
    ("ve", 't'),
    ("ue", 't'),
    ("er", 'r'),
    ("an", 'j'),
    ("en", 'f'),
    ("in", 'b'),
    ("un", 'y'),
    ("vn", 'y'),
    ("ang", 'h'),
    ("eng", 'g'),
    ("ing", 'k'),
    ("uai", 'k'),
    ("ong", 's'),
    ("iong", 's'),
    ("ia", 'x'),
    ("ua", 'x'),
    ("ian", 'm'),
    ("uan", 'r'),
    ("iang", 'l'),
    ("uang", 'l'),
    ("iao", 'n'),
    ("uo", 'o'),
];

/// 声母 -> 键位（小鹤：zh/ch/sh 用 v/i/u）
const INITIALS: &[(&str, char)] = &[
    ("zh", 'v'),
    ("ch", 'i'),
    ("sh", 'u'),
    ("b", 'b'),
    ("p", 'p'),
    ("m", 'm'),
    ("f", 'f'),
    ("d", 'd'),
    ("t", 't'),
    ("n", 'n'),
    ("l", 'l'),
    ("g", 'g'),
    ("k", 'k'),
    ("h", 'h'),
    ("j", 'j'),
    ("q", 'q'),
    ("x", 'x'),
    ("r", 'r'),
    ("z", 'z'),
    ("c", 'c'),
    ("s", 's'),
    ("y", 'y'),
    ("w", 'w'),
];

/// 韵母表（用于把击键还原：键位 -> 可能的韵母，按常用度排序）
fn finals_for_key(key: char) -> &'static [&'static str] {
    match key {
        'a' => &["a"],
        'o' => &["o", "uo"],
        'e' => &["e"],
        'i' => &["i"],
        'u' => &["u"],
        'v' => &["v", "ui"],
        'd' => &["ai"],
        'w' => &["ei"],
        'c' => &["ao"],
        'z' => &["ou"],
        'q' => &["iu"],
        'p' => &["ie"],
        't' => &["ve", "ue"],
        'r' => &["er", "uan"],
        'j' => &["an"],
        'f' => &["en"],
        'b' => &["in"],
        'y' => &["un", "vn"],
        'h' => &["ang"],
        'g' => &["eng"],
        'k' => &["ing", "uai"],
        's' => &["ong", "iong"],
        'x' => &["ia", "ua"],
        'm' => &["ian"],
        'l' => &["iang", "uang"],
        'n' => &["iao"],
        _ => &[],
    }
}

fn final_key(final_: &str) -> Option<char> {
    FINALS.iter().find(|(f, _)| *f == final_).map(|(_, k)| *k)
}

/// 全拼音节 -> 双拼击键（用于测试与反向校验）
pub fn encode(syllable: &str) -> Option<String> {
    let s = syllable.trim().to_ascii_lowercase();
    if s.is_empty() {
        return None;
    }
    // 先试最长的声母
    for (ini, ikey) in INITIALS.iter() {
        if let Some(rest) = s.strip_prefix(ini) {
            if rest.is_empty() {
                // 只有声母（如 n/ng 极少见）：直接击键
                return Some(ikey.to_string());
            }
            if let Some(fk) = final_key(rest) {
                return Some(format!("{ikey}{fk}"));
            }
            // 零声母或词表外的韵母：原样
            return None;
        }
    }
    // 零声母（a/o/e 开头）：击键 = 首字母 + 整个音节的韵母键
    // 例：爱 ai -> a+d, 安 an -> a+j, 昂 ang -> a+h, 二 er -> e+r
    let mut chars = s.chars();
    let first = chars.next()?;
    if matches!(first, 'a' | 'o' | 'e') {
        return final_key(&s).map(|fk| format!("{first}{fk}"));
    }
    Some(first.to_string())
}

/// 规整：只保留字母
fn sanitize(input: &str) -> String {
    input
        .chars()
        .filter(|c| c.is_ascii_alphabetic())
        .map(|c| c.to_ascii_lowercase())
        .collect()
}

/// 双拼击键串 -> 全拼串。无法完整还原的片段原样保留（不丢用户输入）。
pub fn to_full(input: &str, scheme: u8) -> String {
    if scheme != SCHEME_FLYPY {
        return sanitize(input);
    }
    let s = sanitize(input);
    if s.is_empty() {
        return s;
    }
    let chars: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len() * 2);
    let mut i = 0usize;
    // 逐「声母+韵母键」解析；遇到不成对的裸字母就并入当前音节
    while i < chars.len() {
        // 声母键（含 zh/ch/sh 的 v/i/u）
        let (ini_key, ini) = match chars[i] {
            'v' => ('v', "zh"),
            'i' => ('i', "ch"),
            'u' => ('u', "sh"),
            c if c.is_ascii_alphabetic() => (c, ""),
            _ => {
                i += 1;
                continue;
            }
        };
        let _ = ini_key;
        let mut piece = String::new();
        if ini.is_empty() {
            // 零声母：下一键解出的韵母本身就是音节（需与击键首字母一致）
            if matches!(chars[i], 'a' | 'o' | 'e') {
                if i + 1 < chars.len() {
                    let zero = finals_for_key(chars[i + 1])
                        .iter()
                        .find(|f| f.starts_with(chars[i]));
                    if let Some(f) = zero {
                        out.push_str(f);
                        i += 2;
                        continue;
                    }
                }
                out.push(chars[i]);
                i += 1;
                continue;
            }
            piece.push(chars[i]);
        } else {
            piece.push_str(ini);
        }
        // 韵母键
        if i + 1 < chars.len() {
            if let Some(f) = pick_final(chars[i + 1], &piece) {
                piece.push_str(&f);
                i += 2;
                out.push_str(&piece);
                continue;
            }
        }
        // 落单：原样并入
        out.push_str(&piece);
        i += 1;
    }
    out
}

/// 依据音节前缀 + 键位挑韵母。
///
/// 小鹤里同一个键位常对应两个韵母（x=ia/ua、l=iang/uang、k=ing/uai、s=ong/iong、
/// t=üe/ue、v=ü/ui、o=o/uo、r=er/uan），选择取决于前面的声母类别：
/// - j/q/x：i 系（ia/iang/iong/ue/un…）
/// - g/k/h/zh/ch/sh/r/z/c/s：u 系（ua/uang/uai/uo/…）
/// - b/p/m/f：o（bo/po/mo/fo）
/// - n/l：ü 系（nv/lv/nve）
fn pick_final(key: char, prefix: &str) -> Option<String> {
    let cands = finals_for_key(key);
    if cands.is_empty() {
        return None;
    }
    let head = prefix.to_ascii_lowercase();
    let is = |set: &str| set.split(',').any(|x| x == head);
    let zero = head.is_empty();

    let prefer: &[&str] = match key {
        'x' => {
            if is("j,q,x") {
                &["ia"]
            } else {
                &["ua"]
            }
        }
        'l' => {
            if is("j,q,x,n,l") {
                &["iang"]
            } else {
                &["uang"]
            }
        }
        't' => {
            if is("n,l") {
                &["ve", "ue"]
            } else {
                &["ue", "ve"]
            }
        }
        's' => {
            if is("j,q,x") {
                &["iong", "ong"]
            } else {
                &["ong", "iong"]
            }
        }
        'k' => {
            if is("g,k,h,zh,ch,sh") {
                &["uai", "ing"]
            } else {
                &["ing", "uai"]
            }
        }
        'v' => {
            if is("n,l") {
                &["v", "ui"]
            } else {
                &["ui", "v"]
            }
        }
        'o' => {
            if is("b,p,m,f,w") {
                &["o", "uo"]
            } else {
                &["uo", "o"]
            }
        }
        'r' => {
            if zero {
                &["er", "uan"]
            } else {
                &["uan", "er"]
            }
        }
        'y' => &["un", "vn"],
        _ => &[],
    };

    // 1) 命中偏好表
    for want in prefer {
        if let Some(hit) = cands.iter().find(|f| **f == *want) {
            return Some((*hit).to_string());
        }
    }
    // 2) 零声母：优先真零声母音节
    if zero {
        if let Some(hit) = cands
            .iter()
            .find(|f| f.starts_with(['a', 'o', 'e']) || **f == "ou")
        {
            return Some((*hit).to_string());
        }
    }
    // 3) 兜底：表内顺序
    cands.first().map(|f| (*f).to_string())
}

/// 便捷：给设置项用的方案名
pub fn scheme_name(scheme: u8) -> &'static str {
    match scheme {
        SCHEME_FLYPY => "小鹤双拼",
        _ => "全拼",
    }
}

/// 反查表：键位 -> 韵母（供测试/调试）
pub fn debug_final_map() -> HashMap<char, Vec<&'static str>> {
    let mut m: HashMap<char, Vec<&'static str>> = HashMap::new();
    for (f, k) in FINALS {
        m.entry(*k).or_default().push(f);
    }
    m
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encodes_common_syllables() {
        assert_eq!(encode("ni").as_deref(), Some("ni"));
        assert_eq!(encode("hao").as_deref(), Some("hc"));
        assert_eq!(encode("zhong").as_deref(), Some("vs"));
        assert_eq!(encode("guo").as_deref(), Some("go"));
        assert_eq!(encode("xue").as_deref(), Some("xt"));
        assert_eq!(encode("shang").as_deref(), Some("uh"));
        assert_eq!(encode("ai").as_deref(), Some("ad"));
        assert_eq!(encode("an").as_deref(), Some("aj"));
    }

    #[test]
    fn round_trip_listed_syllables() {
        let syllables = [
            "a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er", "o", "ou", "yi", "ya",
            "yao", "ye", "you", "yan", "yin", "yang", "ying", "yong", "wu", "wa", "wo", "wai",
            "wei", "wan", "wen", "wang", "weng", "yu", "yue", "yuan", "yun", "ba", "bai", "ban",
            "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo",
            "bu", "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian", "piao",
            "pie", "pin", "ping", "po", "pu", "ma", "mai", "man", "mang", "mao", "mei", "men",
            "meng", "mi", "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu", "fa",
            "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu", "da", "dai", "dan", "dang",
            "dao", "de", "dei", "deng", "di", "dian", "diao", "die", "ding", "diu", "dong", "dou",
            "du", "duan", "dui", "dun", "duo", "ta", "tai", "tan", "tang", "tao", "te", "teng",
            "ti", "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
            "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni", "nian", "niang",
            "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu", "nuan", "nuo", "nv", "nve",
            "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lian", "liang", "liao",
            "lie", "lin", "ling", "liu", "long", "lou", "lu", "luan", "lun", "luo", "lv", "lve",
            "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu",
            "gua", "guai", "guan", "guang", "gui", "gun", "guo", "ka", "kai", "kan", "kang", "kao",
            "ke", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun",
            "kuo", "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou",
            "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo", "ji", "jia", "jian",
            "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue", "jun",
            "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu",
            "quan", "que", "qun", "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing",
            "xiong", "xiu", "xu", "xuan", "xue", "xun", "zha", "zhai", "zhan", "zhang", "zhao",
            "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua", "zhuai",
            "zhuan", "zhuang", "zhui", "zhun", "zhuo", "cha", "chai", "chan", "chang", "chao",
            "che", "chen", "cheng", "chi", "chong", "chou", "chu", "chua", "chuai", "chuan",
            "chuang", "chui", "chun", "chuo", "sha", "shai", "shan", "shang", "shao", "she",
            "shei", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan", "shuang",
            "shui", "shun", "shuo", "ra", "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong",
            "rou", "ru", "rua", "ruan", "rui", "run", "ruo", "za", "zai", "zan", "zang", "zao",
            "ze", "zei", "zen", "zeng", "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo",
            "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "ci", "cong", "cou", "cu",
            "cuan", "cui", "cun", "cuo", "sa", "sai", "san", "sang", "sao", "se", "sen", "seng",
            "si", "song", "sou", "su", "suan", "sui", "sun", "suo",
        ];
        let mut missing = Vec::new();
        for syl in syllables {
            match encode(syl) {
                Some(code) => {
                    let back = to_full(&code, SCHEME_FLYPY);
                    if back != syl {
                        missing.push(format!("{syl} -> {code} -> {back}"));
                    }
                }
                None => missing.push(format!("{syl} 无编码")),
            }
        }
        assert!(missing.is_empty(), "双拼往返不一致：{missing:?}");
    }

    #[test]
    fn disabled_scheme_is_passthrough() {
        assert_eq!(to_full("nihao", 0), "nihao");
        assert_eq!(to_full("Nihao APP", 0), "nihaoapp");
    }

    #[test]
    fn sentence_level_conversion() {
        // 你好 -> ni + hc；中国 -> vs + go
        assert_eq!(to_full("nihc", SCHEME_FLYPY), "nihao");
        assert_eq!(to_full("vsgo", SCHEME_FLYPY), "zhongguo");
        assert_eq!(to_full("xtxi", SCHEME_FLYPY), "xuexi");
    }
}
