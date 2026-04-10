package com.simmonitor.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.simmonitor.R
import com.simmonitor.model.DataConnectionState
import com.simmonitor.model.SimType
import com.simmonitor.service.SimMonitorService
import com.simmonitor.utils.SimUtils

/**
 * メインActivity
 * SIM回線の状態をリアルタイム表示するメイン画面
 */
class MainActivity : AppCompatActivity() {

    // UI要素
    private lateinit var tvActiveSimLabel: TextView
    private lateinit var tvActiveSimType: TextView
    private lateinit var tvCarrierName: TextView
    private lateinit var tvNetworkType: TextView
    private lateinit var tvSignalStrength: TextView
    private lateinit var tvConnectionType: TextView
    private lateinit var tvLastUpdated: TextView
    private lateinit var cardSim1: View
    private lateinit var cardSim2: View
    private lateinit var tvSim1Name: TextView
    private lateinit var tvSim1Type: TextView
    private lateinit var tvSim1Status: TextView
    private lateinit var tvSim1Signal: TextView
    private lateinit var tvSim2Name: TextView
    private lateinit var tvSim2Type: TextView
    private lateinit var tvSim2Status: TextView
    private lateinit var tvSim2Signal: TextView
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var tvPermissionWarning: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvRoamingWarning: TextView

