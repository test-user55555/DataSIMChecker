package com.simmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.simmonitor.service.SimMonitorService

/**
 * ネットワーク変更・SIM変更を検知するレシーバー
 *
 * - CONNECTIVITY_CHANGE: ネットワーク接続状態の変化（WiFi ↔ モバイル切替など）
 * - DEFAULT_DATA_SUBSCRIPTION_CHANGED: デフォルトデータSIMの切り替え
 *
 * いずれの場合もプロバイダ取得をトリガーする（回線が変わればプロバイダも変わるため）。
 * サービスが停止していた場合は再起動も行う。
 */
class NetworkChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.net.conn.CONNECTIVITY_CHANGE",
            "android.telephony.action.DEFAULT_DATA_SUBSCRIPTION_CHANGED" -> {
                Log.d(TAG, "Network/SIM change detected: ${intent.action}")

                if (SimMonitorService.isRunning) {
                    // サービス起動中 → プロバイダ取得をリクエスト
                    val fetchIntent = Intent(context, SimMonitorService::class.java).apply {
                        action = SimMonitorService.ACTION_FETCH_PROVIDER
                        putExtra(SimMonitorService.EXTRA_TRIGGER, "回線切替検知")
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(fetchIntent)
                        } else {
                            context.startService(fetchIntent)
                        }
                        Log.d(TAG, "Requested provider fetch from running service")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to request fetch: ${e.message}")
                    }
                } else {
                    // サービス停止中 → 起動（起動時にプロバイダ取得も実行される）
                    val startIntent = Intent(context, SimMonitorService::class.java).apply {
                        action = SimMonitorService.ACTION_START
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(startIntent)
                        } else {
                            context.startService(startIntent)
                        }
                        Log.d(TAG, "Restarted service on network change")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restart service: ${e.message}")
                    }
                }
            }
        }
    }
}
