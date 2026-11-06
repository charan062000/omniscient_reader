# Release signing

Release builds never fall back to the Android debug key. When no release signing values are supplied, Gradle can build an unsigned release APK/AAB for verification. Configure a private release key before distributing an artifact.

## Create a keystore locally

Run this in PowerShell, choosing a secure location outside the repository:

```powershell
keytool -genkeypair -v `
  -keystore "$env:USERPROFILE\keys\omniscient-reader-release.jks" `
  -alias omniscient-reader `
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and passwords in a secure password manager and make an offline backup. Losing the key prevents publishing updates under the same application identity.

## Configure Gradle locally

Create the ignored `release-keystore.properties` file in the repository root:

```properties
storeFile=C:/Users/your-user/keys/omniscient-reader-release.jks
storePassword=your-keystore-password
keyAlias=omniscient-reader
keyPassword=your-key-password
```

The file is git-ignored. Never commit it or the keystore. Alternatively, set all four environment variables `OMNISCIENT_RELEASE_STORE_FILE`, `OMNISCIENT_RELEASE_STORE_PASSWORD`, `OMNISCIENT_RELEASE_KEY_ALIAS`, and `OMNISCIENT_RELEASE_KEY_PASSWORD` in the build environment. Gradle rejects partial configuration.

Build artifacts with `:app:assembleRelease` and `:app:bundleRelease`. Invoke the tasks separately when producing both formats so APK ABI splits remain enabled for the APK and disabled only for the AAB task.
