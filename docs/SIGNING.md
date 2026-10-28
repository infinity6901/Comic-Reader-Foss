# Signing every build with the same key

Android only lets an app update an installed copy when both are signed with the same key. The
workflow therefore signs the debug and release APKs with one key that you create once and keep
in GitHub repository secrets. Without those secrets (pull requests from forks, or before you
set them up) the build falls back to Gradle's debug key, which is different on every machine.

## One-time setup

1. Create the key (needs a JDK, which Android Studio includes). Pick your own passwords and keep
   the `.jks` file somewhere safe — if you lose it you can never update installed copies:

   ```
   keytool -genkeypair -v -keystore comicreader-release.jks -alias comicreader \
     -keyalg RSA -keysize 4096 -validity 36500
   ```

   (`tools/make-keystore.ps1` / `tools/make-keystore.sh` run the same command.)

2. Turn the file into one line of base64:

   - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("comicreader-release.jks")) | Set-Clipboard`
   - Linux/macOS: `base64 -w0 comicreader-release.jks`

3. In the GitHub repository open **Settings → Secrets and variables → Actions → New repository
   secret** and add four secrets:

   | Name | Value |
   |---|---|
   | `KEYSTORE_BASE64` | the base64 text from step 2 |
   | `KEYSTORE_PASSWORD` | the keystore password you chose |
   | `KEY_ALIAS` | `comicreader` (the alias from step 1) |
   | `KEY_PASSWORD` | the key password (same as the keystore password if you pressed Enter) |

4. Run the workflow. The log's **Show signing certificate** step prints the certificate SHA-256;
   it should be identical on every later build.

Never commit the `.jks` file or the passwords. Tag builds (`v*`) fail on purpose when the secrets
are missing, so a release can never go out with a random key.

The first build signed with this key cannot be installed over an app signed with any other key
(including earlier CI builds): uninstall that copy once, export a backup from the app first.

## Building locally with the same key

Set `KEYSTORE_FILE` (path to the `.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` in
the environment before running `./gradlew assembleRelease`.
