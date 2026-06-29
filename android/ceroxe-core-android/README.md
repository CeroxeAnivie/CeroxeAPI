# ceroxe-core-android

Standalone Android library packaging for the Ceroxe shared core utilities.

## Structure

- `../../ceroxe-core-shared/src/main/java`: shared cross-platform implementation sources, including the Java 17 platform-thread `TaskManager`
- `src/main/java`: Android-only sources, currently the Android `ThreadManager`
- `src/test/java`: Android local unit tests
- `src/androidTest/java`: instrumentation checks for Android runtime contracts

## Build

Windows:

```cmd
chcp 65001 >nul
gradlew.bat help
gradlew.bat test
```

## Maven Central

Coordinate:

```xml
<dependency>
    <groupId>top.ceroxe.api</groupId>
    <artifactId>ceroxe-core-android</artifactId>
    <version>2.0.2</version>
</dependency>
```

Gradle Kotlin DSL:

```kotlin
dependencies {
    implementation("top.ceroxe.api:ceroxe-core-android:2.0.2")
}
```

Gradle Groovy DSL:

```groovy
dependencies {
    implementation 'top.ceroxe.api:ceroxe-core-android:2.0.2'
}
```

If the artifact does not appear immediately in Maven Central search, wait for Central Portal indexing to finish.

## Android SDK

This project is a real Android library build and needs a local Android SDK.

Use one of the following:

- set `ANDROID_HOME`
- set `ANDROID_SDK_ROOT`
- create `local.properties` with `sdk.dir=<absolute-sdk-path>`

## Runtime Contract

- `minSdk = 33`
- `SecureSocket` requires Android platform support for `X25519/XDH`

This constraint is intentional. The library does not advertise compatibility below the API level that can actually run the negotiated key exchange.
