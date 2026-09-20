//! JNI 边界。
//!
//! 约定：
//! - 热路径（每键调用）返回 `\u{1F}` 分隔串，避免 JSON 解析开销；
//! - 冷路径（收藏/初始化）返回 JSON；
//! - **所有导出函数都经 [`guarded`] 包裹**：Rust panic 不会跨 FFI 传播，
//!   最坏情况返回空串，绝不连带杀掉输入法进程。

use crate::{engine, english, join_delim, store};
use jni::objects::{JClass, JString};
use jni::JNIEnv;

fn jstr_to_rust(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|j| j.to_str().unwrap_or_default().to_owned())
        .unwrap_or_default()
}

fn rust_to_jstr<'local>(env: &mut JNIEnv<'local>, s: &str) -> JString<'local> {
    env.new_string(s)
        .unwrap_or_else(|_| env.new_string("").expect("empty JString must succeed"))
}

/// 捕获 panic：FFI 边界不允许 unwind（否则会 abort 进程）。
fn guarded<F: FnOnce() -> String>(f: F) -> String {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_default()
}

fn ok_json(msg: &str) -> String {
    format!("{{\"ok\":true,{msg}}}")
}

fn err_json(e: &str) -> String {
    format!(
        "{{\"ok\":false,\"err\":{}}}",
        serde_json::to_string(e).unwrap_or_else(|_| "\"error\"".to_string())
    )
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_initStorage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    jdir: JString<'local>,
) -> JString<'local> {
    let dir = jstr_to_rust(&mut env, &jdir);
    let out = guarded(|| match store::init(&dir) {
        Ok((saved, pins)) => ok_json(&format!(
            "\"saved\":{saved},\"pins\":{pins},\"lex\":{}",
            engine::lexicon_size()
        )),
        Err(e) => err_json(&e),
    });
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_lexiconSize<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let out = guarded(|| engine::lexicon_size().to_string());
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_candidatesFor<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JString<'local> {
    let s = jstr_to_rust(&mut env, &input);
    let out = guarded(|| join_delim(&engine::candidates(&s, 8)));
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_pickCandidate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    jpinyin: JString<'local>,
    jword: JString<'local>,
) -> JString<'local> {
    let p = jstr_to_rust(&mut env, &jpinyin);
    let w = jstr_to_rust(&mut env, &jword);
    let out = guarded(|| {
        let updated = engine::record_pick(&p, &w, 8);
        let _ = store::persist_l0();
        join_delim(&updated)
    });
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_predictNext<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    jword: JString<'local>,
) -> JString<'local> {
    let w = jstr_to_rust(&mut env, &jword);
    let out = guarded(|| join_delim(&engine::predict_next(&w, 6)));
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_suggestEnglish<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JString<'local> {
    let s = jstr_to_rust(&mut env, &input);
    let out = guarded(|| english::suggest(&s));
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_englishCandidates<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JString<'local> {
    let s = jstr_to_rust(&mut env, &input);
    let out = guarded(|| join_delim(&english::english_candidates(&s)));
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_grammarExplain<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JString<'local> {
    let s = jstr_to_rust(&mut env, &input);
    let out = guarded(|| english::grammar_explain(&s));
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
    let out = guarded(|| match store::save_phrase(&cn, &en) {
        Ok(p) => serde_json::to_string(&p).unwrap_or_else(|_| ok_json("\"saved\":1")),
        Err(e) => err_json(&e),
    });
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_listSaved<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let out =
        guarded(|| serde_json::to_string(&store::list_saved()).unwrap_or_else(|_| "[]".into()));
    rust_to_jstr(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_typesake_app_TypesakeCore_clearSaved<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let out = guarded(|| match store::clear_saved() {
        Ok(n) => ok_json(&format!("\"cleared\":{n}")),
        Err(e) => err_json(&e),
    });
    rust_to_jstr(&mut env, &out)
}
