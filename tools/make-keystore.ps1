# Creates the stable signing key used by CI. Keep the resulting file and passwords safe.
keytool -genkeypair -v -keystore comicreader-release.jks -alias comicreader `
  -keyalg RSA -keysize 4096 -validity 36500
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("comicreader-release.jks"))
$b64 | Set-Clipboard
Write-Host "Base64 copied to the clipboard: paste it as the KEYSTORE_BASE64 secret."
