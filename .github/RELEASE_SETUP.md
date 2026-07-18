# Rolling GitHub pre-release APKs

The `Build and publish APKs` workflow always builds the latest `main` branch source. It does not require a version tag, manually entered version, or Android signing secrets.

Every push to `main` automatically recreates one rolling GitHub pre-release with the fixed tag `prerelease`. Recreating it refreshes its publication time, so the completed pre-release appears at the top of the Releases list.

The pre-release always contains the newest files:

- `Full-Cithub-PreRelease.apk`
- `Lite-Cithub-PreRelease.apk`
- `SHA256SUMS.txt`

After a successful build, the workflow deletes every existing GitHub pre-release entry and creates exactly one new pre-release. The `prerelease` tag is moved to the exact commit used for the build, and the release description links to that commit. Stable releases are not removed.

## Required GitHub setting

Open:

`Settings` > `Actions` > `General` > `Workflow permissions`

Select `Read and write permissions`, then save. No repository Secrets are required by this pre-release workflow.

## Publishing

Push the newest source to `main` normally:

```powershell
git push origin main
```

GitHub Actions then runs the tests, builds both optimized Performance APKs, verifies them, and updates the rolling pre-release automatically.

The workflow can also be rerun from:

`Actions` > `Build and publish APKs` > `Run workflow`

## Signing note

These are non-debuggable, R8-optimized pre-release test APKs signed with a CI debug key. GitHub Actions caches that key so later pre-release builds can normally replace earlier ones. If GitHub eventually evicts the signing-key cache, Android will require the previously installed pre-release APK to be uninstalled before installing the newly signed build.
