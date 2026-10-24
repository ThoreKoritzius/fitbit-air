package dev.airpulse.fitbit_air_example

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/** User-started example relay; never starts work from a notification callback. */
class CalendarRelayService : Service() {
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    private var filter: CalendarAlertFilter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pending = 0
    private var ready = false
    private var stopping = false
    private var sequence = 0
    private val inFlight = mutableMapOf<String, Pair<String?, Long>>()
    private var selectedPackages = emptySet<String>()
    /** Read by the listener so message text is only inspected after the user opts in. */
    var toneEnabled = false
        private set
    private var pulseStyle = "single"
    private val previewReplies = mutableMapOf<String, (RelayWorkerResult) -> Unit>()
    private val choices = mutableMapOf<String, RelayBurstChoice>()
    private var displayedSilence: String? = null
    private val silenceTick = Runnable { settingsChanged() }
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { settingsChanged() }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(clockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(clockReceiver, filter)
    }

    fun settingsChanged() {
        val config = RelayRuleStore.read(this)
        val packages = config.selectedPackages
        toneEnabled = config.tone.enabled
        if (toneEnabled) RelayToneLexicons.preload(this)
        if (packages != selectedPackages) {
            selectedPackages = packages
            if (ready) filter = CalendarAlertFilter(System.currentTimeMillis(),
                CalendarNotificationListener.existingKeys(packages), packages)
        }
        val now = System.currentTimeMillis()
        val reason = config.silence.reason(now)
        if (reason != null) cancelWaiting(reason)
        if (ready && reason != displayedSilence) {
            displayedSilence = reason
            getSystemService(NotificationManager::class.java).notify(902, buildNotification())
        }
        handler.removeCallbacks(silenceTick)
        if (!stopping) {
            val until = config.silence.pauseUntil
            val delay = if (until > now) minOf(60_000L - now % 60_000L, until - now) else 60_000L - now % 60_000L
            handler.postDelayed(silenceTick, maxOf(1L, delay))
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, 0, Intent(this, CalendarRelayService::class.java).setAction(STOP), flags)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val silence = RelayRuleStore.read(this).silence.reason(System.currentTimeMillis())
        val pause = PendingIntent.getService(this, 2, Intent(this, CalendarRelayService::class.java)
            .setAction(if (silence == "paused") RESUME else PAUSE), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Air Relay")
            .setContentText(when(silence) { "paused" -> "Paused · No notification vibrations"; "quiet_hours" -> "Quiet hours · No notification vibrations"; else -> "Listening for your notification rules" })
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (silence == "paused") "Resume" else "Pause 15 min", pause).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    private val handler = Handler(Looper.getMainLooper())
    private var waitingForListener = false
    /** Set when the relay came back on its own; recorded once it starts. */
    private var resumeReason: String? = null
    private val coalescer = RelayCoalescer()
    private var holdingFollowUp = false
    private val followUp = Runnable { sendFollowUp() }
    private val watchdog = Runnable {
        saveOutcome("Relay command exceeded its deadline; restarting. Delivery is uncertain; no retry.")
        resumeAfterStop = "watchdog"
        stopSelf()
    }
    private var resumeAfterStop: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starting = false
        if (cancelStart) {
            cancelStart = false
            saveOutcome("Relay stopped.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            RelayAutoResume.setWanted(this, false)
            requestStop("Relay stopped.")
            return START_NOT_STICKY
        }
        if (intent?.action == PAUSE || intent?.action == RESUME) {
            RelayRuleStore.update(this) { it.copy(silence = it.silence.copy(pauseUntil = if (intent.action == RESUME) 0 else System.currentTimeMillis() + 900_000)) }
            if (engine == null) stopSelf()
            return START_NOT_STICKY
        }
        if (engine != null || waitingForListener) return START_STICKY
        // Null after Android restarted the sticky service in a new process.
        resumeReason = intent?.getStringExtra(RESUME_REASON) ?: if (intent == null) "process" else null
        selectedPackages = RelayRuleStore.read(this).selectedPackages
        toneEnabled = RelayRuleStore.read(this).tone.enabled
        if (toneEnabled) RelayToneLexicons.preload(this)
        pulseStyle = RelayAppSelection.pulseStyle(this)
        if (selectedPackages.isEmpty()) {
            saveOutcome("Select at least one notification app before starting.")
            stopSelf()
            return START_NOT_STICKY
        }
        val resuming = resumeReason != null
        if (!hasAccess(this) || (!CalendarNotificationListener.connected && !resuming)) {
            if (!hasAccess(this)) RelayAutoResume.setWanted(this, false)
            saveOutcome("Enable notification access and wait for the listener to connect, then start again.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Notifications to Air", NotificationManager.IMPORTANCE_LOW)
            )
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(902, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(902, notification)
            active = this
        } catch (error: Exception) {
            saveOutcome("Relay startup failed: ${error.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!CalendarNotificationListener.connected) {
            // After a restart Android binds the listener a little later.
            waitingForListener = true
            saveOutcome("Resuming notifications…")
            val deadline = android.os.SystemClock.elapsedRealtime() + LISTENER_WAIT_MS
            val check = object : Runnable {
                override fun run() {
                    if (active !== this@CalendarRelayService || stopping) return
                    when {
                        CalendarNotificationListener.connected -> {
                            waitingForListener = false
                            beginRelay()
                        }
                        android.os.SystemClock.elapsedRealtime() >= deadline -> {
                            waitingForListener = false
                            requestStop("Notification access isn’t connected yet. Air Relay resumes when it is.")
                        }
                        else -> handler.postDelayed(this, 500)
                    }
                }
            }
            handler.post(check)
            return START_STICKY
        }
        beginRelay()
        return START_STICKY
    }

    private fun beginRelay() {
        try {
            resumeReason?.let { reason ->
                RelayHistory.add(this, RelayActivity("resumed-$reason-${System.currentTimeMillis()}",
                    null, "resumed", System.currentTimeMillis()))
            }
            saveOutcome("Starting notification relay…")
            val loader = FlutterInjector.instance().flutterLoader()
            loader.startInitialization(applicationContext)
            loader.ensureInitializationComplete(applicationContext, null)
            val relayEngine = FlutterEngine(applicationContext)
            engine = relayEngine
            channel = MethodChannel(relayEngine.dartExecutor.binaryMessenger, "fitbit_air_example/calendar_worker")
                .also { worker ->
                    worker.setMethodCallHandler { call, result ->
                        if (call.method == "ready") {
                            try {
                                check(hasAccess(this) && CalendarNotificationListener.connected)
                                filter = CalendarAlertFilter(System.currentTimeMillis(),
                                    CalendarNotificationListener.existingKeys(selectedPackages), selectedPackages)
                                ready = true
                                settingsChanged()
                                handler.removeCallbacks(watchdog)
                                saveOutcome("Listening for alerts from ${selectedPackages.size} selected app(s).")
                                result.success(null)
                                if (stopping) requestStop("Relay stopped.")
                            } catch (error: Exception) {
                                result.error("NOTIFICATION_ACCESS", "Notification listener unavailable.", null)
                                requestStop("Notification listener unavailable. Enable access and start again.")
                            }
                        } else result.notImplemented()
                    }
                }
            handler.postDelayed(watchdog, 60_000)
            relayEngine.dartExecutor.executeDartEntrypoint(
                DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "airCalendarRelay"))
        } catch (error: Exception) {
            saveOutcome("Relay startup failed: ${error.javaClass.simpleName}")
            stopSelf()
        }
    }

    fun acceptsPackage(packageName: String): Boolean = ready && !stopping && packageName in selectedPackages

    internal fun onAlert(packageName: String, key: String, postedAt: Long, ongoing: Boolean, summary: Boolean, messageTimestamp: Long?, conversationIds: Set<String>, tone: RelayTone? = null) {
        if (!ready || stopping) return
        val now = System.currentTimeMillis()
        if (filter?.accept(packageName, key, postedAt, now, ongoing, summary, messageTimestamp) != true) return
        val config = RelayRuleStore.read(this)
        val match = config.match(packageName, conversationIds, tone)
        val category = tone?.takeIf { config.tone.enabled }
        if (match == null) { record(nextId(), packageName, postedAt, "filtered", tone = category); return }
        val silence = config.silence.reason(now)
        if (silence != null) { cancelWaiting(silence); record(nextId(), packageName, postedAt, silence, tone = category); return }
        submit(packageName, postedAt, match)
    }

    internal fun testPulse(preset: String? = null, completion: ((RelayWorkerResult) -> Unit)? = null) {
        check(ready && !stopping) { "Wait until the relay is listening." }
        val config = RelayRuleStore.read(this)
        check(config.silence.reason(System.currentTimeMillis()) == null) { "Resume the relay or leave quiet hours before trying a vibration." }
        val selected = preset?.let { RelayPreset.parse(it) } ?: config.defaultPreset
        require(selected != RelayPreset.MUTE)
        submit(null, System.currentTimeMillis(), RelayMatch(selected, 2), completion)
    }

    private fun submit(sbnPackage: String?, postedAt: Long, match: RelayMatch, completion: ((RelayWorkerResult) -> Unit)? = null) {
        val id = nextId()
        if (choices.size >= 256) {
            record(id, sbnPackage, postedAt, "queue_full")
            completion?.invoke(RelayWorkerResult("failed", "QUEUE_FULL"))
            return
        }
        if (completion != null) previewReplies[id] = completion
        choices[id] = RelayBurstChoice(match, System.currentTimeMillis())
        record(id, sbnPackage, postedAt, "detected", match = match)
        val now = System.currentTimeMillis()
        when (val decision = coalescer.onAlert(id, now)) {
            RelayCoalescer.Decision.DispatchNow -> dispatch(id, sbnPackage, postedAt, postedAt + 30_000, match)
            is RelayCoalescer.Decision.Merged -> {
                if (!holdingFollowUp) { holdingFollowUp = true; begin() }
                inFlight[id] = sbnPackage to postedAt
                record(id, sbnPackage, postedAt, "merged", match = match)
                decision.scheduleAtMillis?.let { handler.postDelayed(followUp, maxOf(0, it - now)) }
            }
        }
    }

    private fun cancelWaiting(reason: String) {
        handler.removeCallbacks(followUp)
        coalescer.cancelFollowUp().forEach { id ->
            choices.remove(id)
            previewReplies.remove(id)?.invoke(RelayWorkerResult("failed", "SUPPRESSED"))
            inFlight.remove(id)?.let { (source, postedAt) -> record(id, source, postedAt, reason) }
        }
        if (holdingFollowUp) { holdingFollowUp = false; end() }
    }

    private fun sendFollowUp() {
        if (stopping || !coalescer.hasFollowUp) return
        val now = System.currentTimeMillis()
        val silence = RelayRuleStore.read(this).silence.reason(now)
        if (silence != null) { cancelWaiting(silence); return }
        val members = coalescer.takeFollowUp().mapNotNull { id ->
            val source = inFlight.remove(id)
            val choice = choices.remove(id)
            if (source == null || choice == null) null else Triple(id, source, choice)
        }
        val fresh = members.filter { now >= it.second.second && now - it.second.second < 30_000 }
        members.filterNot { it in fresh }.forEach {
            record(it.first, it.second.first, it.second.second, "expired")
            previewReplies.remove(it.first)?.invoke(RelayWorkerResult("failed", "EXPIRED"))
        }
        val winner = fresh.reduceOrNull { a, b -> if (a.third.prefer(b.third) === b.third) b else a }
        fresh.forEach { record(it.first, it.second.first, it.second.second, "combined", match = it.third.match) }
        if (winner != null) {
            val id = nextId()
            val replies = fresh.mapNotNull { previewReplies.remove(it.first) }
            if (replies.isNotEmpty()) previewReplies[id] = { result -> replies.forEach { it(result) } }
            dispatch(id, winner.second.first, winner.second.second, winner.second.second + 30_000, winner.third.match)
        } else coalescer.onFinished(now)
        holdingFollowUp = false
        end()
    }

    internal fun readBattery(reply: MethodChannel.Result) = readDeviceStatus("readBattery", reply)
    internal fun readSignal(reply: MethodChannel.Result) = readDeviceStatus("readSignalStrength", reply)
    internal fun readDeviceInfo(reply: MethodChannel.Result) = readDeviceStatus("readDeviceInfo", reply)

    private fun readDeviceStatus(operation: String, reply: MethodChannel.Result) {
        val worker = channel
        if (!ready || stopping || pending != 0 || worker == null) {
            reply.error("BUSY", "The relay is delivering a notification. Try again shortly.", null)
            return
        }
        begin()
        worker.invokeMethod(operation, null, object : MethodChannel.Result {
            override fun success(result: Any?) { end(); reply.success(result) }
            override fun error(code: String, message: String?, details: Any?) { end(); reply.error(code, message, details) }
            override fun notImplemented() { end(); reply.error("WORKER_UNAVAILABLE", "Device read unavailable.", null) }
        })
    }

    private fun dispatch(id: String, sbnPackage: String?, postedAt: Long, expiresAt: Long, match: RelayMatch) {
        val preset = match.preset
        begin()
        refreshDeadline()
        inFlight[id] = sbnPackage to postedAt
        record(id, sbnPackage, postedAt, "queued", match = match)
        fun finished(result: RelayWorkerResult) {
            previewReplies.remove(id)?.invoke(result)
            choices.remove(id)
            inFlight.remove(id)
            record(id, sbnPackage, postedAt, result.stage, result.errorCode, match)
            saveOutcome("Haptic ${result.stage}${result.errorCode?.let { ": $it" } ?: ""}.")
            val now = System.currentTimeMillis()
            coalescer.onFinished(now)?.let { if (!stopping) handler.postDelayed(followUp, maxOf(0, it - now)) }
            end()
        }
        val silence = RelayRuleStore.read(this).silence.reason(System.currentTimeMillis())
        if (silence != null || System.currentTimeMillis() >= expiresAt) {
            finished(RelayWorkerResult(silence ?: "expired", null)); return
        }
        channel?.invokeMethod("dispatch", mapOf(
            "id" to id, "expiresAt" to expiresAt,
            "kind" to if (preset == RelayPreset.DOUBLE) "preview" else "tap", "intensity" to preset.intensity
        ), object : MethodChannel.Result {
            override fun success(result: Any?) { finished(RelayWorkerResult.decode(result)) }
            override fun error(code: String, message: String?, details: Any?) { finished(RelayWorkerResult("failed", "WORKER_ERROR")) }
            override fun notImplemented() { finished(RelayWorkerResult("failed", "WORKER_UNAVAILABLE")) }
        })
    }

    /** Running commands and a waiting follow-up each hold the wake lock and watchdog. */
    private fun begin() {
        if (pending++ == 0) refreshDeadline()
    }

    /** The watchdog bounds one hung command, so a long burst re-arms it per dispatch. */
    private fun refreshDeadline() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 240_000)
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fitbit_air_example:CalendarRelay")
            .apply { setReferenceCounted(false) }
        wakeLock = lock
        lock.acquire(240_000)
    }

    private fun end() {
        if (--pending == 0) {
            handler.removeCallbacks(watchdog)
            releaseWakeLock()
            if (stopping) stopSelf()
        }
    }

    private fun nextId() = "relay-${System.currentTimeMillis()}-${++sequence}"

    private fun record(id: String, sbnPackage: String?, postedAt: Long, stage: String, code: String? = null,
                       match: RelayMatch? = null, tone: RelayTone? = match?.tone) {
        val now = System.currentTimeMillis()
        RelayHistory.add(this, RelayActivity(id, sbnPackage, stage, now, maxOf(0, now - postedAt), code,
            match?.preset?.id, tone?.id))
    }

    fun requestStop(reason: String) {
        stopping = true
        saveOutcome(reason)
        channel?.invokeMethod("close", null)
        handler.removeCallbacks(silenceTick)
        cancelWaiting("cancelled")
        if (pending == 0) stopSelf()
    }

    override fun onDestroy() {
        ready = false
        stopping = true
        val endedAt = System.currentTimeMillis()
        inFlight.forEach { (id, source) ->
            RelayHistory.add(this, RelayActivity(id, source.first, "interrupted", endedAt,
                maxOf(0, endedAt - source.second), "SERVICE_STOPPED"))
        }
        inFlight.clear()
        choices.clear()
        val replies = previewReplies.values.toList()
        previewReplies.clear()
        replies.forEach { it(RelayWorkerResult("failed", "SERVICE_STOPPED")) }
        unregisterReceiver(clockReceiver)
        handler.removeCallbacks(silenceTick)
        if (active === this) active = null
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(followUp)
        channel?.setMethodCallHandler(null)
        channel = null
        engine?.destroy()
        engine = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        resumeAfterStop?.let { reason ->
            val context = applicationContext
            handler.postDelayed({ RelayAutoResume.resume(context, reason) }, 2_000L)
        }
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun saveOutcome(value: String) {
        getSharedPreferences("calendar_relay", MODE_PRIVATE).edit().putString("outcome", value).apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private var starting = false
        private var cancelStart = false
        val isRunning: Boolean get() = active != null || starting

        fun start(context: Context, resumeReason: String? = null) {
            if (active != null || starting) return
            starting = true
            cancelStart = false
            try {
                val intent = Intent(context, CalendarRelayService::class.java)
                resumeReason?.let { intent.putExtra(RESUME_REASON, it) }
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (error: Exception) {
                starting = false
                throw error
            }
        }

        fun stop() {
            if (starting) cancelStart = true
            active?.requestStop("Relay stopped.")
        }

        private const val CHANNEL = "calendar_relay"
        private const val RESUME_REASON = "calendar_relay.RESUME_REASON"
        private const val LISTENER_WAIT_MS = 60_000L
        private const val STOP = "calendar_relay.STOP"
        private const val PAUSE = "calendar_relay.PAUSE"
        private const val RESUME = "calendar_relay.RESUME"
        var active: CalendarRelayService? = null
            private set

        fun hasAccess(context: Context): Boolean {
            val expected = ComponentName(context, CalendarNotificationListener::class.java)
            return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
        }

        private fun bluetoothPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun status(context: Context): Map<String, Any?> = mapOf(
            "notificationControlsAllowed" to context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled(),
            "setupStep" to context.getSharedPreferences("relay_onboarding", MODE_PRIVATE).getInt("step", 0).coerceIn(0, 3),
            "setupCompleted" to context.getSharedPreferences("relay_onboarding", MODE_PRIVATE).getBoolean("completed", false),
            "access" to hasAccess(context),
            "connected" to CalendarNotificationListener.connected,
            "running" to isRunning,
            "ready" to (active?.ready == true),
            "stopping" to (active?.stopping == true),
            "selectedApps" to RelayRuleStore.read(context).selectedPackages.sorted(),
            "pulseStyle" to RelayRuleStore.read(context).defaultPreset.id,
            "rules" to RelayRuleStore.status(context),
            "conversations" to RelayConversations.status(),
            "history" to RelayHistory.read(context).asReversed().map { it.toMap() },
            "bluetoothPermission" to bluetoothPermission(context),
            "bluetoothEnabled" to if (bluetoothPermission(context)) runCatching {
                context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled
            }.getOrNull() else null,
            "outcome" to context.getSharedPreferences("calendar_relay", MODE_PRIVATE).getString("outcome", null)
        )
    }
}
