package com.simmonitor.model

/**
 * SIM情報データクラス
 * 物理SIM / eSIMの状態を保持する
 */
data class SimInfo(
    val subscriptionId: Int,
    val slotIndex: Int,           // スロット番号（0 = SIM1, 1 = SIM2）
    val displayName: String,      // キャリア表示名
    val carrierName: String,      // キャリア名
    val phoneNumber: String,      // 電話番号（取得できる場合）
    val simType: SimType,         // SIMタイプ（物理/eSIM）
    val isDataActive: Boolean,    // 現在データ通信中か
    val signalStrength: Int,      // 電波強度 (0-4)
    val networkType: String,      // ネットワーク種別（5G/LTE/3G等）
    val isRoaming: Boolean,       // ローミング中か
    val mcc: String,              // モバイルカントリーコード
    val mnc: String               // モバイルネットワークコード
)

/**
 * SIMタイプ列挙型
 */
enum class SimType {
    PHYSICAL_SIM,    // 物理SIM
    ESIM,            // eSIM
    UNKNOWN          // 不明
}

/**
 * データ通信状態
 */
data class DataConnectionState(
    val activeSimInfo: SimInfo?,       // 現在データ通信中のSIM
    val allSims: List<SimInfo>,        // 全SIM一覧
    val connectionType: String,        // WiFi/Mobile/なし
    val isConnected: Boolean,          // 接続中か
    val lastUpdated: Long,             // 最終更新時刻（エポックミリ秒）
    // --- minsoku.net API で取得する「実通信プロバイダ」情報 ---
    val ipv4ProviderName: String = "取得中...",  // IPv4 側のプロバイダ名
    val ipv6ProviderName: String = "未接続",     // IPv6 側のプロバイダ名
    val ipv4Address: String = ""                 // 現在の外部 IPv4 アドレス
)

/**
 * ネットワーク種別の定数
 */
object NetworkTypeConstants {
    const val NETWORK_TYPE_UNKNOWN = "不明"
    const val NETWORK_TYPE_5G = "5G"
    const val NETWORK_TYPE_4G_LTE = "4G/LTE"
    const val NETWORK_TYPE_3G = "3G"
    const val NETWORK_TYPE_2G = "2G"
    const val NETWORK_TYPE_WIFI = "WiFi"
    const val NETWORK_TYPE_NONE = "未接続"
}
