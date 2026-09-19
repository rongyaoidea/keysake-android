//! Typesake Android 离线语言引擎（纯 Rust，可单测；经 JNI 供 Kotlin 输入法调用）。
//!
//! 设计（MVP 离线版，对标 typesake.ai Mac 版核心链路）：
//! 拼音 -> 中文候选 -> 英文表达 -> 双击空格收藏 -> 学习中心复习 + 语法讲解。
//! 词库为内置静态表 + 单文件 JSON 持久化收藏，无网络，保护隐私。

use jni::objects::{JClass, JString};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::sync::{Mutex, OnceLock};

// ---------------- 静态词库 ----------------

/// 拼音音节 -> 汉字候选（MVP 手工小词库，覆盖演示和高频字）。
const PINYIN_TABLE: &[(&str, &[&str])] = &[
    ("ni", &["你", "泥", "尼", "逆", "拟"]),
    ("hao", &["好", "号", "浩", "豪", "耗"]),
    ("nihao", &["你好"]),
    ("shi", &["是", "事", "时", "使", "十"]),
    ("jie", &["界", "结", "姐", "接", "解"]),
    ("shi jie", &["世界"]),
    ("shijie", &["世界"]),
    ("xie", &["谢", "写", "些", "鞋", "蟹"]),
    ("xiexie", &["谢谢"]),
    ("xie xie", &["谢谢"]),
    ("zai", &["在", "再", "载", "灾"]),
    ("jian", &["见", "件", "建", "间", "剑"]),
    ("zaijian", &["再见"]),
    ("zai jian", &["再见"]),
    ("wo", &["我", "握", "窝", "卧"]),
    ("ai", &["爱", "矮", "碍", "艾"]),
    ("woaini", &["我爱你"]),
    ("zhong", &["中", "种", "重", "钟"]),
    ("guo", &["国", "过", "果", "锅"]),
    ("zhongguo", &["中国"]),
    ("ying", &["英", "应", "影", "迎"]),
    ("yu", &["语", "雨", "玉", "育", "鱼"]),
    ("yingyu", &["英语"]),
    ("xue", &["学", "雪", "血", "穴"]),
    ("xi", &["习", "西", "息", "系", "洗"]),
    ("xuexi", &["学习"]),
    ("gong", &["工", "公", "功", "供"]),
    ("zuo", &["作", "做", "坐", "座"]),
    ("gongzuo", &["工作"]),
    ("ming", &["明", "名", "命", "鸣"]),
    ("tian", &["天", "田", "甜", "填"]),
    ("mingtian", &["明天"]),
    ("jin", &["今", "金", "紧", "进"]),
    ("jin tian", &["今天"]),
    ("jintian", &["今天"]),
    ("peng", &["朋", "鹏", "碰", "棚"]),
    ("you", &["友", "有", "又", "右", "油"]),
    ("pengyou", &["朋友"]),
    ("jia", &["加", "家", "价", "假"]),
    ("youxiang", &["邮箱"]),
    ("dian", &["电", "点", "店", "垫"]),
    ("hua", &["话", "画", "花", "化"]),
    ("dianhua", &["电话"]),
    ("hui", &["会", "回", "灰", "惠"]),
    ("yi", &["议", "一", "医", "衣", "益"]),
    ("huiyi", &["会议"]),
    ("bao", &["报", "包", "保", "宝"]),
    ("gao", &["告", "高", "搞", "稿"]),
    ("baogao", &["报告"]),
    ("wen", &["文", "问", "温", "闻"]),
    ("jian2", &["件"]),
    ("wenjian", &["文件"]),
    ("fa", &["发", "法", "罚"]),
    ("song", &["送", "松", "颂"]),
    ("fasong", &["发送"]),
    ("kai", &["开", "凯"]),
    ("shi2", &["始"]),
    ("kaishi", &["开始"]),
    ("jie2", &["束"]),
    ("shu", &["束", "书", "数", "树"]),
    ("jieshu", &["结束"]),
    ("bang", &["帮", "棒", "邦", "绑"]),
    ("zhu", &["助", "住", "注", "猪"]),
    ("bangzhu", &["帮助"]),
    ("gan", &["感", "干", "敢"]),
    ("ganxie", &["感谢"]),
];

