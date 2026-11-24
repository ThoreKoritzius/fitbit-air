# Releasing Air Relay

Air Relay ships as a signed APK on GitHub releases and as an App Bundle on
Google Play. Both use the app ID `dev.airpulse.airrelay`.

## One-time: create the signing key

Android only installs an update when it is signed with the same key as the
installed app. Create the key once and back it up (with its passwords) in at
least two safe places. If it is lost, GitHub users can never update again.

```sh
keytool -genkeypair -v -keystore ~/air-relay-release.jks \
  -alias air-relay -keyalg RSA -keysize 4096 -validity 10000
```

Never commit the keystore or its passwords; `*.jks` and `key.properties` are
gitignored.

### Local builds

Create `example/android/key.properties`:

```properties
storeFile=/Users/you/air-relay-release.jks
storePassword=...
keyAlias=air-relay
keyPassword=...
```

Without it, release builds fall back to the debug key and must not be
distributed.

### GitHub Actions secrets

Add these repository secrets:

| Secret | Value |
|---|---|
| `AIR_RELAY_KEYSTORE_BASE64` | Output of `base64 -i ~/air-relay-release.jks` |
| `AIR_RELAY_KEYSTORE_PASSWORD` | The keystore password |
| `AIR_RELAY_KEY_ALIAS` | `air-relay` |
| `AIR_RELAY_KEY_PASSWORD` | The key password |

## Google Play signing

Play re-signs apps with its own app signing key by default. The Play version
and the GitHub APK would then have different signatures, and users could not
switch between them without uninstalling.

To make both installs interchangeable, choose **Use my own key** when setting
up Play App Signing and upload this same key with Play's PEPK tool. The
trade-off is that you alone are responsible for the key: Play cannot recover
it.

## Each release

1. Update the version in `pubspec.yaml` and `example/pubspec.yaml`.
   - Bump the `+build` number every time; Play and Android need a higher one.
   - Add release notes under the version number in `CHANGELOG.md`. Omit an
     `Unreleased` section.
2. Merge to `main`, tag `vX.Y.Z` and push the tag.
3. The **Release Air Relay** workflow then:
   - builds the APK and the App Bundle;
   - refuses debug-signed output;
   - creates the GitHub release with the APK and its SHA-256 checksum;
   - uploads the bundle as a workflow artifact.
   Without the `AIR_RELAY_*` secrets the workflow skips the build (it shows a
   notice, not a failure). Then build locally with `key.properties`:
   `flutter build apk --release` and
   `flutter build appbundle --release --dart-define=AIR_RELAY_STORE=true`
   in `example/`, check both with `apksigner verify --print-certs` and
   `keytool -printcert -jarfile`, and attach the APK and its `.sha256` to the
   GitHub release yourself.
4. Upload the `.aab` to Play: closed testing first, then production.
5. Publish the package with `dart pub publish` from a clean checkout.
