package com.davidhayesbc.cocktails.android.product

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInferenceLifecycleTest {
    @Test
    fun serializesWholeModelLifecycles() = runBlocking {
        val active = AtomicInteger(0)
        val peakActive = AtomicInteger(0)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            LocalInferenceLifecycle.withExclusiveModel {
                peakActive.updateAndGet { maxOf(it, active.incrementAndGet()) }
                firstEntered.complete(Unit)
                releaseFirst.await()
                active.decrementAndGet()
            }
        }
        firstEntered.await()
        val second = launch {
            LocalInferenceLifecycle.withExclusiveModel {
                peakActive.updateAndGet { maxOf(it, active.incrementAndGet()) }
                secondEntered.complete(Unit)
                active.decrementAndGet()
            }
        }

        yield()
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.join()
        second.join()

        assertTrue(secondEntered.isCompleted)
        assertEquals(1, peakActive.get())
        assertEquals(0, active.get())
    }

    @Test
    fun cancelledWaiterNeverEntersOrCleansUpActiveOwner() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var queuedCleanupCalls = 0
        val first = launch {
            LocalInferenceLifecycle.withExclusiveModel {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val queued = launch {
            LocalInferenceLifecycle.withExclusiveModel {
                queuedCleanupCalls += 1
            }
        }

        yield()
        queued.cancelAndJoin()
        releaseFirst.complete(Unit)
        first.join()

        assertEquals(0, queuedCleanupCalls)
    }

    @Test
    fun failedOwnerReleasesLifecycleForRetry() = runBlocking {
        val first = runCatching {
            LocalInferenceLifecycle.withExclusiveModel<Unit> { error("fixture failure") }
        }
        assertTrue(first.isFailure)

        val retried = LocalInferenceLifecycle.withExclusiveModel { "ready" }

        assertEquals("ready", retried)
    }
}
