package com.nuvio.tv.core.player

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerLifecycleBindingTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun `pause stops playback without waiting for a composition frame`() {
        val owner = Owner()
        val events = mutableListOf<String>()
        val binding = TrailerLifecycleBinding(owner.lifecycle, { events += "resume" }, { events += "stop" })
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(emptyList<String>(), events)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        assertEquals(listOf("resume", "stop"), events)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        binding.close()
        assertEquals(listOf("resume", "stop", "resume", "stop"), events)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        assertEquals(4, events.size)
    }

    @Test fun `binding to an already resumed screen acquires immediately and disposal is idempotent`() {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        var starts = 0; var stops = 0
        val binding = TrailerLifecycleBinding(owner.lifecycle, { starts++ }, { stops++ })
        assertEquals(1, starts)
        binding.close(); binding.close()
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(1, starts)
        assertEquals(1, stops)
    }
}
