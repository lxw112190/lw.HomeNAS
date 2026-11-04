"""Run connected tests, reinstall the app after UTP cleanup, then capture evidence.
Executed on the CI emulator only; no Android SDK is required to run the host-side tests.
"""
from pathlib import Path
import json
import re
import subprocess
import sys


def command(argv, *, binary=False):
    print('+', ' '.join(str(x) for x in argv), flush=True)
    return subprocess.run([str(x) for x in argv], capture_output=True,
                          text=not binary, **({} if binary else {'encoding': 'utf-8', 'errors': 'replace'}))


def component(output, package):
    for line in reversed(output.splitlines()):
        value = line.strip()
        if re.fullmatch(re.escape(package) + r'/[A-Za-z0-9_.$]+', value):
            return value
    raise RuntimeError('Package manager could not resolve the installed launcher activity')


def execute(root, runner=command):
    root = Path(root)
    evidence = root / 'android/app/build/outputs/device-evidence'
    evidence.mkdir(parents=True, exist_ok=True)
    package = 'com.lxw.homenas'
    failure = 0

    def saved(name, argv, *, required=False, binary=False):
        result = runner(argv, binary=binary)
        if binary:
            if result.returncode == 0:
                (evidence / name).write_bytes(result.stdout)
        else:
            content = result.stdout + '\n' + result.stderr
            (evidence / name).write_text(content[-2_000_000:], encoding='utf-8')
            print(content[-20000:], flush=True)
        if required and result.returncode:
            raise RuntimeError(f'{name}: command exited {result.returncode}')
        return result

    try:
        tested = saved('connected-tests.txt', ['gradle', '-p', str(root / 'android'), '--no-daemon', ':app:connectedDebugAndroidTest'])
        failure = tested.returncode
        if failure:
            return failure
        folder = root / 'android/app/build/outputs/apk/debug'
        metadata = json.loads((folder / 'output-metadata.json').read_text(encoding='utf-8'))
        package = metadata['applicationId']
        if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', package):
            raise RuntimeError('Unexpected applicationId in APK metadata')
        # connectedDebugAndroidTest/UTP may uninstall the tested APK during cleanup.
        saved('package-after-tests.txt', ['adb', 'shell', 'pm', 'path', package])
        apk = folder / metadata['elements'][0]['outputFile']
        if apk.resolve().parent != folder.resolve() or not apk.is_file():
            raise RuntimeError('Debug APK is missing or outside its output directory')
        saved('reinstall.txt', ['adb', 'install', '-r', '-t', str(apk)], required=True)
        resolved = saved('launcher.txt', ['adb', 'shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', package], required=True)
        target = component(resolved.stdout, package)
        launched = saved('launch.txt', ['adb', 'shell', 'am', 'start', '-W', '-n', target], required=True)
        if re.search(r'(?mi)^\s*(Error(?: type \d+)?[: ]|Exception)', launched.stdout + '\n' + launched.stderr):
            raise RuntimeError('Activity manager reported a launch error despite adb exit code 0')
        saved('app-process.txt', ['adb', 'shell', 'pidof', package], required=True)
        screenshot = saved('phone.png', ['adb', 'exec-out', 'screencap', '-p'], required=True, binary=True)
        if not screenshot.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
            raise RuntimeError('Emulator screenshot is not a PNG')
        return 0
    except Exception as error:
        (evidence / 'capture-error.txt').write_text(str(error) + '\n', encoding='utf-8')
        print('Emulator evidence error:', error, file=sys.stderr)
        return failure or 1
    finally:
        # The emulator is terminated by the action immediately afterwards, so collect here.
        for name, argv, binary in [
            ('logcat.txt', ['adb', 'logcat', '-d', '-s', 'lw.HomeNAS', 'AndroidRuntime'], False),
            ('package.txt', ['adb', 'shell', 'dumpsys', 'package', package], False),
            ('activities.txt', ['adb', 'shell', 'dumpsys', 'activity', 'activities'], False),
            ('app-logs.tar', ['adb', 'exec-out', 'run-as', package, 'tar', '-cf', '-', 'files/logs'], True),
        ]:
            try:
                saved(name, argv, binary=binary)
            except Exception as error:
                print('Evidence collection unavailable:', name, type(error).__name__)


if __name__ == '__main__':
    sys.exit(execute(Path(__file__).resolve().parents[1]))
