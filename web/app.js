// 画面の係。ファイル選択・言語選択・進捗や結果の表示・コピー/共有/保存を担当する。
// 重い処理は worker.js（別スレッド）に任せる。

/**
 * 元の言語（文字起こしする言語）
 * whisper … Whisper の言語コード、nllb … NLLB 翻訳の言語コード、bcp … ブラウザ内蔵翻訳の言語コード
 */
const SOURCES = [
  { label: '英語', whisper: 'en', nllb: 'eng_Latn', bcp: 'en' },
  { label: '日本語', whisper: 'ja', nllb: 'jpn_Jpan', bcp: 'ja' },
  { label: '中国語', whisper: 'zh', nllb: 'zho_Hans', bcp: 'zh' },
  { label: '韓国語', whisper: 'ko', nllb: 'kor_Hang', bcp: 'ko' },
  { label: 'スペイン語', whisper: 'es', nllb: 'spa_Latn', bcp: 'es' },
  { label: 'フランス語', whisper: 'fr', nllb: 'fra_Latn', bcp: 'fr' },
  { label: 'ドイツ語', whisper: 'de', nllb: 'deu_Latn', bcp: 'de' },
];

/** 翻訳先。nllb が null のときは「翻訳しない」 */
const TARGETS = [
  { label: '翻訳しない', nllb: null, bcp: null },
  { label: '日本語', nllb: 'jpn_Jpan', bcp: 'ja' },
  { label: '英語', nllb: 'eng_Latn', bcp: 'en' },
  { label: '中国語', nllb: 'zho_Hans', bcp: 'zh' },
  { label: '韓国語', nllb: 'kor_Hang', bcp: 'ko' },
  { label: 'スペイン語', nllb: 'spa_Latn', bcp: 'es' },
  { label: 'フランス語', nllb: 'fra_Latn', bcp: 'fr' },
  { label: 'ドイツ語', nllb: 'deu_Latn', bcp: 'de' },
];

const $ = (id) => document.getElementById(id);
const els = {
  drop: $('drop'), file: $('file'), fileLabel: $('fileLabel'),
  srcLang: $('srcLang'), dstLang: $('dstLang'), model: $('model'),
  start: $('start'), cancel: $('cancel'),
  progressCard: $('progressCard'), status: $('status'), percent: $('percent'), barFill: $('barFill'), hint: $('hint'),
  resultCard: $('resultCard'), result: $('result'),
  copy: $('copy'), share: $('share'), save: $('save'),
  storageInfo: $('storageInfo'), clearCache: $('clearCache'), toast: $('toast'),
};

// ================= 状態 =================

let selectedFile = null;
let worker = null;
let jobId = 0;           // キャンセル後に古い結果が混ざらないようにするための番号
let running = false;
let segments = [];       // { original, translated }
let translating = false; // 翻訳ありで処理したか
let wakeLock = null;

// ================= 初期化 =================

for (const s of SOURCES) els.srcLang.add(new Option(s.label, s.whisper));
TARGETS.forEach((t, i) => els.dstLang.add(new Option(t.label, String(i))));
restoreSettings();

els.file.addEventListener('change', () => setFile(els.file.files[0]));
['dragover', 'dragenter'].forEach((ev) =>
  els.drop.addEventListener(ev, (e) => { e.preventDefault(); els.drop.classList.add('over'); }));
['dragleave', 'drop'].forEach((ev) =>
  els.drop.addEventListener(ev, () => els.drop.classList.remove('over')));
els.drop.addEventListener('drop', (e) => {
  e.preventDefault();
  if (e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]);
});
for (const el of [els.srcLang, els.dstLang, els.model]) el.addEventListener('change', saveSettings);

els.start.addEventListener('click', start);
els.cancel.addEventListener('click', cancel);
els.copy.addEventListener('click', copyResult);
els.share.addEventListener('click', shareResult);
els.save.addEventListener('click', saveResult);
els.clearCache.addEventListener('click', clearCache);
if (!navigator.share) els.share.hidden = true;

window.addEventListener('beforeunload', (e) => {
  if (running) { e.preventDefault(); e.returnValue = ''; }
});
document.addEventListener('visibilitychange', () => {
  if (running && document.visibilityState === 'visible') requestWakeLock();
});

showStorageInfo();
// モデルが勝手に消されにくくする（対応ブラウザのみ）
navigator.storage?.persist?.().catch(() => {});