/// 单字 -> 英文（逐字兜底用）。
const CHAR_TABLE: &[(&str, &str)] = &[
    ("你", "you"),
    ("好", "good"),
    ("是", "is"),
    ("我", "I"),
    ("爱", "love"),
    ("中", "China"),
    ("国", "country"),
    ("英", "English"),
    ("语", "language"),
    ("学", "learn"),
    ("习", "practice"),
    ("工", "work"),
    ("作", "work"),
    ("明", "bright"),
    ("天", "day"),
    ("今", "today"),
    ("朋", "friend"),
    ("友", "friend"),
    ("电", "phone"),
    ("话", "call"),
    ("会", "meeting"),
    ("议", "discussion"),
    ("见", "see"),
    ("再", "again"),
    ("谢", "thank"),
    ("不", "not"),
    ("客", "guest"),
    ("气", "polite"),
    ("对", "right"),
    ("起", "rise"),
    ("没", "no"),
    ("关", "matter"),
    ("系", "relation"),
    ("吃", "eat"),
    ("饭", "meal"),
    ("喝", "drink"),
    ("水", "water"),
    ("家", "home"),
    ("人", "person"),
    ("老", "old"),
    ("师", "teacher"),
    ("同", "together"),
    ("事", "matter"),
    ("很", "very"),
    ("高", "tall"),
    ("兴", "happy"),
    ("认", "recognize"),
    ("识", "know"),
];

/// 整词/整句 -> 地道英文（精确与包含匹配用）。
const PHRASE_TABLE: &[(&str, &str)] = &[
    ("你好", "Hello!"),
    ("你好世界", "Hello, world!"),
    ("谢谢", "Thank you!"),
    ("不客气", "You're welcome!"),
    ("对不起", "I'm sorry."),
    ("没关系", "That's all right."),
    ("再见", "Goodbye! / See you!"),
    ("明天见", "See you tomorrow!"),
    ("我爱你", "I love you."),
    ("中国", "China"),
    ("英语", "English"),
    ("学习英语", "Learn English"),
    ("我在学习英语", "I'm learning English."),
    ("我在工作", "I'm working."),
    ("工作", "work / job"),
    ("会议", "meeting"),
    ("开会", "have a meeting"),
    ("今天开会", "I have a meeting today."),
    ("报告", "report"),
    ("发送报告", "Send the report."),
    ("请发送报告", "Please send the report."),
    ("文件", "file / document"),
    ("电话", "phone call"),
    ("打电话", "make a phone call"),
    ("邮箱", "mailbox / email"),
    ("发邮件", "send an email"),
    ("朋友", "friend"),
    ("我的朋友", "my friend"),
    ("很高兴认识你", "Nice to meet you!"),
    ("你叫什么名字", "What's your name?"),
    ("我叫", "My name is…"),
    ("你好吗", "How are you?"),
    ("我很好", "I'm good, thanks."),
    ("帮助", "help"),
    ("需要帮助", "need help"),
    ("我需要帮助", "I need help."),
    ("开始", "start / begin"),
    ("结束", "end / finish"),
    ("今天", "today"),
    ("明天", "tomorrow"),
    ("早上好", "Good morning!"),
    ("晚安", "Good night!"),
    ("吃了吗", "Have you eaten?"),
    ("一起吃饭", "Let's eat together."),
];

// ---------------- 收藏持久化 ----------------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SavedPhrase {
    pub chinese: String,
    pub english: String,
    pub saved_at: u64,
}

#[derive(Debug, Default)]
struct Store {
    dir: String,
    items: Vec<SavedPhrase>,
}

static STORE: OnceLock<Mutex<Store>> = OnceLock::new();

fn store() -> &'static Mutex<Store> {
    STORE.get_or_init(|| Mutex::new(Store::default()))
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn storage_path(dir: &str) -> std::path::PathBuf {
    std::path::Path::new(dir).join("typesake_saved.json")
}

// ---------------- 纯 Rust API（可单测 + JNI 共用） ----------------

