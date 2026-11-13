"""
Tests for nntp_disk_server.py: articles stored and found by message id, the answers the app's NNTP
client reads, the HTTP calls tools/mock-seed makes, and TLS with a certificate made for the test.

  python3 -B tools/stream-bench/test_nntp_disk_server.py
"""
import base64, hashlib, http.client, json, os, shutil, socket, ssl, subprocess, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nntp_disk_server  # noqa: E402

ARTICLE = b"=ybegin part=1 line=128 size=6 name=bench.mkv\r\n=ypart begin=1 end=6\r\nabcdef\r\n=yend size=6 part=1\r\n"


class Session:
    """An NNTP connection to the server, plain or wrapped in [context]'s TLS."""

    def __init__(self, port, context=None):
        raw = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.sock = context.wrap_socket(raw, server_hostname="127.0.0.1") if context else raw
        self.file = self.sock.makefile("rb")
        self.greeting = self.line()

    def line(self):
        return self.file.readline().decode("latin-1").rstrip("\r\n")

    def send(self, command):
        self.sock.sendall(command.encode("latin-1") + b"\r\n")
        return self.line()

    def body(self, length):
        return self.file.read(length)

    def close(self):
        self.file.close()
        self.sock.close()


class DiskServerTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="nntp-disk-test-")
        self.server = nntp_disk_server.start(self.dir, host="127.0.0.1", nntp=0, http=0)

    def tearDown(self):
        self.server.stop()
        shutil.rmtree(self.dir, ignore_errors=True)

    def post(self, path, payload):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.ports["http"], timeout=5)
        connection.request("POST", path, json.dumps(payload), {"Content-Type": "application/json"})
        response = connection.getresponse()
        response.read()
        connection.close()
        return response.status

    def seed(self, message_id, data=ARTICLE):
        self.assertEqual(200, self.post("/mocks/yenc-body/raw", {"articleId": message_id, "data": base64.b64encode(data).decode()}))

    def test_an_article_is_kept_under_the_sha256_of_its_message_id(self):
        self.seed("<part1-1@bench>")
        name = hashlib.sha256(b"<part1-1@bench>").hexdigest() + ".art"
        self.assertEqual([name], os.listdir(self.dir))
        with open(os.path.join(self.dir, name), "rb") as f:
            self.assertEqual(ARTICLE, f.read())
        self.assertEqual(os.path.join(self.dir, name), nntp_disk_server.article_path(self.dir, "<part1-1@bench>"))

    def test_a_body_is_the_article_as_stored_then_the_terminator(self):
        self.seed("<part1-1@bench>")
        session = Session(self.server.ports["nntp"])
        try:
            self.assertTrue(session.greeting.startswith("200 "))
            self.assertEqual("222 0 <part1-1@bench> body follows", session.send("BODY <part1-1@bench>"))
            self.assertEqual(ARTICLE + b"\r\n.\r\n", session.body(len(ARTICLE) + 5))
            # The next command on the same connection is answered as the first was.
            self.assertEqual("223 0 <part1-1@bench> article exists", session.send("STAT <part1-1@bench>"))
        finally:
            session.close()

    def test_an_article_not_stored_or_marked_missing_is_not_found(self):
        self.seed("<part1-2@bench>")
        self.assertEqual(200, self.post("/mocks/stat", {"articleId": "<part1-2@bench>", "exists": False}))
        session = Session(self.server.ports["nntp"])
        try:
            self.assertEqual("430 No Such Article Found", session.send("BODY <part9-9@bench>"))
            self.assertEqual("430 No Such Article Found", session.send("STAT <part9-9@bench>"))
            # Marked missing for STAT, the article check; its body is still there for BODY.
            self.assertEqual("430 No Such Article Found", session.send("STAT <part1-2@bench>"))
            self.assertTrue(session.send("BODY <part1-2@bench>").startswith("222 "))
        finally:
            session.close()

    def test_the_login_and_session_commands_are_answered_as_the_mock_does(self):
        session = Session(self.server.ports["nntp"])
        try:
            self.assertEqual("381 more", session.send("AUTHINFO USER bench"))
            self.assertEqual("281 ok", session.send("AUTHINFO PASS bench"))
            self.assertEqual("200 service available", session.send("MODE READER"))
            self.assertEqual("211 1000 1 1000 alt.binaries.test", session.send("GROUP alt.binaries.test"))
            self.assertTrue(session.send("DATE").startswith("111 "))
            self.assertEqual("500 Command not recognized", session.send("XOVER"))
            self.assertEqual("205 bye", session.send("QUIT"))
        finally:
            session.close()

    def test_stats_count_the_commands_and_the_bytes_served(self):
        self.seed("<part1-1@bench>")
        session = Session(self.server.ports["nntp"])
        try:
            session.send("BODY <part1-1@bench>")
            session.body(len(ARTICLE) + 5)
            session.send("STAT <part1-1@bench>")
        finally:
            session.close()
        connection = http.client.HTTPConnection("127.0.0.1", self.server.ports["http"], timeout=5)
        connection.request("GET", "/stats")
        stats = json.loads(connection.getresponse().read())
        connection.close()
        self.assertEqual(1, stats["BODY"])
        self.assertEqual(1, stats["STAT"])
        self.assertEqual(len(ARTICLE), stats["bytes"])
        self.assertEqual(1, stats["connections"])

    def test_each_server_counts_its_own(self):
        other_dir = tempfile.mkdtemp(prefix="nntp-disk-test-")
        other = nntp_disk_server.start(other_dir, host="127.0.0.1", nntp=0, http=0)
        try:
            Session(self.server.ports["nntp"]).close()
            self.assertEqual(1, self.server.counters["connections"])
            self.assertEqual(0, other.counters["connections"])
        finally:
            other.stop()
            shutil.rmtree(other_dir, ignore_errors=True)


