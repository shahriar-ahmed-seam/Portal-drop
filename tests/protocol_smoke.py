"""Exercise the Portal Drop server using the same protocol as the Android app (v1, v2, and v3)."""

import hashlib
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlparse
import uuid


def wait_until(check, timeout=10):
    deadline = time.monotonic() + timeout
    while not check():
        if time.monotonic() > deadline:
            raise TimeoutError("timed out waiting for Portal Drop")
        time.sleep(0.05)


class Desktop:
    def __init__(self, binary, directory):
        self.cmd = [str(binary), "--data-dir", str(directory / "data"),
                    "--receive-dir", str(directory / "received")]
        self.directory = directory
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            self.port = listener.getsockname()[1]
        self.process = None
        self.fp = None
        self.token = secrets.token_hex(32)

    def cli(self, *args):
        return subprocess.check_output(
            [*self.cmd, *args], text=True, encoding="utf-8"
        ).strip()

    def start(self):
        home = self.directory / "home"
        home.mkdir(exist_ok=True)
        env = {**os.environ, "HOME": str(home), "PORTAL_DROP_NO_SOUND": "1"}
        if os.name != "nt":
            env["PATH"] = ""
        self.process = subprocess.Popen(
            [*self.cmd, "serve", "--listen", f"127.0.0.1:{self.port}", "--no-discovery"],
            env=env,
            stdout=subprocess.DEVNULL,
        )

        def ready():
            if self.process.poll() is not None:
                raise RuntimeError("Desktop service exited during startup")
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=1):
                    return True
            except OSError:
                return False

        wait_until(ready)

    def stop(self):
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
            self.process = None

    def connect(self, timeout=5):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        connection = http.client.HTTPSConnection("127.0.0.1", self.port, timeout=timeout, context=context)
        connection.connect()
        fingerprint = hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest()
        if fingerprint != self.fp:
            connection.close()
            raise ValueError("Desktop certificate differs from the pairing invite")
        return connection

    def request(self, method, path, *, code=200, payload=None, authorized=True, version=1, extra_headers=None, return_headers=False):
        headers = {"Authorization": f"Bearer {self.token}"} if authorized else {}
        if version >= 2:
            headers["X-Portal-Drop-Version"] = str(version)
        if extra_headers:
            headers.update(extra_headers)
        if isinstance(payload, dict):
            headers["Content-Type"] = "application/json"
            payload = json.dumps(payload).encode()
        elif isinstance(payload, bytes):
            headers["Content-Type"] = "application/octet-stream"
        connection = self.connect()
        try:
            connection.request(method, path, body=payload, headers=headers)
            response = connection.getresponse()
            data = response.read()
            assert response.status == code, (method, path, response.status, code, data)
            parsed = json.loads(data) if response.getheader("Content-Type", "").startswith("application/json") else data
            if return_headers:
                return parsed, dict(response.getheaders())
            return parsed
        finally:
            connection.close()

    def start_interrupted_upload(self, item, data):
        connection = self.connect()
        try:
            connection.putrequest("PUT", f"/v1/inbox/{item['id']}/file?offset=0")
            connection.putheader("Authorization", f"Bearer {self.token}")
            connection.putheader("Content-Type", "application/octet-stream")
            connection.putheader("Content-Length", str(item["size"]))
            connection.endheaders()
            connection.send(data)
            partials = self.directory / "received" / ".portal-drop-partial"
            wait_until(lambda: any(path.stat().st_size >= len(data) for path in partials.glob("*")))
        finally:
            connection.close()


