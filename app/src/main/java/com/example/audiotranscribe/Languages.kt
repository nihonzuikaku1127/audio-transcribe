package com.example.audiotranscribe

import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * 元の言語（文字起こしする言語）
 * voskCode … ModelManager で使う音声認識モデルの名前
 * mlkitCode … ML Kit 翻訳で使う言語コード
 */
class SourceLang(val label: String, val voskCode: String, val mlkitCode: String)

/** 翻訳先の言語。mlkitCode が null のときは「翻訳しない」 */
class TargetLang(val label: String, val mlkitCode: String?)

object Languages {

    val sources = listOf(
        SourceLang("英語", "en", TranslateLanguage.ENGLISH),
        SourceLang("日本語", "ja", TranslateLanguage.JAPANESE),
        SourceLang("中国語", "zh", TranslateLanguage.CHINESE),
        SourceLang("韓国語", "ko", TranslateLanguage.KOREAN),
        SourceLang("スペイン語", "es", TranslateLanguage.SPANISH),
        SourceLang("フランス語", "fr", TranslateLanguage.FRENCH),
        SourceLang("ドイツ語", "de", TranslateLanguage.GERMAN),
    )

    val targets = listOf(
        TargetLang("翻訳しない", null),
        TargetLang("日本語", TranslateLanguage.JAPANESE),
        TargetLang("英語", TranslateLanguage.ENGLISH),
        TargetLang("中国語", TranslateLanguage.CHINESE),
        TargetLang("韓国語", TranslateLanguage.KOREAN),
        TargetLang("スペイン語", TranslateLanguage.SPANISH),
        TargetLang("フランス語", TranslateLanguage.FRENCH),
        TargetLang("ドイツ語", TranslateLanguage.GERMAN),
    )

    fun source(voskCode: String): SourceLang = sources.first { it.voskCode == voskCode }
}
