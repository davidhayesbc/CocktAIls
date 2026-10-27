package com.davidhayesbc.cocktails.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arm.aichat.InferenceEngine
import com.arm.aichat.internal.InferenceEngineImpl
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBackendPackagingTest {
    @Test
    fun extractedCpuBackendIsRegisteredAndConcurrentFirstAccessUsesOneEngine() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val backendLibraries = nativeDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith("libggml-cpu-") && it.name.endsWith(".so") }
        assertTrue("No ggml CPU backend libraries were extracted", backendLibraries.isNotEmpty())

        val workers = 8
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures = List(workers) {
                executor.submit<InferenceEngine> {
                    check(start.await(10, TimeUnit.SECONDS))
                    InferenceEngineImpl.getInstance(context)
                }
            }
            start.countDown()
            val engines = futures.map { it.get(15, TimeUnit.SECONDS) }
            val firstEngine = engines.first()
            assertTrue("Concurrent first access created multiple engines", engines.all { it === firstEngine })

            val state = withTimeout(30_000) {
                firstEngine.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                }
            }
            assertTrue(
                "Native runtime did not register the CPU backend (${state.javaClass.simpleName})",
                state is InferenceEngine.State.Initialized,
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
