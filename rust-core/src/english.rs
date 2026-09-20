//! 中文 -> 英文表达（离线词典法）。
//!
//! 离线、无模型，因此策略是"宁可不说，也不吐垃圾"：
//! 1. 整句精确命中 -> 直接给地道表达
//! 2. 贪心最长词匹配（多字词优先）-> 组合出大意，覆盖率不足则放弃
//! 3. 短词（≤4 字）逐字兜底，仅当全部字都认识时才输出
//!
//! 未命中时返回空串/空表，UI 显示提示而不是 `… …` 这类噪音。

use std::collections::HashMap;
use std::sync::OnceLock;

/// 整词/整句 -> 英文（口语与常用书面语，含少量商务场景）。
const PHRASES: &[(&str, &str)] = &[
    ("你好", "Hello!"),
    ("您好", "Hello!"),
    ("大家好", "Hello, everyone!"),
    ("早上好", "Good morning!"),
    ("下午好", "Good afternoon!"),
    ("晚上好", "Good evening!"),
    ("晚安", "Good night!"),
    ("再见", "Goodbye! / See you!"),
    ("明天见", "See you tomorrow!"),
    ("回头见", "See you later!"),
    ("谢谢", "Thank you!"),
    ("感谢", "Thank you!"),
    ("非常感谢", "Thank you so much!"),
    ("多谢", "Thanks a lot!"),
    ("不客气", "You're welcome!"),
    ("不用谢", "You're welcome!"),
    ("对不起", "I'm sorry."),
    ("抱歉", "My apologies."),
    ("不好意思", "Excuse me. / Sorry about that."),
    ("没关系", "That's all right."),
    ("没事", "No worries."),
    ("请问", "Excuse me, may I ask…"),
    ("你好吗", "How are you?"),
    ("我很好", "I'm good, thanks."),
    ("最近怎么样", "How have you been?"),
    ("你叫什么名字", "What's your name?"),
    ("我叫", "My name is…"),
    ("很高兴认识你", "Nice to meet you!"),
    ("我也很高兴", "Nice to meet you too!"),
    ("好久不见", "Long time no see!"),
    ("我爱你", "I love you."),
    ("我想你", "I miss you."),
    ("我同意", "I agree."),
    ("我明白了", "I see. / Got it."),
    ("我不知道", "I don't know."),
    ("我觉得", "I think…"),
    ("我认为", "I think that…"),
    ("我建议", "I suggest that…"),
    ("没问题", "No problem."),
    ("好的", "Okay. / Sure."),
    ("可以", "Sure. / That works."),
    ("不行", "No, that won't work."),
    ("也许吧", "Maybe."),
    ("当然", "Of course."),
    ("真的吗", "Really?"),
    ("太棒了", "That's great!"),
    ("厉害了", "That's impressive!"),
    ("加油", "You can do it! / Keep going!"),
    ("辛苦了", "Thanks for your hard work."),
    ("请稍等", "Please wait a moment."),
    ("稍等一下", "Give me a second, please."),
    ("我马上回来", "I'll be right back."),
    ("我需要帮助", "I need help."),
    ("需要帮助", "need help"),
    ("帮助", "help"),
    ("救命", "Help!"),
    ("请再说一遍", "Could you say that again?"),
    ("我听不懂", "I don't understand."),
    ("你会说英语吗", "Do you speak English?"),
    ("我英语不太好", "My English isn't very good."),
    ("我在学习英语", "I'm learning English."),
    ("学习英语", "learn English"),
    ("我在学中文", "I'm learning Chinese."),
    ("今天", "today"),
    ("明天", "tomorrow"),
    ("昨天", "yesterday"),
    ("每天", "every day"),
    ("现在", "now"),
    ("等一下", "wait a moment"),
    ("今天天气很好", "The weather is nice today."),
    ("下雨了", "It's raining."),
    ("吃了吗", "Have you eaten?"),
    ("一起吃饭", "Let's eat together."),
    ("我请你吃饭", "Dinner is on me."),
    ("我饿了", "I'm hungry."),
    ("我渴了", "I'm thirsty."),
    ("我累了", "I'm tired."),
    ("我生病了", "I'm sick."),
    ("生日快乐", "Happy birthday!"),
    ("新年快乐", "Happy New Year!"),
    ("恭喜", "Congratulations!"),
    ("祝你好运", "Good luck!"),
    ("玩得开心", "Have fun!"),
    ("一路顺风", "Have a safe trip!"),
    ("保重", "Take care!"),
    ("我到了", "I've arrived."),
    ("我在路上", "I'm on my way."),
    ("我迟到了", "I'm late."),
    ("堵车了", "Traffic is bad."),
    ("多少钱", "How much is it?"),
    ("太贵了", "That's too expensive."),
    ("便宜一点", "Can you make it cheaper?"),
    ("我要这个", "I'll take this one."),
    ("我要一杯咖啡", "I'd like a coffee, please."),
    ("买单", "Check, please."),
    ("洗手间在哪里", "Where is the restroom?"),
    ("怎么走", "How do I get there?"),
    ("在哪里", "Where is it?"),
    ("这是什么", "What is this?"),
    ("几点", "What time is it?"),
    ("我住在", "I live in…"),
    ("我来自中国", "I'm from China."),
    ("中国", "China"),
    ("英语", "English"),
    ("中文", "Chinese"),
    ("朋友", "friend"),
    ("我的朋友", "my friend"),
    ("家人", "family"),
    ("同事", "colleague"),
    ("老板", "boss"),
    ("老师", "teacher"),
    ("学生", "student"),
    ("电话", "phone call"),
    ("打电话", "make a phone call"),
    ("给我打电话", "Give me a call."),
    ("发短信", "send a text message"),
    ("邮箱", "mailbox / email"),
    ("发邮件", "send an email"),
    ("邮件", "email"),
    ("文件", "file / document"),
    ("报告", "report"),
    ("发送报告", "Send the report."),
    ("请发送报告", "Please send the report."),
    ("收到", "Received. / Got it."),
    ("确认一下", "Just to confirm…"),
    ("会议", "meeting"),
    ("开会", "have a meeting"),
    ("今天开会", "We have a meeting today."),
    ("开会时间", "meeting time"),
    ("迟到", "be late"),
    ("加班", "work overtime"),
    ("工作", "work / job"),
    ("我在工作", "I'm working."),
    ("上班", "go to work"),
    ("下班", "get off work"),
    ("出差", "business trip"),
    ("请假", "ask for leave"),
    ("项目", "project"),
    ("进度", "progress"),
    ("截止日期", "deadline"),
    ("预算", "budget"),
    ("合同", "contract"),
    ("客户", "client"),
    ("需求", "requirement"),
    ("方案", "plan / proposal"),
    ("报价", "quotation"),
    ("价格", "price"),
    ("付款", "payment"),
    ("发票", "invoice"),
    ("合同已签署", "The contract has been signed."),
    ("请查收", "Please find it attached."),
    ("有问题随时联系我", "Let me know if you have any questions."),
    ("我们保持联系", "Let's keep in touch."),
    ("感谢你的支持", "Thanks for your support."),
    ("合作愉快", "It's a pleasure working with you."),
    ("辛苦了，早点休息", "Good work today—get some rest."),
    ("请确认", "Please confirm."),
    ("我需要更多时间", "I need more time."),
    ("我稍后回复你", "I'll get back to you later."),
    ("我考虑一下", "Let me think about it."),
    ("我尽力", "I'll do my best."),
    ("交给我", "Leave it to me."),
    ("包在我身上", "Consider it done."),
    ("没问题，我来处理", "No problem, I'll handle it."),
    ("开始", "start / begin"),
    ("开始吧", "Let's get started."),
    ("结束", "end / finish"),
    ("完成", "done / complete"),
    ("已完成", "It's done."),
    ("请开始", "Please go ahead."),
    ("等一下，我想想", "Hold on, let me think."),
];

