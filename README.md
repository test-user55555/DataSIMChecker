# SIM回線モニター (SimMonitor)

Android 16対応のデュアルSIM（物理SIM + eSIM）データ通信回線モニターアプリです。

## 機能

### メイン画面
- 現在データ通信に使用しているSIMを大きく表示
- **物理SIM / eSIM** を色分けバッジで瞬時に識別
- キャリア名・ネットワーク種別（5G / LTE / 3G）・電波強度を表示
- ローミング中の場合は警告表示
- 全SIMカードの待機状態を一覧表示
- 3秒ごとの自動更新（フォアグラウンドサービス）

### ホームスクリーンウィジェット
- 2×2サイズのリアルタイム表示ウィジェット
- 接続SIMタイプにより**背景色が自動変化**
  - 🔷 eSIM → 青系グラデーション
  - 💳 物理SIM → 緑系グラデーション
  - 📶 WiFi → シアン系
  - 未接続 → グレー
- タップでアプリを起動
- ↺ボタンで手動更新

### 通知バー
- フォアグラウンドサービスの常駐通知で状態を把握

## プロジェクト構造

```
SimMonitor/
├── app/src/main/
│   ├── java/com/simmonitor/
│   │   ├── model/
│   │   │   └── SimInfo.kt          # データモデル
│   │   ├── utils/
│   │   │   └── SimUtils.kt         # SIM情報取得ユーティリティ
│   │   ├── service/
│   │   │   └── SimMonitorService.kt # フォアグラウンド監視サービス
│   │   ├── widget/
│   │   │   └── SimStatusWidget.kt  # ホームスクリーンウィジェット
│   │   ├── ui/
│   │   │   └── MainActivity.kt     # メイン画面
│   │   └── receiver/
│   │       ├── BootReceiver.kt     # 起動時自動開始
│   │       └── NetworkChangeReceiver.kt # ネットワーク変化検知
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml   # メイン画面レイアウト
│       │   └── widget_sim_status.xml # ウィジェットレイアウト
│       ├── drawable/               # 背景・バッジ素材
│       ├── values/                 # 文字列・色・テーマ
│       └── xml/
│           └── sim_status_widget_info.xml # ウィジェット定義
└── build.gradle.kts
```

## ビルド要件

| 項目 | 要件 |
|------|------|
| Android Studio | Hedgehog (2023.1.1) 以上 |
| Kotlin | 2.0.0 |
| AGP | 8.5.0 |
| minSdk | 29 (Android 10) |
| targetSdk | 36 (Android 16) |
| Java | 17 |

## 必要な権限

| 権限 | 用途 |
|------|------|
| `READ_PHONE_STATE` | SIM情報・電話状態の読み取り |
| `READ_PHONE_NUMBERS` | 電話番号の取得（オプション） |
| `ACCESS_NETWORK_STATE` | ネットワーク状態の監視 |
| `FOREGROUND_SERVICE` | バックグラウンド監視サービス |
| `POST_NOTIFICATIONS` | 通知バー表示（Android 13+） |
| `RECEIVE_BOOT_COMPLETED` | 起動時の自動開始 |

## ビルド方法

```bash
# プロジェクトをAndroid Studioで開く
# またはコマンドラインから

cd SimMonitor
./gradlew assembleDebug        # デバッグビルド
./gradlew assembleRelease      # リリースビルド
./gradlew installDebug         # 接続端末にインストール
```

## ウィジェットの追加方法

1. ホームスクリーンを長押し
2. 「ウィジェット」を選択
3. 「SIM回線モニター」→「SIM回線ウィジェット」を選択
4. 任意の場所に配置

## 技術仕様

### SIM識別ロジック

```kotlin
// eSIM判定（Android 10以降）
val isEsim = subInfo.isEmbedded  // SubscriptionInfo.isEmbedded

// 現在データ通信中のSIM特定
val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
```

### リアルタイム更新の仕組み

1. **フォアグラウンドサービス** が3秒ごとにポーリング
2. **PhoneStateListener** でリアルタイムコールバック
3. `BroadcastIntent` でActivity・Widgetに通知
4. ウィジェットは `AppWidgetManager.updateAppWidget()` で即時反映

## Android 16 対応ポイント

- `foregroundServiceType="dataSync"` をManifestに明示
- `READ_PHONE_NUMBERS` の分離取得（API 33+）
- `RECEIVER_NOT_EXPORTED` フラグでセキュリティ強化
- `PendingIntent.FLAG_IMMUTABLE` の使用（API 31+要件）

## 注意事項

- 一部メーカーはデュアルSIMのAPI制限あり（特にeSIM切り替え中の情報取得）
- Android 12以降、バックグラウンドからのサービス起動に制限あり
- ネットワーク変更のブロードキャストはAndroid 7以降、動的登録が必要
