"""Build locally with Python 3.9+, a JDK and Android SDK; no Gradle or device access.

Unsigned by default. Optional signing uses external files, never copies keys or
reads password files into Python. No installation, publishing or uploading.
"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent
API_URL = 'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar'
API_SHA256 = 'f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25'
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dex_classes(data):
    """List DEX definitions, excluding references to compile-only API classes."""
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]
    strings, types = u32(60), u32(68)
    result = []
    for index in range(u32(96)):
        type_index = u32(u32(100) + index * 32)
        offset = u32(strings + u32(types + type_index * 4) * 4)
        while data[offset] & 128:
            offset += 1
        offset += 1
        result.append(data[offset:data.index(b'\0', offset)].decode('utf-8'))
    return result


def tool(folder, name, batch=False):
    suffix = ('.bat' if batch else '.exe') if os.name == 'nt' else ''
    path = folder / (name + suffix)
    require(path.is_file(), 'Required tool missing: ' + str(path))
    return path


def run(stage, name, args, sensitive=False):
    # Never print commands; signing arguments may reference private local files.
    result = subprocess.run([str(arg) for arg in args], capture_output=True,
                            text=True, encoding='utf-8', errors='replace')
    if not sensitive:
        (stage / (name + '.txt')).write_text(result.stdout + result.stderr, encoding='utf-8')
    require(result.returncode == 0, name + ' failed' +
            (' (signer output suppressed)' if sensitive else '; see build log ' + name + '.txt'))
    print(name + ': OK')
    return result.stdout


def signer_digest(output):
    match = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})', output)
    require(match is not None, 'No signing certificate in verification output')
    return match.group(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, help='Android SDK root; defaults to ANDROID_HOME/ANDROID_SDK_ROOT')
    parser.add_argument('--jdk', type=Path, help='JDK root; defaults to JAVA_HOME or javac on PATH')
    parser.add_argument('--build-tools', default='36.1.0')
    parser.add_argument('--platform', default='35')
    parser.add_argument('--xposed-api', type=Path, help='Existing Xposed API 82 JAR (checksum verified)')
    parser.add_argument('--fetch-api', action='store_true', help='Allow download of the pinned API into build/deps')
    parser.add_argument('--diagnostic', action='store_true', help='Include verbose loader and CALL/RETURN logs')
    parser.add_argument('--keystore', type=Path, help='Optional external signing key; unsigned when omitted')
    parser.add_argument('--ks-alias', help='Signing alias')
    parser.add_argument('--ks-pass-file', type=Path, help='External password file passed directly to apksigner')
    parser.add_argument('--key-pass-file', type=Path, help='Optional separate key password file')
    parser.add_argument('--previous-apk', type=Path, help='Optional check that signing certificate is unchanged')
    args = parser.parse_args()
    signing = bool(args.keystore)
    if signing and (not args.ks_alias or not args.ks_pass_file):
        parser.error('--keystore requires --ks-alias and --ks-pass-file')
    if not signing and any([args.ks_alias, args.ks_pass_file, args.key_pass_file, args.previous_apk]):
        parser.error('Signing options require --keystore')
    sdk = args.sdk or os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    require(sdk, 'Pass --sdk or configure ANDROID_HOME')
    sdk = Path(sdk).resolve()
    jdk = args.jdk or os.environ.get('JAVA_HOME')
    if not jdk:
        executable = shutil.which('javac')
        require(executable, 'Pass --jdk or configure JAVA_HOME')
        jdk = Path(executable).resolve().parent.parent
    jdk_bin = Path(jdk).resolve() / 'bin'
    build_tools = sdk / 'build-tools' / args.build_tools
    android = sdk / 'platforms' / ('android-' + args.platform) / 'android.jar'
    require(android.is_file(), 'Install Android SDK platform ' + args.platform)
    java, javac, jar = [tool(jdk_bin, name) for name in ('java', 'javac', 'jar')]
    d8 = tool(build_tools, 'd8', batch=True)
    aapt2, aapt, zipalign = [tool(build_tools, name) for name in ('aapt2', 'aapt', 'zipalign')]
    api = args.xposed_api or ROOT / 'build/deps/xposed-api-82.jar'
    api = api.resolve()
    if not api.is_file():
        require(args.fetch_api and args.xposed_api is None,
                'Pass --xposed-api with API 82, or --fetch-api to download it')
        with urllib.request.urlopen(API_URL, timeout=30) as response:
            data = response.read(4 * 1024 * 1024)
        require(hashlib.sha256(data).hexdigest() == API_SHA256, 'Downloaded Xposed API checksum mismatch')
        api.parent.mkdir(parents=True, exist_ok=True)
        api.write_bytes(data)
    require(digest(api) == API_SHA256, 'Xposed API 82 checksum mismatch')

    manifest_root = ET.parse(ROOT / 'AndroidManifest.xml').getroot()
    package = manifest_root.attrib['package']
    version = manifest_root.attrib[ANDROID_NS + 'versionName']
    code = manifest_root.attrib[ANDROID_NS + 'versionCode']
    require(re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', version), 'Unexpected versionName format')
    variant = 'diagnostic' if args.diagnostic else 'normal'
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    stage = ROOT / 'build' / (stamp + '-' + variant)
    destination = ROOT / 'dist' / (stamp + '-' + variant)
    for name in ('classes', 'tests', 'dex', 'generated'):
        (stage / name).mkdir(parents=True, exist_ok=True)
    generated = stage / 'generated/BuildConfig.java'
    generated.write_text('package ru.randgor.wearenginebridge;\n'
                         'public final class BuildConfig {\n'
                         ' public static final boolean DEBUG = ' + str(args.diagnostic).lower() + ';\n'
                         ' public static final String VERSION = "' + version + '";\n}\n', encoding='utf-8')
    classpath = os.pathsep.join([str(android), str(api)])
    run(stage, 'javac', [javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                        '-cp', classpath, '-d', stage / 'classes', generated,
                        *sorted((ROOT / 'src').rglob('*.java'))])
    run(stage, 'javac-tests', [javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                              '-cp', stage / 'classes', '-d', stage / 'tests',
                              *sorted((ROOT / 'tests').glob('*.java'))])
    checks = []
    for name in ('AccessPolicyTest', 'ClassHookRegistryTest'):
        checks.append(run(stage, name, [java, '-cp', os.pathsep.join(
            [str(stage / 'tests'), str(stage / 'classes')]), 'ru.randgor.wearenginebridge.' + name]).strip())
    run(stage, 'jar', [jar, 'cf', stage / 'classes.jar', '-C', stage / 'classes', '.'])
    run(stage, 'd8', [d8, '--min-api', '26', '--lib', android, '--classpath', api,
                     '--output', stage / 'dex', stage / 'classes.jar'])
    run(stage, 'resources', [aapt2, 'compile', '--dir', ROOT / 'res', '-o', stage / 'resources.zip'])
    unsigned = stage / 'unsigned.apk'
    run(stage, 'package', [aapt2, 'link', '-I', android, '--manifest', ROOT / 'AndroidManifest.xml',
                          '-A', ROOT / 'assets', '-o', unsigned, stage / 'resources.zip'])
    dex = (stage / 'dex/classes.dex').read_bytes()
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('classes.dex', dex)
    aligned = stage / 'aligned.apk'
    run(stage, 'zipalign', [zipalign, '-P', '16', '-f', '4', unsigned, aligned])
    destination.mkdir(parents=True)
    apk = destination / ('wearengine-bridge-' + version + '-' + variant +
                         ('-signed.apk' if signing else '-unsigned.apk'))
    signature = None
    cert = None
    if signing:
        signer = tool(build_tools, 'apksigner', batch=True)
        previous = None
        if args.previous_apk:
            previous = signer_digest(run(stage, 'previous-signature',
                [signer, 'verify', '--verbose', '--print-certs', args.previous_apk.resolve()]))
        options = [signer, 'sign', '--ks', args.keystore.resolve(), '--ks-key-alias', args.ks_alias,
                   '--ks-pass', 'file:' + str(args.ks_pass_file.resolve()),
                   '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
                   '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false']
        if args.key_pass_file:
            options += ['--key-pass', 'file:' + str(args.key_pass_file.resolve())]
        run(stage, 'sign', [*options, '--out', apk, aligned], sensitive=True)
        signature = run(stage, 'signature', [signer, 'verify', '--verbose', '--print-certs', apk])
        cert = signer_digest(signature)
        require(previous is None or previous == cert, 'Signing certificate differs from previous APK')
    else:
        shutil.copyfile(aligned, apk)
    run(stage, 'alignment', [zipalign, '-c', '-P', '16', '-v', '4', apk])
    badging = run(stage, 'badging', [aapt, 'dump', 'badging', apk])
    require("name='" + package + "' versionCode='" + code + "' versionName='" + version + "'" in badging,
            'APK identity mismatch')
    require("sdkVersion:'26'" in badging and "targetSdkVersion:'35'" in badging, 'SDK mismatch')
    manifest = run(stage, 'manifest', [aapt2, 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml'])
    require(not any(flag in manifest for flag in ('isSplitRequired', 'requiredSplitTypes', 'splitTypes',
                'com.android.vending.splits', 'uses-permission')), 'Unexpected split requirement or permission')
    with zipfile.ZipFile(apk) as archive:
        require(archive.testzip() is None, 'ZIP CRC failure')
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        require([n for n in names if n.endswith('.dex')] == ['classes.dex'], 'Unexpected DEX inventory')
        require(archive.read('classes.dex') == dex, 'Packaged DEX mismatch')
        require(not any(n.endswith(('.so', '.jar')) for n in names), 'Unexpected bundled library')
        require(archive.read('assets/xposed_init').decode().strip() == package + '.WearEngineHook', 'Entry point mismatch')
    definitions = dex_classes(dex)
    require(definitions and all(n.startswith('Lru/randgor/wearenginebridge/') for n in definitions),
            'Foreign/API class definitions bundled')
    require(b'HEARTBEAT' not in dex and b'java/util/Timer' not in dex, 'Heartbeat unexpectedly present')
    require((b'CALL #' in dex) == args.diagnostic, 'Diagnostic build flag mismatch')
    require(not any(n in dex for n in (b'com.huawei.deveco.assistant', b'getPackagesForUid',
                b'getApkContentsSigners', b'MessageDigest', b'isTrustedClient')), 'Unexpected client filter')
    report = {'apk': apk.name, 'version': version, 'versionCode': int(code), 'variant': variant,
              'signed': signing, 'sha256': digest(apk), 'size': apk.stat().st_size,
              'certificate_sha256': cert, 'signature_verification': signature,
              'dex_sha256': hashlib.sha256(dex).hexdigest(), 'defined_classes': definitions,
              'tests': checks, 'runtime_tested': False,
              'requires': 'Compatible Vector/LSPosed; scope com.huawei.health'}
    (destination / 'build-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('APK: ' + str(apk))
    print('SHA-256: ' + report['sha256'])


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        raise SystemExit('Build failed: ' + str(error)) from None
