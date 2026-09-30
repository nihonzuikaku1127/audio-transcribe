# 文字起こし翻訳（AudioTranscribe）

スマホ内の音声・動画ファイルを、端末の中だけで文字起こし（Vosk）するAndroidアプリ。
音声を外部サーバーに送りません。

## APKの作り方
GitHubにpushすると、GitHub Actions（`.github/workflows/build.yml`）が自動でビルドします。
リポジトリの「Actions」タブ → 最新の「Build APK」→ 下の「Artifacts」から
`audio-transcribe-debug-apk` をダウンロードし、中の `app-debug.apk` をスマホに入れてください。

## 開発の段階
- [x] ① ファイルを選んで文字起こし
- [ ] ② 進捗表示と途中結果の表示
- [ ] ③ 翻訳
- [ ] ④ キャンセルと通知
- [ ] ⑤ コピー・共有・保存
- [ ] ⑥ 広告
