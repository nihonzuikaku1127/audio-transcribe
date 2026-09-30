package com.example.audiotranscribe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

class MainActivity : AppCompatActivity() {

    private lateinit var srcSpinner: Spinner
    private lateinit var dstSpinner: Spinner
    private lateinit var pickButton: Button
    private lateinit var progressArea: View
    private lateinit var fileNameText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var statusText: TextView
    private lateinit var cancelButton: Button
    private lateinit var resultText: TextView
    private lateinit var copyButton: Button
    private lateinit var shareButton: Button
    private lateinit var saveButton: Button
    private var adView: AdView? = null

    // JobState（掲示板）が変わったら画面を書きかえる
    private val onStateChanged: () -> Unit = { render() }

    // 「ファイルを選ぶ」画面を開いて、選ばれたファイルを受け取る
    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startJob(uri)
        }

    // 通知を出す許可をもらう（Android 13以上で必要）。許可されなくても処理は行う
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            pickFile.launch(arrayOf("audio/*", "video/*"))
        }

    // 「保存」で、保存先とファイル名を選ぶ画面を開く
    private val saveFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeText(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        srcSpinner = findViewById(R.id.srcSpinner)
        dstSpinner = findViewById(R.id.dstSpinner)
        pickButton = findViewById(R.id.pickButton)
        progressArea = findViewById(R.id.progressArea)
        fileNameText = findViewById(R.id.fileNameText)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        statusText = findViewById(R.id.statusText)
        cancelButton = findViewById(R.id.cancelButton)
        resultText = findViewById(R.id.resultText)
        copyButton = findViewById(R.id.copyButton)
        shareButton = findViewById(R.id.shareButton)
        saveButton = findViewById(R.id.saveButton)

        // 言語の選択肢をスピナー（プルダウン）に入れる
        srcSpinner.adapter = spinnerAdapter(Languages.sources.map { it.label })
        dstSpinner.adapter = spinnerAdapter(Languages.targets.map { it.label })
        if (savedInstanceState == null) {
            dstSpinner.setSelection(Languages.targets.indexOfFirst { it.label == "日本語" })
        }

        pickButton.setOnClickListener { onPickClicked() }
        cancelButton.setOnClickListener {
            startService(Intent(this, TranscribeService::class.java).setAction(TranscribeService.ACTION_CANCEL))
        }
        copyButton.setOnClickListener { copyResult() }
        shareButton.setOnClickListener { shareResult() }
        saveButton.setOnClickListener { saveFile.launch(defaultFileName()) }

        setupAd()
    }

    override fun onStart() {
        super.onStart()
        JobState.addListener(onStateChanged)
        render()
    }

    override fun onStop() {
        JobState.removeListener(onStateChanged)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        adView?.resume()
    }

    override fun onPause() {
        adView?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        adView?.destroy()
        super.onDestroy()
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    // ================= 開始 =================

    private fun onPickClicked() {
        val needPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            pickFile.launch(arrayOf("audio/*", "video/*"))
        }
    }

    private fun startJob(uri: Uri) {
        // アプリを閉じてもファイルを読めるように、読み取りの許可を長く持っておく
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val src = Languages.sources[srcSpinner.selectedItemPosition]
        val dst = Languages.targets[dstSpinner.selectedItemPosition]

        val intent = Intent(this, TranscribeService::class.java)
            .setAction(TranscribeService.ACTION_START)
            .setData(uri)
            .putExtra(TranscribeService.EXTRA_SRC, src.voskCode)
            .putExtra(TranscribeService.EXTRA_DST, dst.mlkitCode)
            .putExtra(TranscribeService.EXTRA_NAME, displayName(uri))
        ContextCompat.startForegroundService(this, intent)
    }

    /** ファイルの名前（例: 授業の録音.m4a）を調べる */
    private fun displayName(uri: Uri): String =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "ファイル"

    // ================= 画面の書きかえ =================

    private fun render() {
        val s = JobState
        val running = s.phase == JobState.Phase.RUNNING

        progressArea.visibility = if (s.phase == JobState.Phase.IDLE) View.GONE else View.VISIBLE
        fileNameText.text = s.fileName
        progressBar.progress = s.progress
        progressText.text = "${s.progress}%"
        statusText.text = s.status
        cancelButton.visibility = if (running) View.VISIBLE else View.GONE

        // 処理中は、新しく始めたり言語を変えたりできないようにする
        pickButton.isEnabled = !running
        srcSpinner.isEnabled = !running
        dstSpinner.isEnabled = !running

        resultText.text = buildResultText()

        val hasResult = s.segments.isNotEmpty() && !running
        copyButton.isEnabled = hasResult
        shareButton.isEnabled = hasResult
        saveButton.isEnabled = hasResult
    }

    /** 結果の文章を作る。訳文は青色、認識途中の文は灰色の斜体にする */
    private fun buildResultText(): CharSequence {
        val sb = SpannableStringBuilder()
        for (seg in JobState.segments) {
            sb.append(seg.original).append("\n")
            if (seg.translated != null) {
                appendStyled(sb, seg.translated, Color.parseColor("#1565C0"), Typeface.NORMAL)
                sb.append("\n")
            }
            if (JobState.translating) sb.append("\n")
        }
        if (JobState.partial.isNotEmpty()) {
            appendStyled(sb, JobState.partial + "…", Color.GRAY, Typeface.ITALIC)
        }
        return sb
    }

    private fun appendStyled(sb: SpannableStringBuilder, text: String, color: Int, style: Int) {
        val start = sb.length
        sb.append(text)
        sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(style), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ================= コピー・共有・保存 =================

    private fun copyResult() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("文字起こし", JobState.exportText()))
        Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
    }

    private fun shareResult() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, JobState.exportText())
        startActivity(Intent.createChooser(send, "共有"))
    }

    /** 保存するときのファイル名の初期値（例: 授業の録音_文字起こし.txt） */
    private fun defaultFileName(): String =
        JobState.fileName.substringBeforeLast('.').ifEmpty { "文字起こし" } + "_文字起こし.txt"

    private fun writeText(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)!!.use { it.write(JobState.exportText().toByteArray()) }
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存できませんでした: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ================= 広告 =================

    private fun setupAd() {
        // 広告の仕組みを準備（少し時間がかかるので裏のスレッドで）
        Thread { MobileAds.initialize(this) {} }.start()

        val ad = AdView(this)
        ad.adUnitId = getString(R.string.banner_ad_unit_id)
        ad.setAdSize(AdSize.BANNER)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        findViewById<FrameLayout>(R.id.adContainer).addView(ad, params)
        ad.loadAd(AdRequest.Builder().build())
        adView = ad
    }
}