function setFile(file) {
  if (!file || running) return;
  selectedFile = file;
  els.fileLabel.innerHTML = '';
  const name = document.createElement('strong');
  name.textContent = file.name;
  const size = document.createElement('small');
  size.textContent = `（${formatBytes(file.size)}）タップして選び直す`;
  els.fileLabel.append(name, document.createElement('br'), size);
  els.start.disabled = false;
}

function saveSettings() {
  try {
    localStorage.setItem('settings', JSON.stringify({
      src: els.srcLang.value, dst: els.dstLang.value, model: els.model.value,
    }));
  } catch { /* 保存できなくても動く */ }
}

function restoreSettings() {
  try {
    const s = JSON.parse(localStorage.getItem('settings') || 'null');
    if (!s) return;
    if ([...els.srcLang.options].some((o) => o.value === s.src)) els.srcLang.value = s.src;
    if ([...els.dstLang.options].some((o) => o.value === s.dst)) els.dstLang.value = s.dst;
    if ([...els.model.options].some((o) => o.value === s.model)) els.model.value = s.model;
  } catch { /* 読めなくても動く */ }
}

// ================= 処理の開始・キャンセル =================

async function start() {
  if (!selectedFile || running) return;
  const myJob = ++jobId;
  const src = SOURCES.find((s) => s.whisper === els.srcLang.value);
  const dst = TARGETS[Number(els.dstLang.value)];
  const needTranslate = dst.nllb !== null && dst.nllb !== src.nllb;

  running = true;
  segments = [];
  translating = needTranslate;
  setRunningUi(true);
  renderResult();
  els.resultCard.hidden = false;
  requestWakeLock();

  try {
    // ① 翻訳の準備。ブラウザ内蔵の翻訳（Chrome など）が使えればそちらを使う。
    //    内蔵翻訳はボタンを押した直後でないと準備できないことがあるので、最初に行う。
    let builtin = null;
    if (needTranslate) {
      builtin = await createBuiltinTranslator(src.bcp, dst.bcp);
      if (myJob !== jobId) return;
    }

    // ② ファイルから音声を取り出す
    setStatus('ファイルから音声を読みこみ中…', null);
    const audio = await decodeAudio(selectedFile);
    if (myJob !== jobId) return;
    if (audio.length < 1600) throw new Error('音声が見つかりませんでした。');

    // ③ ワーカーに文字起こし（＋翻訳）を頼む
    await runWorker(myJob, audio, {
      whisperModel: els.model.value,
      language: src.whisper,
      nllbSrc: src.nllb,
      // 内蔵翻訳が使えないときだけ、ワーカー側（NLLB）で翻訳する
      nllbTgt: needTranslate && !builtin ? dst.nllb : null,
    }, builtin);
    if (myJob !== jobId) return;

    setStatus(segments.length ? '完了しました' : '完了しました（話し声が見つかりませんでした）', 100);
    els.hint.hidden = true;
    builtin?.destroy?.();
  } catch (err) {
    if (myJob !== jobId) return;
    console.error(err);
    setStatus('エラー: ' + friendlyError(err), null);
    els.hint.hidden = true;
  } finally {
    if (myJob === jobId) finish();
  }
}

function cancel() {
  if (!running) return;
  jobId++;
  // ワーカーごと止める（途中の計算もすぐ止まる）
  worker?.terminate();
  worker = null;
  setStatus('キャンセルしました', null);
  els.hint.hidden = true;
  finish();
}

function finish() {
  running = false;
  setRunningUi(false);
  renderResult();
  releaseWakeLock();
  showStorageInfo();
}

function setRunningUi(on) {
  els.start.hidden = on;
  els.cancel.hidden = !on;
  els.file.disabled = on;
  els.srcLang.disabled = on;
  els.dstLang.disabled = on;
  els.model.disabled = on;
  els.drop.classList.toggle('disabled', on);
  if (on) {
    els.progressCard.hidden = false;
    els.hint.hidden = false;
    setStatus('準備中…', null);
  }
}

