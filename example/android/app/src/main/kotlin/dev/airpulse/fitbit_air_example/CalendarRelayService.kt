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
    private val groupAlerts = RelayGroupAlerts()
    private val ringingCalls = RelayRingingCalls()
    /** The call the Air is ringing for, while its ring runs. */
    private var activeRing: RelayRingingCalls.Ring? = null
    /** When call rings start and end; holds the removal and call-state timers. */
    private val calls = RelayCallLifecycle(
        { delay, task -> val run = Runnable(task); handler.postDelayed(run, delay); { handler.removeCallbacks(run) } },
        object : RelayCallLifecycle.Callbacks {
            override fun ringNotification(packageName: String, key: String, postedAt: Long) = onIncomingCall(packageName, key, postedAt)
            override fun ringPhoneCall() = onPhoneCallRinging()
            override fun end(key: String, reason: String) = onCallEnded(key, reason)
            override fun outcome(key: String, outcome: String) = RelayCallSignals.outcome(key, outcome)
            override fun isRinging(key: String) = ringingCalls.isRinging(key)
        },
    )
    /** Phone (SIM) calls from Android's call state, once the Phone permission is allowed. */
    private val phoneCalls = RelayPhoneCalls(this) { state -> calls.callState(state) }
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
    /** Keyword rules the listener checks message text against; empty unless the user adds one. */
    internal var keywordRules: List<RelayKeywordRule> = emptyList()
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
        keywordRules = config.keywords
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
        RelayRuleStore.read(this).let { toneEnabled = it.tone.enabled; keywordRules = it.keywords }
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
                                CalendarNotificationListener.existingRingingKeys().forEach { ringingCalls.onIncoming(it) }
                                ready = true
                                settingsChanged()
                                refreshPhoneCalls()
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

    private fun phoneAwake(silence: RelaySilence): Boolean =
        silence.onlyWhileAsleep && getSystemService(PowerManager::class.java).isInteractive

    /** The default phone app, or a system app such as Samsung's in-call screen. */
    private fun isPhoneApp(packageName: String): Boolean {
        val dialer = runCatching { getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull()
        if (packageName == dialer) return true
        return runCatching {
            packageManager.getApplicationInfo(packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }.onFailure {
            // Hidden by package visibility: the manifest queries in-call screens so this stays rare.
            android.util.Log.i("AirRelay", "call app $packageName not visible: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    internal fun onAlert(packageName: String, key: String, postedAt: Long, ongoing: Boolean, summary: Boolean, messageTimestamp: Long?, conversationIds: Set<String>, tone: RelayTone? = null, keyword: RelayKeywordRule? = null,
                         silent: Boolean = false, alertOnce: Boolean = false,
                         group: String? = null, groupAlertBehavior: Int = android.app.Notification.GROUP_ALERT_ALL,
                         heldForSummary: Boolean = false) {
        if (!ready || stopping) return
        val now = System.currentTimeMillis()
        val groupKey = group?.let { RelayGroupAlerts.key(packageName, it) }
        val summaryAlert = if (summary && groupKey != null) groupAlerts.summary(groupKey, key, groupAlertBehavior, postedAt, alertOnce, silent) else null
        if (filter?.accept(packageName, key, postedAt, now, ongoing, summary && summaryAlert == null, messageTimestamp, alertOnce,
                freshInGroup = summaryAlert?.fresh == true) != true) return
        // Android keeps it quiet on the phone, so the Air stays quiet too. Quiet only because its
        // group alerts through the summary: when the summary alerted just before, this message
        // buzzes itself; otherwise the summary that follows buzzes for it.
        val pairsWithSummary = heldForSummary && groupKey != null && groupAlerts.pairsWithSummary(groupKey, postedAt)
        if (silent && !pairsWithSummary) {
            if (heldForSummary && groupKey != null) groupAlerts.hold(groupKey, RelayGroupAlerts.Message(conversationIds, tone, keyword, postedAt))
            record(nextId(), packageName, postedAt, "silent"); return
        }
        val config = RelayRuleStore.read(this)
        // The listener matched against the rules it saw; only keep a rule that still exists.
        val rule = keyword?.takeIf { it in config.keywords }
        val match = if (summaryAlert != null) config.matchSummary(packageName, summaryAlert, tone, rule)
            else config.match(packageName, conversationIds, tone, rule)
        val category = if (rule != null) RelayMatch.KEYWORD_CATEGORY else tone?.takeIf { config.tone.enabled }?.id
        // A muted message leaves the summary's alert for the next message in the group.
        if (match == null) { record(nextId(), packageName, postedAt, "filtered", category = category); return }
        if (pairsWithSummary) groupAlerts.claim(groupKey!!)
        val silence = config.silence.reason(now)
        if (silence != null) { cancelWaiting(silence); record(nextId(), packageName, postedAt, silence, category = category); return }
        if (phoneAwake(config.silence)) {
            record(nextId(), packageName, postedAt, "phone_awake", match = match)
            return
        }
        submit(packageName, postedAt, match)
    }

    /** Starts or stops reading the phone's call state to match the Phone permission. */
    fun refreshPhoneCalls() {
        if (ready && !stopping) phoneCalls.refresh() else phoneCalls.stop()
        calls.listening = phoneCalls.listening
    }

    val phoneCallsListening: Boolean get() = phoneCalls.listening

    private fun defaultDialer(): String? =
        runCatching { getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull()

    /** Apps that show phone (SIM) calls: the default phone app and in-call screens such as Samsung's. */
    private val inCallApps: Set<String> by lazy {
        runCatching {
            packageManager.queryIntentServices(Intent(android.telecom.InCallService.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun showsPhoneCalls(packageName: String): Boolean =
        packageName == defaultDialer() || packageName in inCallApps

    private fun onPhoneCallRinging() {
        val dialer = defaultDialer() ?: "com.android.phone"
        val key = RelayPhoneCalls.KEY
        RelayCallSignals.seen(key, mapOf(
            "atMillis" to System.currentTimeMillis(), "package" to dialer, "source" to "call_state", "recognized" to true,
        ))
        android.util.Log.i("AirRelay", "phone call ringing (call state)")
        onIncomingCall(dialer, key, System.currentTimeMillis(), phoneCall = true)
    }

    /** A ringing call's notification, posted or re-posted. */
    internal fun onIncomingCallNotification(packageName: String, key: String, postedAt: Long) =
        calls.incoming(packageName, key, postedAt, phoneApp = showsPhoneCalls(packageName))

    /** A call notification says its call no longer rings. */
    internal fun onCallNotRinging(key: String, reason: String) = calls.notRinging(key, reason)

    internal fun onIncomingCall(packageName: String, key: String, postedAt: Long, phoneCall: Boolean = false) {
        if (!ready || stopping) { RelayCallSignals.outcome(key, "relay_not_ready"); return }
        val ring = ringingCalls.onIncoming(key) ?: run { RelayCallSignals.outcome(key, "still_ringing"); return }
        val now = System.currentTimeMillis()
        if (postedAt > now || now - postedAt >= 30_000) { RelayCallSignals.outcome(key, "stale"); return }
        val config = RelayRuleStore.read(this)
        val match = config.calls.match() ?: run { RelayCallSignals.outcome(key, "calls_off"); return }
        if (!RelayCallPolicy.accepts(packageName, selectedPackages, phoneCall || isPhoneApp(packageName))) {
            android.util.Log.i("AirRelay", "call from $packageName ignored: app not selected")
            RelayCallSignals.outcome(key, "app_not_selected")
            record(nextId(), packageName, postedAt, "filtered")
            return
        }
        // "Only when the screen is off" does not apply: a ringing call turns the screen on
        // and covers the lock screen, so the phone always looks in use. Quiet hours and pause do.
        val silence = config.silence.reason(now)
        if (silence != null) {
            RelayCallSignals.outcome(key, silence)
            record(nextId(), packageName, postedAt, silence, match = match)
            return
        }
        RelayCallSignals.outcome(key, "sent_to_air")
        submit(packageName, postedAt, match, ringingCall = ring)
    }

    internal fun onCallEnded(key: String, reason: String) {
        calls.ended(key)
        ringingCalls.onEnded(key)
        // Answered, declined or missed: stop ringing on the wrist right away.
        if (activeRing?.key == key) {
            android.util.Log.i("AirRelay", "ring stopped: call $reason")
            channel?.invokeMethod("stopRing", null)
        }
    }

    /**
     * Whether the relay rings for [key] or is still deciding about it, so a
     * re-post that no longer says "call" is still recognized as that call.
     */
    internal fun isCallTracked(key: String): Boolean = ringingCalls.isRinging(key) || calls.tracks(key)

    internal fun onAlertRemoved(key: String, call: Boolean) {
        filter?.onRemoved(key)
        groupAlerts.removed(key)
        // A ringing call's re-post may have lost its call category; removing it still ends the call.
        calls.removed(key)
    }

    internal fun testPulse(preset: String? = null, completion: ((RelayWorkerResult) -> Unit)? = null) {
        check(ready && !stopping) { "Wait until the relay is listening." }
        val config = RelayRuleStore.read(this)
        check(config.silence.reason(System.currentTimeMillis()) == null) { "Resume the relay or leave quiet hours before trying a vibration." }
        val selected = preset?.let { RelayPreset.parse(it) } ?: config.defaultPreset
        require(selected != RelayPreset.MUTE)
        submit(null, System.currentTimeMillis(), RelayMatch(selected, 2), completion)
    }

    private fun submit(sbnPackage: String?, postedAt: Long, match: RelayMatch,
                       completion: ((RelayWorkerResult) -> Unit)? = null,
                       ringingCall: RelayRingingCalls.Ring? = null) {
        val id = nextId()
        if (choices.size >= 256) {
            record(id, sbnPackage, postedAt, "queue_full")
            completion?.invoke(RelayWorkerResult("failed", "QUEUE_FULL"))
            return
        }
        if (completion != null) previewReplies[id] = completion
        choices[id] = RelayBurstChoice(match, System.currentTimeMillis(), ringingCall)
        record(id, sbnPackage, postedAt, "detected", match = match)
        val now = System.currentTimeMillis()
        when (val decision = coalescer.onAlert(id, now)) {
            RelayCoalescer.Decision.DispatchNow -> dispatch(id, sbnPackage, postedAt, postedAt + 30_000, match, ringingCall)
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
        val config = RelayRuleStore.read(this)
        val settings = config.silence
        val silence = settings.reason(now)
        if (silence != null) { cancelWaiting(silence); return }
        val awake = phoneAwake(settings)
        val members = coalescer.takeFollowUp().mapNotNull { id ->
            val source = inFlight.remove(id)
            val choice = choices.remove(id)
            if (source == null || choice == null) null else Triple(id, source, choice)
        }
        val fresh = members.filter { (id, source, choice) ->
            val stage = when {
                now < source.second || now - source.second >= 30_000 -> "expired"
                // A ringing call turns the screen on itself; the screen-off rule is for notifications.
                awake && source.first != null && choice.ringingCall == null -> "phone_awake"
                choice.ringingCall != null -> callSkipReason(config, source.first, choice.ringingCall)
                else -> null
            }
            if (stage == null) true else {
                record(id, source.first, source.second, stage, match = choice.match)
                previewReplies.remove(id)?.invoke(RelayWorkerResult("failed", "EXPIRED"))
                false
            }
        }
        val winner = fresh.reduceOrNull { a, b -> if (a.third.prefer(b.third) === b.third) b else a }
        fresh.forEach { record(it.first, it.second.first, it.second.second, "combined", match = it.third.match) }
        if (winner != null) {
            val id = nextId()
            val replies = fresh.mapNotNull { previewReplies.remove(it.first) }
            if (replies.isNotEmpty()) previewReplies[id] = { result -> replies.forEach { it(result) } }
            dispatch(id, winner.second.first, winner.second.second, winner.second.second + 30_000,
                winner.third.match, winner.third.ringingCall)
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

    private fun callSkipReason(config: RelayRuleConfig, packageName: String?, ring: RelayRingingCalls.Ring): String? =
        when {
            !ringingCalls.isRinging(ring) -> "call_ended"
            !config.calls.enabled || packageName == null ||
                !RelayCallPolicy.accepts(packageName, config.selectedPackages, isPhoneApp(packageName)) -> "filtered"
            else -> null
        }

    private fun dispatch(id: String, sbnPackage: String?, postedAt: Long, expiresAt: Long,
                         match: RelayMatch, ringingCall: RelayRingingCalls.Ring?) {
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
            ringingCall?.let { RelayCallSignals.outcome(it.key, "air_${result.stage}${result.errorCode?.let { c -> ":$c" } ?: ""}") }
            saveOutcome("Haptic ${result.stage}${result.errorCode?.let { ": $it" } ?: ""}.")
            val now = System.currentTimeMillis()
            coalescer.onFinished(now)?.let { if (!stopping) handler.postDelayed(followUp, maxOf(0, it - now)) }
            end()
        }
        val config = RelayRuleStore.read(this)
        val settings = config.silence
        val silence = settings.reason(System.currentTimeMillis())
            ?: "phone_awake".takeIf { sbnPackage != null && ringingCall == null && phoneAwake(settings) }
        val skip = silence ?: ringingCall?.let { callSkipReason(config, sbnPackage, it) }
        if (skip != null || System.currentTimeMillis() >= expiresAt) {
            finished(RelayWorkerResult(skip ?: "expired", null)); return
        }
        if (ringingCall != null && config.calls.ring) {
            // Ring along with the phone until the call stops ringing.
            activeRing = ringingCall
            channel?.invokeMethod("ring", mapOf(
                "intensity" to null, "gapMillis" to RING_GAP_MS, "maxMillis" to RING_MAX_MS,
            ), object : MethodChannel.Result {
                override fun success(result: Any?) { activeRing = null; finished(RelayWorkerResult.decode(result)) }
                override fun error(code: String, message: String?, details: Any?) { activeRing = null; finished(RelayWorkerResult("failed", "WORKER_ERROR")) }
                override fun notImplemented() { activeRing = null; finished(RelayWorkerResult("failed", "WORKER_UNAVAILABLE")) }
            })
            return
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
                       match: RelayMatch? = null, category: String? = match?.category) {
        val now = System.currentTimeMillis()
        RelayHistory.add(this, RelayActivity(id, sbnPackage, stage, now, maxOf(0, now - postedAt), code,
            match?.preset?.id, category))
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
        phoneCalls.stop()
        calls.clear()
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
        /** About one pulse a second, as measured in the held-session ring PR. */
        private const val RING_GAP_MS = 800
        /** Phones ring for about 30–60 s; the ring also stops as soon as the call does. */
        private const val RING_MAX_MS = 45_000
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
            "setupStep" to context.getSharedPreferences("relay_onboarding", MODE_PRIVATE).getInt("step", 0).coerceIn(0, 4),
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
            "outcome" to context.getSharedPreferences("calendar_relay", MODE_PRIVATE).getString("outcome", null),
            // Battery-optimized apps can be paused in the background on some phones, delaying buzzes.
            "batteryUnrestricted" to runCatching {
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            }.getOrNull(),
            "pausesOptimizedApps" to RelayBatteryPolicy.pausesOptimizedApps(Build.MANUFACTURER, Build.BRAND),
            // For the diagnostic report: why a call did or did not buzz on this phone.
            "phone" to mapOf(
                "manufacturer" to Build.MANUFACTURER, "sdk" to Build.VERSION.SDK_INT,
                "defaultDialer" to runCatching { context.getSystemService(android.telecom.TelecomManager::class.java).defaultDialerPackage }.getOrNull(),
                "callStateAccess" to RelayPhoneCalls.granted(context),
                "callStateDeclined" to context.getSharedPreferences("relay_permissions", MODE_PRIVATE).getBoolean("phoneDeclined", false),
                "callStateListening" to (active?.phoneCallsListening == true),
            ),
            "callSignals" to RelayCallSignals.snapshot(),
        )
    }
}
