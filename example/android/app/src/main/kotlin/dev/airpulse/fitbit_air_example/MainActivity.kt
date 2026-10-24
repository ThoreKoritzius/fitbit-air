package dev.airpulse.fitbit_air_example

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "fitbit_air_example/calendar")
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "readRelayBattery", "readRelaySignal", "readRelayDeviceInfo" -> {
                            val relay = CalendarRelayService.active
                            if (relay == null) result.error("BUSY", "The relay is changing state.", null)
                            else when (call.method) {
                                "readRelaySignal" -> relay.readSignal(result)
                                "readRelayDeviceInfo" -> relay.readDeviceInfo(result)
                                else -> relay.readBattery(result)
                            }
                        }
                        "deviceCache" -> {
                            val cache = getSharedPreferences("air_device", MODE_PRIVATE)
                            result.success(mapOf(
                                "firmware" to cache.getString("firmware", null),
                                "readAt" to cache.getLong("readAt", 0),
                                "dismissedFirmware" to cache.getString("dismissedFirmware", null),
                                "workingFirmware" to cache.getString("workingFirmware", null)))
                        }
                        "saveDevice" -> {
                            val edit = getSharedPreferences("air_device", MODE_PRIVATE).edit()
                            call.argument<String>("firmware")?.let {
                                require(it.length <= 64)
                                edit.putString("firmware", it)
                                    .putLong("readAt", requireNotNull(call.argument<Number>("readAt")).toLong())
                            }
                            for (key in listOf("dismissedFirmware", "workingFirmware")) {
                                call.argument<String>(key)?.let {
                                    require(it.length <= 64)
                                    edit.putString(key, it)
                                }
                            }
                            edit.apply()
                            result.success(null)
                        }
                        "batteryCache" -> {
                            val cache = getSharedPreferences("air_battery", MODE_PRIVATE)
                            result.success(if (!cache.contains("percent")) null else mapOf(
                                "percent" to cache.getInt("percent", 0),
                                "readAt" to cache.getLong("readAt", 0),
                                "onCharger" to if (cache.contains("onCharger")) cache.getBoolean("onCharger", false) else null))
                        }
                        "saveBattery" -> {
                            val percent = requireNotNull(call.argument<Int>("percent"))
                            val readAt = requireNotNull(call.argument<Number>("readAt")).toLong()
                            require(percent in 0..100 && readAt > 0)
                            val edit = getSharedPreferences("air_battery", MODE_PRIVATE).edit()
                                .putInt("percent", percent).putLong("readAt", readAt)
                            val charging = call.argument<Boolean>("onCharger")
                            if (charging == null) edit.remove("onCharger") else edit.putBoolean("onCharger", charging)
                            edit.apply()
                            result.success(null)
                        }
                        "status" -> result.success(CalendarRelayService.status(this))
                        "appSettings" -> {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                            result.success(null)
                        }
                        "allowNotificationControls" -> {
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 902)
                            } else {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                            }
                            result.success(null)
                        }
                        "notificationControlsSettings" -> {
                            val settingsIntent = if (Build.VERSION.SDK_INT >= 26) {
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            } else {
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))
                            }
                            startActivity(settingsIntent)
                            result.success(null)
                        }
                        "saveSetupStep" -> {
                            val step = requireNotNull(call.argument<Int>("step"))
                            require(step in 0..3)
                            getSharedPreferences("relay_onboarding", MODE_PRIVATE).edit().putInt("step", step).apply()
                            result.success(null)
                        }
                        "completeSetup" -> {
                            getSharedPreferences("relay_onboarding", MODE_PRIVATE).edit()
                                .putBoolean("completed", true).apply()
                            result.success(null)
                        }
                        "saveAppRule" -> {
                            val pkg = requireNotNull(call.argument<String>("packageName"))
                            val enabled = requireNotNull(call.argument<Boolean>("enabled"))
                            val preset = call.argument<String>("preset")?.let { RelayPreset.parse(it) }
                            RelayRuleStore.update(this) { it.copy(apps = it.apps + (pkg to RelayAppRule(enabled, preset))) }
                            result.success(null)
                        }
                        "saveChat" -> {
                            // Adds a chat found in the last capture, or changes a saved chat's vibration.
                            val id = requireNotNull(call.argument<String>("id"))
                            val preset = call.argument<String>("preset")?.let { RelayPreset.parse(it) }
                            RelayRuleStore.update(this) { config ->
                                val chat = config.chats[id] ?: checkNotNull(RelayConversations.candidate(id)) {
                                    "Find this chat again, then add it."
                                }
                                config.copy(chats = config.chats + (id to chat.copy(preset = preset)))
                            }
                            result.success(null)
                        }
                        "removeChat" -> {
                            val id = requireNotNull(call.argument<String>("id"))
                            RelayRuleStore.update(this) { it.copy(chats = it.chats - id) }
                            result.success(null)
                        }
                        "setEveryoneElseSilent" -> {
                            val silent = requireNotNull(call.argument<Boolean>("silent"))
                            RelayRuleStore.update(this) { it.copy(everyoneElseSilent = silent) }
                            result.success(null)
                        }
                        "saveDefaultPreset" -> {
                            val preset = RelayPreset.parse(requireNotNull(call.argument<String>("preset")))
                            RelayRuleStore.update(this) { it.copy(defaultPreset = preset) }
                            result.success(null)
                        }
                        "setPause" -> {
                            val duration = requireNotNull(call.argument<Int>("minutes"))
                            require(duration in setOf(-1, 0, 15, 60))
                            RelayRuleStore.update(this) { it.copy(silence = it.silence.copy(pauseUntil = when(duration) {
                                -1 -> -1L; 0 -> 0L; else -> System.currentTimeMillis() + duration * 60_000L
                            })) }
                            result.success(null)
                        }
                        "saveQuietHours" -> {
                            val enabled = requireNotNull(call.argument<Boolean>("enabled"))
                            val start = requireNotNull(call.argument<Int>("startMinute"))
                            val end = requireNotNull(call.argument<Int>("endMinute"))
                            RelayRuleStore.update(this) { it.copy(silence = it.silence.copy(quietEnabled = enabled, startMinute = start, endMinute = end)) }
                            result.success(null)
                        }
                        "saveToneRules" -> {
                            val enabled = requireNotNull(call.argument<Boolean>("enabled"))
                            fun preset(key: String) = call.argument<String>(key)?.let { RelayPreset.parse(it) }
                            val tone = RelayToneRules(enabled, preset("urgent"), preset("negative"), preset("neutral"))
                            RelayRuleStore.update(this) { it.copy(tone = tone) }
                            result.success(null)
                        }
                        "apps" -> Thread {
                            try {
                                val apps = RelayAppSelection.available(this)
                                runOnUiThread { result.success(apps) }
                            } catch (error: Exception) {
                                runOnUiThread { result.error("APP_LIST", "Could not load apps.", null) }
                            }
                        }.start()
                        "clearHistory" -> {
                            RelayHistory.clear(this)
                            result.success(null)
                        }
                        "previewPreset" -> {
                            checkNotNull(CalendarRelayService.active) { "Start the relay first." }
                                .testPulse(requireNotNull(call.argument<String>("preset"))) { outcome ->
                                    if (outcome.stage == "verified") result.success(null)
                                    else result.error(outcome.errorCode ?: "PREVIEW_FAILED", "Vibration was not verified.", null)
                                }
                        }
                        "testPulse" -> {
                            checkNotNull(CalendarRelayService.active) { "Start the relay first." }.testPulse(call.argument<String>("preset"))
                            result.success(null)
                        }
                        "bluetoothSettings" -> {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            result.success(null)
                        }
                        "labSettings" -> result.success(mapOf("secureDurationSeconds" to
                            getSharedPreferences("demo_lab", MODE_PRIVATE).getInt("duration", 30)))
                        "setLabSettings" -> {
                            val seconds = requireNotNull(call.argument<Int>("secureDurationSeconds"))
                            require(seconds in setOf(30, 60, 90))
                            getSharedPreferences("demo_lab", MODE_PRIVATE).edit().putInt("duration", seconds).apply()
                            result.success(null)
                        }
                        "captureConversations" -> {
                            RelayConversations.capture(this, requireNotNull(call.argument<Boolean>("enabled")))
                            result.success(null)
                        }
                        "setPulseStyle" -> {
                            val style = requireNotNull(call.argument<String>("style"))
                            require(style in setOf("single", "double"))
                            RelayRuleStore.update(this) { it.copy(defaultPreset = RelayPreset.parse(style)) }
                            result.success(null)
                        }
                        "selectApps" -> {
                            val packages = requireNotNull(call.argument<List<String>>("packages")).toSet()
                            RelayRuleStore.update(this) { config -> config.copy(apps =
                                (config.apps.keys + packages).associateWith { pkg ->
                                    (config.apps[pkg] ?: RelayAppRule(false)).copy(enabled = pkg in packages)
                                }) }
                            result.success(null)
                        }
                        "openRepository" -> {
                            // Fixed URLs only; the channel never opens arbitrary links.
                            val url = when (call.argument<String>("page")) {
                                "releases" -> "$REPOSITORY_URL/releases"
                                "issues" -> "$REPOSITORY_URL/issues"
                                "privacy" -> "$REPOSITORY_URL/blob/main/PRIVACY.md"
                                else -> REPOSITORY_URL
                            }
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            result.success(null)
                        }
                        "settings" -> {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            result.success(null)
                        }
                        "start" -> {
                            if (!CalendarRelayService.hasAccess(this) || !CalendarNotificationListener.connected) {
                                result.error("NOTIFICATION_ACCESS", "Enable notification access and wait for connection first.", null)
                            } else {
                                require(RelayRuleStore.read(this).selectedPackages.isNotEmpty()) { "Select at least one app first." }
                                RelayAutoResume.setWanted(this, true)
                                CalendarRelayService.start(this)
                                result.success(null)
                            }
                        }
                        "stop" -> {
                            RelayAutoResume.setWanted(this, false)
                            CalendarRelayService.stop()
                            result.success(null)
                        }
                        else -> result.notImplemented()
                    }
                } catch (error: Exception) {
                    result.error("CALENDAR_RELAY", error.message, null)
                }
            }
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "fitbit_air_example/background")
            .setMethodCallHandler { call, result ->
                if (call.method == "startTest") {
                    val intent = Intent(this, HeadlessHapticService::class.java)
                    intent.putExtra("operation", call.argument<String>("operation") ?: "vibrate")
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
                        else startService(intent)
                        result.success(null)
                    } catch (error: Exception) {
                        result.error("BACKGROUND_START", error.message, null)
                    }
                } else if (call.method == "getLastResult") {
                    result.success(getSharedPreferences("headless_test", MODE_PRIVATE).getString("result", null))
                } else result.notImplemented()
            }
    }

    private companion object {
        const val REPOSITORY_URL = "https://github.com/ThoreKoritzius/fitbit-air"
    }
}
