# WearEngine Bridge

English | [Русский](README.ru.md)

A small LSPosed/Vector module for developers working with Huawei Lite Wearable devices.

## The problem it solves

A custom-signed DevEco Assistant build or your own WearEngine client can fail to
retrieve connected watches with this error:

```text
Scope unauthorized
```

Huawei Health checks the calling client's access to WearEngine. A modified client
signed with your own key can be rejected at this check, preventing it from reaching
the watch even when the watch is paired with Health.

WearEngine Bridge runs inside Huawei Health and allows requests for WearEngine's
device-manager scope regardless of the client's package name or signing certificate.
This lets developers use modified DevEco Assistant builds or their own clients as
part of a local watch-app development workflow.

The module addresses this specific access check. It does not install HAP files itself:
transferring and installing an app remains the client's responsibility. Watch-side
installation checks and separate Health consent are unchanged.

## Compatibility and current status

| Component | Requirement or status |
| --- | --- |
| Reference Huawei Health version | **17.0.8.300**, versionCode **1700008300**; other versions are attempted but untested |
| Framework | LSPosed/Vector with legacy Xposed API 82 support |
| Preliminary device testing | Android 15, Magisk 31, Vector/LSPosed |
| APK minimum Android version | API 26; this does not imply testing on every supported Android version |
| Module | 0.1, versionCode 1; package `ru.randgor.wearenginebridge` |

**Health version numbers do not block activation.** On a version other than the
reference version, the module logs `WARN untested Health` and attempts the same hook.
It still requires the exact target class and an instance method with the expected
arguments and boolean return type. If that contract does not match, it leaves the
scope check unchanged and logs the failure. Matching the method signature alone
does not establish compatibility with an untested version's internal behavior.

The hook worked in a preliminary build with Frida disconnected. Version 0.1 keeps
that scope decision, removes periodic heartbeat logging, makes detailed logging
optional, and attempts hooks on untested Health versions with a warning. The developer confirmed successful build, installation and operation of the signed
0.1 APK on their phone. Other Health versions remain untested. HAP installation
through a client has not been separately confirmed.

## Installation

1. Install a **signed** module APK using Android's package installer.
2. Enable WearEngine Bridge in Vector/LSPosed and select **Huawei Health
   (`com.huawei.health`)** as its scope. Do not select the client apps.
3. Keep Huawei Health out of an enforced Magisk DenyList.
4. Force-stop Huawei Health through Android settings, then open Health and your
   WearEngine client, such as DevEco Assistant.
5. Check that the client can retrieve the connected watch. Disable Frida and any
   other implementation of this bypass while testing.

There is no launcher activity or configuration screen. The APK requests no additional
permissions. To undo the change, disable the module and restart Health; clearing
Health's data is unnecessary.

Updates require the same signing key. If you installed a preliminary module with a
higher versionCode, uninstall that module before installing 0.1. **Do not uninstall
Huawei Health.** Normal and diagnostic builds share the same package and versionCode.

## What it changes

Inside Health's `:DaemonService` and `:PhoneService` processes, the module hooks:

```java
boolean com.huawei.wearengine.scope.ScopeManager.checkScopeAvailability(
    String scope, int clientPid, int clientUid, String channel)
```

For `scope = com.huawei.hiwear.devicemanager` and `channel = wearEngine`, it returns
`true` without calling the original method. All client packages and certificates are
accepted for that combination. Other scope/channel combinations retain their original
behavior. This bypasses the selected scope gate, not just its certificate comparison.

Separate Health consent and watch-side installation checks are not changed. Huawei
APKs are not modified or distributed by this project. This is an independent project,
not affiliated with Huawei.

Health loads plugins through additional class loaders. Each distinct target `Class`
is hooked once, including classes loaded later. Hooks apply to all instances of that
class. The module observes class-loading events; it does not poll or use delayed retries.

## Logs and troubleshooting

Messages are written both to the Vector/LSPosed module log and to Android Logcat.
They are not shown as notifications or inside DevEco Assistant.

**On the phone:** open the Vector/LSPosed manager, open its **Logs** section
(the label may vary by version or language), and look for `WearEngineBridge`
in the module log. If there is no search field, export the log and search the
saved text.

