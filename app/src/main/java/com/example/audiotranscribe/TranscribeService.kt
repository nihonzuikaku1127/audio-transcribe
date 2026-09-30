package com.example.audiotranscribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * 裏で文字起こしを続ける係（フォアグラウンドサービス）。
 * 画面を閉じても、通知が出ている間はAndroidがアプリを止めないでくれる。
 * 終わったら「完了」の通知を出す。
 */
class TranscribeService : Service() {

    companion object {
        const val ACTION_START = "com.example.audiotranscribe.START"
        const val ACTION_CANCEL = "com.example.audiotranscribe.CANCEL"
        const val EXTRA_SRC = "src"
        const val EXTRA_DST = "dst"
        const val EXTRA_NAME = "name"
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2
        private const val TAG = "AudioTranscribe"
    }

    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedProgress = -1
    private val onStateChanged: () -> Unit = { updateProgressNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                JobState.cancelRequested = true
                JobState.status = "キャンセルしています…"
                JobState.changed()
            }
            ACTION_START -> {
                createChannels()
                ServiceCompat.startForeground(
                    this, NOTIF_PROGRESS, buildProgressNotification(),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
                )
                val uri = intent.data
                val src = intent.getStringExtra(EXTRA_SRC)
                // すでに処理中なら、2つ目は始めない
                if (JobState.phase == JobState.Phase.RUNNING) return START_NOT_STICKY
                if (uri == null || src == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                start(uri, src, intent.getStringExtra(EXTRA_DST), intent.getStringExtra(EXTRA_NAME) ?: "")
            }
        }
        return START_NOT_STICKY
    }

    private fun start(uri: Uri, src: String, dst: String?, name: String) {
        JobState.reset(name, translate = dst != null)
        JobState.addListener(onStateChanged)

        // 画面が消えてもCPUが眠らないようにする（処理が終わったら必ず解除する）
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioTranscribe:job")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        Thread {
            try {
                Transcriber(this).run(uri, src, dst)
                JobState.phase = JobState.Phase.DONE
                JobState.status = finishedMessage(JobState.originalText())
            } catch (e: CancelledException) {
                JobState.phase = JobState.Phase.CANCELLED
                JobState.status = "キャンセルしました"
            } catch (e: Exception) {
                Log.e(TAG, "error", e)
                JobState.phase = JobState.Phase.ERROR
                JobState.status = "エラー: ${e.message}"
            }
            JobState.partial = ""
            JobState.changed()
            // 画面の処理を行うスレッドに戻ってから後片付けをする
            android.os.Handler(mainLooper).post { finish() }
        }.start()
    }

    /** 文字起こしが終わったときに出すメッセージを作る */
    private fun finishedMessage(text: String): String {
        if (text.isEmpty()) {
            return "音声を聞き取れませんでした"
        }
        return "完了！（${text.length}文字）"
    }

    private fun finish() {
        JobState.removeListener(onStateChanged)
        runCatching { wakeLock?.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        nm.notify(NOTIF_DONE, buildDoneNotification())
        stopSelf()
    }

    // ---- 通知 ----

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "処理中", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "完了のお知らせ", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** 通知をタップしたらアプリの画面を開く */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildProgressNotification(): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val pct = JobState.progress
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("文字起こし中 $pct%")
            .setContentText(JobState.fileName)
            .setProgress(100, pct, false)
            .setContentIntent(openAppIntent())
            .addAction(0, "キャンセル", cancelIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateProgressNotification() {
        if (JobState.phase != JobState.Phase.RUNNING) return
        if (JobState.progress == lastNotifiedProgress) return
        lastNotifiedProgress = JobState.progress
        nm.notify(NOTIF_PROGRESS, buildProgressNotification())
    }

    private fun buildDoneNotification(): Notification {
        val title = when (JobState.phase) {
            JobState.Phase.DONE -> "文字起こしが終わりました"
            JobState.Phase.CANCELLED -> "キャンセルしました"
            else -> "エラーで止まりました"
        }
        return NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("${JobState.fileName}：${JobState.status}")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
    }

    override fun onDestroy() {
        // 万一サービスが途中で終了させられたら、処理も止める
        if (JobState.phase == JobState.Phase.RUNNING) JobState.cancelRequested = true
        JobState.removeListener(onStateChanged)
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }
}
