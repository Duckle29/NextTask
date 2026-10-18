# Releasing

A release is made by pushing a tag. The tag is the only place the version is written down.

```sh
git switch main && git pull
git tag -a v1.2.3 -m "NextTask 1.2.3"
git push origin v1.2.3
```

The **Release** workflow then checks the tag, builds and signs the app, and publishes a GitHub release with
the signed APK, the Android App Bundle and a `SHA256SUMS` file.

## Version numbers

| Tag | `versionName` | `versionCode` | GitHub release |
|---|---|---|---|
| `v1.2.3` | `1.2.3` | `100200399` | release |
| `v1.2.3-rc.1` | `1.2.3-rc.1` | `100200301` | pre-release |

Only these two forms are accepted; any other tag makes the workflow fail before anything is built.
The version code is `(MAJOR * 1_000_000 + MINOR * 1_000 + PATCH) * 100 + N` for `-rc.N`, or `+ 99` for a
release. It grows with the version, a release candidate sorts below its release, and it stays below
Android's limit of 2.1 billion (so `MAJOR <= 20`, `MINOR` and `PATCH <= 999`, `N` = 1..98). The rules live in
[`scripts/version-from-tag.sh`](../scripts/version-from-tag.sh), which you can run locally.

Builds that do not come from a tag are marked `0.0.0-dev`.

## What the workflow checks

1. **The tag is a valid version tag** (see above).
2. **The tagged commit is on `main`**: it has to be `main`'s head or one of its ancestors. A tag on an
   unmerged branch is rejected.
3. The tests pass, the release is **signed** (the workflow fails if the signing key is missing), and the APK
   really carries the version the tag asked for.

## One-time setup

### 1. Create the signing key

This key proves that updates come from you. **Back it up** (password manager or offline storage): without
it, installed copies can no longer be updated, only reinstalled.

```sh
keytool -genkeypair -v -keystore nexttask-release.jks -alias nexttask \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=NextTask"
```

`-dname` answers the questions `keytool` would otherwise ask (name, organisation, city, ...). Android does
not check them, and `Unknown` would be fine too, but they are written into the certificate that ships
inside every APK, so only put in what you are happy to make public. `keytool` still asks for a password.

The keystore is a PKCS12 file, which has a single password for the file and the key. Use the **same value**
for `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD` below, or signing fails.

Keep the file out of the repository.

### 2. Add the secrets

Create an **environment** called `release` (repository → Settings → Environments) and add these as
*environment secrets*:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 nexttask-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `nexttask` (the alias used above) |
| `ANDROID_KEY_PASSWORD` | the key password |

### 3. Protect the release flow (recommended)

GitHub reads a workflow from the commit that is tagged, so the "tag is on `main`" check protects against
mistakes, not against someone who can push a tag with a modified workflow. These settings, which cannot be
kept in the repository, close that gap:

- **Environment `release`**: under *Deployment branches and tags*, allow only the tag pattern `v*`; add
  yourself as a *required reviewer* if you want a confirmation click before every release.
- **Tag ruleset** (Settings → Rules → Rulesets, target *tags*, pattern `v*`): restrict who can create tags,
  and block updating and deleting them, so a published version can never be moved.

## Checking a download

```sh
sha256sum --check SHA256SUMS
"$ANDROID_HOME"/build-tools/<version>/apksigner verify --print-certs NextTask-1.2.3.apk
```

The certificate fingerprint should be the same for every release.

## Good to know

- **First install of a release build:** the debug builds used during development are signed with a
  different key, so Android refuses to update them. Uninstall the debug app once; you will have to sign in
  again.
- **A release went wrong:** delete the GitHub release and the tag (`git push origin :refs/tags/v1.2.3`),
  fix the problem on `main`, and tag again. If people may already have installed the version, use the next
  version number instead.
- **Building a release locally** works without the key: `./gradlew :androidApp:assembleRelease` gives an
  unsigned APK. Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
  `ANDROID_KEY_PASSWORD` to sign it, and pass `-PversionName` and `-PversionCode` to set the version.
- **Not done yet:** code shrinking (R8) is off, and nothing is uploaded to Google Play. The App Bundle in
  each release is ready for it.