/** ワーカーを動かし、終わるまで待つ */
function runWorker(myJob, audio, options, builtin) {
  return new Promise((resolve, reject) => {
    if (!worker) worker = new Worker(new URL('worker.js', import.meta.url), { type: 'module' });
    // 内蔵翻訳は順番に行う（前の翻訳が終わってから次へ）
    let translateQueue = Promise.resolve();
    let lastPct = 0;

    worker.onmessage = (e) => {
      if (myJob !== jobId) return;
      const m = e.data;
      switch (m.type) {
        case 'status':
          setStatus(m.text, null);
          break;
        case 'download':
          setStatus(`${m.label}を準備中…${m.total ? `（${formatBytes(m.loaded)} / ${formatBytes(m.total)}）` : ''}`, m.pct);
          break;
        case 'progress':
          lastPct = m.pct;
          setStatus(options.nllbTgt || builtin ? '文字起こし・翻訳中…' : '文字起こし中…', Math.min(m.pct, 99));
          break;
        case 'segment':
          segments[m.index] = { original: m.text, translated: null };
          renderResult();
          if (builtin) {
            translateQueue = translateQueue.then(async () => {
              let text;
              try { text = await withTimeout(builtin.translate(m.text), 60000); } catch { text = '（翻訳できませんでした）'; }
              if (myJob !== jobId) return;
              segments[m.index].translated = text;
              renderResult();
            });
          }
          break;
        case 'translation':
          segments[m.index].translated = m.text;
          renderResult();
          break;
        case 'done':
          if (builtin) setStatus('翻訳の仕上げ中…', Math.min(lastPct, 99));
          translateQueue.then(resolve, reject);
          break;
        case 'error':
          reject(new Error(m.message));
          break;
      }
    };
    worker.onerror = (e) => {
      if (myJob !== jobId) return;
      e.preventDefault?.();
      reject(new Error(e.message || 'ワーカーでエラーが起きました'));
    };

    // 音声データはコピーせずにワーカーへ渡す（メモリ節約）
    worker.postMessage({ type: 'run', audio, ...options }, [audio.buffer]);
  });
}

// ================= 音声の読みこみ =================

/** ファイルを 16kHz モノラルの音声データ（Float32Array）にする */
async function decodeAudio(file) {
  const data = await file.arrayBuffer();
  // OfflineAudioContext は読みこむときに 16kHz へ変換してくれる
  const ctx = new OfflineAudioContext(1, 1, 16000);
  let buffer;
  try {
    buffer = await ctx.decodeAudioData(data);
  } catch {
    throw new Error('このファイルの音声を読みこめませんでした。別の形式（mp3, m4a, wav, mp4 など）でお試しください。');
  }
  // コピーを作る（ワーカーへ渡すときに中身ごと移せるようにするため）
  if (buffer.numberOfChannels === 1) return new Float32Array(buffer.getChannelData(0));
  // ステレオなどは左右を平均してモノラルにする
  const out = new Float32Array(buffer.length);
  for (let c = 0; c < buffer.numberOfChannels; c++) {
    const ch = buffer.getChannelData(c);
    for (let i = 0; i < ch.length; i++) out[i] += ch[i];
  }
  for (let i = 0; i < out.length; i++) out[i] /= buffer.numberOfChannels;
  return out;
}

// ================= ブラウザ内蔵の翻訳（Chrome など） =================

/** 使えれば翻訳機を返す。使えなければ null（そのときはワーカーの NLLB で翻訳する） */
async function createBuiltinTranslator(source, target) {
  if (!('Translator' in self)) return null;
  // ブラウザによっては返事が来ないまま止まることがあるので、時間を区切る
  const controller = new AbortController();
  try {
    const availability = await withTimeout(
      self.Translator.availability({ sourceLanguage: source, targetLanguage: target }), 5000);
    if (availability === 'unavailable') return null;
    if (availability !== 'available') setStatus('翻訳機能を準備中…', null);
    return await withTimeout(self.Translator.create({
      sourceLanguage: source,
      targetLanguage: target,
      signal: controller.signal,
      monitor(m) {
        m.addEventListener('downloadprogress', (e) => {
          setStatus('翻訳機能を準備中…', Math.floor((e.loaded ?? 0) * 100));
        });
      },
    }), availability === 'available' ? 15000 : 180000);
  } catch (err) {
    controller.abort();
    console.warn('ブラウザ内蔵の翻訳が使えないので NLLB で翻訳します', err);
    return null;
  }
}

function withTimeout(promise, ms) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), ms)),
  ]);
}

// ================= 表示 =================

