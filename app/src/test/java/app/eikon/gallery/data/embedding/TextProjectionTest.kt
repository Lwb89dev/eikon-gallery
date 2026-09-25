package app.eikon.gallery.data.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextProjectionTest {
    @Test
    fun poolsTokensThenProjectsAndNormalises() {
        // A projection that copies the first 512 inputs. Two tokens: (1,0,...) and (3,0,...) average to (2,0,...).
        val weights = FloatArray(TextProjection.OUTPUT * TextProjection.INPUT)
        for (row in 0 until TextProjection.OUTPUT) weights[row * TextProjection.INPUT + row] = 1f
        val hidden = FloatArray(2 * TextProjection.INPUT)
        hidden[0] = 1f
        hidden[TextProjection.INPUT] = 3f
        hidden[1] = 1f
        hidden[TextProjection.INPUT + 1] = 1f

        val out = TextProjection(weights).project(hidden, 2)

        // Pooled (2, 1, 0...) has length sqrt(5).
        assertEquals(2f / Math.sqrt(5.0).toFloat(), out[0], 1e-6f)
        assertEquals(1f / Math.sqrt(5.0).toFloat(), out[1], 1e-6f)
    }

    @Test
    fun rejectsWeightsOfTheWrongSize() {
        assertThrows(IllegalArgumentException::class.java) { TextProjection(FloatArray(10)) }
    }

    @Test
    fun readsTheShippedSafetensorsFileAndRejectsGarbage() {
        val bytes = ModelTestSupport.modelsDir.resolve("clip_text_projection.safetensors").readBytes()
        TextProjection.fromSafetensors(bytes)
        assertThrows(IllegalArgumentException::class.java) { TextProjection.fromSafetensors(bytes.copyOf(bytes.size - 4)) }
    }
}
