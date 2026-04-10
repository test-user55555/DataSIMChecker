package com.simmonitor.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat
import com.simmonitor.model.DataConnectionState
import com.simmonitor.model.NetworkTypeConstants
import com.simmonitor.model.SimInfo
import com.simmonitor.model.SimType

/**
 * SIM情報取得ユーティリティ
 * Android 16（API 36）対応
 */
object SimUtils {

    /**
     * 全SIM情報と現在のデータ接続状態を取得
     */
    @SuppressLint("MissingPermission")
    fun getDataConnectionState(context: Context): DataConnectionState {
        if (!hasRequiredPermissions(context)) {
            return DataConnectionState(
                activeSimInfo = null,
                allSims = emptyList(),
                connectionType = "権限なし",
                isConnected = false,
                lastUpdated = System.currentTimeMillis()
            )
        }

        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as SubscriptionManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                as TelephonyManager
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager

        // アクティブなサブスクリプション一覧を取得
        val activeSubscriptions = try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // 現在データ通信に使用されているサブスクリプションID
        val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()

        // ネットワーク接続タイプを判定
        val connectionType = getConnectionType(connectivityManager)
        val isConnected = connectionType != NetworkTypeConstants.NETWORK_TYPE_NONE

        // 各SIMの情報を収集
        val simInfoList = activeSubscriptions.map { subInfo ->
            buildSimInfo(context, subInfo, telephonyManager, defaultDataSubId)
        }

        // アクティブなデータSIMを特定
        val activeSimInfo = simInfoList.find { it.isDataActive }
            ?: simInfoList.find { it.subscriptionId == defaultDataSubId }

        return DataConnectionState(
            activeSimInfo = activeSimInfo,
            allSims = simInfoList,
            connectionType = connectionType,
            isConnected = isConnected,
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * 個別SIM情報を構築
     */
    @SuppressLint("MissingPermission")
    private fun buildSimInfo(
        context: Context,
        subInfo: SubscriptionInfo,
        telephonyManager: TelephonyManager,
        defaultDataSubId: Int
    ): SimInfo {
        val subId = subInfo.subscriptionId
        val isDataActive = (subId == defaultDataSubId)

        // SIMタイプの判定（eSIM or 物理SIM）
        val simType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    subInfo.isEmbedded -> SimType.ESIM
            subInfo.simSlotIndex >= 0 -> SimType.PHYSICAL_SIM
            else -> SimType.UNKNOWN
        }

        // サブスクリプション専用のTelephonyManagerを取得
        val subTelephonyManager = telephonyManager.createForSubscriptionId(subId)

        // ネットワーク種別を取得
        val networkType = getNetworkTypeString(subTelephonyManager.dataNetworkType)

        // 電話番号を取得（Android 12以降は権限が必要）
        val phoneNumber = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                        as SubscriptionManager
                subscriptionManager.getPhoneNumber(subId) ?: ""
            } else {
                @Suppress("DEPRECATION")
                subInfo.number ?: ""
            }
        } catch (e: Exception) {
            ""
        }

        // 電波強度を取得（0〜4の5段階）
        val signalStrength = getSignalStrength(subTelephonyManager)

        // MCC/MNC（キャリア識別）
        val networkOperator = subTelephonyManager.networkOperator ?: ""
        val mcc = if (networkOperator.length >= 3) networkOperator.substring(0, 3) else ""
        val mnc = if (networkOperator.length > 3) networkOperator.substring(3) else ""

        return SimInfo(
            subscriptionId = subId,
            slotIndex = subInfo.simSlotIndex,
            displayName = subInfo.displayName?.toString() ?: "SIM ${subInfo.simSlotIndex + 1}",
            carrierName = subInfo.carrierName?.toString() ?: subTelephonyManager.networkOperatorName ?: "不明",
            phoneNumber = phoneNumber,
            simType = simType,
            isDataActive = isDataActive,
            signalStrength = signalStrength,
            networkType = networkType,
            isRoaming = subTelephonyManager.isNetworkRoaming,
            mcc = mcc,
            mnc = mnc
        )
    }

    /**
     * ネットワーク接続タイプを文字列で返す
     */
    private fun getConnectionType(connectivityManager: ConnectivityManager): String {
        val network = connectivityManager.activeNetwork ?: return NetworkTypeConstants.NETWORK_TYPE_NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return NetworkTypeConstants.NETWORK_TYPE_NONE

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                NetworkTypeConstants.NETWORK_TYPE_WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                "モバイル通信"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                "有線LAN"
            else -> NetworkTypeConstants.NETWORK_TYPE_UNKNOWN
        }
    }

    /**
     * ネットワーク種別を判定して文字列で返す
     */
    fun getNetworkTypeString(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> NetworkTypeConstants.NETWORK_TYPE_5G
            TelephonyManager.NETWORK_TYPE_LTE -> NetworkTypeConstants.NETWORK_TYPE_4G_LTE
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B -> NetworkTypeConstants.NETWORK_TYPE_3G
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> NetworkTypeConstants.NETWORK_TYPE_2G
            else -> NetworkTypeConstants.NETWORK_TYPE_UNKNOWN
        }
    }

    /**
     * 電波強度を取得（0〜4の5段階）
     */
    @SuppressLint("MissingPermission")
    private fun getSignalStrength(telephonyManager: TelephonyManager): Int {
        return try {
            val signalStrength = telephonyManager.signalStrength
            signalStrength?.level ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 電波強度を視覚的アイコン文字列に変換
     */
    fun signalStrengthToIcon(level: Int): String {
        return when (level) {
            4 -> "▂▄▆█"
            3 -> "▂▄▆░"
            2 -> "▂▄░░"
            1 -> "▂░░░"
            else -> "░░░░"
        }
    }

    /**
     * 必要なパーミッションが付与されているか確認
     */
    fun hasRequiredPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 時刻を "HH:MM:SS" 形式に変換
     */
    fun formatTime(epochMillis: Long): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = epochMillis
        return String.format(
            "%02d:%02d:%02d",
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
            calendar.get(java.util.Calendar.SECOND)
        )
    }
}
