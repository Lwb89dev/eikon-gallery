package app.eikon.gallery.data.embedding

import java.nio.ByteBuffer

/** Turns a photo into a vector of the shared image/text space. Not thread-safe; call from one thread at a time. */
interface ImageEmbedder : AutoCloseable {
    /** A unit vector of [Embeddings.DIMENSIONS] values for an image already cropped to [ClipImagePreprocessor.SIZE] squared. */
    fun embed(image: RgbImage): FloatArray
}

/** Turns a search phrase into a vector of the same space, so it can be compared with photos. */
interface TextEmbedder : AutoCloseable {
    fun embed(text: String): FloatArray
}

/**
 * CLIP ViT-B/32 image tower (OpenAI, MIT), int8-quantized. Input `pixel_values` 1x3x224x224, output the
 * 512-dimensional `image_embeds`, which is normalised here.
 */
class ClipImageEncoder(model: ByteBuffer, threads: Int = DEFAULT_THREADS) : ImageEmbedder {
    private val onnx = OnnxModel(model, threads)

    override fun embed(image: RgbImage): FloatArray {
        val pixels = ClipImagePreprocessor.toTensor(image)
        val size = ClipImagePreprocessor.SIZE.toLong()
        onnx.floatTensor(pixels, 1, 3, size, size).use { input ->
            return Embeddings.normalized(onnx.floats(mapOf("pixel_values" to input), "image_embeds"))
        }
    }

    override fun close() = onnx.close()

    private companion object {
        /** Background analysis is meant to be gentle: two threads keep the phone cool at the cost of speed. */
        const val DEFAULT_THREADS = 2
    }
}

/**
 * Multilingual text tower (sentence-transformers `clip-ViT-B-32-multilingual-v1`, Apache-2.0), int8-quantized:
 * a DistilBERT that was trained to place sentences of 50+ languages where CLIP places the matching photos.
 */
class ClipTextEncoder(
    model: ByteBuffer,
    private val tokenizer: WordPieceTokenizer,
    private val projection: TextProjection,
    threads: Int = DEFAULT_THREADS,
) : TextEmbedder {
    private val onnx = OnnxModel(model, threads)

    override fun embed(text: String): FloatArray {
        val tokens = tokenizer.encode(text)
        val shape = longArrayOf(1, tokens.size.toLong())
        val ids = LongArray(tokens.size) { tokens[it].toLong() }
        val mask = LongArray(tokens.size) { 1L }
        onnx.longTensor(ids, *shape).use { idTensor ->
            onnx.longTensor(mask, *shape).use { maskTensor ->
                val hidden = onnx.floats(mapOf("input_ids" to idTensor, "attention_mask" to maskTensor), "last_hidden_state")
                return projection.project(hidden, tokens.size)
            }
        }
    }

    override fun close() = onnx.close()

    companion object {
        private const val DEFAULT_THREADS = 4

        fun create(store: ModelStore): ClipTextEncoder {
            val vocabulary = store.read(VOCABULARY).toString(Charsets.UTF_8).lines().dropLastWhile { it.isEmpty() }
            val projection = TextProjection.fromSafetensors(store.read(PROJECTION))
            return ClipTextEncoder(store.map(MODEL), WordPieceTokenizer(vocabulary), projection)
        }

        const val MODEL = "clip_text_int8.onnx"
        const val VOCABULARY = "clip_text_vocab.txt"
        const val PROJECTION = "clip_text_projection.safetensors"
    }
}

/** Where the image encoder's model lives in the store. */
const val IMAGE_MODEL = "clip_vision_int8.onnx"
