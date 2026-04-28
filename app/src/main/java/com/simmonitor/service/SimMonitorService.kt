package com.simmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.simmonitor.R
import com.simmonitor.model.DataConnectionState
import com.simmonitor.ui.MainActivity
import com.simmonitor.utils.ProviderFetcher
import com.simmonitor.utils.SimUtils
import com.simmonitor.widget.SimStatusWidget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * SIM状態監視フォアグラウンドサービス
 *
 * 更新タイミング:
 *   - アプリ起動時（onStartCommand）にプロバイダ取得
 *   - 画面ロック解除時（ACTION_USER_PRESENT を動的登録で受信）にプロバイダ取得
 *     ※ Android 8.0以降は Manifest の静的登録では ACTION_USER_PRESENT を受信できないため
 *        サービス起動中のみ動的登録で対応
 *   - 手動更新（ACTION_FETCH_PROVIDER）でもプロバイダ取得
 *   - SIM設定情報（電波・接続タイプ）は 3秒ポーリングで更新（プロバイダ通信なし）
 *   - データ回線切り替え検知時もプロバイダを再取得
 */
class SimMonitorService : Service() {

    companion object {
        const val CHANNEL_ID             = "sim_monitor_channel"
        const val CHANNEL_SILENT_ID      = "sim_monitor_silent"
        const val NOTIFICATION_ID        = 1001
        const val ACTION_START           = "com.simmonitor.service.START"
        const val ACTION_STOP            = "com.simmonitor.service.STOP"
        /** 手動更新・ScreenUnlockReceiver 経由で呼び出すアクション */
        const val ACTION_FETCH_PROVIDER  = "com.simmonitor.service.FETCH_PROVIDER"
        const val BROADCAST_SIM_UPDATE   = "com.simmonitor.SIM_STATUS_UPDATED"
        /** プロバイダ取得ログをUIに通知するブロードキャスト */
        const val BROADCAST_PROVIDER_LOG = "com.simmonitor.PROVIDER_LOG"
        const val EXTRA_LOG_MESSAGES     = "log_messages"
        const val EXTRA_TRIGGER          = "trigger"

        /** SIM設定情報（電波など）の更新間隔 */
        private const val SIM_POLL_INTERVAL_MS = 3_000L

        private const val TAG = "SimMonitorService"

        @Volatile var isRunning = false
            private set

        @Volatile var latestState: DataConnectionState? = null
            private set
    }

    private val handler     = Handler(Looper.getMainLooper())
    private val ioExecutor  = Executors.newSingleThreadExecutor()
    private lateinit var telephonyManager:    TelephonyManager
    private lateinit var subscriptionManager: SubscriptionManager

    // キャッシュ済みプロバイダ情報
    @Volatile private var cachedIpv4Provider: String = "---"
    @Volatile private var cachedIpv6Provider: String = "未接続"
    @Volatile private var cachedIpv4Address:  String = ""

