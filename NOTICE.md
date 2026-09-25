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
- Coil
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) 1.28.0 (MIT License), Copyright (c) Microsoft Corporation. Runs the models above.
- Dagger / Hilt
- kotlinx.coroutines
- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) 4.9.0 (Apache License 2.0), which bundles
  native builds of Tesseract 5.5.1 (Apache License 2.0), Leptonica 1.85.0 (BSD-style), libjpeg v9f (IJG
  license) and libpng 1.6.48 (libpng license). Each keeps its own license and notices.

Test-only, never shipped: JUnit (Eclipse Public License 1.0), sqlite-jdbc (Apache License 2.0),
AndroidX Test (Apache License 2.0).

Exact versions are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml).

## Not yet included

Nothing else is bundled. New models will be listed here, with their licenses, before they ship (see
[docs/ML.md](docs/ML.md)).
