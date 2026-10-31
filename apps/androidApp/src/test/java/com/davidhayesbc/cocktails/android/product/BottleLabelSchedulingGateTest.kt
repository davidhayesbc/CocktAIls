package com.davidhayesbc.cocktails.android.product

import com.davidhayesbc.cocktails.core.model.BottleIntakeDraft
import com.davidhayesbc.cocktails.core.model.InventoryLevel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BottleLabelSchedulingGateTest {
    private val photoA = BottleIntakeDraft("draft", "session", 0, "0086785900001", null,
        "Unidentified bottle", "Spirit", null, null, byteArrayOf(1), "photo-A", locationId = "home", level = InventoryLevel.FULL, createdAt = 1)
    private val photoB = photoA.copy(photoData = byteArrayOf(2), photoSha256 = "photo-B")
    private fun job(attempt: String) = BottleEnrichmentJob("job", "session", "draft", BottleEnrichmentOperation.LOCAL_EXTRACTION,
        BottleEnrichmentStatus.READY_FOR_REVIEW, null, null, null, false, 1, attemptToken = attempt)

    @Test fun staleWebCompletionCannotReplaceAlreadyScheduledNewPhoto() {
        val gate = BottleLabelSchedulingGate()
        var request = "none"
        val replacements = mutableListOf<String>()
        fun enqueue(draft: BottleIntakeDraft): Boolean = gate.scheduleIfCurrent(draft, null, { photoB to null }) {
            request = draft.photoSha256!!; replacements += request; true
        }
        assertTrue(enqueue(photoB))
        assertFalse(enqueue(photoA)) // Old worker resumes after B's enqueue.
        assertEquals("photo-B", request)
        assertEquals(listOf("photo-B"), replacements)
    }

    @Test fun photoChangingDuringOldSchedulingIsReplacedByNewSchedulingInOrder() {
        val gate = BottleLabelSchedulingGate()
        val current = AtomicReference(photoA)
        val oldRead = CountDownLatch(1)
        val finishOld = CountDownLatch(1)
        val newStarted = CountDownLatch(1)
        val replacements = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failure = AtomicReference<Throwable?>(null)
        val old = thread {
            try {
                assertTrue(gate.scheduleIfCurrent(photoA, null, { current.get() to null }) {
                    oldRead.countDown()
                    check(finishOld.await(5, TimeUnit.SECONDS))
                    replacements += "A"; true
                })
            } catch (t: Throwable) { failure.set(t) }
        }
        assertTrue(oldRead.await(5, TimeUnit.SECONDS))
        current.set(photoB) // Saving a photo does not need the scheduler lock.
        val newer = thread {
            try {
                newStarted.countDown()
                assertTrue(gate.scheduleIfCurrent(photoB, null, { current.get() to null }) { replacements += "B"; true })
            } catch (t: Throwable) { failure.set(t) }
        }
        assertTrue(newStarted.await(5, TimeUnit.SECONDS))
        finishOld.countDown()
        old.join(5_000); newer.join(5_000)
        assertFalse(old.isAlive); assertFalse(newer.isAlive)
        failure.get()?.let { throw it }
        assertEquals(listOf("A", "B"), replacements)
    }

    @Test fun staleAttemptCannotReplaceCurrentReprocessEvenForSamePhoto() {
        val gate = BottleLabelSchedulingGate()
        var replacements = 0
        val current = photoA to job("new-attempt")
        assertTrue(gate.scheduleIfCurrent(photoA, "new-attempt", { current }) { replacements++; true })
        assertFalse(gate.scheduleIfCurrent(photoA, "old-attempt", { current }) { replacements++; true })
        assertEquals(1, replacements)
    }

    @Test fun removedPausedOrWrongSessionDraftCannotCreateWork() {
        val gate = BottleLabelSchedulingGate()
        fun enqueue(state: Pair<BottleIntakeDraft?, BottleEnrichmentJob?>): Boolean =
            gate.scheduleIfCurrent(photoA, "attempt", { state }) { fail("Obsolete work must not write a token or replace work"); true }
        assertFalse(enqueue(null to null))
        assertFalse(enqueue(photoA to job("attempt").copy(status = BottleEnrichmentStatus.PAUSED)))
        assertFalse(enqueue(photoA.copy(sessionId = "other") to job("attempt")))
    }
}