    // ---- 動的登録: 画面ロック解除レシーバー ----
    // Android 8.0+ では ACTION_USER_PRESENT を Manifest 静的登録で受信できないため
    // サービス起動中のみ動的登録で受信する
    private val screenUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                Log.d(TAG, "Screen unlocked → fetching provider")
                fetchProviderAsync("ロック解除")
            }
        }
    }
    private var isScreenUnlockReceiverRegistered = false

    // ---- SIM 3秒ポーリング（プロバイダ通信なし） ----
    private val simPollRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                updateSimStatus()
                handler.postDelayed(this, SIM_POLL_INTERVAL_MS)
            }
        }
    }

    // ---- 電話状態コールバック ----
    @Suppress("DEPRECATION")
    private val phoneStateCallback = object : PhoneStateListener() {
        @Deprecated("Use TelephonyCallback for API 31+")
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            updateSimStatus()
            // データ回線が変わった場合のみプロバイダを再取得
            fetchProviderAsync("回線切替検知")
        }

        @Deprecated("Use TelephonyCallback for API 31+")
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
            updateSimStatus()
        }
    }

    // ---- Lifecycle ----

    override fun onCreate() {
        super.onCreate()
        telephonyManager    = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fetchTrigger = intent?.getStringExtra(EXTRA_TRIGGER)

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FETCH_PROVIDER -> {
                // 手動更新・ロック解除・回線切替 → プロバイダ取得
                val trigger = fetchTrigger ?: "手動更新"
                if (!isRunning) {
                    // サービスが停止中の場合はフルスタートする（下に流す）
                } else {
                    fetchProviderAsync(trigger)
                    return START_NOT_STICKY
                }
            }
        }

        if (!isRunning) {
            isRunning = true
            startForeground(NOTIFICATION_ID, buildNotification("SIM監視中"))

            // 動的登録: 画面ロック解除の受信
            // Android 8.0+ で ACTION_USER_PRESENT を Manifest 静的登録で受信できない問題の対策
            registerScreenUnlockReceiver()

            // 電話状態の変化を監視
            try {
                @Suppress("DEPRECATION")
                telephonyManager.listen(
                    phoneStateCallback,
                    PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or
                            PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                )
            } catch (e: Exception) { /* ポーリングで補完 */ }

            // 起動時のトリガー（アプリ起動 or ロック解除経由でサービスを再起動した場合）
            val startTrigger = fetchTrigger ?: "アプリ起動"
            fetchProviderAsync(startTrigger)

            // SIM設定情報の3秒ポーリング開始
            handler.post(simPollRunnable)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        ioExecutor.shutdown()
        unregisterScreenUnlockReceiver()
        try {
            @Suppress("DEPRECATION")
            telephonyManager.listen(phoneStateCallback, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) { /* 無視 */ }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 動的登録ヘルパー ----

    private fun registerScreenUnlockReceiver() {
        if (!isScreenUnlockReceiverRegistered) {
            try {
                val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                registerReceiver(screenUnlockReceiver, filter)
                isScreenUnlockReceiverRegistered = true
                Log.d(TAG, "ScreenUnlockReceiver registered (dynamic)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register ScreenUnlockReceiver", e)
            }
        }
    }

    private fun unregisterScreenUnlockReceiver() {
        if (isScreenUnlockReceiverRegistered) {
            try {
                unregisterReceiver(screenUnlockReceiver)
                isScreenUnlockReceiverRegistered = false
                Log.d(TAG, "ScreenUnlockReceiver unregistered")
            } catch (e: Exception) { /* 無視 */ }
        }
    }

    // ---- 内部メソッド ----

    /**
     * SIM設定情報を取得してウィジェット・通知を更新（外部通信なし）
     */
    private fun updateSimStatus() {
        val simState = SimUtils.getDataConnectionState(applicationContext)
        val state = simState.copy(
            ipv4ProviderName = cachedIpv4Provider,
            ipv6ProviderName = cachedIpv6Provider,
            ipv4Address      = cachedIpv4Address
        )
        latestState = state

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!state.isConnected || state.connectionType == "WiFi") {
            // 未接続またはWiFi接続中 → 通知を非表示
            // フォアグラウンドサービスは維持しつつ最小化（優先度最低の無音通知に差し替え）
            nm.notify(NOTIFICATION_ID, buildSilentNotification())
        } else {
            // モバイルデータ接続中 → プロバイダ名 + アイコンで常時表示
            nm.notify(NOTIFICATION_ID, buildNotification(state))
        }

        SimStatusWidget.updateAllWidgets(applicationContext, state)
        sendBroadcast(Intent(BROADCAST_SIM_UPDATE).also { it.setPackage(packageName) })
    }

    /**
     * minsoku.net API でプロバイダ名を非同期取得
     *
     * @param trigger  どのタイミングで呼ばれたか（UI表示用）
     */
    fun fetchProviderAsync(trigger: String = "手動") {
        Log.d(TAG, "fetchProviderAsync: trigger=$trigger")
        ioExecutor.execute {
            val startMs = System.currentTimeMillis()
            val startDateTime = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(startMs))
            val result = ProviderFetcher.fetch()
            val elapsedMs = System.currentTimeMillis() - startMs

            cachedIpv4Provider = result.ipv4Provider
            cachedIpv6Provider = result.ipv6Provider
            cachedIpv4Address  = result.ipv4Address

            // ログメッセージを組み立て（取得元 + 開始日時 + 所要時間 + 各ステップ）
            val logLines = buildList {
                add("─────────────────────")
                add("🔔 トリガー: $trigger")
                add("📅 開始: $startDateTime")
                add("⏱ 所要時間: ${elapsedMs}ms")
                addAll(result.logMessages)
            }

            handler.post {
                updateSimStatus()
                // ログをUIにブロードキャスト
                sendBroadcast(Intent(BROADCAST_PROVIDER_LOG).also {
                    it.setPackage(packageName)
                    it.putStringArrayListExtra(EXTRA_LOG_MESSAGES, ArrayList(logLines))
                    it.putExtra(EXTRA_TRIGGER, trigger)
                })
            }
        }
    }

    /**
     * 通知バーに表示するテキストを組み立てる
     * タイトル: プロバイダ名（au / 楽天モバイル / IIJmio など）
     * 本文: SIMタイプ + ネットワーク種別
     */
    private fun buildNotificationContent(state: DataConnectionState): Pair<String, String> {
        return when {
            !state.isConnected -> Pair("未接続", "データ通信なし")
            state.connectionType == "WiFi" -> {
                val provider = state.ipv4ProviderName.takeIf { it != "---" && it != "取得中..." } ?: "WiFi"
                Pair(provider, "Wi-Fi接続中")
            }
            else -> {
                val simLabel = when (state.activeSimInfo?.simType) {
                    com.simmonitor.model.SimType.ESIM         -> "eSIM"
                    com.simmonitor.model.SimType.PHYSICAL_SIM -> "物理SIM"
                    else                                      -> "SIM"
                }
                val network = state.activeSimInfo?.networkType ?: ""
                val provider = state.ipv4ProviderName.takeIf { it != "---" && it != "取得中..." }
                    ?: state.activeSimInfo?.carrierName ?: "モバイルデータ"
                Pair(provider, "$simLabel  $network")
            }
        }
    }

    /**
     * プロバイダ名からアイコン resId を選択する
     *
     * 通知バーアイコンは白単色の Vector Drawable が必要。
     * プロバイダ名のキーワードマッチでアイコンを切り替え:
     *   - WiFi接続          → ic_notify_wifi
     *   - au / UQ mobile    → ic_notify_au
     *   - 楽天 / Rakuten    → ic_notify_rakuten
     *   - 未接続 / 取得失敗  → ic_notify_unknown
     *   - その他モバイル     → ic_notify_sim
     */
    private fun resolveNotificationIcon(state: DataConnectionState): Int {
        if (!state.isConnected) return R.drawable.ic_notify_unknown
        if (state.connectionType == "WiFi") return R.drawable.ic_notify_wifi

        val provider = state.ipv4ProviderName.lowercase()
        return when {
            provider.contains("au") || provider.contains("uq") ||
            provider.contains("kddi")                              -> R.drawable.ic_notify_au
            provider.contains("rakuten") || provider.contains("楽天") -> R.drawable.ic_notify_rakuten
            provider.contains("取得失敗") || provider.contains("---") ||
            provider.contains("判定")                              -> R.drawable.ic_notify_sim
            else                                                    -> R.drawable.ic_notify_sim
        }
    }

    private fun buildNotification(state: DataConnectionState): Notification {
        val (title, body) = buildNotificationContent(state)
        val iconRes       = resolveNotificationIcon(state)
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)          // プロバイダ名を最前面に
            .setContentText(body)            // SIMタイプ・ネットワーク種別
            .setSmallIcon(iconRes)           // 状態別アイコン
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /** 起動直後など state が未確定のときだけ使う簡易通知 */
    private fun buildNotification(simpleText: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SIM回線モニター")
            .setContentText(simpleText)
            .setSmallIcon(R.drawable.ic_notify_sim)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun buildSilentNotification(): Notification {
        // WiFi接続中・未接続時はステータスバーに表示しない
        // フォアグラウンドサービスには通知が必須だが IMPORTANCE_MIN で実質非表示にする
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SILENT_ID)
            .setContentTitle("SIM回線モニター")
            .setContentText("WiFi接続中")
            .setSmallIcon(R.drawable.ic_notify_sim)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // モバイル接続中チャンネル（ステータスバーにアイコン表示）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SIM回線モニター", NotificationManager.IMPORTANCE_LOW).apply {
                description = "モバイルデータ通信中の回線プロバイダを表示します"
                setShowBadge(false)
            }
        )
        // WiFi/未接続チャンネル（ステータスバーに表示しない）
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SILENT_ID, "SIM回線モニター（WiFi時）", NotificationManager.IMPORTANCE_MIN).apply {
                description = "WiFi接続中・未接続時は通知を最小化します"
                setShowBadge(false)
            }
        )
    }
}
