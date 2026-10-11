"""Run with python3 -m unittest discover -s tests/skill -v (stdlib only)."""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / "skills/oops/scripts/launcher.py"
RUNTIME = ROOT / "src/main/resources/skill/oops.py"
SCRIPT_PATH = "/openapi/skill/oops.py"


def source(version):
    return ("import json, sys\nprint(json.dumps({\"version\": %r, \"args\": sys.argv[1:]}))\n" % version).encode()


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.TemporaryDirectory()
        self.addCleanup(self.home.cleanup)
        self.source = source("v1")
        self.requests = []
        self.status = 200
        self.hash_override = None
        self.source_override = None
        self.change_after_hash = None
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                fixture.requests.append((self.path, self.headers.get("Authorization")))
                status = fixture.status
                if self.headers.get("Authorization") != "Bearer test-token":
                    status = 401
                if self.path == SCRIPT_PATH + "/hash":
                    body = fixture.hash_override or hashlib.sha256(fixture.source).hexdigest().encode()
                    if fixture.change_after_hash is not None:
                        fixture.source = fixture.change_after_hash
                        fixture.change_after_hash = None
                elif self.path == SCRIPT_PATH:
                    body = fixture.source_override or fixture.source
                elif self.path == "/openapi/namespaces":
                    body = b'{"success":true,"data":[{"name":"test"}]}'
                else:
                    status, body = 404, b"missing"
                self.send_response(status)
                if status == 302:
                    self.send_header("Location", "/redirected")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.endpoint = "http://127.0.0.1:{}".format(self.server.server_port)
        self.environment = {key: value for key, value in os.environ.items()
                            if not key.startswith("OOPS_")}
        self.environment.update(HOME=self.home.name, OOPS_ENDPOINT=self.endpoint,
                                OOPS_TOKEN="test-token")

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def run_cli(self, *arguments, environment=None):
        return subprocess.run([sys.executable, str(LAUNCHER), *arguments],
                              env=environment or self.environment, capture_output=True,
                              text=True, timeout=10)

    def cache(self, endpoint=None):
        key = hashlib.sha256((endpoint or self.endpoint).encode()).hexdigest()
        return Path(self.home.name) / ".oops/cache/skill" / key / "oops.py"

    def assert_version(self, result, version):
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["version"], version)

    def test_download_reuse_upgrade_and_rollback(self):
        original = self.source
        result = self.run_cli("--json", "future-command", "--future-option", "value")
        self.assert_version(result, "v1")
        self.assertEqual(json.loads(result.stdout)["args"],
                         ["--json", "future-command", "--future-option", "value"])
        self.assertEqual(self.cache().read_bytes(), original)
        self.assertEqual(self.cache().stat().st_mode & 0o777, 0o600)
        self.requests.clear()
        self.assert_version(self.run_cli("ns", "ls"), "v1")
        self.assertEqual(self.requests, [(SCRIPT_PATH + "/hash", "Bearer test-token")])
        self.source = source("v2")
        self.assert_version(self.run_cli("ns", "ls"), "v2")
        self.source = original
        self.assert_version(self.run_cli("ns", "ls"), "v1")

    def test_hash_failure_never_executes_cached_script(self):
        self.assert_version(self.run_cli("ns", "ls"), "v1")
        for status in (401, 404, 503):
            self.status = status
            result = self.run_cli("ns", "ls")
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")
            self.assertIn("HTTP " + str(status), result.stderr)

    def test_bad_download_preserves_cache_and_never_executes(self):
        self.assert_version(self.run_cli("ns", "ls"), "v1")
        previous = self.cache().read_bytes()
        self.source = source("v2")
        self.source_override = source("wrong")
        self.requests.clear()
        result = self.run_cli("deploy", "image")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("hash mismatch", result.stderr)
        self.assertEqual(len(self.requests), 6)
        self.assertEqual(self.cache().read_bytes(), previous)

    def test_server_change_between_hash_and_source_retries(self):
        self.change_after_hash = source("v2")
        self.assert_version(self.run_cli("ns", "ls"), "v2")
        self.assertEqual(len(self.requests), 4)

    def test_corrupted_cache_is_replaced(self):
        self.assert_version(self.run_cli("ns", "ls"), "v1")
        self.cache().write_bytes(source("corrupted"))
        self.assert_version(self.run_cli("ns", "ls"), "v1")
        self.assertEqual(self.cache().read_bytes(), self.source)

    def test_endpoint_flag_overrides_environment_and_isolates_cache(self):
        self.assert_version(self.run_cli("ns", "ls"), "v1")
        alternate = self.endpoint.replace("127.0.0.1", "localhost")
        self.source = source("alternate")
        self.environment["OOPS_TOKEN"] = "wrong-token"
        self.assert_version(self.run_cli("--endpoint", alternate, "--token", "test-token", "ns", "ls"), "alternate")
        self.assertEqual(self.cache().read_bytes(), source("v1"))
        self.assertEqual(self.cache(alternate).read_bytes(), source("alternate"))

    def test_auth_and_unconfigured_help_work_offline(self):
        environment = {key: value for key, value in self.environment.items()
                       if not key.startswith("OOPS_")}
        self.assertEqual(self.run_cli("--help", environment=environment).returncode, 0)
        result = self.run_cli("auth", "set", "--endpoint", self.endpoint,
                              "--token", "test-token", environment=environment)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = self.run_cli("auth", "status", environment=environment)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("test-token", result.stdout)
        self.assertEqual(self.requests, [])
        self.assert_version(self.run_cli("ns", "ls", environment=environment), "v1")

    def test_invalid_hash_and_python_do_not_create_cache(self):
        self.hash_override = b"not-a-hash"
        result = self.run_cli("ns", "ls")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.requests), 1)
        self.assertFalse(self.cache().exists())
        self.hash_override = None
        self.source = b"invalid python ("
        result = self.run_cli("ns", "ls")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertFalse(self.cache().exists())

    def test_redirect_is_not_followed(self):
        self.status = 302
        result = self.run_cli("ns", "ls")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.requests), 1)
        self.assertEqual(result.stdout, "")

    def test_runtime_exit_code_is_preserved_without_retry(self):
        self.source = b"import sys\nsys.exit(7)\n"
        self.assertEqual(self.run_cli("ns", "ls").returncode, 7)
        self.assertEqual(len(self.requests), 2)

    def test_concurrent_downloads_leave_complete_cache(self):
        processes = [subprocess.Popen([sys.executable, str(LAUNCHER), "ns", "ls"],
                                     env=self.environment, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, text=True) for _ in range(4)]
        for process in processes:
            stdout, stderr = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 0, stderr)
            self.assertEqual(json.loads(stdout)["version"], "v1")
        self.assertEqual(self.cache().read_bytes(), self.source)
        self.assertEqual(list(self.cache().parent.glob(".oops-*")), [])

    def test_actual_bundled_cli_runs_through_launcher(self):
        self.source = RUNTIME.read_bytes()
        result = self.run_cli("--json", "ns", "ls")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), [{"name": "test"}])
        self.assertEqual(self.requests[-1][0], "/openapi/namespaces")
        result = self.run_cli("deploy", "image", "--help")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--tag", result.stdout)


if __name__ == "__main__":
    unittest.main()
