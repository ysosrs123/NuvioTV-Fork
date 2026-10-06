"""Host JVM checks for IPTV code without the Android SDK.

Usage: python3 tools/iptv-host-tests/run.py [core] [data] [androidtest] [ui FILE...]
Default: core data androidtest.

Downloads pinned jars from Maven Central and the Media3 1.8.0 decoder/container
sources from GitHub into WORK (default build/iptv-host-tests), builds a
default-value android.jar equivalent from Robolectric's android-all, then compiles
production sources and tests with Kotlin 2.3.0. Equivalent to
unitTests.isReturnDefaultValues; it is not the Gradle unit-test task and does not
replace device tests. Requires java (17+), javac, git and network access.
"""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
WORK = Path(os.environ.get('IPTV_HOST_WORK', ROOT / 'build/iptv-host-tests'))
M2 = WORK / 'm2'
CENTRAL = 'https://repo1.maven.org/maven2'
JARS = [
    ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.3.0'), ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.3.0'),
    ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.3.0'), ('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.3.0'),
    ('org.jetbrains.kotlin', 'kotlin-reflect', '2.2.0'), ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'), ('org.jetbrains', 'annotations', '23.0.0'),
    ('org.jetbrains', 'annotations', '13.0'), ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
    ('junit', 'junit', '4.13.2'), ('org.hamcrest', 'hamcrest-core', '1.3'), ('org.json', 'json', '20250517'),
    ('net.sf.kxml', 'kxml2', '2.3.0'), ('com.google.guava', 'guava', '33.3.1-android'), ('com.google.guava', 'failureaccess', '1.0.2'),
    ('org.checkerframework', 'checker-qual', '3.43.0'), ('com.google.errorprone', 'error_prone_annotations', '2.28.0'),
    ('com.squareup.okhttp3', 'okhttp-jvm', '5.3.2'), ('com.squareup.okhttp3', 'mockwebserver', '5.3.2'),
    ('com.squareup.okhttp3', 'mockwebserver3', '5.3.2'), ('com.squareup.okio', 'okio-jvm', '3.16.4'),
    ('org.ow2.asm', 'asm', '9.7.1'), ('org.ow2.asm', 'asm-tree', '9.7.1'),
    ('org.robolectric', 'android-all', '16-robolectric-13921718'),
]
AARS = ['lib-common', 'lib-exoplayer', 'lib-extractor', 'lib-datasource', 'lib-exoplayer-hls', 'lib-datasource-okhttp']


def jar(group, name, version):
    return M2 / group / name / version / 'x' / f'{name}-{version}.jar'


def fetch(url, target):
    partial = target.with_suffix('.part')
    for attempt in range(6):
        try:
            urllib.request.urlretrieve(url, partial)
            partial.replace(target)
            return
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 5:
                raise
        except urllib.error.URLError:
            if attempt == 5:
                raise
        time.sleep(2 ** (attempt + 1))


def run(*args, **kwargs):
    result = subprocess.run([str(a) for a in args], **kwargs)
    if result.returncode:
        raise SystemExit(result.returncode)
    return result


def setup():
    for group, name, version in JARS:
        target = jar(group, name, version)
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            url = f"{CENTRAL}/{group.replace('.', '/')}/{name}/{version}/{name}-{version}.jar"
            print('fetch', name, version, flush=True)
            fetch(url, target)
    for aar in AARS:
        target = WORK / 'aar' / aar / 'classes.jar'
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            run('unzip', '-o', '-q', ROOT / 'app/libs' / f'{aar}-release.aar', 'classes.jar', '-d', target.parent)
    asm = [jar('org.ow2.asm', 'asm', '9.7.1'), jar('org.ow2.asm', 'asm-tree', '9.7.1')]
    android = WORK / 'mockable-android.jar'
    if not android.exists():
        tool = WORK / 'tool'
        run('javac', '-nowarn', '-cp', os.pathsep.join(map(str, asm)), '-d', tool, HERE / 'src/Mockable.java')
        run('java', '-cp', os.pathsep.join(map(str, [tool] + asm)), 'Mockable', jar('org.robolectric', 'android-all', '16-robolectric-13921718'), android)
    deps = [WORK / 'aar/lib-common/classes.jar', android, jar('org.checkerframework', 'checker-qual', '3.43.0'),
            jar('com.google.guava', 'guava', '33.3.1-android'), jar('com.google.errorprone', 'error_prone_annotations', '2.28.0')]
    stubs = WORK / 'stubs.jar'
    if not stubs.exists():
        out = WORK / 'stubs'
        sources = [p for p in (HERE / 'src/stubs').rglob('*.java')]
        run('javac', '-nowarn', '-cp', os.pathsep.join(map(str, deps + [jar('junit', 'junit', '4.13.2')])), '-d', out, *sources)
        run('jar', 'cf', stubs, '-C', out, '.')
    shims = WORK / 'shims.jar'
    if not shims.exists():
        out = WORK / 'shims'
        run('javac', '-nowarn', '-d', out, *(HERE / 'src/shims').rglob('*.java'))
        run('jar', 'cf', shims, '-C', out, '.')
    media3 = WORK / 'media3'
    if not (media3 / '.git').exists():
        media3.mkdir(parents=True, exist_ok=True)
        run('git', 'init', '-q', cwd=media3)
        run('git', 'remote', 'add', 'origin', 'https://github.com/androidx/media.git', cwd=media3)
        run('git', 'config', 'core.sparseCheckout', 'true', cwd=media3)
        (media3 / '.git/info/sparse-checkout').write_text('libraries/decoder/src/main/java/\nlibraries/container/src/main/java/\n')
        run('git', 'fetch', '-q', '--depth', '1', '--filter=blob:none', 'origin', 'refs/tags/1.8.0', cwd=media3)
        run('git', 'checkout', '-q', 'FETCH_HEAD', cwd=media3)
    for module in ('decoder', 'container'):
        target = WORK / f'lib-{module}.jar'
        if not target.exists():
            out = WORK / module
            run('javac', '-nowarn', '-d', out, '-cp', os.pathsep.join(map(str, deps + [stubs])),
                *(media3 / f'libraries/{module}/src/main/java').rglob('*.java'))
            run('jar', 'cf', target, '-C', out, '.')