/// 逐字兜底（仅短词、且所有字都认识时才用）。
const CHAR_TABLE: &[(&str, &str)] = &[
    ("你", "you"),
    ("我", "I"),
    ("他", "he"),
    ("她", "she"),
    ("好", "good"),
    ("是", "is"),
    ("爱", "love"),
    ("中", "China"),
    ("国", "country"),
    ("英", "English"),
    ("语", "language"),
    ("学", "learn"),
    ("习", "practice"),
    ("工", "work"),
    ("作", "work"),
    ("天", "day"),
    ("今", "today"),
    ("明", "tomorrow"),
    ("朋", "friend"),
    ("友", "friend"),
    ("电", "phone"),
    ("话", "call"),
    ("会", "meeting"),
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
    ("早", "morning"),
    ("晚", "night"),
    ("安", "safe"),
    ("请", "please"),
    ("问", "ask"),
    ("钱", "money"),
    ("贵", "expensive"),
    ("买", "buy"),
    ("时", "time"),
    ("间", "interval"),
];

/// 中文虚词/标点：贪心翻译时直接跳过，不参与覆盖率惩罚。
const SKIP_CHARS: &str =
    "的了是在有和就不都一也又还个们这那吗呢吧啊嘛哦嗯了，。！？、；：\"''（）《》…—~·　 ";

