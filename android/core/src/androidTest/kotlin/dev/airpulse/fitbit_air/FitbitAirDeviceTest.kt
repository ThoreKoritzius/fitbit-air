package dev.airpulse.fitbit_air

import android.Manifest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Read-only checks of the Kotlin API on a phone with a paired Air. Never
 * vibrates. Run by hand: ./gradlew :fitbit_air_core:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class FitbitAirDeviceTest {
    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 31)
        GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_CONNECT) else GrantPermissionRule.grant()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())

    private fun <T> await(seconds: Long = 60, call: (FitbitAir, (Result<T>) -> Unit) -> Unit): Result<T> {
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        lateinit var air: FitbitAir
        main.post {
            air = FitbitAir(context)
            call(air) { result = it; done.countDown() }
        }
        assertTrue("no answer within $seconds s", done.await(seconds, TimeUnit.SECONDS))
        main.post { air.close() }
        return result!!
    }

    @Test fun pairedAndConnected() {
        assertEquals(true, await<Boolean> { air, cb -> air.isAirPaired(cb) }.getOrThrow())
        assertEquals(true, await<Boolean> { air, cb -> air.isAirConnected(cb) }.getOrThrow())
    }

    @Test fun batteryOverTheSecureChannel() {
        val battery = await<AirBattery> { air, cb -> air.readBattery(cb) }.getOrThrow()
        assertTrue(battery.percent in 0..100)
    }

    @Test fun deviceInfoListsTheAirServices() {
        val info = await<AirDeviceInfo> { air, cb -> air.readDeviceInfo(cb) }.getOrThrow()
        assertTrue(info.services.isNotEmpty())
    }

    @Test fun connectionStreamReportsTheLink() {
        val event = await<AirConnectionEvent>(10) { air, cb ->
            lateinit var subscription: AirSubscription
            subscription = air.watchConnection(onError = { cb(Result.failure(it)) }) {
                subscription.cancel()
                cb(Result.success(it))
            }
        }.getOrThrow()
        assertTrue(event.connected)
    }
}
