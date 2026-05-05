# Ceroxe Android Architecture

The repository now separates cross-platform utility code from platform packaging:

- `ceroxe-core-shared`: shared Java 17 sources for `AESUtil`, `SecureSocket`, `SecureServerSocket`, and `Sleeper`
- `ceroxe-core`: JVM-focused core module that depends on `ceroxe-core-shared` and keeps Java 21-specific utilities such as the JVM `ThreadManager`
- `android/ceroxe-core-android`: standalone Android Gradle library that reuses `ceroxe-core-shared` sources and provides the Android `ThreadManager`

## Why This Split Exists

- shared protocol and crypto logic now has a single source of truth
- Maven and Android builds can evolve independently
- Android packaging is no longer coupled to the parent Maven reactor
- JVM-only concurrency utilities stay on the JVM side instead of leaking into Android packaging

## Android Runtime Contract

- `ceroxe-core-android` currently requires `minSdk 33`
- reason: `SecureSocket` relies on `X25519/XDH`, and the Android platform crypto APIs expose that capability from API 33 onward

This contract is intentional. The Android library does not claim support below the platform level that can actually execute the negotiated key exchange.
