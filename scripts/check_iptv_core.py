"""Compile and execute production IPTV core + JUnit tests using cached Kotlin/JVM.

This bypasses Android/Gradle: no Android, player, filesystem durability or provider claims.
Requires JAVA_HOME and GRADLE_MODULE_CACHE (a Gradle modules-2/files-2.1 cache).
"""
from pathlib import Path
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]
missing = [name for name in ('GRADLE_MODULE_CACHE', 'JAVA_HOME') if not os.environ.get(name)]
if missing:
    raise SystemExit('Set ' + ', '.join(missing))
CACHE = Path(os.environ['GRADLE_MODULE_CACHE'])
JAVA = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
OUT = ROOT / 'validation/iptv-core'
OUT.mkdir(parents=True, exist_ok=True)

def jar(group, name, version):
    return next((CACHE / group / name / version).glob('**/*.jar'))

compiler = [jar('org.jetbrains.kotlin', a, '2.3.0') for a in ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-daemon-embeddable']]
compiler += [jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.2.0'), jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'), jar('org.jetbrains', 'annotations', '23.0.0')]
classpath = [compiler[1], compiler[-1], jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3'), jar('org.json', 'json', '20250517')]
classpath += [jar('net.sf.kxml', 'kxml2', '2.3.0')]
classpath += [jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2')]
package = Path('com/nuvio/tv/core/iptv')
sources = sorted((ROOT / 'app/src/main/java' / package).glob('*.kt'))
sources += [ROOT / 'app/src/main/java/com/nuvio/tv/core/player/thumbnail/ThumbSourcePolicy.kt']
tests = sorted((ROOT / 'app/src/test/java' / package).glob('*Test.kt'))
target = OUT / 'iptv-core.jar'
compile_result = subprocess.run([str(JAVA), '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', os.pathsep.join(map(str, classpath)), '-d', str(target), *map(str, sources + tests)], capture_output=True, text=True)
(OUT / 'compile.txt').write_text(compile_result.stdout + compile_result.stderr, encoding='utf-8')
if compile_result.returncode:
    raise SystemExit(compile_result.stdout + compile_result.stderr)
result = subprocess.run([str(JAVA), '-cp', os.pathsep.join(map(str, [target, ROOT / "app/src/test/resources"] + classpath)), 'org.junit.runner.JUnitCore', *['com.nuvio.tv.core.iptv.' + t.stem for t in tests]], capture_output=True, text=True)
(OUT / 'results.txt').write_text(result.stdout + result.stderr, encoding='utf-8')
print(result.stdout + result.stderr)
raise SystemExit(result.returncode)
