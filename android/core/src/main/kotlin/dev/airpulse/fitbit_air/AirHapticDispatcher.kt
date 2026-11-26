package dev.airpulse.fitbit_air

import android.os.Handler
import android.os.Looper
import java.security.MessageDigest

/** The known setting-based haptic operations, not arbitrary waveforms. */
enum class AirHapticKind {
    /** Two setting changes; restores and verifies the prior intensity ([FitbitAir.vibrate]). */
    PREVIEW,

    /** One setting change; keeps the alternate intensity ([FitbitAir.pulse]). */
    SINGLE_PULSE,

    /** One pulse at the wearer's strength, which is kept ([FitbitAir.tap]). */
    TAP,
}

/**
 * A command with an app-assigned unique [id] (1–128 characters). Use
 * [fromEventKey] for long keys such as notification keys.
 *
 * Authenticate the sender before building one: the library does not
 * authenticate push payloads or wake an app.
 */
class AirHapticCommand @JvmOverloads constructor(
    val id: String,
    /** Wall-clock time (`System.currentTimeMillis()`) after which the command is dropped. */
    val expiresAtMillis: Long,
    val kind: AirHapticKind = AirHapticKind.PREVIEW,
    /** Optional persistent strength for a single tap; null keeps the current strength. */
    val intensity: HapticIntensity? = null,
) {
    init {
        require(intensity == null || kind == AirHapticKind.TAP) { "An explicit intensity requires AirHapticKind.TAP." }
        require(id.isNotBlank() && id.length <= 128) { "id must contain 1–128 characters." }
    }

    companion object {
        /**
         * A command whose id is the lowercase SHA-256 hex digest of the UTF-8
         * JSON array `[source, eventKey]`: the same id the Dart package makes
         * for the same pair. Use the same pair for retries and a distinct
         * [eventKey] for each new message.
         */
        @JvmStatic
        @JvmOverloads
        fun fromEventKey(
            source: String,
            eventKey: String,
            expiresAtMillis: Long,
            kind: AirHapticKind = AirHapticKind.PREVIEW,
            intensity: HapticIntensity? = null,
        ): AirHapticCommand {
            require(source.isNotBlank()) { "source must not be blank." }
            require(eventKey.isNotBlank()) { "eventKey must not be blank." }
            val json = "[${jsonString(source)},${jsonString(eventKey)}]"
            val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            return AirHapticCommand(digest.joinToString("") { "%02x".format(it) }, expiresAtMillis, kind, intensity)
        }

        /** A JSON string literal, escaped exactly like Dart's `jsonEncode`. */
        internal fun jsonString(value: String): String = buildString {
            append('"')
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\b' -> append("\\b")
                    c == '\t' -> append("\\t")
                    c == '\n' -> append("\\n")
                    c == '\u000C' -> append("\\f")
                    c == '\r' -> append("\\r")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                        append(c).append(value[i + 1]); i++
                    }
                    // A lone surrogate is escaped, as Dart does; it has no UTF-8 encoding.
                    c.isSurrogate() -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
                i++
            }
            append('"')
        }
    }
}

enum class AirHapticDispatchStatus {
    /** The library completed verification. Not a physical-delivery receipt. */
    COMPLETED,
    DUPLICATE,
    EXPIRED,
    QUEUE_FULL,
    CLOSED,

    /** Includes BUSY, timeout, disconnect, and restore or verification failures. */
    FAILED,
}

data class AirHapticDispatchResult @JvmOverloads constructor(
    val status: AirHapticDispatchStatus,
    val error: FitbitAirException? = null,
    /** 2 when the command was retried after a failure that reached nothing on the Air. */
    val attempts: Int = 1,
)

/** Runs one command and reports whether it completed verified. Faked in tests. */
internal fun interface AirHapticRunner {
    fun run(command: AirHapticCommand, done: (Result<Unit>) -> Unit)
}

