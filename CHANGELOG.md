# Changelog

All notable changes to the Bunting Android SDK are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

The first stable release, at feature parity with the Swift SDK. It implements the v1 config wire contract and is certified against conformance bundle `2026-10-17.2`.

### Added

- Published to Maven Central as `io.github.benjaminbriggs:bunting-verify`, `bunting`, `bunting-android` and `bunting-debug`. The codegen Gradle plugin is on the Gradle Plugin Portal as `io.github.benjaminbriggs.bunting.codegen`, and `bunting-cli` ships as a zip on each GitHub release.
- `ArtifactDecoder.decode` decodes a config by the v1 wire contract and reports every skipped or ignored entry as a `DecodeIssue`. Header problems throw `ArtifactRejectedException`.
- `fetchBuntingConfig` refuses a fetched config the SDK would reject at header level, and warns about each flag it will skip, before overwriting the seed.

### Changed

- **BREAKING**: the codegen plugin id is now `io.github.benjaminbriggs.bunting.codegen` (was `com.bunting.codegen`).
- **BREAKING**: config decoding follows the v1 wire contract. One bad entry no longer rejects the whole artifact: a flag with an unknown type or a default that doesn't match its type is skipped (its accessor returns the call-site default), a malformed variant becomes a placeholder that never matches, an unknown condition type or operator never matches, and a malformed test or rollout is dropped. Only a bad header rejects the artifact, and the cached or bundled config stays active.
- **BREAKING**: flag values decode by the flag's declared type, never by JSON shape. `2.0` is the int 2, `3` is the double 3.0, ISO-looking strings stay strings, json flags are strings, and dates must be UTC instants on a real calendar date with at most millisecond precision.
- **BREAKING**: an A/B test user whose bucket lands past every group, or in a group with no value for the flag, is not in the test: evaluation falls through to the next variant and then the default, instead of getting the first group's value. The config fingerprint now always matches the served value.
- **BREAKING**: `ConditionType`, `ConditionOperator` and `VariantType` gain an `UNRECOGNIZED` entry, and `FlagValueSerializer` and the per-model serializers are removed (a value can't be decoded without its flag's type).
- Generated accessors no longer throw before `Bunting.configure`: they return the seed's development default. Codegen escapes keys, defaults and descriptions, handles Kotlin keywords and leading digits, and skips any flag the SDK would skip with a build warning. Two keys that map to the same accessor fail the build.
- Overrides convert by the flag's declared type when the loaded config has the flag.

### Fixed

- A verified config that the SDK can't decode now reports `didCompleteFetch(false)` and no longer forces a full download on every refresh. Bundled seed and cache decode problems are logged.
- Every fetch that starts now reports exactly one `didCompleteFetch`: success for a 304, failure for a non-200 response or a network error.
- `:bunting` exposes coroutines and kotlinx.serialization as `api` dependencies, so apps consuming the published artifacts compile against `Bunting.configure` and `StateFlow` without adding them by hand.

### Known issues

- The debug screen's JSON override editor only accepts text starting with `{` or `[`, so it rejects valid scalar JSON such as `"42"`.
- Fingerprint parity with the admin holds only when the SDK knows every type in the artifact.
- Signatures have no expiry and `config_version` is not checked for monotonicity, so whoever controls the CDN can replay an older signed config.
