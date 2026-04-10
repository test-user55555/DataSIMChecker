package com.simmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.simmonitor.service.SimMonitorService

/**
 * ネットワーク変更・SIM変更を検知するレシーバー
 * → サービスが起動中でない場合に再起動する
 */
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.net.conn.CONNECTIVITY_CHANGE",
            "android.telephony.action.DEFAULT_DATA_SUBSCRIPTION_CHANGED" -> {
                // サービスが停止していれば再起動
                if (!SimMonitorService.isRunning) {
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
                        // バックグラウンド制限の場合は無視
                    }
                }
            }
        }
    }
}
