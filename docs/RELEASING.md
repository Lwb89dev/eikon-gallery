# Releasing

A release is one APK, signed with the release key and attached to a GitHub release with the tag `vX.Y.Z`: `eikon-X.Y.Z-arm64-v8a.apk`. (1.0.0 came as two APKs, `standard` and `backup`; the two were merged in 1.1.0, see [BACKUP.md](BACKUP.md).)

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
4. **Sign** the unsigned APK from `app/build/outputs/apk/release/` with `apksigner` from the Android build tools. The signing key is deliberately **not** part of the repository (and neither is any password): it lives outside it, and the password is passed through the environment so it never appears in a file or in the process list.

   ```sh
   export EIKON_KS_PASS=…           # the key's password
   apksigner sign --ks /path/to/eikon-release.jks --ks-key-alias eikon --ks-pass env:EIKON_KS_PASS \
     --out eikon-X.Y.Z-arm64-v8a.apk app/build/outputs/apk/release/app-release-unsigned.apk
   apksigner verify --print-certs eikon-X.Y.Z-arm64-v8a.apk
   ```

   The unsigned APK is already aligned by the Android Gradle plugin, so signing does not change its alignment. Check that the certificate's SHA-256 is the one written in the changelog.
5. Commit, tag (`git tag -a vX.Y.Z`), push the commit and the tag, and create the release with the APK attached:

   ```sh
   gh release create vX.Y.Z --title "eikon X.Y.Z" --notes-file <the changelog section> eikon-*.apk
   ```

## The key

There is one key for all releases, in a PKCS12 keystore kept outside this repository. Whoever loses it (or its password) can no longer publish updates that install over earlier releases: Android refuses an update signed with a different key, and uninstalling first would delete the user's library, albums and edits. Keep a backup of the keystore and the password somewhere safe.
