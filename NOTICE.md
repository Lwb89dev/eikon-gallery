# Third-party notices

eikon's own source code is released under the [MIT License](LICENSE). The components and data below
are not covered by that license; each keeps its own.

## Data bundled in the app

| What | Where | License |
| --- | --- | --- |
| Place names, coordinates and populations of cities with more than 15,000 inhabitants, and region names | `app/src/main/assets/places/` (a reduced copy of GeoNames `cities15000` and `admin1CodesASCII`) | [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/). Source: [GeoNames](https://www.geonames.org), which requires attribution. The data is used offline to turn a photo's coordinates into a place name; no request is ever made to GeoNames. |
| Tesseract OCR trained data `eng.traineddata`, `ita.traineddata` (`tessdata_fast`) | `app/src/main/assets/tessdata/` | [Apache License 2.0](https://github.com/tesseract-ocr/tessdata_fast/blob/main/LICENSE). Used offline by the text-in-photos analysis. |

## Libraries

All runtime libraries are Apache License 2.0 unless noted:

- AndroidX: Core, Activity, Lifecycle, Navigation, Compose (UI, Foundation, Material 3), Room, Paging,
  DataStore, ExifInterface, Biometric, Core SplashScreen, WorkManager, Hilt integration, Media3
- Coil
- Dagger / Hilt
- kotlinx.coroutines
- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) 4.9.0 (Apache License 2.0), which bundles
  native builds of Tesseract 5.5.1 (Apache License 2.0), Leptonica 1.85.0 (BSD-style), libjpeg v9f (IJG
  license) and libpng 1.6.48 (libpng license). Each keeps its own license and notices.

Test-only, never shipped: JUnit (Eclipse Public License 1.0), sqlite-jdbc (Apache License 2.0),
AndroidX Test (Apache License 2.0).

Exact versions are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml).

## Not yet included

Machine-learning models other than OCR are added in later steps and will be listed here, with their
licenses, before they ship (see [docs/ML.md](docs/ML.md)).