@unittest.skipUnless(shutil.which("openssl"), "openssl is needed to make the test's certificate")
class TlsTest(unittest.TestCase):
    """TLS 1.2 with the one CBC suite asked for, as the profile's server offered it, on a certificate made here."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="nntp-disk-test-")
        self.cert, self.key = nntp_disk_server.make_certificate(self.dir, ["127.0.0.1", "10.0.2.2"])
        self.server = nntp_disk_server.start(self.dir, host="127.0.0.1", nntp=0, tls=0, http=0, cert=self.cert, key=self.key)

    def tearDown(self):
        self.server.stop()
        shutil.rmtree(self.dir, ignore_errors=True)

    def client(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.load_verify_locations(self.cert)
        context.set_ciphers("ALL:@SECLEVEL=0")
        return context

    def test_the_tls_port_serves_the_same_articles(self):
        with open(nntp_disk_server.article_path(self.dir, "<part1-1@bench>"), "wb") as f:
            f.write(ARTICLE)
        session = Session(self.server.ports["tls"], self.client())
        try:
            self.assertEqual("TLSv1.2", session.sock.version())
            self.assertEqual("AES256-SHA", session.sock.cipher()[0])
            self.assertTrue(session.greeting.startswith("200 "))
            self.assertEqual("222 0 <part1-1@bench> body follows", session.send("BODY <part1-1@bench>"))
            self.assertEqual(ARTICLE + b"\r\n.\r\n", session.body(len(ARTICLE) + 5))
        finally:
            session.close()
        self.assertEqual(1, self.server.counters["tls_connections"])

    def test_the_certificate_names_the_addresses_and_its_fingerprint_is_the_one_served(self):
        out = subprocess.run(["openssl", "x509", "-in", self.cert, "-noout", "-ext", "subjectAltName"], capture_output=True, text=True, check=True).stdout
        self.assertIn("IP Address:127.0.0.1", out)
        self.assertIn("IP Address:10.0.2.2", out)
        session = Session(self.server.ports["tls"], self.client())
        try:
            served = session.sock.getpeercert(binary_form=True)
        finally:
            session.close()
        self.assertEqual(hashlib.sha256(served).hexdigest(), nntp_disk_server.fingerprint(self.cert))


class Tls13Test(TlsTest):
    """TLS 1.3 alone (--tls-version 1.3), as a provider's server most likely speaks it: its suites, not the CBC one."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="nntp-disk-test-")
        self.cert, self.key = nntp_disk_server.make_certificate(self.dir, ["127.0.0.1", "10.0.2.2"])
        self.server = nntp_disk_server.start(self.dir, host="127.0.0.1", nntp=0, tls=0, http=0, cert=self.cert, key=self.key, tls_version="1.3")

    def test_the_tls_port_serves_the_same_articles(self):
        with open(nntp_disk_server.article_path(self.dir, "<part1-1@bench>"), "wb") as f:
            f.write(ARTICLE)
        session = Session(self.server.ports["tls"], self.client())
        try:
            self.assertEqual("TLSv1.3", session.sock.version())
            self.assertTrue(session.greeting.startswith("200 "))
            self.assertEqual("222 0 <part1-1@bench> body follows", session.send("BODY <part1-1@bench>"))
            self.assertEqual(ARTICLE + b"\r\n.\r\n", session.body(len(ARTICLE) + 5))
        finally:
            session.close()


if __name__ == "__main__":
    unittest.main()
