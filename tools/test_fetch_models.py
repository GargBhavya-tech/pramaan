"""Tests for tools/fetch_models.py using a local HTTP server and fake models: nothing is downloaded from the internet.

    python -m unittest tools/test_fetch_models.py
"""
import hashlib
import http.server
import io
import os
import sys
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_models as fm  # noqa: E402


def sha(b):
    return hashlib.sha256(b).hexdigest()


class Handler(http.server.BaseHTTPRequestHandler):
    body = b"genuine model bytes"

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(self.body)))
        self.end_headers()
        self.wfile.write(self.body)

    def log_message(self, *args):
        pass


class FetchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        cls.url = "http://127.0.0.1:%d/m.onnx" % cls.server.server_port
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def test_a_matching_download_is_kept(self):
        with tempfile.TemporaryDirectory() as d:
            dest = Path(d) / "m.onnx"
            self.assertTrue(fm.fetch(self.url, dest, sha(Handler.body)))
            self.assertEqual(Handler.body, dest.read_bytes())

    def test_a_download_with_the_wrong_hash_is_refused_and_leaves_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            dest = Path(d) / "m.onnx"
            self.assertFalse(fm.fetch(self.url, dest, sha(b"something else")))
            self.assertFalse(dest.exists())
            self.assertEqual([], list(Path(d).iterdir()), "no .part file may be left behind")

    def test_check_mode_reports_missing_and_wrong_files_and_downloads_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            models = Path(d)
            (models / "good.onnx").write_bytes(b"good")
            (models / "bad.onnx").write_bytes(b"not what the table says")
            table = {"good.onnx": sha(b"good"), "bad.onnx": sha(b"expected"), "absent.onnx": sha(b"x")}
            self.assertEqual(1, fm.run(models, table, True))
            self.assertFalse((models / "absent.onnx").exists())

    def test_everything_present_and_matching_exits_zero(self):
        with tempfile.TemporaryDirectory() as d:
            models = Path(d)
            (models / "a.onnx").write_bytes(b"a")
            self.assertEqual(0, fm.run(models, {"a.onnx": sha(b"a")}, True))

    def test_a_wrong_file_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as d:
            models = Path(d)
            (models / "face_detection_yunet_2023mar.onnx").write_bytes(b"mine")
            table = {"face_detection_yunet_2023mar.onnx": sha(Handler.body)}

            def no_download(url):
                raise AssertionError("must not download over an existing file")

            self.assertEqual(1, fm.run(models, table, False, opener=no_download))
            self.assertEqual(b"mine", (models / "face_detection_yunet_2023mar.onnx").read_bytes())

    def test_the_shipped_table_names_exactly_the_six_models_the_script_knows(self):
        names = set(fm.expected_hashes())
        self.assertEqual(names, set(fm.SOURCES) | set(fm.OURS))


def make_zip(members):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in members.items():
            zf.writestr(name, data)
    return buf.getvalue()


