package app.eikon.gallery.data.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The last step of the multilingual text model, which the ONNX file does not contain: average the
 * per-token vectors of the sentence (mean pooling) and map the 768 numbers to the 512 of the shared
 * image/text space with the model's one linear layer (no bias, no activation).
 */
class TextProjection(private val weights: FloatArray) {
    init {
        require(weights.size == OUTPUT * INPUT) { "projection has ${weights.size} weights, expected ${OUTPUT * INPUT}" }
    }

    /** A unit vector for [tokenCount] tokens whose 768-dimensional outputs are laid out one after the other in [hidden]. */
    fun project(hidden: FloatArray, tokenCount: Int): FloatArray {
        require(tokenCount > 0 && hidden.size == tokenCount * INPUT) { "unexpected hidden state size" }
        val pooled = FloatArray(INPUT)
        for (token in 0 until tokenCount) for (i in 0 until INPUT) pooled[i] += hidden[token * INPUT + i]
        for (i in 0 until INPUT) pooled[i] /= tokenCount
        val out = FloatArray(OUTPUT) { row -> dot(row, pooled) }
        return Embeddings.normalized(out)
    }

    private fun dot(row: Int, pooled: FloatArray): Float {
        var sum = 0f
        for (i in 0 until INPUT) sum += weights[row * INPUT + i] * pooled[i]
        return sum
    }

    companion object {
        const val INPUT = 768
        const val OUTPUT = Embeddings.DIMENSIONS

        /**
         * Reads the layer from the `safetensors` file shipped with the model: 8 bytes with the size of a JSON
         * header, the header, then the raw little-endian floats. The file holds this one tensor; its size and
         * declared type and shape are checked so a wrong file fails loudly instead of giving odd results.
         */
        fun fromSafetensors(bytes: ByteArray): TextProjection {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val headerSize = buffer.getLong(0).toInt()
            val header = String(bytes, Long.SIZE_BYTES, headerSize, Charsets.UTF_8)
            require("\"F32\"" in header && "[$OUTPUT,$INPUT]" in header) { "unexpected projection file: $header" }
            val start = Long.SIZE_BYTES + headerSize
            require(bytes.size - start == OUTPUT * INPUT * Float.SIZE_BYTES) { "projection file has the wrong size" }
            val floats = FloatArray(OUTPUT * INPUT)
            buffer.position(start)
            buffer.asFloatBuffer().get(floats)
            return TextProjection(floats)
        }
    }
}
