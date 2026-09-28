# Third-party notices

eikon's own source code is released under the [MIT License](LICENSE). The components and data below
are not covered by that license; each keeps its own.

## Data bundled in the app

| What | Where | License |
| --- | --- | --- |
| Place names, coordinates and populations of cities with more than 15,000 inhabitants, and region names | `app/src/main/assets/places/` (a reduced copy of GeoNames `cities15000` and `admin1CodesASCII`) | [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/). Source: [GeoNames](https://www.geonames.org), which requires attribution. The data is used offline to turn a photo's coordinates into a place name; no request is ever made to GeoNames. |
| CLIP ViT-B/32 image model, int8 ONNX export (`clip_vision_int8.onnx`) | downloaded at build time into the APK's `assets/models/` (see `app/model-manifest.tsv`) | [MIT License](https://github.com/openai/CLIP/blob/main/LICENSE), Copyright (c) 2021 OpenAI. ONNX conversion and quantization by [Xenova](https://huggingface.co/Xenova/clip-vit-base-patch32). Used offline to describe what a photo shows. |
| Multilingual CLIP text model, int8 ONNX (`clip_text_int8.onnx`, vocabulary and projection) | same | [Apache License 2.0](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1), sentence-transformers / UKP Lab. Used offline to turn a search phrase into a vector. |
| YuNet face detector (`yunet_face_detector.onnx`, from `face_detection_yunet_2026may.onnx`) | downloaded at build time into the APK's `assets/models/` | [MIT License](https://github.com/opencv/opencv_zoo/blob/main/models/face_detection_yunet/LICENSE), Copyright (c) 2020 Shiqi Yu, distributed by [OpenCV Zoo](https://github.com/opencv/opencv_zoo). Used offline to find faces. |
| SFace face model (`sface_recognizer.onnx`, from `face_recognition_sface_2021dec.onnx`) | same | [Apache License 2.0](https://github.com/opencv/opencv_zoo/blob/main/models/face_recognition_sface/LICENSE), from the [SFace](https://github.com/zhongyy/SFace) authors via OpenCV Zoo. Used offline to group faces that look alike. |
| Country outlines for the Places map (`places/world.bin`) | `app/src/main/assets/places/world.bin`, built by `tools/build_worldmap.py` from Natural Earth 1:50m admin-0 countries | Public domain ([Natural Earth](https://www.naturalearthdata.com/about/terms-of-use/)); no attribution is required, but it is given here. Only outlines: no map service is ever contacted. |
| Tesseract OCR trained data `eng.traineddata`, `ita.traineddata` (`tessdata_fast`) | `app/src/main/assets/tessdata/` | [Apache License 2.0](https://github.com/tesseract-ocr/tessdata_fast/blob/main/LICENSE). Used offline by the text-in-photos analysis. |

## Libraries

All runtime libraries are Apache License 2.0 unless noted:

- AndroidX: Core, Activity, Lifecycle, Navigation, Compose (UI, Foundation, Material 3), Room, Paging,
  DataStore, ExifInterface, Biometric, Core SplashScreen, WorkManager, Hilt integration, Media3
- Coil, and Google's [Accompanist](https://github.com/google/accompanist) (`accompanist-drawablepainter`, a Compose helper it pulls in)
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) 1.28.0 (MIT License), Copyright (c) Microsoft Corporation. Runs the models above.
- Dagger / Hilt, and what they pull in: [Guava](https://github.com/google/guava) (`guava`, `failureaccess`, `listenablefuture`), JSR-305 (`com.google.code.findbugs:jsr305`, a BSD-style license), and the `javax.inject`/`jakarta.inject` dependency-injection annotations. None of this is Google Play Services, Firebase or any other proprietary Google API: eikon depends on no closed API from Google or anyone else, checked by reading the full dependency tree (`./gradlew :app:dependencies`) and the shipped code for a Play-Services-style class or string. `com.google.dagger` and `com.google.guava` are Maven *group ids*, not a sign of anything closed: both are Apache-2.0 open-source projects Google publishes on GitHub.
- [SQLCipher for Android](https://www.zetetic.net/sqlcipher/) 4.19.0, Community Edition (BSD-style license, full text below), Copyright (c) 2025 ZETETIC LLC. Encrypts the library's database on the phone. Its native library contains SQLite 3.53 (public domain) and LibTomCrypt (public domain).
- [OkHttp](https://square.github.io/okhttp/) 5.5.0 and Okio (Apache License 2.0), Copyright Square, Inc. The HTTP client of the backup to your own server (used only after you allow it; in 1.0.0 it was only in the separate `backup` build).
- kotlinx.coroutines and kotlinx.serialization (JetBrains, pulled in by Media3); JetBrains' own Compose Multiplatform artifacts (`org.jetbrains.compose.*`, `org.jetbrains.androidx.lifecycle`, `org.jetbrains.androidx.savedstate`) that some AndroidX libraries build on; the `org.jetbrains:annotations` and `org.jspecify:jspecify` nullness annotations. All Apache License 2.0.
- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) 4.9.0 (Apache License 2.0), which bundles
  native builds of Tesseract 5.5.1 (Apache License 2.0), Leptonica 1.85.0 (BSD-style), libjpeg v9f (IJG
  license) and libpng 1.6.48 (libpng license). Each keeps its own license and notices.

Test-only, never shipped: JUnit (Eclipse Public License 1.0), sqlite-jdbc (Apache License 2.0),
AndroidX Test (Apache License 2.0), OkHttp MockWebServer and okhttp-tls (Apache License 2.0; the fake server and generated certificates of the backup's tests).

Exact versions are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml).

## SQLCipher license

This is the license of SQLCipher Community Edition, reproduced as it requires:

    Copyright (c) 2025, ZETETIC LLC
    All rights reserved.

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions are met:
        * Redistributions of source code must retain the above copyright
          notice, this list of conditions and the following disclaimer.
        * Redistributions in binary form must reproduce the above copyright
          notice, this list of conditions and the following disclaimer in the
          documentation and/or other materials provided with the distribution.
        * Neither the name of the ZETETIC LLC nor the
          names of its contributors may be used to endorse or promote products
          derived from this software without specific prior written permission.

    THIS SOFTWARE IS PROVIDED BY ZETETIC LLC ''AS IS'' AND ANY
    EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
    WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
    DISCLAIMED. IN NO EVENT SHALL ZETETIC LLC BE LIABLE FOR ANY
    DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
    (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
    LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
    ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
    (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Not yet included

Nothing else is bundled. New models will be listed here, with their licenses, before they ship (see
[docs/ML.md](docs/ML.md)).
