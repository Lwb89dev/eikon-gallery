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

/**
 * The promise about the network, checked on every build: the only thing that goes online is the optional backup to a server of the user's own, so the merged manifest must ask
 * for INTERNET (a build that silently lost it would fail at the first upload), must forbid unencrypted traffic, and must not have gained any other permission that touches the
 * network or the phone's identity than the ones listed here (see docs/PRIVACY.md and docs/BACKUP.md). A library that starts adding one fails the build.
 */
abstract class VerifyNetworkPermissionsTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        check("android.permission.INTERNET" in text) { "The merged manifest does not request INTERNET, which the backup to the user's own server needs." }
        check("android:usesCleartextTraffic=\"false\"" in text) { "The app must forbid unencrypted traffic (android:usesCleartextTraffic=\"false\")." }
        val allowed = setOf(
            "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.FOREGROUND_SERVICE", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.ACCESS_MEDIA_LOCATION",
            "android.permission.USE_BIOMETRIC", "android.permission.USE_FINGERPRINT",
        )
        val requested = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
        val unexpected = requested.filterNot { it in allowed || it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
        check(unexpected.isEmpty()) { "The merged manifest requests permissions nobody reviewed: $unexpected. Add them to docs/PRIVACY.md and this task, or remove what brings them." }
    }
}

/**
 * Puts NOTICE.md into the APK as an asset, so the licenses of everything bundled can be read inside the app (Settings, Open-source licenses), not only in the source. Their terms ask
 * for the notices to travel with the app; one file is the source of truth for both.
 */
abstract class BundleNoticeTask : DefaultTask() {
    @get:InputFile
    abstract val notice: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val target = outputDir.get().asFile.apply { mkdirs() }
        notice.get().asFile.copyTo(File(target, "NOTICE.md"), overwrite = true)
    }
}

val fetchModels = tasks.register<FetchModelsTask>("fetchModels") {
    group = "eikon"
    description = "Downloads and verifies the bundled machine-learning models."
    manifest.set(layout.projectDirectory.file("model-manifest.tsv"))
    outputDir.set(layout.projectDirectory.dir("models/assets"))
}

val bundleNotice = tasks.register<BundleNoticeTask>("bundleNotice") {
    group = "eikon"
    description = "Adds NOTICE.md to the APK's assets."
    notice.set(rootProject.layout.projectDirectory.file("NOTICE.md"))
    outputDir.set(layout.buildDirectory.dir("generated/notice"))
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
        // 1.1.0 is 10100 (major * 10000 + minor * 100 + patch), so later releases always sort higher.
        versionCode = 10100
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // The languages the app is translated into (see docs/TRANSLATING.md): the 24 official languages of the EU, Simplified Chinese, Russian and Japanese.
        localeFilters += listOf(
            "en", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "ga", "hr", "hu", "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro", "sk", "sl", "sv",
            "zh-rCN", "ru", "ja",
        )
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
        variant.sources.assets?.addGeneratedSourceDirectory(bundleNotice, BundleNoticeTask::outputDir)

        val verify = tasks.register<VerifyNetworkPermissionsTask>("verifyNetworkPermissions${variant.name.replaceFirstChar { it.uppercase() }}") {
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
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)

    implementation(libs.coil.compose)

    // The HTTP client of the backup to a server of the user's own: the only network code in the app, and inert until the user allows the network and sets a server up.
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)

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
