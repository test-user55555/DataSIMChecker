package com.simmonitor.utils

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * minsoku.net/provider_detections API を使って
 * 現在のデータ通信回線のプロバイダ名を取得するユーティリティ
 *
 * 仕組み：
 *   1. https://minsoku.net/provider_detections のHTMLを取得し、
 *      ページ固有の provider_detection_key と ipv4_ip を抜き出す
 *   2. https://minsoku.net/api/utils/provider_detection?... を呼び出す
 *   3. レスポンスの ipv4_provider_name を返す
 *
 * これにより「今実際にデータが流れている回線（SIM）のプロバイダ」が判定できる。
 */
object ProviderFetcher {

    private const val TAG = "ProviderFetcher"
    private const val PAGE_URL = "https://minsoku.net/provider_detections"
    private const val API_URL  = "https://minsoku.net/api/utils/provider_detection"
    private const val TIMEOUT_MS = 10_000  // 最大10秒タイムアウト

    data class ProviderResult(
        val ipv4Provider: String,   // IPv4 側のプロバイダ名
        val ipv6Provider: String,   // IPv6 側のプロバイダ名（"未接続" の場合あり）
        val ipv4Address: String,    // ページから取得した IPv4 アドレス
        val isSuccess: Boolean,
        val logMessages: List<String> = emptyList()  // デバッグ用ログ
    )

    /**
     * ブロッキング呼び出し（バックグラウンドスレッドから呼ぶこと）
     */
    fun fetch(): ProviderResult {
        val logs = mutableListOf<String>()

        return try {
            logs.add("🔄 プロバイダ取得開始 (タイムアウト: ${TIMEOUT_MS/1000}秒)")

            // Step1: HTML を取得して key と IPv4 を抽出
            logs.add("📡 Step1: minsoku.net HTML取得中...")
            val html = fetchText(PAGE_URL, logs)
                ?: return failResult("❌ HTMLページ取得失敗", logs)

            val key = extractGon(html, "provider_detection_key")
                ?: return failResult("❌ provider_detection_key が見つかりません", logs)
            val ipv4Ip = extractGon(html, "ipv4_ip")
                ?: return failResult("❌ ipv4_ip が見つかりません", logs)

            logs.add("✅ Step1完了: IP=$ipv4Ip")
            Log.d(TAG, "key=$key  ipv4=$ipv4Ip")

            // Step2: API 呼び出し
            logs.add("📡 Step2: プロバイダAPI呼び出し中...")
            val apiEndpoint = "$API_URL?ipv4_ip=$ipv4Ip&ipv6_ip=&provider_detection_key=$key"
            val json = fetchText(apiEndpoint, logs)
                ?: return failResult("❌ プロバイダAPI呼び出し失敗", logs)

            Log.d(TAG, "API response: $json")

            // Step3: JSON パース
            val obj = JSONObject(json)
            val ipv4Provider = obj.optString("ipv4_provider_name", "判定中")
            val ipv6Provider = obj.optString("ipv6_provider_name", "未接続")

            logs.add("✅ 取得成功: $ipv4Provider")

            ProviderResult(
                ipv4Provider = ipv4Provider,
                ipv6Provider = ipv6Provider,
                ipv4Address  = ipv4Ip,
                isSuccess    = true,
                logMessages  = logs
            )
        } catch (e: SocketTimeoutException) {
            val msg = "⏱️ タイムアウト (${TIMEOUT_MS/1000}秒超過)"
            logs.add(msg)
            Log.w(TAG, msg, e)
            failResult(msg, logs)
        } catch (e: Exception) {
            val msg = "❌ 例外: ${e.javaClass.simpleName}: ${e.message}"
            logs.add(msg)
            Log.e(TAG, "fetch failed", e)
            failResult(msg, logs)
        }
    }

    // ---- private helpers ----

    private fun failResult(reason: String, logs: MutableList<String>): ProviderResult {
        if (!logs.contains(reason)) logs.add(reason)
        Log.w(TAG, reason)
        return ProviderResult(
            ipv4Provider = "取得失敗",
            ipv6Provider = "未接続",
            ipv4Address  = "",
            isSuccess    = false,
            logMessages  = logs
        )
    }

    /**
     * gon.xxx="VALUE" の VALUE を抽出する
     */
    private fun extractGon(html: String, key: String): String? {
        // 例: gon.ipv4_ip="1.2.3.4"; または gon.provider_detection_key="abc123";
        val pattern = Regex("""gon\.$key\s*=\s*"([^"]+)"""")
        return pattern.find(html)?.groupValues?.get(1)
    }

    /**
     * URL からテキストコンテンツを取得（GET）
     */
    private fun fetchText(urlString: String, logs: MutableList<String>): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout    = TIMEOUT_MS
                requestMethod  = "GET"
                setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 16; Pixel) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/json,*/*")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } else {
                val msg = "⚠️ HTTP $code: ${urlString.substringAfterLast('/')}"
                logs.add(msg)
                Log.w(TAG, "HTTP $code for $urlString")
                null
            }
        } catch (e: SocketTimeoutException) {
            val msg = "⏱️ タイムアウト: ${urlString.substringAfterLast('/')}"
            logs.add(msg)
            Log.w(TAG, msg, e)
            null
        } catch (e: Exception) {
            val msg = "❌ 通信エラー: ${e.javaClass.simpleName}"
            logs.add(msg)
            Log.e(TAG, "fetchText error: $urlString", e)
            null
        } finally {
            conn?.disconnect()
        }
    }
}
