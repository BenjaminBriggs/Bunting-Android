# Bunting Android SDK — AI Assistant Guide

Kotlin counterpart to `bunting-sdk-swift`: fetches a signed `config.json`, verifies a
detached RS256 JWS offline, caches it, and evaluates all flags locally.

**See [README.md](README.md) for modules, usage, and the documented divergences from Swift.**

## Source tree

```
bunting-verify/        # Detached-JWS RS256 verification (⇄ Swift BuntingVerify)
bunting/               # Pure-JVM core (⇄ Swift Sources/Bunting)
  com/bunting/model/       # Config artifact models; FlagValue decode precedence is contract
  com/bunting/evaluation/  # Bucketing, conditions, evaluator, fingerprint, memoization, ContextProvider seam
  com/bunting/storage/     # BootstrapConfig, Http, ConfigStore, KeyValueStore, OverridesStore
  com/bunting/identity/    # Per-install UUID
  com/bunting/             # BuntingClient + object Bunting facade, StateFlow, events
  src/test/resources/conformance/  # Vendored cross-language vectors — THE contract gate
bunting-cli/           # JVM CLI: fetch / verify / decode (⇄ Swift bunting-cli)
bunting-android/       # AGP binding: configure(context), AndroidContextProvider, assets, foreground refresh
bunting-debug/         # Compose Info/Debug screens over the FlagInfo presenter
bunting-codegen-gradle/  # Included build: io.github.benjaminbriggs.bunting.codegen Gradle plugin (⇄ Swift codegen plugin)
example/               # Compose demo app consuming everything above
```

## Dev commands

```bash
./gradlew build                       # everything, incl. the included codegen build
./gradlew :bunting:test --tests 'com.bunting.conformance.*'   # conformance gate
./gradlew :example:assembleDebug      # demo app (also proves codegen integration)
```

## Key conventions

**Conformance is the contract.** The vendored bundle (version pinned in
`BuildInfo.CONFORMANCE_BUNDLE_VERSION`) must stay green; never edit vectors to make
tests pass — re-sync from `../bunting-admin/conformance/` instead.

**Byte-level contracts** (do not touch casually): bucketing
`SHA-256("<salt>:<UUID-UPPERCASE>")` first 8 bytes unsigned big-endian `% 100 + 1`
(Java UUIDs must be uppercased); fingerprint MSB-first bit packing + CRC-8/SMBUS
uppercase hex; strict JWS header order (`alg` → `b64` → `crit` → `kid`);
verify-before-decode-before-cache; signature persisted before config; 304 counts as
a successful fetch; `FlagValue` decode precedence Bool → Int → Double →
(String → date → JSON → string).

**Synchronous flag reads.** Accessors are lock-free snapshot reads, callable from any
thread; only `refresh()`, `resetIdentity()`, `awaitInitialLoad()` suspend.

**Pure-JVM core stays pure.** `:bunting`/`:bunting-verify`/`:bunting-cli` depend only on
kotlinx-serialization + coroutines; anything touching `android.*` lives in
`:bunting-android` (Robolectric-tested) or above.

**Codegen never fails the build over seed content** — missing/invalid seed emits a
fallback file; unknown types and mismatched development defaults skip the flag with a
warning. The one hard error is two keys mapping to the same Kotlin identifier.
Generated accessors go through the default-returning `Bunting.bool(...)` facade, never
`Bunting.instance` (which throws before `configure`).
Generated dirs register on the **java** source set (`sources.kotlin` did not feed
compileKotlin on AGP 8.9; the java wiring is kept on later AGP versions as the
known-good path).

**Style**: `bool == false` over `!bool`; libraries log via `java.util.logging`, never
`println` (CLI output is the exception). Unit tests run once against the debug variant
in AGP modules.

**Publishing**: one version (`VERSION_NAME` in `gradle.properties`, also read by the
codegen build). Libraries go to Maven Central as `io.github.benjaminbriggs:<module>` via
`com.vanniktech.maven.publish` 0.35.0 (0.36+ needs Gradle 9); the plugin goes to the
Plugin Portal. Signing is only required when `mavenCentralUsername` is set, so
`publishToMavenLocal` needs no keys. See `docs/releasing.md`.

**Repo policy**: commits land directly on `main`; `./gradlew build` green before every
commit.
