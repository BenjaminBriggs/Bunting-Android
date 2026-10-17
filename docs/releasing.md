# Releasing the Android SDK

A release publishes three things. The tag publishes the libraries and the CLI; the plugin follows by hand once the libraries are live:

| What | Where | Coordinates |
|---|---|---|
| `bunting-verify`, `bunting`, `bunting-android`, `bunting-debug` | Maven Central (Central Portal) | `io.github.benjaminbriggs:<module>:<version>` |
| Codegen Gradle plugin | Gradle Plugin Portal | plugin id `io.github.benjaminbriggs.bunting.codegen` (artifact `io.github.benjaminbriggs:bunting-codegen-gradle`) |
| `bunting-cli` | GitHub release asset | `bunting-cli-<version>.zip` |

The version is `VERSION_NAME` in the root `gradle.properties`. The libraries, the CLI zip, and the codegen plugin (`bunting-codegen-gradle/build.gradle.kts` reads the same file) all use it. POM metadata (license, SCM, developer) also lives in `gradle.properties` as `POM_*` properties.

## One-time setup

### 1. Maven Central

1. Create an account at <https://central.sonatype.com>. Signing in with the `BenjaminBriggs` GitHub account is easiest, because the portal then verifies the `io.github.benjaminbriggs` namespace automatically.
2. Open **Namespaces** and confirm `io.github.benjaminbriggs` is listed as verified. If it isn't, add it: the portal shows a verification key, create a temporary public GitHub repository named after that key under `BenjaminBriggs`, click **Verify**, then delete the repository.
3. Open **Account**, choose **Generate User Token**, and keep the generated username and password. These are not your login credentials.

### 2. GPG signing key

Maven Central rejects unsigned artifacts.

```bash
gpg --full-generate-key                      # RSA, 4096 bits, no expiry or a long one; set a passphrase
gpg --list-secret-keys --keyid-format=long   # note the key ID after "rsa4096/"
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
gpg --export-secret-keys --armor <KEY_ID>    # the whole output, including the BEGIN/END lines, is the secret
```

Central checks signatures against public keyservers, so publish the public key before the first release and give it a few minutes to propagate. Keep a backup of the secret key outside GitHub.

### 3. Gradle Plugin Portal

1. Create an account at <https://plugins.gradle.org> by signing in with the `BenjaminBriggs` GitHub account. Plugin ids under `io.github.benjaminbriggs` are only accepted from the account linked to that GitHub user.
2. Open your profile, go to **API Keys**, and generate a key. Keep the key and the secret.

### 4. Repository secrets

In the GitHub repository, **Settings > Secrets and variables > Actions > New repository secret**:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user token password |
| `SIGNING_IN_MEMORY_KEY` | output of `gpg --export-secret-keys --armor <KEY_ID>` |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | the GPG key passphrase |
| `GRADLE_PUBLISH_KEY` | Plugin Portal API key |
| `GRADLE_PUBLISH_SECRET` | Plugin Portal API secret |

## Cutting a release

1. Set `VERSION_NAME` in `gradle.properties` and commit to `main`.
2. Optionally dry-run locally. Neither command needs keys or credentials:
   ```bash
   ./gradlew publishToMavenLocal
   ./gradlew -p bunting-codegen-gradle publishToMavenLocal
   ```
3. Tag and push:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
4. `.github/workflows/release.yml` then:
   - fails straight away unless the tag is exactly `v<VERSION_NAME>`;
   - runs the same checks as `ci.yml`;
   - signs the libraries and uploads them to the Central Portal as a deployment (`./gradlew publishToMavenCentral`);
   - builds `bunting-cli-<version>.zip` and creates the GitHub release with generated notes.

   It does not publish the codegen plugin (step 6).
5. **Release the Central deployment by hand.** Uploads are not released automatically (`mavenCentralAutomaticPublishing=false`). Open <https://central.sonatype.com/publishing/deployments>, wait for the deployment to reach **Validated**, check the files, then click **Publish**. Artifacts reach Maven Central (`repo1.maven.org`) within about 30 minutes. A deployment that fails validation, or one you don't want, can be dropped there instead. Published versions can never be changed or deleted, so bump the version for any fix.
6. **Publish the codegen plugin.** Generated code calls the libraries, so the plugin must not go live before them. Once the version is on Maven Central, open **Actions > Publish codegen plugin > Run workflow** and enter the version without the `v` (for example `1.0.0`). `.github/workflows/publish-plugin.yml`:
   - checks out the tag `v<version>` and fails unless the input equals `VERSION_NAME` there;
   - fails unless all four libraries are already on `repo1.maven.org` (rerun it later if Central is still syncing);
   - builds and tests the plugin, then publishes it (`./gradlew -p bunting-codegen-gradle publishPlugins`).

   The first version of a new plugin id is held for manual review by Gradle before it is listed; later versions go live immediately.

## How signing is wired

`signAllPublications=true` in `gradle.properties` makes the vanniktech plugin sign every publication, and on its own it would make signing mandatory for any non-SNAPSHOT version, including `publishToMavenLocal`. The root `build.gradle.kts` instead requires signing only when the `mavenCentralUsername` property is set. In CI that is always true for the Central upload, so an upload can never go out unsigned; on a developer machine without Central credentials, local publishing works without a key (signing tasks are skipped).

The release workflow passes credentials as `ORG_GRADLE_PROJECT_*` environment variables (`mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`, `signingInMemoryKeyPassword`); the plugin workflow passes the Plugin Portal key as `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET`.

## Tool versions

- `com.vanniktech.maven.publish` **0.35.0**: the newest release that supports Gradle 8.13 (0.36.0 and later require Gradle 9).
- `com.gradle.plugin-publish` **2.0.0**.
- Javadoc jars are empty (Dokka is not set up, and AGP's built-in javadoc generation fails on these modules); the sources jars carry the KDoc.
