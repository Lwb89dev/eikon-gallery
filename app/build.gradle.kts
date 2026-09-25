import com.android.build.api.artifact.SingleArtifact
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Downloads the machine-learning models listed in `model-manifest.tsv` into `models/assets/` (ignored by
 * git) and refuses any file whose SHA-256 differs from the manifest. Files already present and correct
 * are kept, so this only touches the network the first time. The directory is registered below as a
 * generated assets source, so the models end up in the APK.
 */
abstract class FetchModelsTask : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val root = outputDir.get().asFile
        manifest.get().asFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .forEach { (path, url, sha256) -> ensure(root.resolve(path), url, sha256) }
    }

    private fun ensure(target: File, url: String, sha256: String) {
        if (target.isFile && digest(target) == sha256) return
        target.parentFile.mkdirs()
        val partial = File(target.path + ".part")
        logger.lifecycle("Downloading ${target.name} ...")
        val request = HttpRequest.newBuilder(URI(url)).GET().build()
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "Download of $url failed: HTTP ${response.statusCode()}" }
        response.body().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val actual = digest(partial)
        check(actual == sha256) {
            partial.delete()
            "Checksum mismatch for $url: expected $sha256 but got $actual"
        }
        partial.renameTo(target)
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Fails the build if a merged manifest asks for the INTERNET permission (see docs/PRIVACY.md). */
abstract class VerifyNoInternetTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        check("android.permission.INTERNET" !in text) {
            "The merged manifest requests INTERNET. eikon must not have network access; find the library that " +
                "adds it (./gradlew :app:dependencies, or the merged manifest report) and remove or replace it."
        }
    }
}

val fetchModels = tasks.register<FetchModelsTask>("fetchModels") {
    group = "eikon"
    description = "Downloads and verifies the bundled machine-learning models."
    manifest.set(layout.projectDirectory.file("model-manifest.tsv"))
    outputDir.set(layout.projectDirectory.dir("models/assets"))
}

android {
    namespace = "app.eikon.gallery"
    // Recent AndroidX releases require compiling against API 37; targetSdk (runtime behaviour)
    // stays one level behind until the API 37 behaviour changes have been reviewed.
    compileSdk = 37

    defaultConfig {
        applicationId = "app.eikon.gallery"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("en", "it")
        // Models are read straight out of the APK (memory-mapped), which needs them stored uncompressed.
        noCompress += "onnx"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // The OCR engine and the model runtime ship native code: only the ABIs of a real phone and of the
            // emulator (debug only), to keep the APK small.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // Real phones only: the x86_64 build exists for the emulator (debug) and would add about 45 MB.
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Local device testing only: `-Peikon.signReleaseWithDebugKey` signs the release build with
            // the debug key so `installRelease` works. Never set it for a build that is distributed.
            if (providers.gradleProperty("eikon.signReleaseWithDebugKey").isPresent) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        // Exported Room schemas let MigrationTest rebuild the old database versions.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        // The public-domain sample photos of the JVM tests are reused by the on-device model test.
        getByName("androidTest").assets.directories.add("$projectDir/src/test/resources")
    }

    testOptions {
        // Framework classes (Log, Uri...) return defaults in plain JVM tests instead of throwing.
        unitTests.isReturnDefaultValues = true
        // Tests that run the real models look here; they skip themselves when the models are not fetched yet.
        unitTests.all { it.systemProperty("eikon.models", "$projectDir/models/assets/models") }
    }
}

// Unit tests run the real models, so make sure they are there.
tasks.matching { it.name.endsWith("UnitTest") && it.name.startsWith("test") }.configureEach { dependsOn(fetchModels) }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchModels, FetchModelsTask::outputDir)

        val verify = tasks.register<VerifyNoInternetTask>("verifyNoInternet${variant.name.replaceFirstChar { it.uppercase() }}") {
            group = "verification"
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.matching { it.name == "assemble${variant.name.replaceFirstChar { c -> c.uppercase() }}" }
            .configureEach { dependsOn(verify) }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(verify) }
    }
}

ksp {
    // Exported Room schemas are committed so migrations can be reviewed and tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.tesseract4android)
    // Runs the image and text embedding models. Stay on 1.28.x: from 1.29 the Android artifact adds
    // telemetry code and the INTERNET permission (checked when choosing it; see docs/ML.md).
    implementation(libs.onnxruntime.android)
    // Declared explicitly (the code uses coroutines everywhere) so the app and its tests share one version.
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)

    implementation(libs.coil.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Runs the real library SQL against a real SQLite in JVM tests (test scope only, never shipped).
    testImplementation(libs.sqlite.jdbc)
    // The same ONNX Runtime Java API for the desktop JVM, so tests can run the real models (test scope only).
    testImplementation(libs.onnxruntime.jvm)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