class BundleTest(unittest.TestCase):
    """The bundle is untrusted: only table names with matching hashes are taken, and no path inside it decides anything."""

    @classmethod
    def setUpClass(cls):
        cls.payload = {"value": b""}
        payload = cls.payload

        class ZipHandler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload["value"])))
                self.end_headers()
                self.wfile.write(payload["value"])

            def log_message(self, *args):
                pass

        cls.server = http.server.HTTPServer(("127.0.0.1", 0), ZipHandler)
        cls.url = "http://127.0.0.1:%d/bundle.zip" % cls.server.server_port
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def test_takes_only_wanted_files_with_matching_hashes(self):
        with tempfile.TemporaryDirectory() as d:
            models = Path(d)
            table = {"a.onnx": sha(b"A"), "b.onnx": sha(b"B"), "c.onnx": sha(b"C")}
            self.payload["value"] = make_zip({"pramaan/a.onnx": b"A", "b.onnx": b"tampered", "extra.onnx": b"X",
                                              "README.txt": b"hello"})
            placed = fm.fetch_bundle(self.url, models, table, {"a.onnx", "b.onnx", "c.onnx"})
            self.assertEqual(["a.onnx"], placed)
            self.assertEqual(b"A", (models / "a.onnx").read_bytes())
            self.assertFalse((models / "b.onnx").exists(), "a member with the wrong hash must be refused")
            self.assertFalse((models / "extra.onnx").exists(), "a name the table does not list must be ignored")
            self.assertEqual(["a.onnx"], sorted(p.name for p in models.iterdir()), "nothing else, and no .part files")

    def test_a_path_inside_the_zip_never_decides_where_a_file_goes(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            models = root / "models"
            models.mkdir()
            table = {"a.onnx": sha(b"A")}
            self.payload["value"] = make_zip({"../../escape/a.onnx": b"A"})
            fm.fetch_bundle(self.url, models, table, {"a.onnx"})
            self.assertTrue((models / "a.onnx").exists())
            self.assertFalse((root / "escape").exists())

    def test_a_download_that_is_not_a_zip_places_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            self.payload["value"] = b"<html>Google Drive: virus scan warning</html>"
            self.assertEqual([], fm.fetch_bundle(self.url, Path(d), {"a.onnx": sha(b"A")}, {"a.onnx"}))
            self.assertEqual([], list(Path(d).iterdir()))

    def test_run_fills_the_missing_files_from_the_bundle_and_exits_zero(self):
        with tempfile.TemporaryDirectory() as d:
            models = Path(d)
            table = {"ours1.onnx": sha(b"1"), "ours2.onnx": sha(b"2")}
            self.payload["value"] = make_zip({"ours1.onnx": b"1", "ours2.onnx": b"2"})
            self.assertEqual(0, fm.run(models, table, False, bundle=self.url))
            self.assertEqual(b"2", (models / "ours2.onnx").read_bytes())

    def test_run_still_exits_nonzero_if_the_bundle_lacks_a_file(self):
        with tempfile.TemporaryDirectory() as d:
            table = {"ours1.onnx": sha(b"1"), "ours2.onnx": sha(b"2")}
            self.payload["value"] = make_zip({"ours1.onnx": b"1"})
            self.assertEqual(1, fm.run(Path(d), table, False, bundle=self.url))

    def test_check_only_never_touches_the_bundle(self):
        with tempfile.TemporaryDirectory() as d:
            table = {"ours1.onnx": sha(b"1")}
            self.payload["value"] = make_zip({"ours1.onnx": b"1"})
            self.assertEqual(1, fm.run(Path(d), table, True, bundle=self.url))
            self.assertFalse((Path(d) / "ours1.onnx").exists())

    def test_bundle_url_order_is_argument_then_environment_then_file_and_skips_comments(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "bundle-url.txt").write_text("# a comment" + chr(10) + chr(10) + "https://example.test/from-file.zip" + chr(10), encoding="utf-8")
            old = os.environ.pop("PRAMAAN_MODELS_URL", None)
            try:
                self.assertEqual("https://example.test/from-file.zip", fm.bundle_url(None, d))
                os.environ["PRAMAAN_MODELS_URL"] = "https://example.test/from-env.zip"
                self.assertEqual("https://example.test/from-env.zip", fm.bundle_url(None, d))
                self.assertEqual("https://example.test/arg.zip", fm.bundle_url("https://example.test/arg.zip", d))
            finally:
                os.environ.pop("PRAMAAN_MODELS_URL", None)
                if old is not None:
                    os.environ["PRAMAAN_MODELS_URL"] = old

    def test_the_shipped_bundle_url_file_holds_one_https_zip_link(self):
        old = os.environ.pop("PRAMAAN_MODELS_URL", None)
        try:
            url = fm.bundle_url(None, fm.MODELS)
        finally:
            if old is not None:
                os.environ["PRAMAAN_MODELS_URL"] = old
        self.assertIsNotNone(url, "models/bundle-url.txt should name the release that holds the converted models")
        self.assertTrue(url.startswith("https://") and url.endswith(".zip"), url)


class SetupTest(unittest.TestCase):
    def test_the_gradle_wrapper_is_an_absolute_path_that_exists(self):
        import setup
        wrapper = Path(setup.gradle_command(":desktop:showcase")[0])
        self.assertTrue(wrapper.is_absolute(), "a relative name is not found on Windows when run from another directory")
        self.assertTrue(wrapper.exists())
        self.assertEqual([":desktop:showcase", "--no-daemon"], setup.gradle_command(":desktop:showcase")[1:])


if __name__ == "__main__":
    unittest.main()
