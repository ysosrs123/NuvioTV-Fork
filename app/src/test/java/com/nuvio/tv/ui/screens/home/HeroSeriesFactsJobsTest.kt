package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroSeriesFactsJobsTest {

    @Test fun movingOnLeavesEarlierLookUpsRunning() {
        val jobs = HeroSeriesFactsJobs(max = 3)
        val first = Job()
        val second = Job()
        jobs.add("a", first)
        jobs.add("b", second)
        assertTrue(first.isActive)
        assertTrue(jobs.isRunning("a"))
        assertTrue(jobs.isRunning("b"))
    }

    @Test fun pastTheLimitTheOldestStops() {
        val jobs = HeroSeriesFactsJobs(max = 2)
        val first = Job()
        val second = Job()
        val third = Job()
        jobs.add("a", first)
        jobs.add("b", second)
        jobs.add("c", third)
        assertTrue(first.isCancelled)
        assertFalse(jobs.isRunning("a"))
        assertTrue(second.isActive)
        assertTrue(third.isActive)
    }

    @Test fun aFinishedLookUpFreesItsPlace() {
        val jobs = HeroSeriesFactsJobs(max = 2)
        val first = Job()
        val second = Job()
        jobs.add("a", first)
        jobs.add("b", second)
        first.complete()
        jobs.finished("a", first)
        val third = Job()
        jobs.add("c", third)
        assertTrue(second.isActive)
        assertTrue(third.isActive)
    }

    @Test fun anOlderJobFinishingDoesNotDropTheNewOne() {
        val jobs = HeroSeriesFactsJobs(max = 2)
        val old = Job()
        val new = Job()
        jobs.add("a", old)
        jobs.add("a", new)
        assertTrue(old.isCancelled)
        jobs.finished("a", old)
        assertTrue(jobs.isRunning("a"))
    }

    @Test fun clearingStopsEverything() {
        val jobs = HeroSeriesFactsJobs(max = 4)
        val first = Job()
        val second = Job()
        jobs.add("a", first)
        jobs.add("b", second)
        jobs.cancelAll()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertFalse(jobs.isRunning("a"))
    }
}