/// 拼音 -> 中文候选，最多 8 个。
pub fn candidates_for(pinyin: &str) -> Vec<String> {
    let key = pinyin.trim().to_lowercase().replace('\'', "");
    if key.is_empty() {
        return vec![];
    }
    let nospace: String = key.chars().filter(|c| !c.is_whitespace()).collect();
    let mut out: Vec<String> = vec![];
    // 1. 精确匹配（含去空格形态）
    for (k, words) in PINYIN_TABLE {
        let kn: String = k.chars().filter(|c| !c.is_whitespace()).collect();
        if kn == nospace {
            for w in *words {
                if !out.contains(&w.to_string()) {
                    out.push(w.to_string());
                }
            }
        }
    }
    if !out.is_empty() {
        out.truncate(8);
        return out;
    }
    // 2. 前缀匹配
    for (k, words) in PINYIN_TABLE {
        let kn: String = k.chars().filter(|c| !c.is_whitespace()).collect();
        if kn.starts_with(&nospace) || nospace.starts_with(&kn) {
            for w in *words {
                if !out.contains(&w.to_string()) && out.len() < 8 {
                    out.push(w.to_string());
                }
            }
        }
    }
    out
}

fn char_to_en(ch: &str) -> Option<&'static str> {
    CHAR_TABLE.iter().find(|(c, _)| *c == ch).map(|(_, e)| *e)
}

/// 中文 -> 英文候选（精确 / 包含 / 逐字兜底），最多 3 个。
pub fn english_candidates(chinese: &str) -> Vec<String> {
    let text = chinese.trim();
    if text.is_empty() {
        return vec![];
    }
    // 1. 精确
    if let Some((_, en)) = PHRASE_TABLE.iter().find(|(c, _)| *c == text) {
        return vec![en.to_string()];
    }
    // 2. 包含：取最长的 3 个包含该片段的词条英文
    let mut hits: Vec<(&str, &str)> = PHRASE_TABLE
        .iter()
        .filter(|(c, _)| text.contains(*c) || (*c).contains(text))
        .map(|(c, e)| (*c, *e))
        .collect();
    if !hits.is_empty() {
        hits.sort_by_key(|(c, _)| std::cmp::Reverse(c.chars().count()));
        let mut out: Vec<String> = vec![];
        for (_, e) in hits.into_iter().take(3) {
            if !out.contains(&e.to_string()) {
                out.push(e.to_string());
            }
        }
        return out;
    }
    // 3. 逐字兜底
    let words: Vec<String> = text
        .chars()
        .map(|ch| {
            let s = ch.to_string();
            char_to_en(&s).unwrap_or("…").to_string()
        })
        .collect();
    vec![words.join(" ")]
}

/// 中文 -> 英文首选（输入法英文条显示用）。
pub fn suggest_english(chinese: &str) -> String {
    english_candidates(chinese)
        .into_iter()
        .next()
        .unwrap_or_default()
}

/// 英文 -> 中文语法讲解（启发式，离线）。
pub fn grammar_explain(english: &str) -> String {
    let text = english.trim();
    if text.is_empty() {
        return "输入英文句子后，我会给出结构讲解。".to_string();
    }
    let words: Vec<&str> = text.split_whitespace().collect();
    let lower = text.to_lowercase();
    let mut lines: Vec<String> = vec![format!("句子：{}", text)];
    lines.push(format!("词数：{} 个单词", words.len()));
    // 句式
    if text.ends_with('?') {
        lines.push("句式：疑问句（提问，注意助动词 do / be / what / how 开头）".to_string());
    } else if text.ends_with('!') {
        lines.push("句式：感叹 / 招呼语（语气较强，口语常用）".to_string());
    } else {
        lines.push("句式：陈述句（主语 + 谓语 + 其他成分）".to_string());
    }
    // 时态线索
    if lower.contains("will") || lower.contains("going to") || lower.contains("tomorrow") {
        lines.push("时态线索：将来（will / going to / tomorrow）".to_string());
    } else if lower.contains(" yesterday")
        || lower.contains(" was ")
        || lower.contains(" were ")
        || lower.ends_with("ed.")
        || lower.ends_with("ed")
    {
        lines.push("时态线索：可能过去时（was / were / 动词-ed / yesterday）".to_string());
    } else if lower.contains("'m ")
        || lower.contains("am ")
        || lower.contains("is ")
        || lower.contains("are ")
        || lower.contains("ing")
    {
        lines.push("时态线索：现在进行 / 现在时（be + doing）".to_string());
    }
    // 情态
    for m in ["please", "can", "could", "would", "should", "must"] {
        if lower.contains(m) {
            lines.push(format!("情态/礼貌词：含 “{m}”，多用于请求或建议"));
            break;
        }
    }
    // 简单成分拆分
    if words.len() >= 2 {
        lines.push(format!(
            "成分速览：主语≈ {} ｜ 谓语≈ {} ｜ 其余≈ {}",
            words[0],
            words[1],
            if words.len() > 2 {
                words[2..].join(" ")
            } else {
                "—".to_string()
            }
        ));
    }
    lines.push("用法提示：收藏后多读两遍，下次在同样中文场景直接套用。".to_string());
    lines.join("\n")
}

