package com.nuvio.tv.core.runtime

import android.app.Activity
import android.app.Application
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedLifecycleCallbacksTest {

    private val activity = mockk<Activity>(relaxed = true)

    private class Recorder(private val failOnResume: Boolean = false) : Application.ActivityLifecycleCallbacks {
        val events = mutableListOf<String>()
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) { events += "created" }
        override fun onActivityStarted(activity: Activity) { events += "started" }
        override fun onActivityResumed(activity: Activity) {
            events += "resumed"
            if (failOnResume) throw NoClassDefFoundError("androidx/appcompat/app/AppCompatActivity")
        }
        override fun onActivityPaused(activity: Activity) { events += "paused" }
        override fun onActivityStopped(activity: Activity) { events += "stopped" }
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) { events += "saved" }
        override fun onActivityDestroyed(activity: Activity) { events += "destroyed" }
    }

    @Test
    fun `calls are passed on while the callback works`() {
        val recorder = Recorder()
        val guarded = GuardedLifecycleCallbacks(recorder)
        guarded.onActivityCreated(activity, null)
        guarded.onActivityResumed(activity)
        guarded.onActivityDestroyed(activity)
        assertEquals(listOf("created", "resumed", "destroyed"), recorder.events)
        assertFalse(guarded.failed)
    }

    @Test
    fun `a missing class inside the callback is caught and switches it off`() {
        val recorder = Recorder(failOnResume = true)
        val guarded = GuardedLifecycleCallbacks(recorder)
        guarded.onActivityResumed(activity)
        guarded.onActivityPaused(activity)
        guarded.onActivityDestroyed(activity)
        assertTrue(guarded.failed)
        assertEquals(listOf("resumed"), recorder.events)
    }

    @Test
    fun `only callbacks from another class loader are wrapped`() {
        val appLoader = javaClass.classLoader
        assertFalse(GuardedLifecycleCallbacks.isFromLoadedCode(Recorder(), appLoader))
        assertTrue(GuardedLifecycleCallbacks.isFromLoadedCode(Recorder(), ClassLoader.getSystemClassLoader().parent))
    }
}