def compiler():
    return os.pathsep.join(map(str, [jar('org.jetbrains.kotlin', a, '2.3.0') for a in
        ('kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-daemon-embeddable')] +
        [jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.2.0'), jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
         jar('org.jetbrains', 'annotations', '23.0.0')]))


def classpath():
    return [jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.3.0'), jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'),
            jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3'), jar('org.json', 'json', '20250517'),
            jar('net.sf.kxml', 'kxml2', '2.3.0'), jar('com.google.guava', 'guava', '33.3.1-android'),
            jar('com.google.guava', 'failureaccess', '1.0.2')] + [WORK / 'aar' / a / 'classes.jar' for a in AARS] + [
            WORK / 'lib-decoder.jar', WORK / 'lib-container.jar', WORK / 'stubs.jar', WORK / 'shims.jar', WORK / 'mockable-android.jar',
            jar('com.squareup.okhttp3', 'okhttp-jvm', '5.3.2'), jar('com.squareup.okhttp3', 'mockwebserver', '5.3.2'),
            jar('com.squareup.okhttp3', 'mockwebserver3', '5.3.2'), jar('com.squareup.okio', 'okio-jvm', '3.16.4')]


def kotlinc(out, sources, extra=(), before=()):
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    run('java', '-cp', compiler(), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn',
        '-jvm-target', '17', '-opt-in=androidx.media3.common.util.UnstableApi', *extra,
        '-classpath', os.pathsep.join(map(str, list(before) + classpath())), '-d', out, *sources)


def tests(out, package_dirs):
    names = []
    for directory, package in package_dirs:
        names += [f'{package}.{p.stem}' for p in sorted(directory.glob('*Test.kt'))]
    run('java', '-cp', os.pathsep.join(map(str, [out, ROOT / 'app/src/test/resources'] + classpath())), 'org.junit.runner.JUnitCore', *names)


def main(argv):
    targets = argv or ['core', 'data', 'androidtest']
    setup()
    main_java = ROOT / 'app/src/main/java/com/nuvio/tv'
    test_java = ROOT / 'app/src/test/java/com/nuvio/tv'
    production = sorted((main_java / 'core/iptv').glob('*.kt')) + [main_java / 'core/player/thumbnail/ThumbSourcePolicy.kt'] + sorted((main_java / 'data/iptv').glob('*.kt'))
    out = WORK / 'out'
    kotlinc(out, production + sorted((test_java / 'core/iptv').glob('*.kt')) + sorted((test_java / 'data/iptv').glob('*.kt')))
    if 'core' in targets:
        tests(out, [(test_java / 'core/iptv', 'com.nuvio.tv.core.iptv')])
    if 'data' in targets:
        tests(out, [(test_java / 'data/iptv', 'com.nuvio.tv.data.iptv')])
    if 'androidtest' in targets:
        kotlinc(WORK / 'androidtest', sorted((ROOT / 'app/src/androidTest/java/com/nuvio/tv/data/iptv').glob('*.kt')),
                ['-Xfriend-paths=' + str(out)], [out])
        print('androidTest compile OK')
    if 'ui' in targets:
        files = argv[argv.index('ui') + 1:]
        kotlinc(WORK / 'ui', [HERE / 'src/stubs/OptIn.kt'] + [ROOT / f for f in files], ['-Xfriend-paths=' + str(out)], [out])
        print('ui compile OK')


if __name__ == '__main__':
    main(sys.argv[1:])