/**
 * Bounded, in-memory dispatch of notification haptics; the Kotlin port of the
 * Dart `AirHapticDispatcher`, with the same rules:
 *
 * - Commands run one at a time, at least [minimumIntervalMillis] apart.
 * - Accepted ids are remembered for [deduplicationWindowMillis], even after a
 *   failure: a lost reply can mean the command physically ran, so failures
 *   are never retried.
 * - The one exception, with [retryUnsent]: a failure raised before any
 *   command could reach the Air ([unsentErrorCodes]) is retried once, after
 *   [retryDelayMillis]. `HAPTIC_UNCONFIRMED` is never retried.
 * - Persisting dedupe across restarts, and coordinating processes, is the
 *   app's job.
 *
 * Use from the main thread. Callbacks run there, always after [submit] or
 * [close] returned, never inside them. Reuse one dispatcher.
 */
class AirHapticDispatcher internal constructor(
    private val runner: AirHapticRunner,
    private val timers: AirTimers,
    private val clock: () -> Long,
    val minimumIntervalMillis: Long,
    val deduplicationWindowMillis: Long,
    val maxPending: Int,
    val maxRemembered: Int,
    val retryUnsent: Boolean,
    val retryDelayMillis: Long,
) {
    @JvmOverloads
    constructor(
        air: FitbitAir,
        minimumIntervalMillis: Long = 2_000,
        deduplicationWindowMillis: Long = 5 * 60_000,
        maxPending: Int = 4,
        maxRemembered: Int = 256,
        retryUnsent: Boolean = true,
        retryDelayMillis: Long = 3_000,
    ) : this(runnerFor(air), mainThreadTimers(), System::currentTimeMillis, minimumIntervalMillis,
        deduplicationWindowMillis, maxPending, maxRemembered, retryUnsent, retryDelayMillis)

    init {
        require(minimumIntervalMillis >= 0 && deduplicationWindowMillis > 0 && maxPending >= 1 &&
            maxRemembered >= maxPending) { "Invalid dispatcher limits." }
    }

    private class Pending(val command: AirHapticCommand, val callback: (AirHapticDispatchResult) -> Unit)

    private val queue = ArrayDeque<Pending>()
    /** Id → remembered until; insertion order, so the oldest is evicted first. */
    private val seen = LinkedHashMap<String, Long>()
    private val pendingIds = mutableSetOf<String>()
    private var lastFinished: Long? = null
    private var running = false
    private var closed = false
    private var pauseCancel: (() -> Unit)? = null
    private var pauseThen: (() -> Unit)? = null

    /** Includes the command that is running. */
    val pendingCount: Int get() = pendingIds.size

    fun submit(command: AirHapticCommand, callback: (AirHapticDispatchResult) -> Unit) {
        fun answer(status: AirHapticDispatchStatus) { later { callback(AirHapticDispatchResult(status)) } }
        if (closed) return answer(AirHapticDispatchStatus.CLOSED)
        val now = clock()
        if (now >= command.expiresAtMillis) return answer(AirHapticDispatchStatus.EXPIRED)
        seen.entries.removeAll { now >= it.value }
        if (command.id in pendingIds || command.id in seen) return answer(AirHapticDispatchStatus.DUPLICATE)
        if (pendingIds.size >= maxPending) return answer(AirHapticDispatchStatus.QUEUE_FULL)
        if (seen.size >= maxRemembered) seen.remove(seen.keys.first())
        seen[command.id] = now + deduplicationWindowMillis
        pendingIds += command.id
        queue.addLast(Pending(command, callback))
        if (!running) {
            running = true
            drainNext()
        }
    }

    /**
     * Rejects new work and drops queued work. A running haptic finishes,
     * including best-effort restoration; this does not cancel a write.
     */
    fun close() {
        closed = true
        val then = pauseThen
        pauseCancel?.invoke()
        pauseCancel = null
        pauseThen = null
        // The interrupted wait resumes after the queue is dropped, as in Dart.
        if (then != null) later(then)
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            pendingIds -= pending.command.id
            later { pending.callback(AirHapticDispatchResult(AirHapticDispatchStatus.CLOSED)) }
        }
    }

    private fun later(task: () -> Unit) { timers.post(0, task) }

    private fun drainNext() {
        val pending = queue.removeFirstOrNull() ?: run { running = false; return }
        val wait = lastFinished?.let { it + minimumIntervalMillis - clock() } ?: 0
        pause(wait) { start(pending) }
    }

    private fun start(pending: Pending) {
        if (closed) return finish(pending, AirHapticDispatchResult(AirHapticDispatchStatus.CLOSED))
        attempt(pending.command) { first ->
            val code = first.error?.code
            if (retryUnsent && first.status == AirHapticDispatchStatus.FAILED && code in unsentErrorCodes) {
                pause(retryDelayMillis) {
                    if (closed) finish(pending, first)
                    else attempt(pending.command) { retried ->
                        finish(pending, retried.copy(attempts = 2))
                    }
                }
            } else finish(pending, first)
        }
    }

    private fun finish(pending: Pending, result: AirHapticDispatchResult) {
        pendingIds -= pending.command.id
        later { pending.callback(result) }
        drainNext()
    }

    /** Waits [millis]; [close] ends the wait early. */
    private fun pause(millis: Long, then: () -> Unit) {
        if (millis <= 0) return then()
        pauseThen = then
        pauseCancel = timers.post(millis) {
            pauseCancel = null
            pauseThen = null
            then()
        }
    }

    private fun attempt(command: AirHapticCommand, done: (AirHapticDispatchResult) -> Unit) {
        if (clock() >= command.expiresAtMillis) {
            lastFinished = clock()
            return done(AirHapticDispatchResult(AirHapticDispatchStatus.EXPIRED))
        }
        runner.run(command) { result ->
            lastFinished = clock()
            done(result.fold(
                onSuccess = { AirHapticDispatchResult(AirHapticDispatchStatus.COMPLETED) },
                onFailure = { error ->
                    AirHapticDispatchResult(AirHapticDispatchStatus.FAILED, error as? FitbitAirException
                        ?: FitbitAirException("DISPATCH_FAILED", "Haptic dispatch failed."))
                },
            ))
        }
    }

    companion object {
        /** Errors raised before any device command can have been sent. */
        @JvmField
        val unsentErrorCodes: Set<String> = setOf("HANDSHAKE_TIMEOUT", "AIR_NO_REPLY")

        private fun unverified(what: String) = Result.failure<Unit>(FitbitAirException("UNVERIFIED", what))

        internal fun runnerFor(air: FitbitAir) = AirHapticRunner { command, done ->
            when (command.kind) {
                AirHapticKind.TAP -> air.tap(command.intensity) { result ->
                    done(result.fold({ if (it.verified) Result.success(Unit) else unverified("Tap was not verified.") },
                        { Result.failure(it) }))
                }
                AirHapticKind.SINGLE_PULSE -> air.pulse { result ->
                    done(result.fold({ if (it.verified) Result.success(Unit) else unverified("Pulse was not verified.") },
                        { Result.failure(it) }))
                }
                AirHapticKind.PREVIEW -> air.vibrate { result ->
                    done(result.fold({
                        if (it.verified && it.restored) Result.success(Unit) else unverified("Restoration was not verified.")
                    }, { Result.failure(it) }))
                }
            }
        }

        private fun mainThreadTimers(): AirTimers {
            val handler = Handler(Looper.getMainLooper())
            return AirTimers { delay, task ->
                val runnable = Runnable(task)
                handler.postDelayed(runnable, delay)
                ({ handler.removeCallbacks(runnable) })
            }
        }
    }
}

/** Delayed work; returns a cancel function. Faked in tests. */
internal fun interface AirTimers {
    fun post(delayMillis: Long, task: () -> Unit): () -> Unit
}
