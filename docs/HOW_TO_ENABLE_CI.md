# GitHub Actions ビルドの有効化方法

## 手順

1. `docs/github-actions-build.yml` の内容をコピー
2. リポジトリに `.github/workflows/build.yml` を作成して貼り付け
3. コミット → 自動でAPKビルドが開始されます

または、GitHub UIから直接ファイルを作成できます：
→ https://github.com/test-user55555/DataSIMChecker/new/main/.github/workflows

## ビルド成果物

Actions完了後、以下のAPKがダウンロード可能になります：
- `SimMonitor-debug-*.apk` — そのままインストール可能
- `SimMonitor-release-unsigned-*.apk` — 署名後にPlay Store配布可能
