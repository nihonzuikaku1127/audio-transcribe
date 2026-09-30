# 文字起こし翻訳（AudioTranscribe）

スマホ内の音声・動画ファイルを、端末の中だけで文字起こし（Vosk）・翻訳（ML Kit）するAndroidアプリ。
音声を外部サーバーに送りません。初回だけモデルをダウンロードし、以降はオフラインで動きます。

## APKの作り方
GitHubにpushすると、GitHub Actions（`.github/workflows/build.yml`）が自動でビルドします。
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
