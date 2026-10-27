package com.arm.aichat.internal

import com.arm.aichat.InferenceEngine
import com.arm.aichat.ModelLoadException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceEngineInitializationTest {
    @Test
    fun nativeNullModelResultIsNotMisreportedAsUnsupportedArchitecture() {
        try {
            requireNativeModelLoadSucceeded(1)
            throw AssertionError("A failed native model load must fail")
        } catch (failure: ModelLoadException) {
            assertEquals("ModelLoadException", failure.javaClass.simpleName)
            assertFalse(failure.message.orEmpty().contains("architecture", ignoreCase = true))
        }
    }

    @Test
    fun nativeModelLoadSuccessResultIsAccepted() {
        requireNativeModelLoadSucceeded(0)
    }

    @Test
    fun ordinaryInitializationFailureIsPublishedWithoutEscapingBackgroundLaunch() = runBlocking {
        val state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
        val uncaught = AtomicReference<Throwable?>()
        val handler = CoroutineExceptionHandler { _, failure -> uncaught.set(failure) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)

        try {
            val job = scope.launch {
                initializeInferenceRuntime(state, initialize = {
                    throw IllegalStateException("private native error detail")
                })
            }
            job.join()

            assertFalse(job.isCancelled)
            val error = state.value as InferenceEngine.State.Error
            assertEquals("IllegalStateException", error.diagnosticType)
            assertNull(uncaught.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationRemainsCancellationAndLeavesAwaitableErrorState() {
        val state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
        try {
            initializeInferenceRuntime(state, initialize = { throw CancellationException("cancel init") })
            throw AssertionError("Initialization cancellation should propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("cancel init", cancelled.message)
        }

        val error = state.value as InferenceEngine.State.Error
        assertEquals("CancellationException", error.diagnosticType)
    }
}
