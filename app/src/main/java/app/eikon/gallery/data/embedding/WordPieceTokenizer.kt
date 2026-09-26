package app.eikon.gallery.data.embedding

/**
 * The tokenizer of the multilingual text model (BERT "cased" WordPiece), written out so that it runs
 * without a tokenizer library. It reproduces what the model's own `tokenizer.json` does: control
 * characters dropped, whitespace and CJK characters spaced out, words split from punctuation, then each
 * word cut greedily into the longest pieces the vocabulary knows. It does not lowercase or strip accents,
 * because this model was trained without doing either. A test compares its output with the reference
 * tokenizer's for a list of Italian, English and unusual inputs.
 */
class WordPieceTokenizer(vocabulary: List<String>, private val maxLength: Int = MAX_TOKENS) {
    private val ids: Map<String, Int> = vocabulary.withIndex().associate { (i, token) -> token to i }
    private val unknown = id(UNKNOWN)
    private val start = id(CLASSIFIER)
    private val end = id(SEPARATOR)

    /** `[CLS] pieces... [SEP]`, cut to [maxLength] tokens. */
    fun encode(text: String): IntArray {
        val pieces = ArrayList<Int>()
        for (word in words(normalize(text))) {
            pieces += wordPieces(word)
            if (pieces.size >= maxLength - SPECIAL_TOKENS) break
        }
        val kept = pieces.take(maxLength - SPECIAL_TOKENS)
        return (listOf(start) + kept + listOf(end)).toIntArray()
    }

    private fun id(token: String): Int = ids[token] ?: error("vocabulary has no $token")

    // --- Normalization: control characters, whitespace and CJK ---------------------------------

    private fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        text.codePoints().forEach { cp ->
            when {
                cp == 0 || cp == REPLACEMENT || isControl(cp) -> Unit
                isSpace(cp) -> out.append(' ')
                isCjk(cp) -> out.append(' ').appendCodePoint(cp).append(' ')
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true
            else -> false
        }
    }

    private fun isSpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    private fun isCjk(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF || cp in 0x2A700..0x2B73F ||
            cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    // --- Pre-tokenization: split on spaces, isolate punctuation --------------------------------

    private fun words(text: String): List<IntArray> {
        val splitter = WordSplitter()
        text.codePoints().forEach(splitter::add)
        return splitter.finish()
    }

    /** Collects code points into words: a space ends one, and a punctuation mark is a word of its own. */
    private inner class WordSplitter {
        private val words = ArrayList<IntArray>()
        private val current = ArrayList<Int>()

        fun add(cp: Int) {
            when {
                cp == ' '.code -> flush()
                isPunctuation(cp) -> {
                    flush()
                    words += intArrayOf(cp)
                }
                else -> current += cp
            }
        }

        fun finish(): List<IntArray> {
            flush()
            return words
        }

        private fun flush() {
            if (current.isNotEmpty()) words += current.toIntArray()
            current.clear()
        }
    }

    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    // --- WordPiece: longest match first ---------------------------------------------------------

    private fun wordPieces(word: IntArray): List<Int> {
        if (word.size > MAX_WORD_CHARS) return listOf(unknown)
        val pieces = ArrayList<Int>()
        var from = 0
        while (from < word.size) {
            val (id, next) = longestPiece(word, from) ?: return listOf(unknown)
            pieces += id
            from = next
        }
        return pieces
    }

    /** The longest vocabulary entry that starts at [from] (with `##` unless it starts the word), and where it ends. */
    private fun longestPiece(word: IntArray, from: Int): Pair<Int, Int>? {
        for (to in word.size downTo from + 1) {
            val piece = String(word, from, to - from)
            val id = ids[if (from == 0) piece else CONTINUATION + piece]
            if (id != null) return id to to
        }
        return null
    }

    companion object {
        const val MAX_TOKENS = 128
        private const val SPECIAL_TOKENS = 2
        private const val MAX_WORD_CHARS = 100
        private const val REPLACEMENT = 0xFFFD
        private const val CONTINUATION = "##"
        private const val UNKNOWN = "[UNK]"
        private const val CLASSIFIER = "[CLS]"
        private const val SEPARATOR = "[SEP]"
    }
}
