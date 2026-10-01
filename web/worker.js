// 重い処理（文字起こし・翻訳）を画面とは別のスレッドで行う係。
// 画面（app.js）から音声データを受け取り、できた部分から順に送り返す。
// キャンセルのときは画面側がこのワーカーごと止める（terminate）。

import { pipeline, env } from 'https://cdn.jsdelivr.net/npm/@huggingface/transformers@4.3.0';

// モデルは Hugging Face から読みこみ、ブラウザのキャッシュに保存する（2回目以降は再利用）
env.allowLocalModels = false;
env.useBrowserCache = true;

const SAMPLE_RATE = 16000;
// Whisper は一度に30秒までしか聞けないので、28秒以内に区切って渡す
const MAX_CHUNK_SEC = 28;
// 区切る位置は、この秒数以降でいちばん静かなところにする（言葉の途中で切らないため）
const MIN_CHUNK_SEC = 20;
// これより静かな区間は無音とみなして飛ばす（無音だと Whisper がありもしない文を作りやすい）
const SILENCE_RMS = 0.002;

const TRANSLATION_MODEL = 'Xenova/nllb-200-distilled-600M';

let asr = null;
let asrKey = '';
let translator = null;

self.onmessage = async (e) => {
  const msg = e.data;
  if (msg.type !== 'run') return;
  try {
    await run(msg);
    post({ type: 'done' });
  } catch (err) {
    console.error(err);
    post({ type: 'error', message: err?.message || String(err) });
  }
};

function post(msg) {
  self.postMessage(msg);
}

/**
 * msg.audio … 16kHz モノラルの音声（Float32Array）
 * msg.whisperModel … 'onnx-community/whisper-base' など
 * msg.language … Whisper の言語コード（'ja' など）
 * msg.nllbSrc / msg.nllbTgt … NLLB の言語コード。nllbTgt が null なら、ここでは翻訳しない
 */
async function run(msg) {
  // ① 音声認識モデルを用意する
  post({ type: 'status', text: '音声認識モデルを準備中…' });
  await loadAsr(msg.whisperModel);

  // ② 翻訳モデルを用意する（必要なときだけ）
  if (msg.nllbTgt) {
    post({ type: 'status', text: '翻訳モデルを準備中…（初回のみ大きなダウンロードがあります）' });
    await loadTranslator();
  }

  // ③ 区切りながら文字起こし（＋翻訳）
  const chunks = splitChunks(msg.audio);
  post({ type: 'status', text: msg.nllbTgt ? '文字起こし・翻訳中…' : '文字起こし中…' });
  post({ type: 'progress', pct: 0 });

  let index = 0;
  for (let i = 0; i < chunks.length; i++) {
    const [start, end] = chunks[i];
    const piece = msg.audio.subarray(start, end);

    if (rms(piece) >= SILENCE_RMS) {
      const out = await asr(piece, { language: msg.language, task: 'transcribe' });
      const text = cleanText(out.text);
      if (text) {
        const myIndex = index++;
        post({ type: 'segment', index: myIndex, text, start: start / SAMPLE_RATE });
        if (msg.nllbTgt) {
          const translated = await translate(text, msg.nllbSrc, msg.nllbTgt);
          post({ type: 'translation', index: myIndex, text: translated });
        }
      }
    }
    post({ type: 'progress', pct: Math.floor(((i + 1) / chunks.length) * 100) });
  }
}

// ================= モデルの読みこみ =================

/** 読みこみの進み具合を画面に送る */
function progressCallback(label) {
  return (info) => {
    if (info.status === 'progress_total') {
      post({ type: 'download', label, pct: Math.floor(info.progress), loaded: info.loaded, total: info.total });
    }
  };
}

async function hasWebGPU() {
  try {
    return !!(self.navigator?.gpu && (await self.navigator.gpu.requestAdapter()));
  } catch {
    return false;
  }
}

async function loadAsr(model) {
  if (asr && asrKey === model) return;
  asr = null;
  const progress_callback = progressCallback('音声認識モデル');

  // 速いWebGPU（パソコンのGPU）が使えればそれを使い、だめなら普通の方法（WASM）にする
  if (await hasWebGPU()) {
    try {
      asr = await pipeline('automatic-speech-recognition', model, {
        device: 'webgpu',
        dtype: { encoder_model: 'fp32', decoder_model_merged: 'q4' },
        progress_callback,
      });
    } catch (err) {
      console.warn('WebGPU が使えなかったので WASM で動かします', err);
    }
  }
  if (!asr) {
    asr = await pipeline('automatic-speech-recognition', model, {
      device: 'wasm',
      dtype: 'q8',
      progress_callback,
    });
  }
  asrKey = model;
}

async function loadTranslator() {
  if (translator) return;
  translator = await pipeline('translation', TRANSLATION_MODEL, {
    device: 'wasm',
    dtype: 'q8',
    progress_callback: progressCallback('翻訳モデル'),
  });
}

// ================= 翻訳 =================

/** 文ごとに分けて翻訳し、つなげて返す（長い文章を一度に渡すと訳が抜けやすいため） */
async function translate(text, src, tgt) {
  const sentences = splitSentences(text);
  const parts = [];
  for (const s of sentences) {
    try {
      const out = await translator(s, { src_lang: src, tgt_lang: tgt, max_new_tokens: 256 });
      parts.push(out[0].translation_text.trim());
    } catch (err) {
      console.warn(err);
      parts.push('（翻訳できませんでした）');
    }
  }
  const noSpace = tgt === 'jpn_Jpan' || tgt === 'zho_Hans';
  return parts.join(noSpace ? '' : ' ');
}

function splitSentences(text) {
  const list = text
    .split(/(?<=[。．！？!?]|[.](?=\s))\s*/u)
    .map((s) => s.trim())
    .filter(Boolean);
  return list.length ? list : [text];
}

// ================= 音声の区切り =================

/** 音声を28秒以内のかたまりに分ける。[開始位置, 終了位置] の配列を返す */
function splitChunks(audio) {
  const maxLen = MAX_CHUNK_SEC * SAMPLE_RATE;
  const minLen = MIN_CHUNK_SEC * SAMPLE_RATE;
  const frame = SAMPLE_RATE / 10; // 0.1秒ごとに音の大きさを調べる
  const chunks = [];
  let start = 0;
  while (start < audio.length) {
    if (audio.length - start <= maxLen) {
      chunks.push([start, audio.length]);
      break;
    }
    // 20〜28秒のあいだで、いちばん静かな0.1秒を探してそこで切る
    let best = start + maxLen;
    let bestEnergy = Infinity;
    for (let pos = start + minLen; pos + frame <= start + maxLen; pos += frame) {
      const energy = rms(audio.subarray(pos, pos + frame));
      if (energy < bestEnergy) {
        bestEnergy = energy;
        best = pos + frame / 2;
      }
    }
    chunks.push([start, best]);
    start = best;
  }
  return chunks;
}

function rms(samples) {
  if (samples.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
  return Math.sqrt(sum / samples.length);
}

/** Whisper が無音や雑音から作りがちな決まり文句や記号だけの結果を取りのぞく */
function cleanText(text) {
  const t = (text || '').trim();
  if (!t) return '';
  if (/^[\s\p{P}\p{S}]*$/u.test(t)) return '';
  if (/^[\[(（【].*[\])）】]$/u.test(t)) return ''; // [音楽] (拍手) など
  return t;
}
