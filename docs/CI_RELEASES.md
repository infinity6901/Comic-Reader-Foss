# CI builds and FOSS releases

The GitHub Actions workflow runs unit tests and lint, builds debug and release APKs, uploads them as workflow artifacts, and publishes them when a `v*` tag is pushed.

Builds are signed with one stable key stored in repository secrets, so every APK installs over the previous one. Set it up once following [SIGNING.md](SIGNING.md). Without the secrets (for example pull requests from forks) the build falls back to Gradle's per-machine debug key, and tag builds fail on purpose.

For long-term public distribution where users should install updates over existing versions, configure a stable private release key and protect it securely.
