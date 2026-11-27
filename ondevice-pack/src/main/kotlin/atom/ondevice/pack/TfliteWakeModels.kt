package atom.ondevice.pack

import atom.core.ondevice.WakeModels
import atom.core.ondevice.WakeModelsLoad
import atom.core.ondevice.WakeShapes
import atom.core.ondevice.WakeShapes.EMBEDDING_DIM
import atom.core.ondevice.WakeShapes.MEL_BINS
import atom.core.ondevice.WakeShapes.MEL_FRAMES_PER_CHUNK
import atom.core.ondevice.WakeShapes.MEL_INPUT_SAMPLES
import atom.core.ondevice.WakeShapes.MEL_WINDOW_FRAMES
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.zip.ZipFile
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * LiteRT (TFLite) implementation of [WakeModels]: CPU only, 1 thread, XNNPack on, never GPU (waking the GPU
 * for kilobyte-sized models costs more than the inference). The models are read from the pack APK's
 * `assets/wakeword/` into direct buffers (about 2.4 MB, held only while a listening session runs), and all I/O goes
 * through preallocated direct buffers.
 *
 * Shapes are validated at load time against openWakeWord's front-end (dscripka/openWakeWord,
 * `openwakeword/utils.py` AudioFeatures and `openwakeword/model.py`):
 *  - melspectrogram: input [1, N] float32 raw PCM16 values (dynamic, stored as [1, 1]), resized to [1, 1760] before
 *    its tensors are allocated; output 8 x 32 (e.g. [1,1,8,32])
 *  - embedding: input [1, 76, 32, 1] float32; output 96 values (e.g. [1,1,1,96])
 *  - classifier: input [1, F, 96] float32 (F = 16 for openWakeWord models); output 1 value
 */