struct PhraseIndex {
    /// first char -> (char_len, chinese, english), longest first
    by_first: HashMap<char, Vec<(usize, &'static str, &'static str)>>,
    chars: HashMap<char, &'static str>,
}

fn index() -> &'static PhraseIndex {
    static IDX: OnceLock<PhraseIndex> = OnceLock::new();
    IDX.get_or_init(|| {
        let mut by_first: HashMap<char, Vec<(usize, &'static str, &'static str)>> = HashMap::new();
        for (cn, en) in PHRASES.iter().chain(CHAR_TABLE.iter()) {
            if let Some(first) = cn.chars().next() {
                by_first
                    .entry(first)
                    .or_default()
                    .push((cn.chars().count(), *cn, *en));
            }
        }
        for v in by_first.values_mut() {
            v.sort_by_key(|b| std::cmp::Reverse(b.0));
        }
        let mut chars = HashMap::new();
        for (c, e) in CHAR_TABLE {
            if let Some(ch) = c.chars().next() {
                chars.insert(ch, *e);
            }
        }
        PhraseIndex { by_first, chars }
    })
}

/// 贪心最长匹配（整词优先，其次单字）；覆盖率不足（或完全没命中）返回 None。
fn greedy_gloss(text: &str) -> Option<String> {
    let idx = index();
    let chars: Vec<char> = text.chars().collect();
    let mut i = 0usize;
    let mut parts: Vec<&'static str> = Vec::new();
    let mut matched = 0usize;
    let mut unknown = 0usize;

    while i < chars.len() {
        let mut hit: Option<(usize, &'static str)> = None;
        if let Some(cands) = idx.by_first.get(&chars[i]) {
            for (clen, cn, en) in cands {
                if i + clen <= chars.len() {
                    let seg: String = chars[i..i + clen].iter().collect();
                    if seg == *cn {
                        hit = Some((*clen, *en));
                        break;
                    }
                }
            }
        }
        match hit {
            Some((clen, en)) => {
                parts.push(en);
                matched += clen;
                i += clen;
            }
            None => {
                let ch = chars[i];
                if !SKIP_CHARS.contains(ch) {
                    unknown += 1;
                }
                i += 1;
            }
        }
    }

    if matched == 0 {
        return None;
    }
    let total = matched + unknown;
    if total == 0 || (matched as f64) / (total as f64) < 0.5 {
        return None;
    }
    let mut s = parts.join(" ");
    // 多词组合视作句子 -> 首字母大写；单词保持小写，便于直接嵌进英文句子
    if parts.len() > 1 {
        if let Some(first) = s.chars().next() {
            if first.is_ascii_lowercase() {
                s = format!("{}{}", first.to_ascii_uppercase(), &s[first.len_utf8()..]);
            }
        }
    }
    Some(s)
}

/// 逐字兜底：仅 ≤4 字且全部认识时输出，否则 None。
fn char_gloss(text: &str) -> Option<String> {
    let idx = index();
    let chars: Vec<char> = text.chars().collect();
    if chars.is_empty() || chars.len() > 4 {
        return None;
    }
    let mut words = Vec::with_capacity(chars.len());
    for ch in &chars {
        words.push(*idx.chars.get(ch)?);
    }
    Some(words.join(" "))
}

/// 中文 -> 英文候选（最多 3 条，可能为空）。
pub fn english_candidates(text: &str) -> Vec<String> {
    let t = text.trim();
    if t.is_empty() {
        return Vec::new();
    }
    let mut out: Vec<String> = Vec::new();

    // 1) 精确整句
    if let Some((_, en)) = PHRASES.iter().find(|(cn, _)| *cn == t) {
        out.push((*en).to_string());
    }

    // 2) 贪心组合
    if let Some(g) = greedy_gloss(t) {
        if !out.iter().any(|w| w == &g) {
            out.push(g);
        }
    }

    // 3) 短词逐字
    if out.is_empty() {
        if let Some(c) = char_gloss(t) {
            out.push(c);
        }
    }

    out.truncate(3);
    out
}

/// 中文 -> 英文首选（空串表示暂无建议）。
pub fn suggest(text: &str) -> String {
    english_candidates(text)
        .into_iter()
        .next()
        .unwrap_or_default()
}

/// 英文 -> 语法讲解（离线启发式）。
pub fn grammar_explain(english: &str) -> String {
    let text = english.trim();
    if text.is_empty() {
        return "输入或收藏英文句子后，这里会给出结构讲解。".to_string();
    }
    let words: Vec<&str> = text.split_whitespace().collect();
    let lower = text.to_lowercase();
    let mut lines = vec![format!("句子：{text}")];
    lines.push(format!("词数：{} 个单词", words.len()));

    if text.ends_with('?') {
        lines.push("句式：疑问句（助动词/疑问词开头：do、be、what、how…）".to_string());
    } else if text.ends_with('!') {
        lines.push("句式：感叹句或招呼语（语气强，口语常用）".to_string());
    } else if lower.starts_with("please") || lower.starts_with("let ") {
        lines.push("句式：祈使句（省略主语的请求/建议）".to_string());
    } else {
        lines.push("句式：陈述句（主语 + 谓语 + 其他成分）".to_string());
    }

    if lower.contains("will ") || lower.contains("going to") || lower.contains("tomorrow") {
        lines.push("时态线索：将来（will / be going to / tomorrow）".to_string());
    } else if lower.contains(" yesterday")
        || lower.contains(" was ")
        || lower.contains(" were ")
        || lower.ends_with("ed")
        || lower.ends_with("ed.")
    {
        lines.push("时态线索：过去（was / were / 动词-ed / yesterday）".to_string());
    } else if lower.contains("'m ")
        || lower.contains(" am ")
        || lower.contains(" is ")
        || lower.contains(" are ")
        || lower.contains("ing ")
    {
        lines.push("时态线索：现在/进行（be 动词或 -ing）".to_string());
    }

    for m in ["please", "could", "would", "should", "can ", "must"] {
        if lower.contains(m) {
            lines.push(format!(
                "情态/礼貌词：含 \"{}\"，多用于请求或建议",
                m.trim()
            ));
            break;
        }
    }

    if words.len() >= 2 {
        lines.push(format!(
            "成分速览：主语≈ {} | 谓语≈ {} | 其余≈ {}",
            words[0],
            words[1],
            if words.len() > 2 {
                words[2..].join(" ")
            } else {
                "—".to_string()
            }
        ));
    }
    lines.push("用法提示：收藏后朗读两遍，下次遇到同样中文场景直接套用。".to_string());
    lines.join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_phrase_wins() {
        assert_eq!(suggest("谢谢"), "Thank you!");
        assert_eq!(suggest("再见"), "Goodbye! / See you!");
        assert_eq!(suggest("你好"), "Hello!");
    }

    #[test]
    fn greedy_composes_known_sentence() {
        let s = suggest("我今天很忙");
        assert!(!s.is_empty());
        assert!(s.to_lowercase().contains("today") || s.to_lowercase().contains("busy"));
    }

    #[test]
    fn no_garbage_for_unknown_text() {
        // 覆盖率过低时宁可不给，也不能出现 "…"
        let s = suggest("龘靐齉爩鱻");
        assert!(s.is_empty(), "expected empty, got {s:?}");
        for c in english_candidates("龘靐齉爩鱻") {
            assert!(!c.contains('…'));
        }
    }

    #[test]
    fn short_word_char_fallback() {
        assert_eq!(suggest("好"), "good");
        assert_eq!(suggest("读书"), ""); // 有未知字 -> 放弃
    }

    #[test]
    fn candidates_capped_and_deduped() {
        let v = english_candidates("请发送报告");
        assert!(v.len() <= 3);
        let mut sorted = v.clone();
        sorted.dedup();
        assert_eq!(sorted, v);
    }

    #[test]
    fn grammar_shapes() {
        assert!(grammar_explain("What's your name?").contains("疑问句"));
        assert!(grammar_explain("Please send the report.").contains("祈使句"));
        assert!(
            grammar_explain("See you tomorrow!").contains("将来")
                || grammar_explain("See you tomorrow!").contains("感叹")
        );
        assert!(!grammar_explain("").is_empty());
    }
}
