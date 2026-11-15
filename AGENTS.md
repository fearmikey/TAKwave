# TAKwave - Guidelines for AI Agents & Developers

## AGENT WORKFLOW & CODE MAP (.agent_manifest.json)

### MANDATORY CODE MAP CONSULTATION
Before attempting any code modification, agents **MUST** consult `.agent_manifest.json` to identify the architectural pattern, data flow, and specific files responsible for the feature. Do not guess file paths.

### CONTINUOUS MANIFEST MAINTENANCE
At the end of each session, if any files, features, data flows, or package structures were added, renamed, deleted, or altered, agents **MUST** update `.agent_manifest.json` to keep the codebase map accurate and up-to-date for future sessions.

### GIT COMMIT ATTRIBUTION
* **NO AI Co-Author Trailers:** Agents **MUST NOT** add `Co-Authored-By:` trailers or any other AI attribution lines (such as "Generated with ...") to commit messages, tags, or pull request descriptions. All commits are authored solely by the repository owner.

## CRITICAL ARCHITECTURAL CONSTRAINTS

### 1. STRICT 100% FOSS POLICY (F-DROID COMPLIANCE)
* **Zero Proprietary SDKs:** TAKwave is strictly Free and Open Source Software (FOSS), targeted for distribution on F-Droid and other open-source Android repositories.
* **FORBIDDEN DEPENDENCIES:**
  * **NO** Google Play Services (`com.google.android.gms:*`)
  * **NO** Google Mobile Ads / AdMob
  * **NO** Google Play Billing (`com.android.billingclient:*`)
  * **NO** ML Kit or closed-source vision/speech SDKs (`com.google.mlkit:*`)
  * **NO** Firebase SDKs (`com.google.firebase:*`)
  * **NO** Analytics, tracking, crash reporting, or proprietary telemetry libraries
* **ALLOWED DEPENDENCIES:**
  * Android Jetpack libraries (`androidx.*`)
  * Kotlin standard/coroutines libraries
  * Open-source third-party dependencies with permissive/copyleft FOSS licenses (Apache 2.0, MIT, BSD, GPL, MPL)
* **NO PRODUCT FLAVORS.** Do not add product flavor dimensions or build variants.
* The `checkFossDependencies` Gradle task (wired into every `:app` build) fails the build when a forbidden artifact shows up in a resolved runtime classpath. Never weaken it.

### 2. PRIVACY & OFFLINE FIRST
* The application must remain fully functional offline. The only sockets it opens are the local TAK server socket and, optionally, a user-configured KISS TCP connection.
* No user data leaves the device except what the user chooses to transmit over the radio.

### 3. MODULE BOUNDARIES
* `:core` is pure Kotlin/JVM. **No Android imports.** All protocol, modem, codec, and scheduling logic lives here, with unit tests (`./gradlew :core:test`).
* `:app` holds everything Android: service, audio, PTT, USB, TCP server, UI.
* `:usbserial` is vendored third-party source (usb-serial-for-android, MIT). Do not edit it except to bump the vendored version.
* `:xmlparser` only repackages the kXML 2 jar without `org/xmlpull/**` (Android already ships that API and R8 rejects a second copy). It has no source of its own.
* No DI frameworks. Wiring is manual, in `AppContainer`.

### 5. ENCRYPTION
* Encryption is off by default and must stay that way; it is not permitted on amateur bands. Keep the warning in the settings UI.
* Never fall back to clear text when encryption is selected but unusable; disable transmitting instead.
* Never log, display or fingerprint private keys or passphrases.

### 4. RADIO SAFETY
* PTT must always be released: in `finally` blocks, on service destroy, on USB detach, and by the TX watchdog.
* Never transmit without a valid callsign. Never retransmit anything that arrived from RF, with one exception: store-and-forward relaying of chat, markers and emergency alerts (`core/sched/StoreForward.kt`; alerts added with the owner's approval), which is opt-in, single-hop, rate-limited and specified in `docs/PROTOCOL.md` section 9. Do not widen it (no position reports, no multi-hop, no weakening of the limits) without the owner's say-so.
* Changes to the over-air format must follow the versioning rules in `docs/PROTOCOL.md`.

## RELEASE & PUBLISHING WORKFLOW (F-DROID)

When helping the user create a new release, follow this exact workflow:

1. **Bump Versions:** Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. **Reproducible Build Configuration:** Ensure `vcsInfo { include = false }` is present in `app/build.gradle.kts` under `buildTypes.release`.
3. **Fastlane Changelog:** Create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` with concise release notes.
4. **Standard Changelog & Readme:** Update `CHANGELOG.md` under `## [X.Y.Z] - YYYY-MM-DD` and update the version badge in `README.md`.
5. **F-Droid Metadata Recipe:** Update `fdroid/com.fearmikey.takwave.yml`:
   * Update `versionName` and `versionCode`.
   * Update `commit` with the full 40-character Git commit hash (`git rev-parse HEAD`).
   * Ensure `gradle:` is set to `- yes`.
6. **Commit & Tag:** Commit all changes, create a git tag strictly following the `vX.Y.Z` format, and push both the commit and the tag to GitHub. Only do this when the user asks.
7. **Reproducible Build Binary:** Instruct the user to:
   * Build the signed release APK (`app-release.apk`) using their `TAKwave-Release.jks` keystore.
   * Draft a GitHub release for the pushed `vX.Y.Z` tag and upload `app-release.apk`. F-Droid looks for it at `https://github.com/fearmikey/TAKwave/releases/download/v%v/app-release.apk`.
