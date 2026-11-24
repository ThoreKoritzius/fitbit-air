package dev.airpulse.fitbit_air

/**
 * Tells "the Air answered, but not as expected" apart from "the Air did not
 * answer". Only the first can mean an incompatible firmware; the second is a
 * temporary transport problem and, before anything was sent, safe to retry.
 */
internal object AirCommandFailure {
    /** The Air replied, but with a rejection or a value this SDK does not accept. */
    class Answered(message: String) : IllegalStateException(message)

    /** Nothing that changes the Air was sent; only a read went unanswered. */
    const val NO_REPLY = "AIR_NO_REPLY"

    /** A change was sent, but neither its reply nor a follow-up read arrived. */
    const val UNCONFIRMED = "HAPTIC_UNCONFIRMED"

    /**
     * Failures after which the link that carried the command is not reused:
     * it connected but did not carry a session or replies. A rejection from
     * the Air proves the link works, so it is not in this set.
     */
    private val unresponsiveLink = setOf(
        NO_REPLY, UNCONFIRMED, "HANDSHAKE_TIMEOUT", "DTLS", "TIMEOUT", "GOLDENGATE_TX", "TX", "RX",
    )

    fun answered(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is Answered }

    /** [answeredCode] when the Air replied unexpectedly; otherwise whether anything was [sent]. */
    fun code(error: Throwable, sent: Boolean, answeredCode: String): String = when {
        answered(error) -> answeredCode
        !sent -> NO_REPLY
        else -> UNCONFIRMED
    }

    fun distrustsLink(code: String): Boolean = code in unresponsiveLink
}
