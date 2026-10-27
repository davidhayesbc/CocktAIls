package com.arm.aichat.internal

import android.content.Context
import com.arm.aichat.InferenceEngine
import com.arm.aichat.ModelLoadException
import com.arm.aichat.NativeModelAllocationException
import com.arm.aichat.NativeCpuBackendUnavailableException
import com.arm.aichat.UnsupportedModelArchitectureException
import com.arm.aichat.internal.InferenceEngineImpl.Companion.getInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * JNI wrapper for the llama.cpp library providing Android-friendly access to large language models.
 *
 * This class implements a singleton pattern for managing the lifecycle of a single LLM instance.
 * All operations are executed on a dedicated single-threaded dispatcher to ensure thread safety
 * with the underlying C++ native code.
 *
 * The typical usage flow is:
 * 1. Get instance via [getInstance]
 * 2. Load a model with [loadModel]
 * 3. Send prompts with [sendUserPrompt]
 * 4. Generate responses as token streams
 * 5. Perform [cleanUp] when done with a model
 * 6. Properly [destroy] when completely done
 *
 * State transitions are managed automatically and validated at each operation.
 *
 * @see ai_chat.cpp for the native implementation details
 */
internal class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        @Volatile
        private var instance: InferenceEngine? = null

        /**
         * Create or obtain [InferenceEngineImpl]'s single instance.
         *
         * @param Context for obtaining native library directory
         * @throws IllegalArgumentException if native library path is invalid
         * @throws UnsatisfiedLinkError if library failed to load
         */
        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                InferenceEngineImpl(nativeLibDir).also { instance = it }
            }
    }

    /**
     * JNI methods
     * @see ai_chat.cpp
     */
    private external fun init(nativeLibDir: String): Boolean

    private external fun load(modelPath: String): Int

    private external fun prepare(): Int

    private external fun systemInfo(): String

    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    private external fun processSystemPrompt(systemPrompt: String): Int

    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    private external fun generateNextToken(): String?

    private external fun unload()

    private external fun shutdown()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var _readyForSystemPrompt = false
    @Volatile
    private var _nativeInitialized = false
    @Volatile
    private var _cancelGeneration = false

    /**
     * Single-threaded coroutine dispatcher & scope for LLama asynchronous operations
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            initializeInferenceRuntime(
                state = _state,
                initialize = {
                    System.loadLibrary("ai-chat")
                    requireCpuBackendRegistered(init(nativeLibDir))
                },
                onInitialized = { _nativeInitialized = true },
            )
        }
    }

    /**
     * Load the LLM
     */
    override suspend fun loadModel(pathToModel: String) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            try {
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }

                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                requireNativeModelLoadSucceeded(load(pathToModel))
                prepare().let {
                    if (it != 0) throw IOException("Failed to prepare resources")
                }
                _readyForSystemPrompt = true

                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (failure: Exception) {
                _state.value = InferenceEngine.State.Error(
                    exception = failure,
                    diagnosticType = failure.javaClass.simpleName,
                    diagnosticFrames = failure.stackTrace.toList(),
                )
                throw failure
            }
        }

    /**
     * Process the plain text system prompt
     *
     * TODO-han.yin: return error code if system prompt not correct processed?
     */
    override suspend fun setSystemPrompt(systemPrompt: String) =
        withContext(llamaDispatcher) {
            require(systemPrompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            processSystemPrompt(systemPrompt).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            _state.value = InferenceEngine.State.ModelReady
        }

    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            processUserPrompt(message, predictLength).let { result ->
                if (result != 0) {
                    return@flow
                }
            }

            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                currentCoroutineContext().ensureActive()
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            if (_cancelGeneration) {
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (failure: Exception) {
            _state.value = InferenceEngine.State.Error(failure)
            throw failure
        }
    }.flowOn(llamaDispatcher)

    /**
     * Benchmark the model
     */
    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Benchmark request discarded due to: $state"
            }
            _readyForSystemPrompt = false   // Just to be safe
            _state.value = InferenceEngine.State.Benchmarking
            benchModel(pp, tg, pl, nr).also {
                _state.value = InferenceEngine.State.ModelReady
            }
        }

    /**
     * Unloads the model and frees resources, or reset error states
     */
    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel

                    unload()

                    _state.value = InferenceEngine.State.Initialized
                    Unit
                }

                is InferenceEngine.State.Error -> {
                    check(_nativeInitialized) { "Native local model runtime did not initialize" }
                    unload()
                    _state.value = InferenceEngine.State.Initialized
                    Unit
                }

                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    /**
     * Cancel all ongoing coroutines and free GGML backends
     */
    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when(_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}

/** JNI reports success, explicit allowlisted loader categories, or generic failure. */
internal fun requireNativeModelLoadSucceeded(result: Int) {
    when (result) {
        NATIVE_MODEL_LOAD_OK -> Unit
        NATIVE_MODEL_ARCHITECTURE_UNSUPPORTED -> throw UnsupportedModelArchitectureException()
        NATIVE_MODEL_ALLOCATION_FAILED -> throw NativeModelAllocationException()
        else -> throw ModelLoadException()
    }
}

private const val NATIVE_MODEL_LOAD_OK = 0
private const val NATIVE_MODEL_ARCHITECTURE_UNSUPPORTED = 2
private const val NATIVE_MODEL_ALLOCATION_FAILED = 3

/** Backend discovery is separate from loading the llama runtime library itself. */
internal fun requireCpuBackendRegistered(registered: Boolean) {
    if (!registered) throw NativeCpuBackendUnavailableException()
}

/** Publishes ordinary initialization failures to the awaited state instead of leaking them from launch. */
internal fun initializeInferenceRuntime(
    state: MutableStateFlow<InferenceEngine.State>,
    initialize: () -> Unit,
    onInitialized: () -> Unit = {},
) {
    try {
        check(state.value is InferenceEngine.State.Uninitialized) {
            "Cannot load native library in ${state.value.javaClass.simpleName}!"
        }
        state.value = InferenceEngine.State.Initializing
        initialize()
        onInitialized()
        state.value = InferenceEngine.State.Initialized
    } catch (cancelled: CancellationException) {
        state.value = InferenceEngine.State.Error(
            exception = IllegalStateException("Native local model runtime initialization was cancelled"),
            diagnosticType = "CancellationException",
            diagnosticFrames = cancelled.stackTrace.toList(),
        )
        throw cancelled
    } catch (failure: Exception) {
        state.value = InferenceEngine.State.Error(
            exception = failure,
            diagnosticType = failure.javaClass.simpleName,
            diagnosticFrames = failure.stackTrace.toList(),
        )
    } catch (failure: LinkageError) {
        state.value = InferenceEngine.State.Error(
            exception = IllegalStateException("Native local model runtime is unavailable"),
            diagnosticType = failure.javaClass.simpleName,
            diagnosticFrames = failure.stackTrace.toList(),
        )
    }
}