/// 设置收藏存储目录（Kotlin 传 filesDir），会尝试载入已有 JSON。
pub fn init_storage(dir: &str) -> Result<usize, String> {
    let mut s = store().lock().map_err(|e| e.to_string())?;
    s.dir = dir.to_string();
    let path = storage_path(dir);
    if let Ok(data) = std::fs::read_to_string(&path) {
        if let Ok(items) = serde_json::from_str::<Vec<SavedPhrase>>(&data) {
            s.items = items;
        }
    }
    Ok(s.items.len())
}

fn persist_locked(s: &Store) -> Result<(), String> {
    if s.dir.is_empty() {
        return Ok(()); // 未初始化则仅内存保存
    }
    let data = serde_json::to_string_pretty(&s.items).map_err(|e| e.to_string())?;
    std::fs::write(storage_path(&s.dir), data).map_err(|e| e.to_string())
}

/// 收藏（去重：同中文+英文只保留一条，更新时间）。
pub fn save_phrase(chinese: &str, english: &str) -> Result<SavedPhrase, String> {
    let (cn, en) = (chinese.trim(), english.trim());
    if cn.is_empty() || en.is_empty() {
        return Err("中文和英文都不能为空".to_string());
    }
    let mut s = store().lock().map_err(|e| e.to_string())?;
    if let Some(p) = s
        .items
        .iter_mut()
        .find(|p| p.chinese == cn && p.english == en)
    {
        p.saved_at = now_secs();
        let c = p.clone();
        persist_locked(&s)?;
        return Ok(c);
    }
    let p = SavedPhrase {
        chinese: cn.to_string(),
        english: en.to_string(),
        saved_at: now_secs(),
    };
    s.items.push(p.clone());
    persist_locked(&s)?;
    Ok(p)
}

pub fn list_saved() -> Vec<SavedPhrase> {
    let mut items = store()
        .lock()
        .map(|s| s.items.clone())
        .unwrap_or_default();
    items.sort_by_key(|p| std::cmp::Reverse(p.saved_at));
    items
}

pub fn clear_saved() -> Result<usize, String> {
    let mut s = store().lock().map_err(|e| e.to_string())?;
    let n = s.items.len();
    s.items.clear();
    persist_locked(&s)?;
    Ok(n)
}

#[cfg(test)]
fn reset_for_test() {
    if let Ok(mut s) = store().lock() {
        s.dir = String::new();
        s.items.clear();
    }
}

// ---------------- JNI 导出（Kotlin: com.typesake.app.TypesakeCore） ----------------

fn jstr_to_rust(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|j| j.to_str().unwrap_or_default().to_owned())
        .unwrap_or_default()
}

