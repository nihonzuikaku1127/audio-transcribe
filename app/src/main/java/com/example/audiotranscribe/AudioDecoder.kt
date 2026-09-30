package com.example.audiotranscribe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

/**
 * 音声・動画ファイル（mp3, m4a, wav, mp4 など）を開いて、
 * Voskが読める形（16000Hz・モノラル・16bit の生の音声データ）に変換する係。
 *
 * 流れ：
 *   MediaExtractor … ファイルの中から「音声の部分」だけを取り出す
 *   MediaCodec     … 圧縮された音声（mp3やAAC）を、生の音声データに戻す（デコード）
 *   toMono / Resampler … ステレオ→モノラル、44100Hzなど→16000Hz に変換する
 */
class AudioDecoder(private val ctx: Context, private val uri: Uri) {

    companion object {
        const val OUT_RATE = 16000
        private const val TIMEOUT_US = 10_000L
    }

    /**
     * ファイルを最初から最後までデコードする。
     * 変換できた音声が少したまるたびに onPcm が呼ばれる。
     * progress は「ファイル全体のうち、どこまで進んだか」（0.0〜1.0）。長さがわからないときは -1。
     * isCancelled が true を返したら、途中でやめて CancelledException を投げる。
     */
    fun decode(
        isCancelled: () -> Boolean,
        onPcm: (samples: ShortArray, count: Int, progress: Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(ctx, uri, null)

        // ファイルの中から音声トラックを探す（動画ファイルには映像トラックもあるため）
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IOException("このファイルには音声が入っていません")
        }
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        // ファイルの長さ（マイクロ秒 = 100万分の1秒）。進捗の％を計算するのに使う
        val durationUs =
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else -1L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var isFloat = false
        var resampler = Resampler(sampleRate, OUT_RATE)

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (isCancelled()) throw CancelledException()

                // ① ファイルから圧縮データを読んで、デコーダーに渡す
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            // ファイルの終わり
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // ② デコーダーから生の音声データを受け取る
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 実際の出力形式がわかったので、それに合わせる
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(sampleRate, OUT_RATE)
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIndex)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            val mono = toMono(buf, channels, isFloat)
                            val out = resampler.process(mono)
                            val progress =
                                if (durationUs > 0) (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f) else -1f
                            if (out.isNotEmpty()) onPcm(out, out.size, progress)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }

    /** 複数チャンネル（ステレオなど）の音を平均して1チャンネルにする */
    private fun toMono(buf: java.nio.ByteBuffer, channels: Int, isFloat: Boolean): ShortArray {
        if (isFloat) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / channels
            return ShortArray(frames) { i ->
                var sum = 0f
                for (c in 0 until channels) sum += fb.get(i * channels + c)
                ((sum / channels).coerceIn(-1f, 1f) * 32767).toInt().toShort()
            }
        } else {
            val sb = buf.asShortBuffer()
            val frames = sb.remaining() / channels
            return ShortArray(frames) { i ->
                var sum = 0
                for (c in 0 until channels) sum += sb.get(i * channels + c)
                (sum / channels).toShort()
            }
        }
    }

    /**
     * サンプリングレート（1秒あたりの音の点の数）を変換する。
     * 例えば 44100Hz の音を 16000Hz にするときは、
     * 元の音の点と点の間を直線でつないで（線形補間）、新しい位置の値を読み取る。
     */
    private class Resampler(inRate: Int, outRate: Int) {
        private val step = inRate.toDouble() / outRate
        // 次に読み取る位置。-1 以上 0 未満のときは「前のかたまりの最後の点」との間を指す
        private var pos = 0.0
        private var prev: Short = 0

        fun process(input: ShortArray): ShortArray {
            val n = input.size
            if (n == 0) return ShortArray(0)
            val out = ShortArray(((n - pos) / step).toInt() + 2)
            var count = 0
            while (pos < n - 1) {
                val i = kotlin.math.floor(pos).toInt()
                val frac = pos - i
                val a = if (i < 0) prev else input[i]
                val b = input[i + 1]
                out[count++] = (a + (b - a) * frac).toInt().toShort()
                pos += step
            }
            pos -= n
            prev = input[n - 1]
            return out.copyOf(count)
        }
    }
}

/** ユーザーがキャンセルボタンを押したときに投げる */
class CancelledException : Exception("キャンセルしました")
