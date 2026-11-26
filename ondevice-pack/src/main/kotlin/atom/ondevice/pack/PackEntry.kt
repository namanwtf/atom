package atom.ondevice.pack

import android.content.Context
import atom.core.llm.LlmEngine
import atom.core.ondevice.OnDeviceBackend
import atom.core.ondevice.OnDeviceEngines
import atom.core.ondevice.WakeModelsLoad
import atom.engine.litert.LiteRtBackend
import atom.engine.litert.LiteRtEngine
import atom.engine.llamacpp.LlamaConfig
import atom.engine.llamacpp.LlamaCppEngine

/**
 * The on-device AI pack's entry point, created by name ([OnDeviceEngines.ENTRY_CLASS]) by the app's class loader
 * for this APK. Nothing here runs until the app calls it; native libraries load the first time an engine or the
 * wake-word models need them, through this APK's class loader (its library path points into this APK).
 */
class PackEntry : OnDeviceEngines {
    override val engineNames: List<String> = listOf("LiteRT-LM", "llama.cpp", "LiteRT (Hey Atom)")

    override fun liteRt(context: Any, modelPath: String, backend: OnDeviceBackend, contextWindow: Int): LlmEngine =
        LiteRtEngine(
            context = context as Context,
            modelPath = modelPath,
            backend = when (backend) {
                OnDeviceBackend.Auto -> LiteRtBackend.Auto
                OnDeviceBackend.Npu -> LiteRtBackend.Npu
                OnDeviceBackend.Gpu -> LiteRtBackend.Gpu
                OnDeviceBackend.Cpu -> LiteRtBackend.Cpu
            },
            contextWindow = contextWindow,
        )

    override fun llamaCpp(modelPath: String, contextWindow: Int, gpuLayers: Int): LlmEngine =
        LlamaCppEngine(modelPath = modelPath, config = LlamaConfig(contextWindow = contextWindow, gpuLayers = gpuLayers))

    override fun wakeModels(packPath: String): WakeModelsLoad = TfliteWakeModels.load(packPath)

    override fun probeNative(): List<String> = listOf(
        probe("atom_llama (llama.cpp)") { System.loadLibrary("atom_llama") },
        probe("litertlm_jni (LiteRT-LM)") { System.loadLibrary("litertlm_jni") },
        probe("LiteRT (wake word)") { org.tensorflow.lite.TensorFlowLite.init() },
    )

    private inline fun probe(name: String, load: () -> Unit): String = try {
        load()
        "$name: ok"
    } catch (e: Throwable) {
        "$name: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}"
    }
}