fn rust_to_jstr<'local>(env: &mut JNIEnv<'local>, s: &str) -> JString<'local> {
    env.new_string(s).unwrap_or_else(|_| {
        env.new_string("").expect("empty JString must succeed")
    })
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_initStorage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    jdir: JString<'local>,
) -> JString<'local> {
    let dir = jstr_to_rust(&mut env, &jdir);
    let msg = match init_storage(&dir) {
        Ok(n) => format!("{{\"ok\":true,\"count\":{n}}}"),
        Err(e) => format!("{{\"ok\":false,\"err\":{}}}", serde_json::to_string(&e).unwrap()),
    };
    rust_to_jstr(&mut env, &msg)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_suggestEnglish<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JString<'local> {
    let s = jstr_to_rust(&mut env, &input);
    let out = suggest_english(&s);
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_candidatesFor<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JString<'local> {
    let s = jstr_to_rust(&mut env, &input);
    let arr = serde_json::to_string(&candidates_for(&s)).unwrap_or_else(|_| "[]".into());
    rust_to_jstr(&mut env, &arr)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_englishCandidates<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JString<'local> {
    let s = jstr_to_rust(&mut env, &input);
    let arr = serde_json::to_string(&english_candidates(&s)).unwrap_or_else(|_| "[]".into());
    rust_to_jstr(&mut env, &arr)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_grammarExplain<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JString<'local> {
    let s = jstr_to_rust(&mut env, &input);
    let out = grammar_explain(&s);
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_savePhrase<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    jcn: JString<'local>,
    jen: JString<'local>,
) -> JString<'local> {
    let cn = jstr_to_rust(&mut env, &jcn);
    let en = jstr_to_rust(&mut env, &jen);
    let msg = match save_phrase(&cn, &en) {
        Ok(p) => serde_json::to_string(&p).unwrap_or("{\"ok\":true}".into()),
        Err(e) => format!("{{\"ok\":false,\"err\":{}}}", serde_json::to_string(&e).unwrap()),
    };
    rust_to_jstr(&mut env, &msg)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_listSaved<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let arr = serde_json::to_string(&list_saved()).unwrap_or_else(|_| "[]".into());
    rust_to_jstr(&mut env, &arr)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_clearSaved<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let msg = match clear_saved() {
        Ok(n) => format!("{{\"ok\":true,\"cleared\":{n}}}"),
        Err(e) => format!("{{\"ok\":false,\"err\":{}}}", serde_json::to_string(&e).unwrap()),
    };
    rust_to_jstr(&mut env, &msg)
}

// ---------------- 单测（CI 跑 cargo test） ----------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pinyin_exact_match() {
        reset_for_test();
        assert_eq!(candidates_for("nihao"), vec!["你好".to_string()]);
        assert!(candidates_for("xie").contains(&"谢".to_string()));
    }

    #[test]
    fn pinyin_empty_and_prefix() {
        reset_for_test();
        assert!(candidates_for("").is_empty());
        assert!(!candidates_for("zhong").is_empty());
    }

    #[test]
    fn suggest_exact_phrase() {
        reset_for_test();
        assert_eq!(suggest_english("谢谢"), "Thank you!");
        assert_eq!(suggest_english("再见"), "Goodbye! / See you!");
    }

    #[test]
    fn english_candidates_fallback_never_empty() {
        reset_for_test();
        let v = english_candidates("今天开会");
        assert!(!v.is_empty());
        assert!(v.len() <= 3);
    }

    #[test]
    fn save_list_dedup() {
        reset_for_test();
        save_phrase("谢谢", "Thank you!").unwrap();
        save_phrase("谢谢", "Thank you!").unwrap();
        assert_eq!(list_saved().len(), 1);
        save_phrase("再见", "Goodbye! / See you!").unwrap();
        assert_eq!(list_saved().len(), 2);
        assert!(clear_saved().is_ok());
        assert!(list_saved().is_empty());
    }

    #[test]
    fn save_rejects_empty() {
        reset_for_test();
        assert!(save_phrase("", "hi").is_err());
        assert!(save_phrase("你好", "").is_err());
    }

    #[test]
    fn grammar_mentions_pattern() {
        reset_for_test();
        let q = grammar_explain("What's your name?");
        assert!(q.contains("疑问句"));
        let f = grammar_explain("See you tomorrow!");
        assert!(f.contains("将来") || f.contains("感叹"));
    }
}
