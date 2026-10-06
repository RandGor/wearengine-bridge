# Changelog

## 0.1 — Unreleased

Initial public version, versionCode 1. The developer confirmed successful build,
installation and operation of the signed APK on their phone on 2026-10-06.

- Allow any client package and signing certificate for Huawei Health's
  `com.huawei.hiwear.devicemanager` scope on the `wearEngine` channel.
- Observe plugin class loading in Health's DaemonService and PhoneService processes.
  Hook each distinct ScopeManager class once, including classes loaded later.
- Preserve original behavior for other scope/channel combinations.
- Use Huawei Health versionCode 1700008300 (17.0.8.300) as the reference version.
  Warn on other versions and attempt the hook instead of blocking activation.
  Keep exact target class and method signature checks; untested versions are not
  claimed to be compatible.
- Provide normal and diagnostic builds. Normal builds retain readiness/error logs
  without a periodic heartbeat; diagnostic builds include class and call details.
- Include a Python build script, 32 JVM checks, APK validation, optional local signing,
  a manual GitHub Actions build, and signed normal builds with draft releases on tag pushes.

The preliminary hook was tested on Android 15 with Magisk 31 and Vector/LSPosed.
HAP installation through a client remains unverified. The module does not transfer
or install HAP files by itself.
