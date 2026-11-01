# Offline Talks 1.2.0-alpha — Offline Android project

This is an updated source package based on the supplied Offline Talks v1.1 project.

## Build APK with GitHub Actions

1. Extract this ZIP.
2. Upload the **contents** of this folder to the root of your GitHub repository (including the `.github` folder).
3. Commit to the `main` branch.
4. Open **Actions** in GitHub and open **Build Offline Talks APK**.
5. When the run succeeds, download the `OfflineTalks-debug-apk` artifact and extract `app-debug.apk`.

The workflow uses Java 17, Gradle 8.9, Android platform 35, and explicitly installs `platform-tools` to avoid the retired Android SDK `tools` package.

## Important current implementation status

The included app source is the supplied v1.1 Bluetooth Low Energy prototype with a version bump and updated CI workflow. It is **not yet** the completed Offline Talks 2.0 feature set.

The current app still has the original BLE single-packet text transport and its short-message limit. Do not use this alpha as proof that long-message transfer, synchronized delete-for-everyone, voice notes, image/file transfer, or voice calls are implemented.

## Planned Offline Talks 2.0 architecture

- Bluetooth Low Energy: nearby device discovery.
- Wi-Fi Direct: local peer-to-peer data channel; no internet, mobile data, router, or cloud server.
- Reliable framed transfer: message IDs, chunking, reassembly, acknowledgements, retries, and integrity checks.
- Local media storage: voice notes, images, and documents.
- Voice calls: real-time audio over a local peer-to-peer connection.

These features require implementation and two-device testing before they can be described as working. Wi-Fi Direct availability and behavior vary by Android device.