internal class TfliteWakeModels private constructor(
    private val mel: Interpreter,
    private val emb: Interpreter,
    private val cls: Interpreter,
    override val classifierFrames: Int,
) : WakeModels {

    private val melIn = direct(MEL_INPUT_SAMPLES)
    private val melOut = direct(MEL_FRAMES_PER_CHUNK * MEL_BINS)
    private val embIn = direct(MEL_WINDOW_FRAMES * MEL_BINS)
    private val embOut = direct(EMBEDDING_DIM)
    private val clsIn = direct(classifierFrames * EMBEDDING_DIM)
    private val clsOut = direct(1)
    private val melInF: FloatBuffer = melIn.asFloatBuffer()
    private val melOutF: FloatBuffer = melOut.asFloatBuffer()
    private val embInF: FloatBuffer = embIn.asFloatBuffer()
    private val embOutF: FloatBuffer = embOut.asFloatBuffer()
    private val clsInF: FloatBuffer = clsIn.asFloatBuffer()
    private val clsOutF: FloatBuffer = clsOut.asFloatBuffer()

    override fun melspectrogram(audio: FloatArray, out: FloatArray) {
        run(mel, melIn, melInF, audio, melOut, melOutF)
        melOutF.get(out, 0, out.size)
    }

    override fun embedding(melWindow: FloatArray, out: FloatArray) {
        run(emb, embIn, embInF, melWindow, embOut, embOutF)
        embOutF.get(out, 0, out.size)
    }

    override fun classify(embeddings: FloatArray): Float {
        run(cls, clsIn, clsInF, embeddings, clsOut, clsOutF)
        return clsOutF.get(0)
    }

    private fun run(
        interpreter: Interpreter,
        input: ByteBuffer, inputF: FloatBuffer, data: FloatArray,
        output: ByteBuffer, outputF: FloatBuffer,
    ) {
        inputF.clear()
        inputF.put(data, 0, inputF.capacity())
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        outputF.rewind()
    }

    override fun close() {
        mel.close()
        emb.close()
        cls.close()
    }

    companion object {
        /** The input shapes the pipeline feeds, set before each model's tensors are allocated. */
        private val MELSPEC_INPUT = intArrayOf(1, MEL_INPUT_SAMPLES)
        private val EMBEDDING_INPUT = intArrayOf(1, MEL_WINDOW_FRAMES, MEL_BINS, 1)

        fun load(packPath: String): WakeModelsLoad {
            val opened = mutableListOf<Interpreter>()
            return try {
                val files = ZipFile(packPath).use { zip ->
                    listOf(WakeShapes.MELSPEC_MODEL, WakeShapes.EMBEDDING_MODEL, WakeShapes.CLASSIFIER_MODEL).map { read(zip, it) }
                }
                val mel = open(WakeShapes.MELSPEC_MODEL, files[0], MELSPEC_INPUT).also(opened::add)
                val emb = open(WakeShapes.EMBEDDING_MODEL, files[1], EMBEDDING_INPUT).also(opened::add)
                val cls = open(WakeShapes.CLASSIFIER_MODEL, files[2], null).also(opened::add)
                val frames = classifierFrames(cls)
                val error = validateMel(mel) ?: validateEmbedding(emb)
                    ?: if (frames == null) classifierError(cls) else null
                if (error != null || frames == null) {
                    fail(opened, error ?: classifierError(cls))
                } else {
                    WakeModelsLoad.Ok(TfliteWakeModels(mel, emb, cls, frames))
                }
            } catch (e: Throwable) {
                fail(opened, "Wake-word model failed to load: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        private fun fail(opened: List<Interpreter>, reason: String): WakeModelsLoad {
            opened.forEach { runCatching { it.close() } }
            return WakeModelsLoad.Failed(reason)
        }

        /** One model file of the pack, in a direct buffer as the interpreter needs it. */
        private fun read(zip: ZipFile, name: String): ByteBuffer {
            val entry = zip.getEntry("assets/${WakeShapes.ASSET_DIR}/$name")
                ?: throw IllegalStateException("$name is missing from the on-device AI pack")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }

        /**
         * Opens a model, gives its input the shape the pipeline feeds ([inputShape], null keeps the model's own),
         * and only then allocates its tensors. The melspectrogram model's input is dynamic ([-1, -1]) and is stored
         * as [1, 1]: allocated at that size, its first CONV_2D (a 512-sample STFT window) has no valid output and
         * fails to prepare ("Node number 2 (CONV_2D) failed to prepare", or "XNNPack delegate failed to reshape"
         * with XNNPack), so the input must be resized first. XNNPack is used when the phone's runtime can prepare
         * the model, else the plain CPU kernels, which are fast enough for 80 ms audio frames. A failure names the
         * model file ([name]).
         */
        private fun open(name: String, model: ByteBuffer, inputShape: IntArray?): Interpreter {
            fun create(xnnpack: Boolean): Interpreter {
                val interpreter = Interpreter(
                    model.duplicate().order(ByteOrder.nativeOrder()),
                    Interpreter.Options().setNumThreads(1).setUseXNNPACK(xnnpack),
                )
                try {
                    if (inputShape != null && !interpreter.getInputTensor(0).shape().contentEquals(inputShape)) {
                        interpreter.resizeInput(0, inputShape)
                    }
                    interpreter.allocateTensors()
                } catch (e: Exception) {
                    interpreter.close()
                    throw e
                }
                return interpreter
            }
            return try {
                create(xnnpack = true)
            } catch (e: Exception) {
                try {
                    create(xnnpack = false)
                } catch (plain: Exception) {
                    throw IllegalStateException("$name: ${plain.message ?: plain.javaClass.simpleName}", plain)
                }
            }
        }

        private fun validateMel(mel: Interpreter): String? {
            val input = mel.getInputTensor(0)
            if (input.dataType() != DataType.FLOAT32 || input.numDimensions() != 2) {
                return "melspectrogram.tflite: expected float32 [1, samples] input, got ${input.dataType()} ${input.shape().contentToString()}"
            }
            if (!input.shape().contentEquals(MELSPEC_INPUT)) {
                return "melspectrogram.tflite: input could not be set to [1, $MEL_INPUT_SAMPLES], got ${input.shape().contentToString()}"
            }
            val out = mel.getOutputTensor(0)
            val shape = out.shape()
            if (out.dataType() != DataType.FLOAT32 || shape.last() != MEL_BINS ||
                out.numElements() != MEL_FRAMES_PER_CHUNK * MEL_BINS
            ) {
                return "melspectrogram.tflite: expected $MEL_FRAMES_PER_CHUNK x $MEL_BINS float32 output for " +
                    "$MEL_INPUT_SAMPLES samples, got ${out.dataType()} ${shape.contentToString()}"
            }
            return null
        }

        private fun validateEmbedding(emb: Interpreter): String? {
            val expected = EMBEDDING_INPUT
            val input = emb.getInputTensor(0)
            if (input.dataType() != DataType.FLOAT32 || input.numDimensions() != 4) {
                return "embedding_model.tflite: expected float32 [1, 76, 32, 1] input, got ${input.dataType()} ${input.shape().contentToString()}"
            }
            val resized = input.shape()
            val out = emb.getOutputTensor(0)
            if (!resized.contentEquals(expected) || out.dataType() != DataType.FLOAT32 ||
                out.numElements() != EMBEDDING_DIM
            ) {
                return "embedding_model.tflite: expected [1, 76, 32, 1] -> $EMBEDDING_DIM float32, got " +
                    "${resized.contentToString()} -> ${out.dataType()} ${out.shape().contentToString()}"
            }
            return null
        }

        /** The classifier's embedding window (16 for openWakeWord models), or null when its shape is wrong. */
        private fun classifierFrames(cls: Interpreter): Int? {
            val input = cls.getInputTensor(0)
            val shape = input.shape()
            val out = cls.getOutputTensor(0)
            val ok = input.dataType() == DataType.FLOAT32 && shape.size == 3 && shape[0] == 1 &&
                shape[1] in 1..64 && shape[2] == EMBEDDING_DIM &&
                out.dataType() == DataType.FLOAT32 && out.numElements() == 1
            if (!ok) return null
            return shape[1]
        }

        private fun classifierError(cls: Interpreter): String {
            val input = cls.getInputTensor(0)
            val out = cls.getOutputTensor(0)
            return "${WakeShapes.CLASSIFIER_MODEL}: expected float32 [1, 16, $EMBEDDING_DIM] -> [1, 1], got " +
                "${input.dataType()} ${input.shape().contentToString()} -> ${out.dataType()} ${out.shape().contentToString()}"
        }

        private fun direct(floats: Int): ByteBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())
    }
}
