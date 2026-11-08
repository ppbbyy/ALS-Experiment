"""Check Android sources; package a debug APK only with explicit --apk."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import ctypes
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.build-tools'
TOOLS.mkdir(exist_ok=True)


def fetch(url, target):
    if target.exists():
        return
    print('Downloading', target.name, flush=True)
    try:
        response = urllib.request.urlopen(url, timeout=90)
    except Exception:
        if target.name != 'gradle89.zip':
            raise
        request = urllib.request.Request(
            'https://api.github.com/repos/gradle/gradle-distributions/releases/assets/178998845',
            headers={'Accept': 'application/octet-stream', 'User-Agent': 'ALS-Experiment-build-check'})
        response = urllib.request.urlopen(request, timeout=90)
    with response, target.with_suffix('.part').open('wb') as out:
        while block := response.read(1024 * 1024):
            out.write(block)
    target.with_suffix('.part').replace(target)


def unpack(url, name, folder, checksum=None):
    target = TOOLS / name
    fetch(url, target)
    if checksum and hashlib.sha256(target.read_bytes()).hexdigest() != checksum:
        raise RuntimeError('Checksum mismatch: ' + name)
    if not folder.exists():
        print('Extracting', name, flush=True)
        with zipfile.ZipFile(target) as archive:
            archive.extractall(folder)


def main(build_apk=False):
    gradle_sha = urllib.request.urlopen('https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256', timeout=60).read().decode().strip()
    jobs = [
        ('https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip', 'jdk17.zip', TOOLS / 'jdk', None),
        ('https://services.gradle.org/distributions/gradle-8.9-bin.zip', 'gradle89.zip', TOOLS / 'gradle', gradle_sha),
        ('https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip', 'android-cli.zip', TOOLS / 'android-cli', None),
    ]
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(unpack, *job) for job in jobs]
        for future in futures:
            future.result()
    # Some Windows Java/native tools cannot read worker argfiles with non-ASCII paths.
    # A temporary drive alias keeps all build paths ASCII without moving the project.
    drive = None
    build_root = ROOT
    if not str(ROOT).isascii():
        drive_mask = ctypes.windll.kernel32.GetLogicalDrives()
        drive = next(f'{letter}:' for letter in 'RSTUVWXYZ'
                     if not (drive_mask & (1 << (ord(letter) - ord('A')))))
        subprocess.run(['subst', drive, str(ROOT)], check=True)
        build_root = Path(drive + '\\')
    try:
        run_checks(build_root, build_apk=build_apk)
    finally:
        write_sdk_property(TOOLS / 'sdk')
        if drive:
            subprocess.run(['subst', drive, '/D'], check=True)
    if build_apk:
        print('Debug APK: ' + str(ROOT / 'app/build/outputs/apk/debug/app-debug.apk'), flush=True)


def write_sdk_property(sdk):
    sdk_property = ''.join(c if ord(c) < 128 else f'\\u{ord(c):04x}' for c in sdk.as_posix())
    (ROOT / 'local.properties').write_text('sdk.dir=' + sdk_property + '\n', encoding='ascii')


def run_checks(build_root, build_apk=False):
    build_tools = build_root / '.build-tools'
    jdk = next((build_tools / 'jdk').glob('*/bin/java.exe')).parents[1]
    sdk = build_tools / 'sdk'
    env = os.environ.copy()
    env.update(JAVA_HOME=str(jdk), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
               GRADLE_USER_HOME=str(build_tools / 'gradle-home'))
    env['PATH'] = str(jdk / 'bin') + os.pathsep + env['PATH']
    sdkmanager = build_tools / 'android-cli/cmdline-tools/bin/sdkmanager.bat'
    print('Preparing Android SDK (license prompts included in log)', flush=True)
    result = subprocess.run([str(sdkmanager), '--sdk_root=' + str(sdk),
        'platforms;android-35', 'build-tools;34.0.0', 'platform-tools'],
        cwd=build_root, env=env, input='y\n' * 30, text=True)
    result.check_returncode()
    write_sdk_property(sdk)
    gradle = build_tools / 'gradle/gradle-8.9/bin/gradle.bat'
    commands = [':app:compileDebugKotlin', ':app:testDebugUnitTest', ':app:lintDebug',
                '--console=plain', '--no-daemon', '-Pandroid.overridePathCheck=true']
    if build_apk:
        commands.insert(3, ':app:assembleDebug')
    print('Running compile, unit tests and lint' +
          (' and packaging a debug APK' if build_apk else '; no assemble/package task'), flush=True)
    subprocess.run([str(gradle), *commands], cwd=build_root, env=env, check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Check Android code; optionally build a test APK.')
    parser.add_argument('--apk', action='store_true', help='Also build app-debug.apk after checks.')
    main(build_apk=parser.parse_args().apk)
