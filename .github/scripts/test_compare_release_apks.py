import copy
import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("compare_release_apks", Path(__file__).with_name("compare-release-apks.py"))
apk = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(apk)


class ReleaseComparisonTest(unittest.TestCase):
    def write_apk(self, root, sha="a" * 40, font=b"font", version="1.11.0-alpha06"):
        with zipfile.ZipFile(root / "app-release.apk", "w") as archive:
            archive.writestr("META-INF/version-control-info.textproto", f'repositories {{ revision: "{sha}" }}')
            archive.writestr("META-INF/androidx.compose.ui_ui-text.version", version)
            archive.writestr("res/terminal.ttf", font)
            archive.writestr("classes.dex", b"dex-sample")
            archive.writestr("lib/arm64-v8a/libtest.so", b"native")
            archive.writestr("AndroidManifest.xml", b"manifest")

    @staticmethod
    def tool_output(command, **_):
        if command[0].endswith("apksigner"):
            return "Verified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: " + "b" * 64 + "\n"
        return "package: name='me.rerere.rikkahub.dev.next.mod' versionCode='151' versionName='2.1.62'\nsdkVersion:'26'\ntargetSdkVersion:'28'\nnative-code: 'arm64-v8a'\n"

    def snapshot(self, root, sha="a" * 40):
        with patch.object(apk.subprocess, "check_output", side_effect=self.tool_output):
            return apk.snapshot(root, sha, Path("tools"))

    def test_embedded_sha_and_every_zip_entry_are_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_apk(root)
            result = self.snapshot(root)
            self.assertEqual("a" * 40, result["source_sha"])
            self.assertEqual(apk.digest(b"font"), result["fonts"]["res/terminal.ttf"])
            self.assertEqual(6, len(result["entries"]))
            self.assertEqual(1, result["groups"]["dex"]["count"])
            self.assertTrue(result["zip_verified"] and result["signature_verified"])
            with self.assertRaisesRegex(ValueError, "Embedded source revision mismatch"):
                self.snapshot(root, "c" * 40)

    def test_version_font_and_entry_differences_are_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_apk(root)
            good = self.snapshot(root)
            self.write_apk(root, "c" * 40, font=b"changed font", version="another-version")
            candidate = self.snapshot(root, "c" * 40)
            result = apk.compare(good, candidate)
            self.assertTrue(result["same_signer"] and result["same_manifest_identity"])
            self.assertFalse(result["same_font_content_multiset"])
            self.assertEqual({"metadata": 2, "resources": 1}, result["changed_entry_counts"])
            self.assertEqual("another-version", result["dependency_version_changes"][
                "META-INF/androidx.compose.ui_ui-text.version"]["after"])

    def test_equal_payload_reports_no_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_apk(root)
            good = self.snapshot(root)
            result = apk.compare(good, copy.deepcopy(good))
            self.assertEqual({}, result["changed_entry_counts"])
            self.assertEqual({}, result["dependency_version_changes"])
            self.assertTrue(result["same_font_content_multiset"])

    def test_missing_or_multiple_apks_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, "Expected one APK"):
                self.snapshot(root)
            self.write_apk(root)
            (root / "other.apk").write_bytes(b"not an apk")
            with self.assertRaisesRegex(ValueError, "Expected one APK"):
                self.snapshot(root)

    def test_debug_or_invalid_signing_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_apk(root)
            with patch.object(apk.subprocess, "check_output", return_value="not signed"):
                with self.assertRaisesRegex(ValueError, "no verified"):
                    apk.snapshot(root, "a" * 40, Path("tools"))
            def debug_output(command, **kwargs):
                return self.tool_output(command, **kwargs) + ("application-debuggable\n" if command[0].endswith("aapt") else "")
            with patch.object(apk.subprocess, "check_output", side_effect=debug_output):
                with self.assertRaisesRegex(ValueError, "production Release"):
                    apk.snapshot(root, "a" * 40, Path("tools"))


if __name__ == "__main__":
    unittest.main()
