# Releasing

A release is one APK, signed with the release key and attached to a GitHub release with the tag `vX.Y.Z`: `eikon-X.Y.Z-arm64-v8a.apk`. (1.0.0 came as two APKs, `standard` and `backup`; the two were merged in 1.2.0, see [BACKUP.md](BACKUP.md).)

Release builds contain the arm64-v8a native libraries only (`ndk { abiFilters += "arm64-v8a" }` for `release` in `app/build.gradle.kts`), which is what makes them "arm64-v8a" APKs.

## Steps

1. Set `versionName` (and raise `versionCode`) in `defaultConfig` in `app/build.gradle.kts`.
2. Add the release to [CHANGELOG.md](../CHANGELOG.md); its text is the body of the GitHub release.
3. Run the checks and build the release:

   ```sh
   ./gradlew :app:testDebugUnitTest :app:lintDebug
   ./gradlew :app:assembleRelease
   ```

   The first build downloads the machine-learning models (see the README). `assembleRelease` also runs `verifyNetworkPermissionsRelease`, which fails the build if the manifest lacks `INTERNET`, allows unencrypted traffic, or asks for a permission that is not on the reviewed list. Check by hand what the APK says about itself (`aapt2 dump permissions`, `aapt2 dump configurations` for the languages).
4. **Signing is automatic.** `app/build.gradle.kts` reads `../eikon-gallery-release-keystore/keystore.properties` (a sibling of this repo, never committed — the build fails with a clear error if the file is missing) and signs `release` with it directly, so `assembleRelease` already produces a signed, aligned APK at `app/build/outputs/apk/release/app-release.apk`. Copy it to `eikon-X.Y.Z-arm64-v8a.apk` and check its certificate matches the one on record:

   ```sh
   cp app/build/outputs/apk/release/app-release.apk eikon-X.Y.Z-arm64-v8a.apk
   apksigner verify --print-certs eikon-X.Y.Z-arm64-v8a.apk
   ```

   (1.0.0 and 1.1.0 built unsigned APKs and signed them by hand with `apksigner` and a password typed at release time; that step no longer exists.)
5. Commit, tag (`git tag -a vX.Y.Z`), push the commit and the tag, and create the release with the APK attached:

   ```sh
   gh release create vX.Y.Z --title "eikon X.Y.Z" --notes-file <the changelog section> eikon-*.apk
   ```

## The key

There is one key for all releases, in a PKCS12 keystore kept outside this repository, alongside a `keystore.properties` that `app/build.gradle.kts` reads at build time (so the password lives on disk in that file, not typed in at release time as before). Whoever loses the keystore or its password can no longer publish updates that install over earlier releases: Android refuses an update signed with a different key, and uninstalling first would delete the user's library, albums and edits. This happened once already (the original key made 2026-09-26 was lost days later; every release from 1.2.0 on uses its replacement, which does not install over anything signed with the first key or with 1.0.0's). Keep a backup of the keystore and `keystore.properties` somewhere safe.
