# Rolling GitHub pre-release APKs

The `Build and publish APKs` workflow always builds the latest `main` branch source. It does not require a version tag or manually entered version.

Every push to `main` automatically recreates one rolling GitHub pre-release with the fixed tag `prerelease`. Recreating it refreshes its publication time, so the completed pre-release appears at the top of the Releases list.

The pre-release always contains the newest files:

- `Full-Cithub-<version>-PreRelease.apk`
- `Lite-Cithub-<version>-PreRelease.apk`
- `SHA256SUMS.txt`

After a successful build, the workflow deletes every existing GitHub pre-release entry and creates exactly one new pre-release. The `prerelease` tag is moved to the exact commit used for the build, and the release description links to that commit. Stable releases are not removed.

## Required GitHub setting

Open:

`Settings` > `Actions` > `General` > `Workflow permissions`

Select `Read and write permissions`, then save.

## Required signing secrets

Both rolling pre-releases and formal releases are signed with the established publishing certificate. Configure these repository Actions secrets before running the workflow:

- `ANDROID_SIGNING_KEYSTORE_BASE64`: the publishing keystore encoded as a single-line Base64 value
- `ANDROID_SIGNING_STORE_PASSWORD`: the keystore password
- `ANDROID_SIGNING_KEY_ALIAS`: the publishing key alias
- `ANDROID_SIGNING_KEY_PASSWORD`: the publishing key password

The workflow rejects an APK unless its signer certificate SHA-256 is `f9de6015c070e755465c9eee74cb492853421bbe9fec3d64ccaf4dbc65ad02c1`.

## Publishing

Push the newest source to `main` normally:

```powershell
git push origin main
```

GitHub Actions then runs the tests, builds both optimized Performance APKs, verifies them, and updates the rolling pre-release automatically.

The workflow can also be rerun from:

`Actions` > `Build and publish APKs` > `Run workflow`

## Signing note

These are non-debuggable, R8-optimized pre-release APKs signed with the same established publishing certificate as formal releases, so a newer compatible build can replace an installed pre-release without changing signing identity.
