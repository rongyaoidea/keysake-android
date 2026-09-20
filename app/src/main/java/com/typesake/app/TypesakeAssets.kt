package com.typesake.app

import android.content.Context
import java.io.File

/**
 * 把打包在 assets 里的离线词典（CC-CEDICT 生成的 en_dict.tsv）拷到 filesDir，
 * Rust 引擎启动时从该路径载入。版本升级时自动重拷。
 *
 * 放在 assets 而不是编进 .so：APK 里只存一份（且被压缩），三个 ABI 不再各背一份。
 */
object TypesakeAssets {

    /** (assets 名, filesDir 名) 列表 */
    private val FILES = listOf(
        "en_dict.tsv" to "en_dict.tsv",
        "s2t.tsv" to "s2t.tsv",
        "t2s.tsv" to "t2s.tsv",
        "lex.bin" to "lex.bin",
        "sentbank.bin" to "sentbank.bin",
    )
    private const val KEY_DICT_VERSION = "en_dict_version"

    /** 确保随包数据（英文词典 + 简繁表）就绪；返回是否全部可读。 */
    fun ensureEnglishDict(context: Context): Boolean {
        val prefs = context.getSharedPreferences("typesake_prefs", Context.MODE_PRIVATE)
        val version = BuildConfig.VERSION_CODE
        val upToDate = prefs.getInt(KEY_DICT_VERSION, -1) == version &&
            FILES.all { File(context.filesDir, it.second).let { f -> f.exists() && f.length() > 0 } }
        if (upToDate) return true
        var ok = true
        for ((asset, name) in FILES) {
            val target = File(context.filesDir, name)
            try {
                context.assets.open(asset).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                target.delete()
                ok = false
            }
        }
        if (ok) prefs.edit().putInt(KEY_DICT_VERSION, version).apply()
        return ok
    }
}