/** pct が null のときは「進み具合がわからない」動くバーにする */
function setStatus(text, pct) {
  els.status.textContent = text;
  if (pct === null) {
    els.percent.textContent = '';
    els.barFill.classList.add('indeterminate');
    els.barFill.style.width = '';
  } else {
    els.percent.textContent = `${pct}%`;
    els.barFill.classList.remove('indeterminate');
    els.barFill.style.width = `${pct}%`;
  }
}

function renderResult() {
  els.result.innerHTML = '';
  for (const seg of segments) {
    if (!seg) continue;
    const block = document.createElement('div');
    block.className = 'seg';
    const p = document.createElement('p');
    p.className = 'original';
    p.textContent = seg.original;
    block.append(p);
    if (translating) {
      const t = document.createElement('p');
      t.className = 'translated' + (seg.translated === null ? ' pending' : '');
      t.textContent = seg.translated ?? '翻訳中…';
      block.append(t);
    }
    els.result.append(block);
  }
  if (running) {
    const more = document.createElement('p');
    more.className = 'working';
    more.textContent = segments.length ? '続きを処理中…' : 'できた部分から順にここに表示されます…';
    els.result.append(more);
  }
  const has = segments.some(Boolean);
  for (const b of [els.copy, els.share, els.save]) b.disabled = !has;
}

function showToast(text) {
  els.toast.textContent = text;
  els.toast.hidden = false;
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => { els.toast.hidden = true; }, 2200);
}

function formatBytes(n) {
  if (n >= 1e9) return (n / 1e9).toFixed(1) + 'GB';
  if (n >= 1e6) return (n / 1e6).toFixed(0) + 'MB';
  if (n >= 1e3) return (n / 1e3).toFixed(0) + 'KB';
  return n + 'B';
}

function friendlyError(err) {
  const msg = err?.message || String(err);
  if (/memory|allocation|out of memory/i.test(msg)) {
    return 'メモリが足りませんでした。短いファイルや「速さ重視」でお試しください。';
  }
  if (/fetch|network|Failed to load/i.test(msg)) {
    return 'モデルをダウンロードできませんでした。通信環境を確認してもう一度お試しください。';
  }
  return msg;
}

// ================= コピー・共有・保存 =================

/** コピー・共有・保存に使う文章。翻訳があるときは「原文 → 訳文」を交互に並べる */
function exportText() {
  const list = segments.filter(Boolean);
  if (!translating) return list.map((s) => s.original).join('\n');
  return list.map((s) => (s.translated ? `${s.original}\n${s.translated}` : s.original)).join('\n\n');
}

async function copyResult() {
  try {
    await navigator.clipboard.writeText(exportText());
    showToast('コピーしました');
  } catch {
    showToast('コピーできませんでした');
  }
}

async function shareResult() {
  try {
    await navigator.share({ title: '文字起こし', text: exportText() });
  } catch (err) {
    if (err?.name !== 'AbortError') showToast('共有できませんでした');
  }
}

/** テキストファイルとして保存（例: 授業の録音_文字起こし.txt） */
function saveResult() {
  const base = selectedFile?.name.replace(/\.[^.]+$/, '') || '文字起こし';
  const blob = new Blob([exportText()], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${base}_文字起こし.txt`;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  showToast('保存しました');
}

// ================= 画面の消灯防止・保存領域 =================

async function requestWakeLock() {
  try {
    if ('wakeLock' in navigator && !wakeLock) {
      wakeLock = await navigator.wakeLock.request('screen');
      wakeLock.addEventListener('release', () => { wakeLock = null; });
    }
  } catch { /* 対応していなければ何もしない */ }
}

function releaseWakeLock() {
  wakeLock?.release().catch(() => {});
  wakeLock = null;
}

async function showStorageInfo() {
  try {
    const est = await navigator.storage?.estimate?.();
    els.storageInfo.textContent = est?.usage ? `保存しているデータ: 約${formatBytes(est.usage)}` : '';
  } catch {
    els.storageInfo.textContent = '';
  }
}

async function clearCache() {
  if (running) return;
  if (!confirm('保存したモデルを削除しますか？次に使うときにもう一度ダウンロードします。')) return;
  try {
    for (const key of await caches.keys()) await caches.delete(key);
    worker?.terminate();
    worker = null;
    showToast('削除しました');
  } catch {
    showToast('削除できませんでした');
  }
  showStorageInfo();
}
