package dev.airpulse.fitbit_air

/**
 * What a tap's write and the strength read after it prove. A tap at the
 * wearer's own strength leaves that strength in place, so reading it back
 * cannot tell a played tap from one that never happened: only the Air's
 * reply to the write can. A tap at another strength is proven by the new
 * strength being read back.
 */
internal object AirTapCheck {
    enum class Write {
        /** The Air accepted the change. */
        ACCEPTED,
        /** The Air answered with a rejection. */
        REJECTED,
        /** No reply: the change may or may not have been applied. */
        NO_REPLY,
    }

    sealed interface Outcome
    object Verified : Outcome
    /** Failed. [restore]: put the wearer's strength back first. */
    data class Failed(val reason: Reason, val restore: Boolean) : Outcome

    enum class Reason {
        /** The Air rejected the tap. */
        REJECTED,
        /** The Air accepted it but the strength read back is not the tap's. */
        NOT_VERIFIED,
        /** Neither a reply nor the strength proves the tap: it may have played, so it is not resent. */
        UNCONFIRMED,
    }

    /** [readBack]: the strength read after the write, null when that read failed. */
    fun after(original: Int, target: Int, write: Write, readBack: Int?): Outcome {
        // Unknown strength: only a tap that changes the strength can have left something to undo.
        val restore = if (readBack == null) target != original else readBack != original
        return when (write) {
            Write.REJECTED -> Failed(Reason.REJECTED, restore)
            Write.ACCEPTED -> when (readBack) {
                null -> Failed(Reason.UNCONFIRMED, restore)
                target -> Verified
                else -> Failed(Reason.NOT_VERIFIED, restore)
            }
            // A changed strength read back proves the change arrived; an unchanged one proves nothing.
            Write.NO_REPLY ->
                if (target != original && readBack == target) Verified
                else Failed(Reason.UNCONFIRMED, restore)
        }
    }
}
