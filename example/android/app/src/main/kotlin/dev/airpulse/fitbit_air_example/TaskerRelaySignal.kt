package dev.airpulse.fitbit_air_example

/** Tasker's fixed notification title reuses an Android notification key. */
internal object TaskerRelaySignal {
    const val PACKAGE = "net.dinglisch.android.taskerm"
    private const val TITLE = "Air Relay trigger"
    private const val MAX_AGE_MILLIS = 30_000L

    fun isTriggerTitle(title: CharSequence?): Boolean = TITLE.contentEquals(title)

    fun timestamp(text: String?, postedAt: Long): Long? {
        val timestamp = text?.toLongOrNull() ?: return null
        return timestamp.takeIf {
            it > 0 && it <= postedAt && postedAt - it < MAX_AGE_MILLIS
        }
    }
}
