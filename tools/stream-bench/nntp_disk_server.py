"""
An NNTP server that serves articles from disk, for the stream bench (StreamBenchDeviceTest): the shared
mock keeps its articles in a 1 GB JVM shared with other tests, and the bench's 860 MB release would not
fit beside them. It answers as the mock does: the raw yEnc article, then "\r\n.\r\n".

  POST /mocks/yenc-body/raw {"articleId": "<id>", "data": base64}  stores an article (tools/mock-seed's call)
  POST /mocks/stat {"articleId": "<id>", "exists": false}           STAT answers 430 for it
  GET  /stats                                                        commands, connections and bytes served

NNTP in plain TCP on --nntp, and with --tls on another port, TLS 1.2 with the one suite --ciphers names
(by default TLS_RSA_WITH_AES_256_CBC_SHA, the suite the bench has measured since its first runs, when the client's
TLS was Ktor's: kept so runs compare across the client's changes); with --tls-version 1.3, TLS 1.3 alone with its own
suites (AES-GCM, ChaCha20), as a provider's server most likely speaks it. --make-cert makes a
self-signed certificate for --cert-ip's addresses (10.0.2.2 is the host as an emulator reaches it) with
openssl, and prints its SHA-256 fingerprint, which the bench trusts. Standard library only.

  python3 -I tools/stream-bench/nntp_disk_server.py --dir ARTICLES --nntp 11119 --tls 11563 --http 18081 --make-cert DIR
"""
import argparse, base64, hashlib, json, os, socketserver, ssl, subprocess, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def article_path(directory, message_id):
    """Where the article [message_id] ("<...>", as BODY names it) is kept: the id's SHA-256, so any id is a safe name."""
    return os.path.join(directory, hashlib.sha256(message_id.encode()).hexdigest() + ".art")


def make_certificate(directory, addresses, days=7):
    """A self-signed RSA certificate and its key for [addresses] (IP addresses), in [directory]: their paths."""
    cert, key = os.path.join(directory, "bench-cert.pem"), os.path.join(directory, "bench-key.pem")
    names = ",".join("IP:" + a for a in addresses)
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", str(days), "-subj", "/CN=stream-bench",
         "-addext", "subjectAltName=" + names, "-keyout", key, "-out", cert],
        check=True, capture_output=True,
    )
    return cert, key


def fingerprint(cert):
    """The SHA-256 of the certificate in [cert] (PEM) as served, in lower-case hex."""
    with open(cert) as f:
        return hashlib.sha256(ssl.PEM_cert_to_DER_cert(f.read())).hexdigest()


class Store:
    """One server's articles directory, the ids STAT is to answer missing for, and what it has served."""

    def __init__(self, directory):
        self.dir = directory
        self.missing = set()
        self.lock = threading.Lock()
        self.counters = {"BODY": 0, "STAT": 0, "bytes": 0, "connections": 0, "tls_connections": 0}

    def count(self, key, n=1):
        with self.lock:
            self.counters[key] = self.counters.get(key, 0) + n

    def snapshot(self):
        with self.lock:
            return dict(self.counters)


class Http(BaseHTTPRequestHandler):
    store = None

    def log_message(self, *a):
        pass

    def body(self):
        return json.loads(self.rfile.read(int(self.headers["Content-Length"])))

    def reply(self, code, text):
        data = text.encode()
        self.send_response(code)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if self.path == "/mocks/yenc-body/raw":
            req = self.body()
            path = article_path(self.store.dir, req["articleId"])
            # Written whole, then renamed: a BODY never reads half an article.
            with open(path + ".tmp", "wb") as f:
                f.write(base64.b64decode(req["data"]))
            os.replace(path + ".tmp", path)
            self.reply(200, "stored")
        elif self.path == "/mocks/stat":
            req = self.body()
            if not req.get("exists", True):
                with self.store.lock:
                    self.store.missing.add(req["articleId"])
            self.reply(200, "ok")
        else:
            self.reply(404, "no")

    def do_GET(self):
        if self.path == "/stats":
            self.reply(200, json.dumps(self.store.snapshot()))
        else:
            self.reply(404, "no")


