# 文字起こし翻訳（AudioTranscribe）

スマホ内の音声・動画ファイルを、端末の中だけで文字起こし（Vosk）・翻訳（ML Kit）するAndroidアプリ。
音声を外部サーバーに送りません。初回だけモデルをダウンロードし、以降はオフラインで動きます。

ブラウザ版（`web` フォルダ）もあります。→ [ブラウザ版の説明](#ブラウザ版web)

## APKの作り方
Android版のファイル（`app` フォルダやGradleの設定）を変えてGitHubにpushすると、GitHub Actions（`.github/workflows/build.yml`）が自動でビルドします。
リポジトリの「Actions」タブ → 最新の「Build APK」→ 下の「Artifacts」から
`audio-transcribe-debug-apk` をダウンロードし、中の `app-debug.apk` をスマホに入れてください。

## 開発の段階
- [x] ① ファイルを選んで文字起こし
- [x] ② 進捗表示と途中結果の表示
- [x] ③ 翻訳
- [x] ④ キャンセルと通知
- [x] ⑤ コピー・共有・保存
- [x] ⑥ 広告

## ファイルの役割
| ファイル | 役割 |
|---|---|
| `MainActivity.kt` | 画面。言語選択・ファイル選択・進捗や結果の表示・コピー/共有/保存・広告 |
| `TranscribeService.kt` | 裏で処理を続けるサービス。通知の表示とキャンセル |
| `JobState.kt` | 処理の状態をまとめた「掲示板」。サービスが書き、画面と通知が読む |
| `Transcriber.kt` | Voskで文字起こしし、ML Kitで翻訳する |
| `AudioDecoder.kt` | 音声・動画ファイルを16kHzモノラルの音声データに変換する |
| `ModelManager.kt` | 音声認識モデルを初回だけダウンロードする |
| `Languages.kt` | 選べる言語の一覧 |

## 広告について
いまはGoogleのテスト用IDを使っています（`AndroidManifest.xml` のアプリIDと `res/values/strings.xml` の広告ユニットID）。
公開するときは自分のAdMobのIDに書きかえてください。

## ブラウザ版（web）
`web` フォルダに入っている、同じ機能のウェブアプリです。パソコンでもスマホでも使えます。

- 公開URL: https://nihonzuikaku1127.github.io/audio-transcribe/
- 文字起こしは Whisper、翻訳は NLLB を [Transformers.js](https://huggingface.co/docs/transformers.js) でブラウザの中で動かします。
  Chrome などブラウザに内蔵の翻訳機能が使えるときは、そちらを優先します（ダウンロードが小さく速いため）。
- モデルは初回だけダウンロードしてブラウザに保存し、2回目以降は再利用します。
- `web` フォルダを変えてpushすると、GitHub Actions（`.github/workflows/pages.yml`）が自動でGitHub Pagesに公開します。

| ファイル | 役割 |
|---|---|
| `web/index.html` | 画面の部品 |
| `web/style.css` | 見た目（スマホ・パソコン両対応、ダークモード対応） |
| `web/app.js` | 画面の係。言語選択・ファイル読みこみ・進捗や結果の表示・コピー/共有/保存・キャンセル |
| `web/worker.js` | 別スレッドで Whisper による文字起こしと NLLB による翻訳を行う |

手元で試すときは、`web` フォルダでかんたんなサーバーを動かして（例: `python3 -m http.server`）ブラウザで開いてください。
