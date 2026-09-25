package app.eikon.gallery.data.embedding

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue

/** Test helpers: the fetched model files, and the reference outputs produced by the models' own tooling. */
object ModelTestSupport {
    /** Where `./gradlew :app:fetchModels` put the models; the unit-test task runs it first. */
    val modelsDir: File
        get() = File(System.getProperty("eikon.models") ?: error("eikon.models is not set")).also {
            assertTrue("models are missing: run ./gradlew :app:fetchModels", File(it, "clip_vision_int8.onnx").isFile)
        }

    fun store(): ModelStore = DirectoryModelStore(modelsDir)

    fun resource(name: String): String =
        checkNotNull(ModelTestSupport::class.java.getResourceAsStream("/embedding/$name")) { "missing test resource $name" }
            .bufferedReader().use { it.readText() }

    fun floats(text: String): FloatArray = text.trim().split(' ').map { it.toFloat() }.toFloatArray()

    fun cosine(a: FloatArray, b: FloatArray): Float = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()

    /** A lossless 224 x 224 test image as ARGB pixels. */
    fun png(name: String): RgbImage {
        val image = ImageIO.read(ModelTestSupport::class.java.getResourceAsStream("/embedding/$name.png"))
        return RgbImage(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    }

    /** A test photo at its own size, as ARGB pixels. */
    fun fullPhoto(name: String): RgbImage {
        val image = ImageIO.read(ModelTestSupport::class.java.getResourceAsStream("/embedding/photos/$name.jpg"))
        return RgbImage(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    }

    /** A test photo prepared like the app prepares one: shorter side 224, central square, as ARGB pixels. */
    fun photo(name: String): RgbImage {
        val source = ImageIO.read(ModelTestSupport::class.java.getResourceAsStream("/embedding/photos/$name.jpg"))
        val (width, height) = ClipImagePreprocessor.decodeSize(source.width, source.height)
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()
        val pixels = scaled.getRGB(0, 0, width, height, null, 0, width)
        return RgbImage(width, height, pixels).centerCrop(ClipImagePreprocessor.SIZE)
    }
}
