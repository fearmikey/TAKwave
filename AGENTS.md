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

## RELEASE WORKFLOW (GITHUB + F-DROID, REPRODUCIBLE BUILDS)

### F-Droid state (re-check at the start of EVERY release)

**State A: not submitted** (checked 2026-11-25). The inclusion MR
<https://gitlab.com/fdroid/fdroiddata/-/merge_requests/59101> was **closed, not merged**, on
2026-11-25 because the app had not been maintained for 3 months yet (first commit 2026-11-14).
Do not resubmit before **2027-02-14**, and only when the owner asks. The draft recipe lives in
`fdroid/com.fearmikey.takwave.yml` (reproducible: `Binaries:` + `AllowedAPKSigningKeys`).

The state changes when an MR is opened or merged, so before every release re-run these checks
(read-only) and update this section if the state changed:
1. `cd ~/fdroid/fdroiddata && git fetch origin master && git show origin/master:metadata/com.fearmikey.takwave.yml`
   (never switch the branch of `~/fdroid/fdroiddata`; other apps use it. Use worktrees.)
2. Open MRs: `curl -s "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/merge_requests?state=opened&search=com.fearmikey.takwave&in=title,description"`
   (MR comments need `https://gitlab.com/fdroid/fdroiddata/-/merge_requests/<iid>/discussions.json`; the API notes endpoint needs auth.)
3. `curl -s https://f-droid.org/api/v1/packages/com.fearmikey.takwave` (404 = not published). If
   published, `apksigner verify --print-certs` the newest `https://f-droid.org/repo/com.fearmikey.takwave_<versionCode>.apk`
   and compare with the release cert below.

| State | Meaning | F-Droid follow-up on release |
|---|---|---|
| A | not on fdroiddata master, no open MR | none; the in-repo recipe is updated (step 8) |
| B | open MR | update the MR's recipe (replace the build entry, full commit hash), one commit, amend + `--force-with-lease`; **ask before every push to the fork**, push only after the GitHub release has its APK |
| C | merged, F-Droid-signed (no `AllowedAPKSigningKeys` or signer differs) | none; checkupdates builds the tag. Never add `Binaries:`/`AllowedAPKSigningKeys` |
| D | merged, reproducible, published signer = our cert | none; checkupdates builds the tag. Asset name and signing must stay exactly as the recipe expects |

In merged states never open version-bump MRs, never reuse the old inclusion branch, and only
change fdroiddata (fresh worktree from `origin/master`, `fdroid rewritemeta` + `fdroid lint`)
when the recipe itself must change, after asking the owner.

### Facts the build depends on

* Release certificate SHA-256: `aa758ce5a29b8165063485a52049d17718d3653d6d8382df287e1a6acfa8e464`
  (keystore `~/Documents/Keystores/TAKwave-Release.jks`).
* GitHub asset name: `TAKwave-X.Y.Z.apk` (one universal APK; no native code of our own, no ABI
  splits). Recipe: `Binaries: https://github.com/fearmikey/TAKwave/releases/download/v%v/TAKwave-%v.apk`.
  Releases up to 0.4.0 used `app-release.apk`; the 0.4.0 APK was built from `2cf1952`, not the tag.
* Tags: annotated `vX.Y.Z`, message `TAKwave X.Y.Z` (recipe: `UpdateCheckMode: Tags`).
* `versionCode` goes up by 1 per release; minSdk 29, so signing is v2+v3 only.
* Recipe build steps mirrored by the script: `rm gradle/gradle-daemon-jvm.properties`, delete the
  `foojay-resolver` line from `settings.gradle.kts`, `./gradlew assembleRelease` with JDK 25
  (F-Droid installs `openjdk-25-jdk-headless`; locally `~/android-studio/jbr` is JDK 25). If
  the recipe's steps change, change `build_variant` in `scripts/build-release.sh` the same way.
