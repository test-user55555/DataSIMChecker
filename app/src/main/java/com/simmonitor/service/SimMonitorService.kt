package com.simmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.*
import androidx.core.app.NotificationCompat
import com.simmonitor.R
import com.simmonitor.model.DataConnectionState
import com.simmonitor.ui.MainActivity
import com.simmonitor.utils.SimUtils
import com.simmonitor.widget.SimStatusWidget

/**
 * SIM状態をリアルタイム監視するフォアグラウンドサービス
 * 定期的にSIM情報を取得し、ウィジェット・通知を更新する
 */
class SimMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "sim_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.simmonitor.service.START"
        const val ACTION_STOP = "com.simmonitor.service.STOP"
        const val BROADCAST_SIM_UPDATE = "com.simmonitor.SIM_STATUS_UPDATED"
        const val EXTRA_CONNECTION_STATE = "connection_state_json"

        // 監視間隔（ミリ秒）
        private const val MONITOR_INTERVAL_MS = 3000L  // 3秒ごとに更新

        @Volatile
        var isRunning = false
            private set

        // 最新の状態をキャッシュ（Activity/Widgetから参照可能）
        @Volatile
        var latestState: DataConnectionState? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var subscriptionManager: SubscriptionManager

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                updateSimStatus()
                handler.postDelayed(this, MONITOR_INTERVAL_MS)
            }
        }
    }

    // 電話状態の変化コールバック（Android 12以降）
    private val phoneStateCallback = object : PhoneStateListener() {
        @Deprecated("Use TelephonyCallback instead for API 31+")
        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            updateSimStatus()
        }

        @Deprecated("Use TelephonyCallback instead for API 31+")
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
            updateSimStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!isRunning) {
            isRunning = true
            startForeground(NOTIFICATION_ID, buildNotification("SIM監視を開始しました"))

            // 電話状態の変化を監視
            try {
                @Suppress("DEPRECATION")
                telephonyManager.listen(
                    phoneStateCallback,
                    PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or
                            PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                )
            } catch (e: Exception) {
                // リッスン失敗は無視してポーリングで対応
            }

            // 定期ポーリング開始
            handler.post(monitorRunnable)

            // 初回即時更新
            updateSimStatus()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try {
            @Suppress("DEPRECATION")
            telephonyManager.listen(phoneStateCallback, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
            // 無視
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * SIM状態を取得して更新通知を発行
     */
    private fun updateSimStatus() {
        val state = SimUtils.getDataConnectionState(applicationContext)
        latestState = state

        // 通知バーを更新
        val notificationText = buildNotificationText(state)
        val notification = buildNotification(notificationText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        // ウィジェットを更新
        SimStatusWidget.updateAllWidgets(applicationContext, state)

        // ActivityへBroadcast送信
        val broadcastIntent = Intent(BROADCAST_SIM_UPDATE)
        broadcastIntent.setPackage(packageName)
        sendBroadcast(broadcastIntent)
    }

    /**
     * 通知テキストの組み立て
     */
    private fun buildNotificationText(state: DataConnectionState): String {
        val activeSim = state.activeSimInfo
        return when {
            !state.isConnected -> "📵 データ通信: 未接続"
            state.connectionType == "WiFi" -> "📶 WiFi接続中"
            activeSim == null -> "📱 SIM情報取得中..."
            else -> {
                val simTypeLabel = when (activeSim.simType) {
                    com.simmonitor.model.SimType.ESIM -> "eSIM"
                    com.simmonitor.model.SimType.PHYSICAL_SIM -> "物理SIM"
                    else -> "SIM"
                }
                val signal = SimUtils.signalStrengthToIcon(activeSim.signalStrength)
                "📱 ${activeSim.carrierName} ($simTypeLabel) ${activeSim.networkType} $signal"
            }
        }
    }

    /**
     * フォアグラウンド通知の構築
     */
    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SIM回線モニター")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * 通知チャンネルの作成（Android 8.0以降必須）
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SIM回線モニター",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "現在のデータ通信SIM回線を表示します"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
