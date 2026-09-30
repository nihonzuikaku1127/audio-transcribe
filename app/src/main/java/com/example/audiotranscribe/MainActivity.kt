package com.example.audiotranscribe

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var pickButton: Button

    // 「ファイルを選ぶ」画面を開いて、選ばれたファイルを受け取る仕組み
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startTranscribe(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        result = findViewById(R.id.resultText)
        pickButton = findViewById(R.id.pickButton)

        pickButton.setOnClickListener {
            // 音声ファイルと動画ファイルだけを選べるようにする
            pickFile.launch(arrayOf("audio/*", "video/*"))
        }
    }

    private fun selectedLang(): String = when {
        findViewById<RadioButton>(R.id.radioJa).isChecked -> "ja"
        findViewById<RadioButton>(R.id.radioZh).isChecked -> "zh"
        else -> "en"
    }

    private fun startTranscribe(uri: Uri) {
        val lang = selectedLang()
        pickButton.isEnabled = false   // 処理中にもう一度押せないようにする
        result.text = ""
        status.text = "準備中…"

        // 文字起こしは時間がかかるので、別のスレッド（裏方の作業係）で行う。
        // 画面を書きかえるときは runOnUiThread { } の中で行う決まりになっている。
        Thread {
            try {
                val text = Transcriber(this).transcribe(uri, lang) { msg ->
                    runOnUiThread { status.text = msg }
                }
                runOnUiThread {
                    result.text = text
                    status.text = finishedMessage(text)
                }
            } catch (e: Exception) {
                Log.e("AudioTranscribe", "error", e)
                runOnUiThread { status.text = "エラー: ${e.message}" }
            } finally {
                runOnUiThread { pickButton.isEnabled = true }
            }
        }.start()
    }

    /** 文字起こしが終わったときに、ステータス欄に出すメッセージを作る */
    private fun finishedMessage(text: String): String {
        // TODO: ここを自分で書いてみよう！
        //  ・text が空っぽ（何も聞き取れなかった）なら「音声を聞き取れませんでした」
        //  ・そうでなければ「完了！（123文字）」のように、文字数も入れたメッセージ
        //  を return で返すようにしてください。
        //
        // ヒント：
        //  ・空っぽかどうかは text.isEmpty() で調べられる（空なら true）
        //  ・文字数は text.length でわかる
        //  ・文字列の中に値を入れるには "完了！（${text.length}文字）" のように ${ } を使う
        //  ・if (条件) { return "…" } のように書ける
        return "完了"
    }
}