    // 権限リクエストランチャー
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.READ_PHONE_STATE] == true
        if (granted) {
            tvPermissionWarning.visibility = View.GONE
            startMonitorService()
        } else {
            tvPermissionWarning.visibility = View.VISIBLE
            tvPermissionWarning.text = "⚠️ 電話状態の読み取り権限が必要です。\n設定から権限を許可してください。"
        }
    }

    // SIM状態更新ブロードキャストレシーバー
    private val simUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SimMonitorService.BROADCAST_SIM_UPDATE) {
                SimMonitorService.latestState?.let { updateUI(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // ブロードキャストレシーバー登録
        val filter = IntentFilter(SimMonitorService.BROADCAST_SIM_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(simUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(simUpdateReceiver, filter)
        }
        // 最新状態があれば即時反映
        SimMonitorService.latestState?.let { updateUI(it) }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(simUpdateReceiver)
    }

    /**
     * UI要素の初期化
     */
    private fun initViews() {
        tvActiveSimLabel = findViewById(R.id.tvActiveSimLabel)
        tvActiveSimType = findViewById(R.id.tvActiveSimType)
        tvCarrierName = findViewById(R.id.tvCarrierName)
        tvNetworkType = findViewById(R.id.tvNetworkType)
        tvSignalStrength = findViewById(R.id.tvSignalStrength)
        tvConnectionType = findViewById(R.id.tvConnectionType)
        tvLastUpdated = findViewById(R.id.tvLastUpdated)
        cardSim1 = findViewById(R.id.cardSim1)
        cardSim2 = findViewById(R.id.cardSim2)
        tvSim1Name = findViewById(R.id.tvSim1Name)
        tvSim1Type = findViewById(R.id.tvSim1Type)
        tvSim1Status = findViewById(R.id.tvSim1Status)
        tvSim1Signal = findViewById(R.id.tvSim1Signal)
        tvSim2Name = findViewById(R.id.tvSim2Name)
        tvSim2Type = findViewById(R.id.tvSim2Type)
        tvSim2Status = findViewById(R.id.tvSim2Status)
        tvSim2Signal = findViewById(R.id.tvSim2Signal)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)
        tvPermissionWarning = findViewById(R.id.tvPermissionWarning)
        progressBar = findViewById(R.id.progressBar)
        tvRoamingWarning = findViewById(R.id.tvRoamingWarning)

        btnStartService.setOnClickListener { startMonitorService() }
        btnStopService.setOnClickListener { stopMonitorService() }
    }

    /**
     * 権限チェックとリクエスト
     */
    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            tvPermissionWarning.visibility = View.VISIBLE
            tvPermissionWarning.text = "📋 権限の確認が必要です..."
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            tvPermissionWarning.visibility = View.GONE
            startMonitorService()
        }
    }

    /**
     * SIM監視サービスを開始
     */
    private fun startMonitorService() {
        val intent = Intent(this, SimMonitorService::class.java).apply {
            action = SimMonitorService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        progressBar.visibility = View.VISIBLE
        btnStartService.isEnabled = false
        btnStopService.isEnabled = true
    }

    /**
     * SIM監視サービスを停止
     */
    private fun stopMonitorService() {
        val intent = Intent(this, SimMonitorService::class.java).apply {
            action = SimMonitorService.ACTION_STOP
        }
        startService(intent)
        progressBar.visibility = View.GONE
        btnStartService.isEnabled = true
        btnStopService.isEnabled = false
    }

    /**
     * UIをSIM状態で更新
     */
    private fun updateUI(state: DataConnectionState) {
        progressBar.visibility = View.GONE

        // 接続タイプ表示
        tvConnectionType.text = "接続: ${state.connectionType}"
        tvLastUpdated.text = "更新: ${SimUtils.formatTime(state.lastUpdated)}"

        val activeSim = state.activeSimInfo

        if (!state.isConnected) {
            // 未接続状態
            tvActiveSimLabel.text = "データ通信"
            tvActiveSimType.text = "未接続"
            tvActiveSimType.setBackgroundResource(R.drawable.badge_disconnected)
            tvCarrierName.text = "-"
            tvNetworkType.text = "-"
            tvSignalStrength.text = "電波なし"
            tvRoamingWarning.visibility = View.GONE
        } else if (state.connectionType == "WiFi") {
            // WiFi接続
            tvActiveSimLabel.text = "データ通信"
            tvActiveSimType.text = "WiFi"
            tvActiveSimType.setBackgroundResource(R.drawable.badge_wifi)
            tvCarrierName.text = "Wi-Fi接続中"
            tvNetworkType.text = "WiFi"
            tvSignalStrength.text = "📶"
            tvRoamingWarning.visibility = View.GONE
        } else if (activeSim != null) {
            // モバイルデータ通信
            val simTypeLabel = when (activeSim.simType) {
                SimType.ESIM -> "eSIM"
                SimType.PHYSICAL_SIM -> "物理SIM"
                SimType.UNKNOWN -> "SIM"
            }
            val slotLabel = if (activeSim.slotIndex >= 0) "スロット${activeSim.slotIndex + 1}" else ""

            tvActiveSimLabel.text = "アクティブ回線"
            tvActiveSimType.text = "$simTypeLabel  $slotLabel"
            tvActiveSimType.setBackgroundResource(
                if (activeSim.simType == SimType.ESIM) R.drawable.badge_esim
                else R.drawable.badge_physical_sim
            )
            tvCarrierName.text = activeSim.carrierName
            tvNetworkType.text = activeSim.networkType
            tvSignalStrength.text = "電波: ${SimUtils.signalStrengthToIcon(activeSim.signalStrength)}"

            // ローミング警告
            if (activeSim.isRoaming) {
                tvRoamingWarning.visibility = View.VISIBLE
                tvRoamingWarning.text = "⚠️ ローミング中"
            } else {
                tvRoamingWarning.visibility = View.GONE
            }
        }

        // 各SIMカードの詳細情報表示
        val sims = state.allSims
        if (sims.isNotEmpty()) {
            cardSim1.visibility = View.VISIBLE
            val sim1 = sims[0]
            tvSim1Name.text = sim1.displayName
            tvSim1Type.text = when (sim1.simType) {
                SimType.ESIM -> "🔷 eSIM"
                SimType.PHYSICAL_SIM -> "💳 物理SIM"
                else -> "📱 SIM"
            }
            tvSim1Status.text = if (sim1.isDataActive) "● データ通信中" else "○ 待機中"
            tvSim1Status.setTextColor(
                if (sim1.isDataActive) getColor(R.color.active_green)
                else getColor(R.color.inactive_gray)
            )
            tvSim1Signal.text = SimUtils.signalStrengthToIcon(sim1.signalStrength)

            // SIM1カードの強調表示
            cardSim1.setBackgroundResource(
                if (sim1.isDataActive) R.drawable.card_active else R.drawable.card_inactive
            )
        } else {
            cardSim1.visibility = View.GONE
        }

        if (sims.size >= 2) {
            cardSim2.visibility = View.VISIBLE
            val sim2 = sims[1]
            tvSim2Name.text = sim2.displayName
            tvSim2Type.text = when (sim2.simType) {
                SimType.ESIM -> "🔷 eSIM"
                SimType.PHYSICAL_SIM -> "💳 物理SIM"
                else -> "📱 SIM"
            }
            tvSim2Status.text = if (sim2.isDataActive) "● データ通信中" else "○ 待機中"
            tvSim2Status.setTextColor(
                if (sim2.isDataActive) getColor(R.color.active_green)
                else getColor(R.color.inactive_gray)
            )
            tvSim2Signal.text = SimUtils.signalStrengthToIcon(sim2.signalStrength)

            // SIM2カードの強調表示
            cardSim2.setBackgroundResource(
                if (sim2.isDataActive) R.drawable.card_active else R.drawable.card_inactive
            )
        } else {
            cardSim2.visibility = View.GONE
        }
    }
}
