package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface defining the core LLM inference operations.
 */
interface InferenceEngine {
    /**
     * Current state of the inference engine
     */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @throws ModelLoadException if the native model loader cannot load the file.
     */
    suspend fun loadModel(pathToModel: String)

    /**
     * Sends a system prompt to the loaded model
     */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Sends a user prompt to the loaded model and returns a Flow of generated tokens.
     */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /** Requests interruption of an in-flight native prompt/generation operation when supported. */
    fun requestGenerationCancellation() = Unit

    /**
     * Runs a benchmark with the specified parameters.
     */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Unloads the currently loaded model.
     */
    fun cleanUp()

    /**
     * Cleans up resources when the engine is no longer needed.
     */
    fun destroy()

    /**
     * States of the inference engine
     */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(
            val exception: Exception,
            /** Original native/runtime type and first stack frames, without the exception message. */
            val diagnosticType: String = exception.javaClass.simpleName,
            val diagnosticFrames: List<StackTraceElement> = exception.stackTrace.toList(),
        ) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val State.isUninterruptible
    get() = this is State.Initializing ||
        this is State.LoadingModel ||
        this is State.UnloadingModel ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() = this is State.ModelReady ||
        this is State.Benchmarking ||
        this is State.ProcessingSystemPrompt ||
        this is State.ProcessingUserPrompt ||
        this is State.Generating

/** A native load failure whose underlying cause is not exposed by the JNI loader. */
class ModelLoadException : Exception("The native model loader could not load the selected model.")

/** The native runtime initialized but could not register its required CPU backend. */
class NativeCpuBackendUnavailableException : Exception("The native CPU backend could not be loaded.")

/** The native loader explicitly identified the GGUF architecture as unsupported. */
class UnsupportedModelArchitectureException : Exception("The selected GGUF architecture is unsupported by this runtime.")

/** The native loader reported a resource allocation failure while loading the model. */
class NativeModelAllocationException : Exception("The native model loader could not allocate required resources.")
