package app.eikon.gallery.data.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokenizer is compared with the model's reference tokenizer (Hugging Face `tokenizers`, from the
 * model's own tokenizer.json) on Italian, English and awkward inputs. A single different id would change
 * the embedding and silently worsen every search.
 */
class WordPieceTokenizerTest {
    private val vocabulary = ModelTestSupport.modelsDir.resolve("clip_text_vocab.txt").readLines().dropLastWhile { it.isEmpty() }
    private val tokenizer = WordPieceTokenizer(vocabulary)

    @Test
    fun matchesTheReferenceTokenizerOnEveryCase() {
        val cases = ModelTestSupport.resource("tokenizer-cases.tsv").lines().filter { it.isNotEmpty() }
        assertTrue(cases.size > 30)
        for (line in cases) {
            val (escaped, ids) = line.split('\t').let { it[0] to it.getOrElse(1) { "" } }
            val text = escaped.replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")
            val expected = ids.trim().split(' ').filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
            assertArrayEquals("tokens of '$text'", expected, tokenizer.encode(text))
        }
    }

    @Test
    fun theVocabularyIsTheOneTheModelWasTrainedWith() {
        assertEquals(119_547, vocabulary.size)
    }

    @Test
    fun longInputIsCutToTheModelLimitKeepingBothMarkers() {
        val ids = tokenizer.encode("parola ".repeat(500))
        assertEquals(WordPieceTokenizer.MAX_TOKENS, ids.size)
        assertEquals(101, ids.first())
        assertEquals(102, ids.last())
    }

    @Test
    fun specialTokenNamesTypedByTheUserAreJustText() {
        // The reference tokenizer would turn a typed "[SEP]" into the marker; here it stays ordinary characters,
        // so nothing a user types can act as a control token.
        val ids = tokenizer.encode("[CLS] [SEP]")
        assertEquals(1, ids.count { it == 101 })
        assertEquals(1, ids.count { it == 102 })
        assertTrue(ids.size > 4)
    }

    @Test
    fun emptyTextIsJustTheTwoMarkers() {
        assertArrayEquals(intArrayOf(101, 102), tokenizer.encode(""))
    }
}