def smoke(desktop):
    desktop.start()
    invite = urlparse(desktop.cli("pair", "--address", f"127.0.0.1:{desktop.port}", "--print-only"))
    assert invite.scheme == "portaldrop" and invite.port == desktop.port
    query = parse_qs(invite.query)
    desktop.fp = query["fp"][0]
    assert query["v"] == ["1"]
    assert len(desktop.fp) == 64
    desktop.request("GET", "/v1/outbox", authorized=False, code=401)

    pair = {"code": query["code"][0], "token": desktop.token, "device_os": "android", "device_name": "SmokePixel"}
    _, pair_headers = desktop.request("POST", "/v1/pair", authorized=False, payload=pair, code=204, return_headers=True)
    header_keys = {k.lower(): v for k, v in pair_headers.items()}
    assert header_keys["x-portal-drop-os"] in ("linux", "windows", "macos"), header_keys
    assert header_keys["x-portal-drop-version"] == "3", header_keys

    desktop.request("POST", "/v1/pair", authorized=False, payload=pair, code=204)
    desktop.request("POST", "/v1/pair", authorized=False, payload={**pair, "token": secrets.token_hex(32)}, code=403)

    now = int(time.time() * 1000)
    message = {"id": str(uuid.uuid4()), "kind": "text", "ts": now, "text": "From Android ✓"}
    expected_text_receipt = {"received": True, "offset": 0}
    assert desktop.request("POST", "/v1/inbox", payload=message) == expected_text_receipt
    assert desktop.request("POST", "/v1/inbox", payload=message) == expected_text_receipt
    assert desktop.cli("history").count(message["text"]) == 1

    desktop.cli("send-text", "Queued on Linux")
    data = bytes(range(256)) * 4096
    source = desktop.directory / "from-linux.bin"
    source.write_bytes(data)
    desktop.cli("send-file", str(source))
    source.unlink()
    desktop.stop()
    desktop.start()
    outbox = desktop.request("GET", "/v1/outbox")
    items = {item["kind"]: item for item in outbox}
    text, file = items["text"], items["file"]
    assert text["text"] == "Queued on Linux"
    assert file["sha256"] == hashlib.sha256(data).hexdigest()
    assert desktop.request("GET", f"/v1/outbox/{file['id']}/file?offset=12345") == data[12345:]
    for item in outbox:
        for _ in range(2):
            desktop.request("POST", f"/v1/outbox/{item['id']}/ack", code=204)
    assert desktop.request("GET", "/v1/outbox") == []

    target = desktop.directory / "received" / "Compressed" / "from-android.zip"
    incoming = {"id": str(uuid.uuid4()), "kind": "file", "ts": now, "name": target.name,
                "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    assert desktop.request("POST", "/v1/inbox", payload=incoming) == {"received": False, "offset": 0}
    desktop.start_interrupted_upload(incoming, data[:123456])
    assert not target.exists()
    desktop.stop()
    desktop.start()
    receipt = desktop.request("POST", "/v1/inbox", payload=incoming)
    assert not receipt["received"] and 0 < receipt["offset"] < len(data), receipt
    offset = receipt["offset"]
    upload = f"/v1/inbox/{incoming['id']}/file?offset={offset}"
    desktop.request("PUT", upload, payload=data[offset:], code=204)
    assert target.read_bytes() == data
    receipt = {"received": True, "offset": len(data)}
    assert desktop.request("POST", "/v1/inbox", payload=incoming) == receipt
    assert target.name in desktop.cli("history")
    desktop.cli("clear-history")
    assert target.name not in desktop.cli("history")
    assert target.exists()
    assert desktop.request("POST", "/v1/inbox", payload=incoming) == receipt
    assert target.name not in desktop.cli("history")

    modern_smoke(desktop)
    v3_smoke(desktop)

    desktop.cli("unpair")
    desktop.request("GET", "/v1/outbox", code=401)
    desktop.request("POST", "/v1/pair", authorized=False, payload=pair, code=403)
    print("PASS: pairing, v1/v2/v3 compatibility, device info, SSE instant push, chat delete, 10m TTL expiry, resume, revocation")


def modern_smoke(desktop):
    chat_id = str(uuid.uuid4())
    clock = int(time.time() * 1000)
    chat = {"id": chat_id, "title": "Release notes", "created_at": clock, "updated_at": clock}
    path = f"/v1/chats/{chat_id}"
    desktop.request("PUT", path, payload=chat, code=204, version=2)
    renamed = {**chat, "title": "Build logs", "updated_at": clock + 1}
    desktop.request("PUT", path, payload=renamed, code=204, version=2)
    desktop.request("PUT", path, payload=chat, code=204, version=2)
    assert renamed in desktop.request("GET", "/v1/chats", version=2)

    exact_text = ("\n  **Build log**   \r\n```json\r\n{\"ready\": true}\r\n```\n"
                  + "unicode ✓ and indentation  \n" * 15000 + "\r\n  ").encode()
    sent = subprocess.check_output([*desktop.cmd, "send-text", "--chat", chat_id, "-"], input=exact_text)
    sent_id = sent.decode().split()[-1]
    assert desktop.request("GET", "/v1/outbox") == [], "v1 peer must defer modern items"
    item, = desktop.request("GET", "/v1/outbox", version=2)
    assert item["id"] == sent_id and item["chat_id"] == chat_id and item["kind"] == "text"
    assert item["text_size"] == len(exact_text) and "text" not in item
    assert item["text_sha256"] == hashlib.sha256(exact_text).hexdigest()
    offset = 517
    downloaded = desktop.request("GET", f"/v1/outbox/{sent_id}/file?offset={offset}", version=2)
    assert downloaded == exact_text[offset:]
    desktop.request("POST", f"/v1/outbox/{sent_id}/ack", code=204, version=2)

    incoming = {**item, "id": str(uuid.uuid4()), "ts": clock + 2, "reply_to": sent_id}
    target = f"/v1/inbox/{incoming['id']}/file?offset=0"
    assert desktop.request("POST", "/v1/inbox", payload=incoming, version=2) == {"received": False, "offset": 0}
    desktop.request("PUT", target, payload=exact_text, code=204, version=2)
    assert desktop.request("POST", "/v1/inbox", payload=incoming, version=2) == {
        "received": True, "offset": len(exact_text),
    }
    args = ("history", "--json", "--chat", chat_id, "--limit", "1")
    page = json.loads(desktop.cli(*args))
    newest, = page["items"]
    assert newest["id"] == incoming["id"] and newest["reply_to"] == sent_id and newest["text_truncated"]
    older = json.loads(desktop.cli(*args, "--before", str(page["next_before"])))
    assert [row["id"] for row in older["items"]] == [sent_id] and older["next_before"] is None
    for message_id in (incoming["id"], sent_id):
        assert subprocess.check_output([*desktop.cmd, "message-text", message_id]) == exact_text
    desktop.cli("delete-message", incoming["id"])
    assert [row["id"] for row in json.loads(desktop.cli(*args))["items"]] == [sent_id]
    assert desktop.request("POST", "/v1/inbox", payload=incoming, version=2)["received"]
    assert [row["id"] for row in json.loads(desktop.cli(*args))["items"]] == [sent_id]


def v3_smoke(desktop):
    # 1. Device info & presence
    info = desktop.request("GET", "/v1/info", version=3)
    assert info["protocol_version"] == 3 and info["os"] in ("linux", "windows", "macos")

    # 2. SSE instant push (/v1/events) wakes in milliseconds when CLI queues a message
    ready_event = threading.Event()
    sync_event = threading.Event()
    sse_conn = desktop.connect(timeout=10)

    def read_sse():
        sse_conn.putrequest("GET", "/v1/events")
        sse_conn.putheader("Authorization", f"Bearer {desktop.token}")
        sse_conn.putheader("X-Portal-Drop-Version", "3")
        sse_conn.putheader("X-Portal-Drop-OS", "android")
        sse_conn.putheader("X-Portal-Drop-Name", "SmokePixel")
        sse_conn.endheaders()
        resp = sse_conn.getresponse()
        assert resp.status == 200, resp.status
        event_name = ""
        while True:
            raw = resp.readline()
            if not raw:
                break
            line = raw.decode("utf-8", errors="replace").strip()
            if line.startswith("event:"):
                event_name = line.split(":", 1)[1].strip()
            elif line == "":
                if event_name == "ready":
                    ready_event.set()
                elif event_name == "sync":
                    sync_event.set()
                    break
                event_name = ""

    reader = threading.Thread(target=read_sse, daemon=True)
    reader.start()
    assert ready_event.wait(timeout=4), "SSE ready event timed out"

    status = json.loads(desktop.cli("status", "--json"))
    assert status["paired"] and status["connected"] and status["peer_name"] == "SmokePixel", status

    t0 = time.monotonic()
    desktop.cli("send-text", "Instant push check")
    assert sync_event.wait(timeout=2.0), "SSE did not push instantaneously on CLI send"
    elapsed = time.monotonic() - t0
    assert elapsed < 1.5, f"Expected sub-second push, took {elapsed:.2f}s"
    sse_conn.close()
    reader.join(timeout=2)

    out = desktop.request("GET", "/v1/outbox", version=3)
    assert len(out) == 1 and out[0]["text"] == "Instant push check"
    desktop.request("POST", f"/v1/outbox/{out[0]['id']}/ack", code=204, version=3)

    # 3. Chat deletion sync
    temp_chat = json.loads(desktop.cli("new-chat", "Ephemeral Chat"))
    assert any(c["id"] == temp_chat["id"] for c in json.loads(desktop.cli("chats", "--json")))
    desktop.cli("delete-chat", temp_chat["id"])
    assert all(c["id"] != temp_chat["id"] for c in json.loads(desktop.cli("chats", "--json")))
    v3_chats = desktop.request("GET", "/v1/chats", version=3)
    tombstone = next(c for c in v3_chats if c["id"] == temp_chat["id"])
    assert tombstone.get("deleted") is True
    v2_chats = desktop.request("GET", "/v1/chats", version=2)
    assert all(c["id"] != temp_chat["id"] for c in v2_chats)

    # 3b. Batch message & file deletion (including optional --delete-files cleanup)
    batch_msg = desktop.cli("send-text", "Batch delete text").split()[-1]
    batch_file_data = b"batch delete received file content"
    batch_incoming = {
        "id": str(uuid.uuid4()),
        "kind": "file",
        "ts": int(time.time() * 1000),
        "name": "batch-remove.txt",
        "size": len(batch_file_data),
        "sha256": hashlib.sha256(batch_file_data).hexdigest(),
    }
    assert desktop.request("POST", "/v1/inbox", payload=batch_incoming, version=3) == {"received": False, "offset": 0}
    desktop.request("PUT", f"/v1/inbox/{batch_incoming['id']}/file?offset=0", payload=batch_file_data, code=204, version=3)
    batch_saved = desktop.directory / "received" / "Documents" / "batch-remove.txt"
    assert batch_saved.is_file()
    desktop.cli("delete-message", "--delete-files", batch_msg, batch_incoming["id"])
    assert not batch_saved.exists()
    hist_after_batch = json.loads(desktop.cli("history", "--json", "--limit", "30"))["items"]
    assert all(row["id"] not in (batch_msg, batch_incoming["id"]) for row in hist_after_batch)

    # 4. Temporary TTL message gating (v2 defers, v3 receives) and auto-expiry
    desktop.cli("send-text", "--ttl", "10", "Self destruct secret")
    assert desktop.request("GET", "/v1/outbox", version=2) == [], "v2 peer must defer TTL items"
    ttl_out = desktop.request("GET", "/v1/outbox", version=3)
    assert len(ttl_out) == 1 and ttl_out[0]["ttl_sec"] == 10
    ttl_id = ttl_out[0]["id"]
    desktop.request("POST", f"/v1/outbox/{ttl_id}/ack", code=204, version=3)
    hist_now = json.loads(desktop.cli("history", "--json", "--limit", "5"))
    assert any(row["id"] == ttl_id and row.get("expires_at") for row in hist_now["items"])
    # Also test temporary incoming file auto-deletes from disk & history after TTL
    temp_file_data = b"temporary secret file payload"
    temp_incoming = {
        "id": str(uuid.uuid4()),
        "kind": "file",
        "ts": int(time.time() * 1000),
        "name": "secret-temp.txt",
        "size": len(temp_file_data),
        "sha256": hashlib.sha256(temp_file_data).hexdigest(),
        "ttl_sec": 10,
    }
    assert desktop.request("POST", "/v1/inbox", payload=temp_incoming, version=3) == {"received": False, "offset": 0}
    desktop.request("PUT", f"/v1/inbox/{temp_incoming['id']}/file?offset=0", payload=temp_file_data, code=204, version=3)
    temp_saved = desktop.directory / "received" / "Documents" / "secret-temp.txt"
    assert temp_saved.is_file()
    wait_until(
        lambda: not temp_saved.exists()
        and all(row["id"] not in (ttl_id, temp_incoming["id"])
                for row in json.loads(desktop.cli("history", "--json", "--limit", "20"))["items"]),
        timeout=16,
    )


if __name__ == "__main__":
    binary = Path(sys.argv[1] if len(sys.argv) > 1 else "linux/target/debug/portal-drop").resolve()
    with tempfile.TemporaryDirectory(prefix="portal-drop-smoke-") as directory:
        desktop = Desktop(binary, Path(directory))
        try:
            smoke(desktop)
        finally:
            desktop.stop()
