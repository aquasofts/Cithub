# GitHub pre-release APK setup

The `Build and publish APKs` workflow is version-independent. It builds the Full and Lite release variants whenever any new semantic-version tag is pushed. Tags with `V`, `v`, or no prefix are accepted, for example `V2.1.30`, `v2.1.31`, or `2.2.0`.

The workflow always publishes a GitHub **pre-release** and uploads:

- `Full-Cithub-V<version>.apk`
- `Lite-Cithub-V<version>.apk`
- `SHA256SUMS.txt`

It is safe to run the same tag again: existing APK assets are replaced, the release description is preserved, and the release is marked as a pre-release.

## 1. Create and back up a release keystore

Create the keystore once. Never commit the keystore or its passwords to Git.

```powershell
keytool -genkeypair -v -keystore cithub-release.jks -alias cithub -keyalg RSA -keysize 4096 -validity 10000
```

Keep an offline backup. Future versions must use the same keystore if they need to update an already installed production-signed app.

## 2. Configure GitHub Actions secrets

Open the repository on GitHub, then go to:

`Settings` > `Secrets and variables` > `Actions` > `New repository secret`

Create these four repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_SIGNING_KEYSTORE_BASE64` | Base64 text of the complete `.jks`/`.keystore` file |
| `ANDROID_SIGNING_STORE_PASSWORD` | Keystore password |
| `ANDROID_SIGNING_KEY_ALIAS` | Key alias, for example `cithub` |
| `ANDROID_SIGNING_KEY_PASSWORD` | Private-key password |

On Windows PowerShell, copy the keystore as one Base64 string with:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\cithub-release.jks")) | Set-Clipboard
```

Paste the clipboard value into `ANDROID_SIGNING_KEYSTORE_BASE64`.

## 3. Allow the workflow to create releases

Open:

`Settings` > `Actions` > `General` > `Workflow permissions`

Select `Read and write permissions`, then save. The workflow also declares `contents: write` explicitly.

## 4. Publish every new version as a pre-release

Before creating a tag, update both current-version locations so that they match:

- `VERSION_HISTORY.md`
- `app/build.gradle.kts`

For every future version, commit and push the matching version update first, then create a new tag on that commit. For example:

```powershell
git tag V2.1.30
git push origin V2.1.30
```

The example version is not hardcoded. The next releases can use tags such as `V2.1.31`, `V2.2.0`, and so on. Each pushed version tag creates or updates its own GitHub pre-release automatically.

The tag version must match the current `versionName`. The workflow uses the current `versionCode` from the repository instead of inventing a different CI-only code.

## 5. Retry an existing tag

After this workflow file is on the default branch, open:

`Actions` > `Build and publish APKs` > `Run workflow`

Enter an existing tag whose tagged commit already contains the same `versionName` and `versionCode`. This builds the source at that tag and creates or updates its pre-release without deleting the existing release notes.

If a tag was created before its version changes were committed, do not use it as-is: publish a new version tag after committing the matching version files, or deliberately move the incorrect tag to the corrected commit before retrying.
