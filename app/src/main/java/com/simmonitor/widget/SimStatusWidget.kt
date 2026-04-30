package com.simmonitor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.simmonitor.R
import com.simmonitor.model.DataConnectionState
import com.simmonitor.model.SimType
import com.simmonitor.service.SimMonitorService
import com.simmonitor.ui.MainActivity
import com.simmonitor.utils.SimUtils

/**
 * SIM回線状態ホームスクリーンウィジェット
 * リアルタイムで現在のデータ通信SIMを表示
 */
class SimStatusWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.simmonitor.WIDGET_REFRESH"

        /**
         * 全ウィジェットを更新（サービスから呼び出し）
         */
        fun updateAllWidgets(context: Context, state: DataConnectionState) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, SimStatusWidget::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

            if (widgetIds.isNotEmpty()) {
                for (widgetId in widgetIds) {
                    updateWidget(context, appWidgetManager, widgetId, state)
                }
            }
        }

        /**
         * 単一ウィジェットを更新
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            state: DataConnectionState?
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_sim_status)

            // タップでアプリを開くインテント
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val launchPendingIntent = PendingIntent.getActivity(
                context, widgetId, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetContainer, launchPendingIntent)

            // 手動更新ボタン
            val refreshIntent = Intent(context, SimStatusWidget::class.java).apply {
                action = ACTION_WIDGET_REFRESH
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context, widgetId + 1000, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetRefresh, refreshPendingIntent)

            if (state == null) {
                // 状態未取得
                views.setTextViewText(R.id.tvWidgetSimType, "読込中...")
                views.setTextViewText(R.id.tvWidgetProvider, "取得中...")
                views.setTextViewText(R.id.tvWidgetCarrier, "---")
                views.setTextViewText(R.id.tvWidgetNetwork, "---")
                views.setTextViewText(R.id.tvWidgetSignal, "░░░░")
                views.setTextViewText(R.id.tvWidgetTime, "--:--:--")
                views.setInt(R.id.widgetBackground, "setBackgroundResource", R.drawable.widget_bg_unknown)
            } else {
                updateWidgetWithState(context, views, state)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        }

        /**
         * 状態に応じてウィジェットビューを更新
         */
        private fun updateWidgetWithState(
            context: Context,
            views: RemoteViews,
            state: DataConnectionState
        ) {
            val activeSim = state.activeSimInfo
            val timeStr = SimUtils.formatTime(state.lastUpdated)

            views.setTextViewText(R.id.tvWidgetTime, timeStr)

            when {
                !state.isConnected -> {
                    // 未接続
                    views.setTextViewText(R.id.tvWidgetSimType, "未接続")
                    views.setTextViewText(R.id.tvWidgetProvider, "データ通信なし")
                    views.setTextViewText(R.id.tvWidgetCarrier, "---")
                    views.setTextViewText(R.id.tvWidgetNetwork, "---")
                    views.setTextViewText(R.id.tvWidgetSignal, "📵")
                    views.setInt(R.id.widgetBackground, "setBackgroundResource",
                        R.drawable.widget_bg_disconnected)
                }
                state.connectionType == "WiFi" -> {
                    // WiFi
                    views.setTextViewText(R.id.tvWidgetSimType, "📶 WiFi")
                    views.setTextViewText(R.id.tvWidgetProvider, state.ipv4ProviderName)
                    views.setTextViewText(R.id.tvWidgetCarrier, "Wi-Fi接続中")
                    views.setTextViewText(R.id.tvWidgetNetwork, "WiFi")
                    views.setTextViewText(R.id.tvWidgetSignal, "▂▄▆█")
                    views.setInt(R.id.widgetBackground, "setBackgroundResource",
                        R.drawable.widget_bg_wifi)
                }
                activeSim != null -> {
                    // モバイルデータ通信
                    val simTypeLabel = when (activeSim.simType) {
                        SimType.ESIM -> "🔷 eSIM"
                        SimType.PHYSICAL_SIM -> "💳 物理SIM"
                        SimType.UNKNOWN -> "📱 SIM"
                    }
                    val roamingLabel = if (activeSim.isRoaming) " [ローミング]" else ""
                    val slotLabel = if (activeSim.slotIndex >= 0) " (S${activeSim.slotIndex + 1})" else ""

                    views.setTextViewText(R.id.tvWidgetSimType, "$simTypeLabel$slotLabel")
                    // プロバイダ名をメインに表示（実際に通信している回線）
                    views.setTextViewText(R.id.tvWidgetProvider,
                        state.ipv4ProviderName + roamingLabel)
                    // キャリア設定名はサブ表示
                    views.setTextViewText(R.id.tvWidgetCarrier, activeSim.carrierName)
                    views.setTextViewText(R.id.tvWidgetNetwork, activeSim.networkType)
                    views.setTextViewText(R.id.tvWidgetSignal,
                        SimUtils.signalStrengthToIcon(activeSim.signalStrength))

                    // SIMタイプでウィジェット背景色を切り替え
                    views.setInt(R.id.widgetBackground, "setBackgroundResource",
                        when (activeSim.simType) {
                            SimType.ESIM -> R.drawable.widget_bg_esim
                            SimType.PHYSICAL_SIM -> R.drawable.widget_bg_physical
                            else -> R.drawable.widget_bg_unknown
                        }
                    )
                }
                else -> {
                    views.setTextViewText(R.id.tvWidgetSimType, "取得中...")
                    views.setTextViewText(R.id.tvWidgetProvider, state.ipv4ProviderName)
                    views.setTextViewText(R.id.tvWidgetCarrier, "---")
                    views.setTextViewText(R.id.tvWidgetNetwork, "---")
                    views.setTextViewText(R.id.tvWidgetSignal, "░░░░")
                    views.setInt(R.id.widgetBackground, "setBackgroundResource",
                        R.drawable.widget_bg_unknown)
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // サービスを起動して最新状態を取得
        startMonitorService(context)

        // 現在の状態で即時更新
        val state = SimMonitorService.latestState
        for (widgetId in appWidgetIds) {
            if (state != null) {
                updateWidget(context, appWidgetManager, widgetId, state)
            } else {
                // 初期状態表示
                updateWidget(context, appWidgetManager, widgetId, null)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_REFRESH -> {
                // 手動更新
                startMonitorService(context)
                val state = SimMonitorService.latestState
                        ?: SimUtils.getDataConnectionState(context)
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, SimStatusWidget::class.java)
                val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
                for (widgetId in widgetIds) {
                    updateWidget(context, appWidgetManager, widgetId, state)
                }
            }
            SimMonitorService.BROADCAST_SIM_UPDATE -> {
                // サービスからの更新通知
                SimMonitorService.latestState?.let { state ->
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    val componentName = ComponentName(context, SimStatusWidget::class.java)
                    val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
                    for (widgetId in widgetIds) {
                        updateWidget(context, appWidgetManager, widgetId, state)
                    }
                }
            }
        }
    }

    override fun onEnabled(context: Context) {
        // ウィジェット初回追加時
        startMonitorService(context)
    }

    override fun onDisabled(context: Context) {
        // 全ウィジェット削除時はサービスも停止可能
        // （バックグラウンドで監視継続したい場合はコメントアウト）
        // val stopIntent = Intent(context, SimMonitorService::class.java)
        // context.stopService(stopIntent)
    }

    /**
     * モニタリングサービスを起動
     */
    private fun startMonitorService(context: Context) {
        val serviceIntent = Intent(context, SimMonitorService::class.java).apply {
            action = SimMonitorService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // バックグラウンド起動制限の場合は無視
        }
    }
}
