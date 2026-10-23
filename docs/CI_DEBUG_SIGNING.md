# Persistent CI signing for TurboFetch APKs

Android only accepts an APK as an update when its application ID and signing identity are compatible with the installed app. A new GitHub-hosted runner may not have the same default Android debug keystore as the previous runner. The distributable APK therefore needs a persistent signing key.

## One-time setup

Create a dedicated keystore on a trusted computer. Keep the original keystore and passwords in a safe backup; do not commit the keystore or its passwords to this public repository.

```sh
keytool -genkeypair -v \
  -keystore turbofetch-debug.jks \
  -storetype JKS \
  -alias turbofetch-debug \
  -keyalg RSA -keysize 2048 -validity 10000
```

When prompted, choose a strong keystore password and key password. Record the alias as `turbofetch-debug`. To create the one-line Base64 value for GitHub, run this from the directory containing the keystore:

```sh
base64 -w 0 turbofetch-debug.jks
```

On GitHub, open **5ydr01d/TurboFetch → Settings → Secrets and variables → Actions → New repository secret**, and add these four repository secrets:

| Secret name | Value |
|---|---|
| `TURBOFETCH_DEBUG_KEYSTORE_BASE64` | The complete one-line output from the Base64 command |
| `TURBOFETCH_DEBUG_STORE_PASSWORD` | The keystore password |
| `TURBOFETCH_DEBUG_KEY_ALIAS` | `turbofetch-debug` |
| `TURBOFETCH_DEBUG_KEY_PASSWORD` | The key password |

Do not paste the keystore or passwords into an issue, pull request, chat, or source file. The workflow deliberately fails a main-branch/dispatched distributable build if these secrets are missing, rather than uploading an APK signed with a temporary runner key. Pull requests still run tests and a debug build, but do not publish an APK artifact.

## Important compatibility warning

This establishes a stable signing identity for builds **after it is configured**. It does not recover or reproduce the signing key used by an APK already installed on a device. If the installed app was signed with a different key, Android will still reject the new APK as an update. To preserve the app's local data, first try using the exact keystore that signed the installed build; if that key is unavailable, an uninstall may be required and can erase app data.

The workflow prints the APK certificate digest in the Actions log. Check that the digest stays identical across successful main-branch builds.
