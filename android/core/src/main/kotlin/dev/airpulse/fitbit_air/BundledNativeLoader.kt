package dev.airpulse.fitbit_air

import com.fitbit.goldengate.bindings.GoldenGate

/**
 * Loads the Golden Gate transport built from the public Apache-2.0 source and
 * bundled with this plugin (`libgg.so` core and `libxp.so` JNI bridge). Nothing
 * is read from Google Health.
 */
internal object BundledNativeLoader {
    @Volatile
    private var initialized = false

    /**
     * Starts Golden Gate in the background while the app has few files open.
     * Its loop watches its sockets with `select()`, which aborts the process
     * for a file descriptor of 1024 or more, and creates those sockets when it
     * starts. Started at the first command instead, in an app that has opened
     * many files (AirBuzz saw this after listing installed apps), the sockets
     * can get such numbers. A failure here is reported by the first command,
     * which calls [initialize] again.
     */
    fun initializeEarly() {
        if (initialized) return
        Thread({ runCatching { initialize() } }, "FitbitAirInit").start()
    }

    @Synchronized
    fun initialize() {
        if (initialized) return
        // libxp.so links against libgg.so; load the core first.
        System.loadLibrary("gg")
        GoldenGate.init(loggingEnabled = false)
        initialized = true
    }
}
