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

    private const val DICT_ASSET = "en_dict.tsv"
    private const val DICT_FILE = "en_dict.tsv"
    private const val KEY_DICT_VERSION = "en_dict_version"

    fun dictPath(context: Context): String = File(context.filesDir, DICT_FILE).absolutePath

    /** 确保词典就绪；返回是否可读（失败时引擎自动退回内建小词表）。 */
    fun ensureEnglishDict(context: Context): Boolean {
        val target = File(context.filesDir, DICT_FILE)
        val prefs = context.getSharedPreferences("typesake_prefs", Context.MODE_PRIVATE)
        val version = BuildConfig.VERSION_CODE
        if (target.exists() && target.length() > 0 && prefs.getInt(KEY_DICT_VERSION, -1) == version) {
            return true
        }
        return try {
            context.assets.open(DICT_ASSET).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            prefs.edit().putInt(KEY_DICT_VERSION, version).apply()
            true
        } catch (_: Exception) {
            target.delete()
            false
        }
    }
}
