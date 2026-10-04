from __future__ import annotations

import pathlib
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which("bash"), "release metadata requires Bash")
class ReleaseMetadataTests(unittest.TestCase):
    def test_rebuilt_candidate_preserves_notes_range_and_updates_source_boundary(self):
        script = pathlib.Path(__file__).resolve().parents[1] / "release-metadata.sh"
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)

            def git(*args):
                return subprocess.check_output(["git", *args], cwd=root, text=True).strip()

            git("init", "-q")
            git("config", "user.name", "Release test")
            git("config", "user.email", "test@example.invalid")
            app = root / "app"
            app.mkdir()

            def commit(version, code, message):
                (app / "build.gradle.kts").write_text(
                    f'versionCode = {code}\nversionName = "{version}"\n'
                )
                git("add", ".")
                git("commit", "-qm", message)
                return git("rev-parse", "HEAD")

            previous = commit("0.9.0-beta-nt1", 1360, "Previous release")
            original = commit("0.9.3-beta-nt1", 1361, "New release candidate")
            rebuilt = commit("0.9.3-beta-nt1", 1362, "Fix candidate startup")
            (root / "notes.md").write_text("Release notes")
            git("add", ".")
            git("commit", "-qm", "Document candidate")
            output = subprocess.check_output(["bash", str(script), "HEAD"], cwd=root, text=True)
            values = dict(line.split("=", 1) for line in output.splitlines())
            self.assertEqual(original, values["current_bump"])
            self.assertEqual(rebuilt, values["current_build_bump"])
            self.assertEqual(previous, values["previous_bump"])
            self.assertEqual("0.9.0-beta-nt1", values["previous_version"])
            self.assertEqual("1362", values["version_code"])
            self.assertEqual("true", values["prerelease"])
            self.assertEqual("notes.md", git("diff", "--name-only", rebuilt + "..HEAD"))
            (app / "unexpected.kt").write_text("// After build-code bump")
            git("add", ".")
            git("commit", "-qm", "Unversioned application edit")
            self.assertIn("app/unexpected.kt", git("diff", "--name-only", rebuilt + "..HEAD"))
