# ProGuard ルール
# SIM関連クラスを難読化から除外
-keep class com.simmonitor.model.** { *; }
-keep class com.simmonitor.service.SimMonitorService { *; }
-keep class com.simmonitor.widget.SimStatusWidget { *; }
-keep class com.simmonitor.receiver.** { *; }

# TelephonyManager関連
-keep class android.telephony.** { *; }
-dontwarn android.telephony.**
