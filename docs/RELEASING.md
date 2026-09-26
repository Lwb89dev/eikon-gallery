# Releasing

A release is two APKs built from the same commit, signed with the same key, and attached to a GitHub release with the tag `vX.Y.Z`:

| File | Build |
| --- | --- |
| `eikon-X.Y.Z-arm64-v8a.apk` | `standard` (no network permission) |
| `eikon-X.Y.Z-backup-arm64-v8a.apk` | `backup` (adds the backup to a server of your own) |

Release builds contain the arm64-v8a native libraries only (`ndk { abiFilters += "arm64-v8a" }` for `release` in `app/build.gradle.kts`), which is what makes them "arm64-v8a" APKs.

## Steps

1. Set `versionName` (and raise `versionCode`) in `defaultConfig` in `app/build.gradle.kts`. The `backup` build appends `-backup` to the name; both builds share the version code.
2. Add the release to [CHANGELOG.md](../CHANGELOG.md); its text is the body of the GitHub release.
3. Run the checks and build both variants:

   ```sh
   ./gradlew :app:testStandardDebugUnitTest :app:testBackupDebugUnitTest :app:lintStandardDebug :app:lintBackupDebug
   ./gradlew :app:assembleStandardRelease :app:assembleBackupRelease
   ```

   The first build downloads the machine-learning models (see the README). `assemble…Release` also runs `verifyNoInternetStandardRelease` and `verifyBackupNetworkBackupRelease`, which fail the build if the standard APK has the `INTERNET` permission or the backup APK asks for one that is not on the reviewed list.
4. **Sign** the unsigned APKs from `app/build/outputs/apk/<flavor>/release/` with `apksigner` from the Android build tools. The signing key is deliberately **not** part of the repository (and neither is any password): it lives outside it, and the password is passed through the environment so it never appears in a file or in the process list.

   ```sh
   export EIKON_KS_PASS=…           # the key's password
   apksigner sign --ks /path/to/eikon-release.jks --ks-key-alias eikon --ks-pass env:EIKON_KS_PASS \
     --out eikon-X.Y.Z-arm64-v8a.apk app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk
   apksigner verify --print-certs eikon-X.Y.Z-arm64-v8a.apk
   ```

   The unsigned APK is already aligned by the Android Gradle plugin, so signing does not change its alignment. Check that the certificate's SHA-256 is the one written in the changelog.
5. Commit, tag (`git tag -a vX.Y.Z`), push the commit and the tag, and create the release with both APKs attached:

   ```sh
   gh release create vX.Y.Z --title "eikon X.Y.Z" --notes-file <the changelog section> eikon-*.apk
   ```

## The key

There is one key for all releases, in a PKCS12 keystore kept outside this repository. Whoever loses it (or its password) can no longer publish updates that install over earlier releases: Android refuses an update signed with a different key, and uninstalling first would delete the user's library, albums and edits. Keep a backup of the keystore and the password somewhere safe.
