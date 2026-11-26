package dev.airpulse.fitbit_air

/** Marshal onto the lifecycle thread, then check identity (never before posting).
 * A queued callback from a closed session must not touch its replacement.
 */
internal class AirSessionCallbacks<T : Any>(
    private val current: () -> T?,
    private val post: (() -> Unit) -> Unit,
) {
    fun dispatch(session: T, action: () -> Unit) {
        post { if (current() === session) action() }
    }
}
