"""Dependency acquisition regressions; no requests to external download sites."""
import contextlib
import hashlib
import http.server
import importlib.util
import io
from pathlib import Path
import ssl
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('ffmpeg_download_checks', ROOT/'tools/fetch-ffmpeg.py')
ffmpeg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ffmpeg)


def response(body, declared=None):
    stream = io.BytesIO(body)
    stream.headers = {} if declared is None else {'Content-Length': str(declared)}
    return stream


class FFmpegDownloads(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.folder = Path(self.directory.name)
        self.target = self.folder/'ffmpeg-source.tar.xz'
        self.payload = b'pinned source archive fixture'
        self.pin = hashlib.sha256(self.payload).hexdigest()
        self.sleep = self.enterContext(patch.object(ffmpeg.time, 'sleep'))
        self.enterContext(contextlib.redirect_stdout(io.StringIO()))

    def download(self):
        ffmpeg.download('https://download.invalid/archive', self.target, self.pin,
                        len(self.payload), len(self.payload))

    def test_handshake_reset_retries_then_publishes_verified_file(self):
        with patch.object(ffmpeg.urllib.request, 'urlopen', side_effect=[
                urllib.error.URLError(ConnectionResetError(10054, 'peer reset')),
                response(self.payload)]) as opener:
            self.download()
        self.assertEqual(opener.call_count, 2)
        self.sleep.assert_called_once_with(2)
        self.assertEqual(self.target.read_bytes(), self.payload)
        self.assertEqual(list(self.folder.glob('*.part')), [])

    def test_interrupted_body_cleans_attempt_and_preserves_existing_file(self):
        class Interrupted(io.BytesIO):
            headers = {}
            def read(self, count=-1):
                if self.tell():
                    raise ConnectionResetError(10054, 'body interrupted')
                return super().read(4)
        self.target.write_bytes(b'previous file')
        attempts = 0
        def open_response(*args, **kwargs):
            nonlocal attempts
            attempts += 1
            self.assertEqual(self.target.read_bytes(), b'previous file')
            self.assertEqual(list(self.folder.glob('*.part')), [])
            return Interrupted(self.payload) if attempts == 1 else response(self.payload)
        with patch.object(ffmpeg.urllib.request, 'urlopen', side_effect=open_response):
            self.download()
        self.assertEqual(attempts, 2)
        self.assertEqual(self.target.read_bytes(), self.payload)

    def test_short_body_without_content_length_retries_from_zero(self):
        with patch.object(ffmpeg.urllib.request, 'urlopen', side_effect=[
                response(self.payload[:4]), response(self.payload)]) as opener:
            self.download()
        self.assertEqual(opener.call_count, 2)
        self.assertEqual(self.target.read_bytes(), self.payload)

    def test_network_exhaustion_is_bounded_and_leaves_no_partial_file(self):
        with patch.object(ffmpeg.urllib.request, 'urlopen', side_effect=TimeoutError('timeout')) as opener:
            with self.assertRaisesRegex(RuntimeError, 'network failed after 5 attempts'):
                self.download()
        self.assertEqual(opener.call_count, 5)
        self.assertEqual([c.args[0] for c in self.sleep.call_args_list], [2, 4, 8, 16])
        self.assertEqual(list(self.folder.iterdir()), [])

    def test_transient_http_status_retries(self):
        for code in (408, 429, 500, 502, 503, 504):
            with self.subTest(code=code), patch.object(ffmpeg.urllib.request, 'urlopen', side_effect=[
                    urllib.error.HTTPError('https://download.invalid', code, 'transient', {}, None),
                    response(self.payload)]) as opener:
                self.download()
                self.assertEqual(opener.call_count, 2)

    def test_not_found_and_invalid_certificate_are_not_retried(self):
        for failure in (
            urllib.error.HTTPError('https://download.invalid', 404, 'missing', {}, None),
            urllib.error.URLError(ssl.SSLCertVerificationError('certificate verification failed')),
        ):
            with self.subTest(failure=type(failure).__name__), patch.object(
                    ffmpeg.urllib.request, 'urlopen', side_effect=failure) as opener:
                with self.assertRaises(type(failure)):
                    self.download()
                opener.assert_called_once()
        self.sleep.assert_not_called()

    def test_hash_mismatch_does_not_retry_or_replace_existing_file(self):
        self.target.write_bytes(b'previous file')
        with patch.object(ffmpeg.urllib.request, 'urlopen', return_value=response(b'x'*len(self.payload))) as opener:
            with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
                self.download()
        opener.assert_called_once()
        self.sleep.assert_not_called()
        self.assertEqual(self.target.read_bytes(), b'previous file')
        self.assertEqual(list(self.folder.glob('*.part')), [])

    def test_size_limit_does_not_retry_or_leave_partial_file(self):
        for declared in (None, len(self.payload)+1):
            with self.subTest(declared=declared), patch.object(ffmpeg.urllib.request, 'urlopen',
                    return_value=response(self.payload+b'x', declared)) as opener:
                with self.assertRaisesRegex(ValueError, 'size limit'):
                    self.download()
                opener.assert_called_once()
        self.assertEqual(list(self.folder.iterdir()), [])
        self.sleep.assert_not_called()

    def test_metadata_handshake_uses_same_retry_policy(self):
        with patch.object(ffmpeg.urllib.request, 'urlopen', side_effect=[
                urllib.error.URLError(ConnectionResetError(10054, 'peer reset')),
                response(b'{"urls": []}')]) as opener:
            self.assertEqual(ffmpeg.wheel_metadata(), {'urls': []})
        self.assertEqual(opener.call_count, 2)

    def test_real_http_truncated_content_length_recovers(self):
        payload = self.payload
        class Handler(http.server.BaseHTTPRequestHandler):
            attempts = 0
            def do_GET(self):
                Handler.attempts += 1
                self.send_response(200)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload[:4] if Handler.attempts == 1 else payload)
                self.wfile.flush()
                self.close_connection = True
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            # Explicitly bypass any machine-wide proxy only for this loopback test.
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with patch.object(ffmpeg.urllib.request, 'urlopen', side_effect=opener.open):
                ffmpeg.download(f'http://127.0.0.1:{server.server_port}/source', self.target,
                                self.pin, len(payload), len(payload))
            self.assertEqual(Handler.attempts, 2)
            self.assertEqual(self.target.read_bytes(), payload)
            self.assertEqual(list(self.folder.glob('*.part')), [])
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=5)


if __name__ == '__main__':
    unittest.main()
