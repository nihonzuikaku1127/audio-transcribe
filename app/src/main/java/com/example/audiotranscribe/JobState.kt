package com.example.audiotranscribe

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** 文字起こしできた1文と、その翻訳 */
data class Segment(val original: String, val translated: String? = null)

/**
 * いま行っている処理の「状態」をまとめて持っておく掲示板のようなもの。
 *
 * 裏で動く TranscribeService がここに書きこみ、
 * 画面（MainActivity）と通知がここを読んで表示する。
 * アプリの画面を閉じても、この掲示板はサービスと一緒に残るので、
 * もう一度アプリを開けば続きが表示される。
 */
object JobState {

    enum class Phase { IDLE, RUNNING, DONE, CANCELLED, ERROR }

    @Volatile var phase = Phase.IDLE
    @Volatile var progress = 0          // 0〜100（％）
    @Volatile var status = ""           // 「文字起こし中…」などのメッセージ
    @Volatile var partial = ""          // まだ確定していない、認識途中の文
    @Volatile var fileName = ""
    @Volatile var translating = false   // 翻訳もするかどうか
    @Volatile var cancelRequested = false
    val segments = CopyOnWriteArrayList<Segment>()

    // ---- 変化を知らせるしくみ（画面や通知が「変わったら教えて」と登録しておく） ----

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private val pending = AtomicBoolean(false)

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /**
     * 「状態が変わったよ」と知らせる。どのスレッドから呼んでもよい。
     * 短い間に何回呼ばれても、画面の書きかえは1回にまとめる（重くならないように）。
     */
    fun changed() {
        if (pending.compareAndSet(false, true)) {
            main.post {
                pending.set(false)
                listeners.toList().forEach { it() }
            }
        }
    }

    fun reset(name: String, translate: Boolean) {
        phase = Phase.RUNNING
        progress = 0
        status = "準備中…"
        partial = ""
        fileName = name
        translating = translate
        cancelRequested = false
        segments.clear()
        changed()
    }

    /** 文字起こしした全文（翻訳なし） */
    fun originalText(): String = segments.joinToString("\n") { it.original }

    /** コピー・共有・保存に使う文章。翻訳があるときは「原文 → 訳文」を交互に並べる */
    fun exportText(): String =
        if (!translating) {
            originalText()
        } else {
            segments.joinToString("\n\n") { s ->
                if (s.translated.isNullOrEmpty()) s.original else "${s.original}\n${s.translated}"
            }
        }
}