* `scripts/build-release.sh <tag>` clones the **pushed** tag from GitHub, builds it clean, signs
  with `apksigner --alignment-preserved true` (v1 off, v2 on, v3 on, v4 off), checks the cert
  against the recipe's `AllowedAPKSigningKeys` and runs fdroidserver's `verify_apks`. Output:
  `dist/<tag>/TAKwave-X.Y.Z.apk` (`OUT_DIR` overrides). Secrets come from
  `~/.config/takwave-release/release.env` (chmod 600: `KEYSTORE`, `KS_PASS`, optional
  `KEY_PASS`, `KEY_ALIAS`). **Never read, print or grep that file, and never ask for passwords
  in chat.**

### Trigger

"do a release" / "new release build" is the owner's approval to commit, tag, push `master` and
the tag, run the script and create the GitHub release. It is **not** approval to push anything
to fdroiddata or GitLab.

### Steps

1. Re-check the F-Droid state (above). If a bot update or another MR touches this app, stop and ask.
2. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`. Keep
   `vcsInfo { include = false }` and `dependenciesInfo { includeInApk = false }`.
3. Move `CHANGELOG.md`'s `[Unreleased]` items into a new `## [X.Y.Z] - YYYY-MM-DD` section.
   Never add to an already released section.
4. Create `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (short, plain text,
   under 500 characters).
5. Update the README version badge, and `.agent_manifest.json` if files or flows changed.
6. Commit `Release X.Y.Z: <summary>` (no AI attribution) and create the annotated tag:
   `git tag -a vX.Y.Z -m "TAKwave X.Y.Z"`.
7. Push: `git push origin master vX.Y.Z`. If this shell has no push credentials, give the owner
   the command and wait until they confirm.
8. Update the in-repo recipe `fdroid/com.fearmikey.takwave.yml` (state A, or C/D for the record):
   replace the build entry's `versionName`/`versionCode`/`commit` (full hash of the tagged
   commit, `git rev-parse vX.Y.Z^{commit}`) and `CurrentVersion`/`CurrentVersionCode`; commit
   `F-Droid recipe for X.Y.Z` and push `master`.
9. Run `scripts/build-release.sh vX.Y.Z` in the background (it takes about 5 minutes).
10. Check the printed `Cert SHA-256` equals the release certificate above (and, in state D, the
    merged recipe's `AllowedAPKSigningKeys`). Stop if anything differs or the script fails.
11. `gh release create vX.Y.Z dist/vX.Y.Z/TAKwave-X.Y.Z.apk --title "TAKwave X.Y.Z" --notes "<the CHANGELOG section>"`
    (if `gh` is unavailable, ask the owner to upload in the browser).
12. F-Droid follow-up by state (table above). States A, C, D: nothing more.

### Rules

* Never build release APKs from the working tree or Android Studio; only the script, from a pushed tag.
* Never move or re-create a pushed tag. If a release is broken, fix forward with a new version.
* Never change the signing key, the signing flags or the asset name (renaming needs an
  fdroiddata MR merged first once the app is in F-Droid).
* Never add path-, time- or machine-dependent build inputs (absolute paths, timestamps, git
  metadata, hostnames). Any build change that could affect the APK bytes must be proven
  reproducible again: two clean clones at different paths, `cmp`, then `verify_apks`.
* If a shell command times out, check whether it ran (`git reflog`, `git tag`, `pgrep`, output
  files) before retrying.

### Resubmitting to F-Droid (state A, not before 2027-02-14, owner asks first)

Branch from a fresh `origin/master` in a new worktree (`~/fdroid/fdroiddata-takwave`), copy the
in-repo recipe to `metadata/com.fearmikey.takwave.yml`, run `fdroid rewritemeta`, `fdroid lint`
and `fdroid build -v com.fearmikey.takwave:<versionCode>` (from `~/fdroid/venv`, with
`JAVA_HOME` set) and check that the build verifies against the GitHub APK. The build entry must
be a release made by the script (0.4.0 has no `TAKwave-0.4.0.apk` asset). Ask whether to reopen
!59101 or open a new MR; tick "Enable Reproducible Builds" and say the APK is built by
`scripts/build-release.sh` from the tag and verified with `verify_apks`.
