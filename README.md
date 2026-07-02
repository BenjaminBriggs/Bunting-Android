# bunting-sdk-android

Kotlin client SDK for [Bunting](../docs/README.md) feature flags — the Android counterpart to `bunting-sdk-swift`. Fetches a signed `config.json`, verifies a detached RS256 JWS offline, caches it, and evaluates flags locally.

## Modules
- `:bunting-verify` — detached-JWS (RS256) signature verification (mirrors Swift `BuntingVerify`).
- `:bunting` — models, evaluation engine, config store, identity, overrides (mirrors Swift `Bunting`).
- `:bunting-cli` — JVM CLI that fetches/verifies/decodes a config from a bootstrap JSON document.

Pure-JVM core: unit tests, the conformance runner, and the CLI run headless (no emulator).

## Build & test
```bash
./gradlew build        # compile + test all modules
./gradlew :bunting:test --tests 'com.bunting.conformance.*'   # conformance gate
```

## Conformance
The vendored bundle under `bunting/src/test/resources/conformance/` is a copy of `bunting-admin/conformance/`. Re-sync with:
```bash
cp ../bunting-admin/conformance/{manifest,bucketing,evaluation,fingerprint,signature,bootstrap}.json \
   bunting/src/test/resources/conformance/
```
