from __future__ import annotations

import unittest

from release_beta import is_github_prerelease


class ReleaseChannelTests(unittest.TestCase):
    def test_zero_major_fork_is_a_prerelease(self) -> None:
        self.assertTrue(is_github_prerelease("0.8.12-beta"))

    def test_plain_version_is_still_a_fork_prerelease(self) -> None:
        self.assertTrue(is_github_prerelease("1.0.0"))

    def test_post_stable_beta_is_a_prerelease(self) -> None:
        self.assertTrue(is_github_prerelease("1.1.0-beta.1"))

    def test_release_candidate_is_a_prerelease(self) -> None:
        self.assertTrue(is_github_prerelease("v1.1.0-rc.2"))


if __name__ == "__main__":
    unittest.main()
