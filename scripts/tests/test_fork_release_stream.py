import unittest
from release_beta import is_github_prerelease

class ForkReleaseStreamTests(unittest.TestCase):
    def test_all_fork_versions_are_beta(self):
        for version in ("0.9.4-beta-nt1", "1.0.0", "1.0.0-beta-nt10"):
            self.assertTrue(is_github_prerelease(version))
