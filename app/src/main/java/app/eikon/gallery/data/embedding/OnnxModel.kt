package app.eikon.gallery.data.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** One ONNX Runtime session over a memory-mapped model file. Not thread-safe by itself; callers serialise use. */
class OnnxModel(model: ByteBuffer, threads: Int) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()

    // The mapped buffer is kept referenced for as long as the session lives.
    private val buffer = model
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session = environment.createSession(buffer, options)

    /** Runs the model and returns every output by name, each flattened. */
    fun run(inputs: Map<String, OnnxTensor>): Map<String, FloatArray> =
        session.run(inputs).use { result ->
            result.associate { (name, value) ->
                val floats = (value as OnnxTensor).floatBuffer
                name to FloatArray(floats.remaining()).also { floats.get(it) }
            }
        }

    fun floats(inputs: Map<String, OnnxTensor>, output: String): FloatArray = run(inputs).getValue(output)

    fun floatTensor(data: FloatArray, vararg shape: Long): OnnxTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(data), shape)

    fun longTensor(data: LongArray, vararg shape: Long): OnnxTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(data), shape)

    override fun close() {
        session.close()
        options.close()
    }
}