**In Android Studio:** open **View > Tool Windows > Logcat**, select the connected
phone, and enter `tag:WearEngineBridge` in the filter field. Include **Info** level
messages. Remove `package:mine` or any filter restricted to the module or DevEco
Assistant: the messages come from Huawei Health's `:DaemonService` and
`:PhoneService` processes.

To capture a fresh hook installation, start viewing the log, force-stop Huawei
Health through Android settings, then reopen Health and the WearEngine client and
repeat the request. Messages are event-driven; there is no periodic heartbeat.
`READY`, `CLASS_INACTIVE` and `CLASS_HOOK_FAILED` are available in normal builds;
a diagnostic build is only needed for additional class-loader and per-call details.

Normal builds log `READY` and errors, without heartbeat or per-call logs. Diagnostic
builds additionally log class loaders and `CALL` / `RETURN` events.

- `READY`: the target hook was installed; still verify the client's actual result.
- `WARN untested Health`: Health differs from the reference version; hook installation is still attempted.
- `CLASS_INACTIVE` or `CLASS_HOOK_FAILED`: the target method could not be hooked.

Report problems through [GitHub Issues](https://github.com/RandGor/wearengine-bridge/issues).
Include Health version, Android and framework versions, module variant, reproduction
steps and relevant module logs. Remove personal information before posting logs.

## Local build

Requirements: Python 3.9+, a JDK with `javac`/`jar`, Android SDK Platform 35 and
Build Tools 36.1.0. Builds have passed on Windows with Python 3.11/JDK 8 and on
Ubuntu 24.04 with Python 3.11/JDK 17. macOS is untested.

No Gradle is required. You can open the folder in Android Studio and run the Python
builder in its terminal; it is not a Gradle project with an Assemble task.

```sh
python build.py --fetch-api --sdk "PATH_TO_ANDROID_SDK" --jdk "PATH_TO_JDK"
```

`--sdk` and `--jdk` can be omitted when `ANDROID_HOME` / `ANDROID_SDK_ROOT` and
`JAVA_HOME` are configured; the builder also checks `javac` on PATH.

`--fetch-api` downloads [Xposed API 82](https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar)
if missing and verifies its pinned SHA-256:
`f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`.
For offline builds, supply `--xposed-api PATH_TO_API_82.jar` instead. The API is a
compile-only dependency and is not bundled in the APK. A Huawei Health APK is not
needed to compile this project.

The default output is **unsigned**. To produce an installable APK, use an external key:

```sh
python build.py --fetch-api --keystore "PATH_TO_KEY.p12" --ks-alias "ALIAS" --ks-pass-file "PATH_TO_PASSWORD_FILE"
```

Add `--diagnostic` for detailed hook logs. Supply SDK/JDK paths as above if needed.
`--key-pass-file` supports a separate key password; `--previous-apk PATH_TO_APK`
checks that an update uses the same certificate. Password files are passed directly
to apksigner; the local Python builder does not read their contents or copy the key.
Signing uses v2/v3 with v1 explicitly disabled (minSdk 26).

The APK and `build-report.json` are written to `dist/<timestamp>-<variant>/`.
Intermediates and logs stay in `build/`; old builds are preserved. Signed filenames
end in `-signed.apk`, unsigned ones in `-unsigned.apk`. Version and filenames come
from the manifest. Move signing material separately when using another computer;
it is not included in the repository.

The build runs 18 policy checks and 14 class-identity/lifecycle/concurrency checks,
then checks manifest, ZIP integrity, DEX contents, alignment and logging variant.
For signed builds it also verifies the APK signature. These checks do not replace
an on-device test. Identical source and options do not guarantee byte-identical APKs:
ZIP timestamps and toolchain versions may differ between machines.

## Source and license

[Source code](https://github.com/RandGor/wearengine-bridge) ·
[Changelog](CHANGELOG.md) · [MIT license](LICENSE)

The repository contains only this module's source, tests, build tooling and documentation.
Huawei APKs, decompiled code, signing keys and historical device logs are not included.
