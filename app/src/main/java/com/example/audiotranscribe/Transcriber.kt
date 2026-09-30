package com.example.audiotranscribe

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * ファイルを文字起こしする係。
 * AudioDecoder が作った音声データを、Vosk（音声認識エンジン）に少しずつ渡していく。
 */
class Transcriber(private val ctx: Context) {

    /**
     * uri のファイルを lang の言語として文字起こしして、結果の文章を返す。
     * 時間がかかるので、画面の処理とは別のスレッドから呼ぶこと。
     */
    fun transcribe(uri: Uri, lang: String, status: (String) -> Unit): String {
        // 音声認識モデルを用意する（初回だけダウンロード）
        val modelDir = ModelManager.ensureModel(ctx, lang, status)

        status("文字起こし中…")
        val model = Model(modelDir.absolutePath)
        val recognizer = Recognizer(model, AudioDecoder.OUT_RATE.toFloat())
        val sentences = mutableListOf<String>()
        try {
            AudioDecoder(ctx, uri).decode { samples, count ->
                // acceptWaveForm が true を返したら「ひと区切りの文が確定した」という意味
                if (recognizer.acceptWaveForm(samples, count)) {
                    addSentence(sentences, recognizer.result, lang)
                }
            }
            // ファイルの最後に残っている分を確定させる
            addSentence(sentences, recognizer.finalResult, lang)
        } finally {
            recognizer.close()
            model.close()
        }
        return sentences.joinToString("\n")
    }

    /** Voskの結果（JSON形式）から文章を取り出して、空でなければリストに追加する */
    private fun addSentence(list: MutableList<String>, json: String, lang: String) {
        val raw = runCatching { JSONObject(json).optString("text", "") }.getOrDefault("")
        val text = clean(raw, lang)
        if (text.isNotEmpty()) list += text
    }

    // 日本語・中国語のモデルは単語の間にスペースが入るので取り除く
    private fun clean(text: String, lang: String): String =
        if (lang == "ja" || lang == "zh") text.replace(" ", "").trim() else text.trim()
}
