package com.example.audiotranscribe

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * ファイルを文字起こし（＋翻訳）する係。
 * AudioDecoder が作った音声データを Vosk に少しずつ渡し、
 * 文ができるたびに JobState（掲示板）に書きこむ。
 */
class Transcriber(private val ctx: Context) {

    companion object {
        // 認識途中の文を画面に出す間隔（音声 0.5 秒ぶんごと）
        private const val PARTIAL_INTERVAL = AudioDecoder.OUT_RATE / 2
    }

    private val state = JobState

    /**
     * srcCode … 元の言語（"en" など）、dstCode … 翻訳先（ML Kitの言語コード。null なら翻訳しない）
     * 時間がかかるので、画面の処理とは別のスレッドから呼ぶこと。
     */
    fun run(uri: Uri, srcCode: String, dstCode: String?) {
        val src = Languages.source(srcCode)

        // ① 音声認識モデルを用意する（初回だけダウンロード）
        val modelDir = ModelManager.ensureModel(ctx, srcCode) { msg -> setStatus(msg) }
        checkCancel()

        // ② 翻訳モデルを用意する（初回だけダウンロード）
        val translator = if (dstCode != null && dstCode != src.mlkitCode) {
            setStatus("翻訳モデルを準備中…（初回のみ30MBほどダウンロードします）")
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(src.mlkitCode)
                .setTargetLanguage(dstCode)
                .build()
            Translation.getClient(options).also { Tasks.await(it.downloadModelIfNeeded()) }
        } else {
            null
        }
        checkCancel()

        // ③ 文字起こし
        setStatus("文字起こし中…")
        val model = Model(modelDir.absolutePath)
        val recognizer = Recognizer(model, AudioDecoder.OUT_RATE.toFloat())
        // 話が長く続くとき、この文字数を超えたら区切る（翻訳しやすくするため）
        val forceLimit = if (srcCode == "ja" || srcCode == "zh") 60 else 200
        var sinceLastPartial = 0
        try {
            AudioDecoder(ctx, uri).decode({ state.cancelRequested }) { samples, count, progress ->
                if (progress >= 0) setProgress((progress * 100).toInt().coerceAtMost(99))

                // acceptWaveForm が true を返したら「ひと区切りの文が確定した」という意味
                if (recognizer.acceptWaveForm(samples, count)) {
                    addSentence(field(recognizer.result, "text"), srcCode, translator)
                    sinceLastPartial = 0
                } else {
                    sinceLastPartial += count
                    if (sinceLastPartial >= PARTIAL_INTERVAL) {
                        sinceLastPartial = 0
                        val partial = clean(field(recognizer.partialResult, "partial"), srcCode)
                        if (partial.length > forceLimit) {
                            addSentence(field(recognizer.finalResult, "text"), srcCode, translator)
                        } else if (partial != state.partial) {
                            state.partial = partial
                            state.changed()
                        }
                    }
                }
            }
            // ファイルの最後に残っている分を確定させる
            addSentence(field(recognizer.finalResult, "text"), srcCode, translator)
            setProgress(100)
        } finally {
            recognizer.close()
            model.close()
            translator?.close()
        }
    }

    /** 確定した文を掲示板に追加し、必要なら翻訳する */
    private fun addSentence(raw: String, srcCode: String, translator: Translator?) {
        state.partial = ""
        val text = clean(raw, srcCode)
        if (text.isEmpty()) {
            state.changed()
            return
        }
        state.segments += Segment(text)
        state.changed()

        if (translator != null) {
            val translated = runCatching { Tasks.await(translator.translate(text)) }.getOrNull()
            val index = state.segments.lastIndex
            state.segments[index] = Segment(text, translated ?: "（翻訳できませんでした）")
            state.changed()
        }
    }

    private fun setStatus(msg: String) {
        checkCancel()
        state.status = msg
        state.changed()
    }

    private fun setProgress(pct: Int) {
        if (pct != state.progress) {
            state.progress = pct
            state.changed()
        }
    }

    private fun checkCancel() {
        if (state.cancelRequested) throw CancelledException()
    }

    /** Voskの結果（JSON形式）から、key の文字を取り出す */
    private fun field(json: String, key: String): String =
        runCatching { JSONObject(json).optString(key, "") }.getOrDefault("")

    // 日本語・中国語のモデルは単語の間にスペースが入るので取り除く
    private fun clean(text: String, lang: String): String =
        if (lang == "ja" || lang == "zh") text.replace(" ", "").trim() else text.trim()
}