class Nntp(socketserver.StreamRequestHandler):
    store = None
    tls = None

    def setup(self):
        if self.tls is not None:
            self.request = self.tls.wrap_socket(self.request, server_side=True)
            self.store.count("tls_connections")
        self.store.count("connections")
        super().setup()

    def finish(self):
        super().finish()
        # The TLS socket took the connection's fd over: the server closes only the plain socket it accepted.
        if self.tls is not None:
            self.request.close()

    def send(self, line):
        self.wfile.write(line.encode("latin-1") + b"\r\n")

    def handle(self):
        self.send("200 service available")
        self.wfile.flush()
        while True:
            raw = self.rfile.readline()
            if not raw:
                return
            line = raw.decode("latin-1").rstrip("\r\n")
            parts = line.split(" ", 1)
            cmd = parts[0].upper()
            arg = parts[1] if len(parts) > 1 else None
            if cmd == "BODY" and arg:
                self.store.count("BODY")
                try:
                    with open(article_path(self.store.dir, arg), "rb") as f:
                        data = f.read()
                except FileNotFoundError:
                    self.send("430 No Such Article Found")
                else:
                    self.wfile.write(f"222 0 {arg} body follows\r\n".encode("latin-1") + data + b"\r\n.\r\n")
                    self.store.count("bytes", len(data))
            elif cmd == "STAT" and arg:
                self.store.count("STAT")
                with self.store.lock:
                    missing = arg in self.store.missing
                if not missing and os.path.exists(article_path(self.store.dir, arg)):
                    self.send(f"223 0 {arg} article exists")
                else:
                    self.send("430 No Such Article Found")
            elif cmd == "CAPABILITIES":
                self.wfile.write(b"101 Capability list:\r\nVERSION 2\r\nREADER\r\n.\r\n")
            elif cmd == "MODE":
                self.send("200 service available")
            elif cmd == "AUTHINFO":
                self.send("381 more" if arg and arg.upper().startswith("USER") else "281 ok")
            elif cmd == "GROUP":
                self.send(f"211 1000 1 1000 {arg}")
            elif cmd == "DATE":
                self.send("111 " + time.strftime("%Y%m%d%H%M%S", time.gmtime()))
            elif cmd == "QUIT":
                self.send("205 bye")
                self.wfile.flush()
                return
            else:
                self.send("500 Command not recognized")
            self.wfile.flush()


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True


class Running:
    """The servers [start] started: [ports] by name ("nntp", "tls", "http"), what they have served ([counters])."""

    def __init__(self, store, servers):
        self.store = store
        self.servers = servers
        self.ports = {name: server.server_address[1] for name, server in servers.items()}

    @property
    def counters(self):
        return self.store.snapshot()

    def stop(self):
        for server in self.servers.values():
            server.shutdown()
            server.server_close()


def start(directory, host="127.0.0.1", nntp=11119, tls=None, http=18081, cert=None, key=None, ciphers="AES256-SHA", tls_version="1.2"):
    """
    Serves [directory]'s articles on [host]: NNTP on [nntp], TLS on [tls] when it isn't None (with [cert] and [key]), the
    HTTP calls on [http]. A port of 0 is one the system picks; [Running.ports] says which. TLS is [tls_version] alone:
    1.2 with [ciphers], or 1.3 with its own suites.
    """
    os.makedirs(directory, exist_ok=True)
    store = Store(directory)
    servers = {
        "http": ThreadingHTTPServer((host, http), type("BenchHttp", (Http,), {"store": store})),
        "nntp": Server((host, nntp), type("BenchNntp", (Nntp,), {"store": store})),
    }
    if tls is not None:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        if tls_version == "1.3":
            context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_3
        else:
            context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_2
            # SECLEVEL 0: OpenSSL 3 turns the RSA key exchange suites off at its default level.
            context.set_ciphers(ciphers + ":@SECLEVEL=0")
        context.load_cert_chain(cert, key)
        servers["tls"] = Server((host, tls), type("BenchTlsNntp", (Nntp,), {"store": store, "tls": context}))
    for server in servers.values():
        threading.Thread(target=server.serve_forever, daemon=True).start()
    return Running(store, servers)


def main():
    p = argparse.ArgumentParser(description="Serves NNTP articles from disk for the stream bench.")
    p.add_argument("--dir", required=True, help="the articles, one file each (article_path)")
    p.add_argument("--host", default="127.0.0.1", help="the address to listen on; an emulator reaches the host's loopback as 10.0.2.2")
    p.add_argument("--http", type=int, default=18081)
    p.add_argument("--nntp", type=int, default=11119)
    p.add_argument("--tls", type=int, help="the TLS port; none without it")
    p.add_argument("--cert")
    p.add_argument("--key")
    p.add_argument("--make-cert", metavar="DIR", help="make a self-signed certificate and key in DIR and serve them")
    p.add_argument("--cert-ip", action="append", help="an address the made certificate names (default 10.0.2.2 and 127.0.0.1)")
    p.add_argument("--ciphers", default="AES256-SHA", help="TLS 1.2's suite")
    p.add_argument("--tls-version", choices=["1.2", "1.3"], default="1.2")
    p.add_argument("--quiet", action="store_true", help="no counters every 30 s")
    args = p.parse_args()
    cert, key = args.cert, args.key
    if args.make_cert:
        os.makedirs(args.make_cert, exist_ok=True)
        cert, key = make_certificate(args.make_cert, args.cert_ip or ["10.0.2.2", "127.0.0.1"])
    if args.tls is not None and not (cert and key):
        p.error("--tls needs --cert and --key, or --make-cert")
    running = start(args.dir, args.host, args.nntp, args.tls, args.http, cert, key, args.ciphers, args.tls_version)
    ports = running.ports
    tls = f"{ports['tls']} (TLSv{args.tls_version})" if "tls" in ports else "-"
    print(f"serving {args.dir} on {args.host}: http {ports['http']}, nntp {ports['nntp']}, tls {tls}", flush=True)
    if cert:
        print(f"certificate {cert} sha256 {fingerprint(cert)}", flush=True)
    while True:
        time.sleep(30)
        if not args.quiet:
            print(time.strftime("%H:%M:%S"), json.dumps(running.counters), flush=True)


if __name__ == "__main__":
    main()
