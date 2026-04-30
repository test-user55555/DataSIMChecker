package com.simmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.simmonitor.service.SimMonitorService

/**
 * 画面ロック解除レシーバー
 *
 * ACTION_USER_PRESENT（スワイプ・PIN・指紋などでロック解除完了）を受信し、
 * SimMonitorService に ACTION_FETCH_PROVIDER を送信してプロバイダ情報を取得する。
 *
 * ポイント:
 *   - ACTION_USER_PRESENT は動的登録が不要（static receiver で受け取れる）
 *   - 定期通信をやめ、ロック解除のタイミングのみ外部APIを呼ぶのでバッテリー節約
 *   - ロック解除 → サービス起動 → minsoku.net API → ウィジェット更新 の流れ
 */
class ScreenUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return

        // サービスが動いていない場合も起動して即プロバイダ取得させる
        val serviceIntent = Intent(context, SimMonitorService::class.java).apply {
            action = SimMonitorService.ACTION_FETCH_PROVIDER
            putExtra(SimMonitorService.EXTRA_TRIGGER, "ロック解除")
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
