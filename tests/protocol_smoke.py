"""Exercise the Portal Drop server using legacy and current Android protocols."""

from contextlib import closing

import base64
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
    # A loopback-only daemon should advertise no LAN endpoints but should use
    # the service's selected port when the address is given explicitly.
    wait_until(lambda: (desktop.directory / "data" / "wake.json").is_file())
    assert json.loads(desktop.cli("lan-addresses")) == []
    invite = urlparse(desktop.cli("pair", "--address", "127.0.0.1", "--print-only"))
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
    assert int(header_keys["x-portal-drop-version"]) >= 4, header_keys

    desktop.request("POST", "/v1/pair", authorized=False, payload=pair, code=204)
    desktop.request("POST", "/v1/pair", authorized=False, payload={**pair, "token": secrets.token_hex(32)}, code=403)
    desktop.request("GET", "/v1/events", authorized=False, code=401)
    desktop.request("GET", "/v1/outbox", extra_headers={"Authorization": f"Bearer {secrets.token_hex(32)}"}, code=401)
    assert "connected_local_ips" not in json.loads(desktop.cli("status", "--json"))

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
    clipboard_and_search_smoke(desktop)
    v3_smoke(desktop)

    desktop.cli("unpair")
    desktop.request("GET", "/v1/outbox", code=401)
    desktop.request("POST", "/v1/pair", authorized=False, payload=pair, code=403)
    print("PASS: pairing, v1/v2/v3 compatibility, device info, SSE push, clipboard images, search, chat deletion, TTL expiry, resume, revocation")


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


def clipboard_and_search_smoke(desktop):
    # A small PNG as it arrives from either native desktop host.
    encoded = b"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=="
    pixels = base64.b64decode(encoded)
    chat = json.loads(desktop.cli("new-chat", "Clipboard tests"))
    queued = subprocess.check_output(
        [*desktop.cmd, "send-image", "--chat", chat["id"], "--ttl", "300"], input=encoded,
    )
    item_id = queued.decode().split()[-1]
    image = next(row for row in desktop.request("GET", "/v1/outbox", version=3) if row["id"] == item_id)
    assert image["name"].startswith("Clipboard_") and image["name"].endswith(".png")
    assert image["chat_id"] == chat["id"] and image["ttl_sec"] == 300
    assert desktop.request("GET", f"/v1/outbox/{item_id}/file?offset=0", version=3) == pixels
    desktop.request("POST", f"/v1/outbox/{item_id}/ack", code=204, version=3)

    long_text = ("First chapter\n" + "x" * 40_940 + "BoundarySearchToken").encode()
    queued = subprocess.check_output([*desktop.cmd, "send-text", "--chat", chat["id"], "-"], input=long_text)
    text_id = queued.decode().split()[-1]
    (match,) = json.loads(desktop.cli("search", "boundarysearchtoken"))
    assert match["id"] == text_id and match["chat_id"] == chat["id"]
    assert any(row["id"] == item_id for row in json.loads(desktop.cli("search", "CLIPBOARD_")))
    desktop.cli("delete-message", text_id)
    assert json.loads(desktop.cli("search", "boundarysearchtoken")) == []
    outbox = desktop.request("GET", "/v1/outbox", version=3)
    assert all(row["id"] != text_id for row in outbox)


def v3_smoke(desktop):
    # 1. Device info & presence
    info = desktop.request("GET", "/v1/info", version=3)
    assert info["protocol_version"] >= 4 and info["os"] in ("linux", "windows", "macos")

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
    assert status["connected_local_ips"] == ["127.0.0.1"], status

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


    # 5. Notification, sound & custom save folder settings CLI round-trip
    default_settings = json.loads(desktop.cli("settings"))
    for key in (
        "message_notify",
        "message_sound",
        "file_notify",
        "file_sound",
        "connected_notify",
        "connected_sound",
    ):
        assert default_settings[key] is True, default_settings
    assert "effective_receive_dir" in default_settings and "default_receive_dir" in default_settings
    custom_dir = desktop.directory / "custom-save"
    updated_settings = json.loads(
        desktop.cli(
            "settings",
            "--connected-notify=false",
            "--connected-sound=false",
            "--message-sound=false",
            "--save-dir",
            str(custom_dir),
        )
    )
    assert updated_settings["connected_notify"] is False and updated_settings["connected_sound"] is False
    assert updated_settings["message_notify"] is True and updated_settings["message_sound"] is False
    assert updated_settings.get("receive_dir") == str(custom_dir)
    assert custom_dir.is_dir()
    assert json.loads(desktop.cli("settings")) == updated_settings
    reset_settings = json.loads(desktop.cli("settings", "--reset-save-dir"))
    assert "receive_dir" not in reset_settings

    # 6. Concurrent text message delivery while a file upload is in progress (per-item lock)
    slow_data = b"0123456789abcdef" * 4096
    slow_file = {
        "id": str(uuid.uuid4()),
        "kind": "file",
        "ts": int(time.time() * 1000),
        "name": "concurrent-video.mp4",
        "size": len(slow_data),
        "sha256": hashlib.sha256(slow_data).hexdigest(),
    }
    assert desktop.request("POST", "/v1/inbox", payload=slow_file, version=3) == {"received": False, "offset": 0}
    conn = desktop.connect(timeout=10)
    conn.putrequest("PUT", f"/v1/inbox/{slow_file['id']}/file?offset=0")
    conn.putheader("Authorization", f"Bearer {desktop.token}")
    conn.putheader("Content-Type", "application/octet-stream")
    conn.putheader("Content-Length", str(len(slow_data)))
    conn.putheader("X-Portal-Drop-Version", "3")
    conn.endheaders()
    half = len(slow_data) // 2
    conn.send(slow_data[:half])
    # Verify progress reports started_at while upload is mid-stream
    wait_until(
        lambda: any(
            p["id"] == slow_file["id"] and p["direction"] == "received" and p.get("started_at", 0) > 0
            for p in json.loads(desktop.cli("progress", "--json"))
        ),
        timeout=5,
    )
    # Send an inline text message on a separate connection while file upload is still open!
    concurrent_msg = {
        "id": str(uuid.uuid4()),
        "kind": "text",
        "ts": int(time.time() * 1000),
        "text": "Hello while video is uploading!",
    }
    assert desktop.request("POST", "/v1/inbox", payload=concurrent_msg, version=3) == {"received": True, "offset": 0}
    # Finish the paused upload cleanly
    conn.send(slow_data[half:])
    resp = conn.getresponse()
    resp.read()
    conn.close()
    assert resp.status == 204, resp.status
    assert (desktop.directory / "received" / "Videos" / "concurrent-video.mp4").read_bytes() == slow_data

    transfer_controls(desktop)
    folders_and_media(desktop)


def transfer_controls(desktop):
    request = desktop.request
    payload = bytes(range(256)) * 1024
    incoming = {
        "id": str(uuid.uuid4()), "kind": "file", "ts": int(time.time() * 1000),
        "name": "transfer-control.txt", "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest(),
    }
    item_id = incoming["id"]
    route = f"/v1/transfers/received/{item_id}"
    upload = f"/v1/inbox/{item_id}/file"
    assert request("POST", "/v1/inbox", payload=incoming, version=4)["offset"] == 0
    partial_bytes = payload[:45_000]
    partials = desktop.directory / "received" / ".portal-drop-partial"
    with closing(desktop.connect()) as connection:
        connection.putrequest("PUT", f"{upload}?offset=0")
        connection.putheader("Authorization", f"Bearer {desktop.token}")
        connection.putheader("Content-Length", str(len(payload)))
        connection.putheader("X-Portal-Drop-Version", "4")
        connection.endheaders()
        connection.send(partial_bytes)
        wait_until(lambda: any(p.stat().st_size == len(partial_bytes) for p in partials.glob("*" + item_id)))
        assert json.loads(desktop.cli("transfer-control", "received", item_id, "pause")) == {"state": "paused"}
        assert connection.getresponse().status == 423
    request("POST", "/v1/inbox", payload=incoming, version=4, code=423)
    rows = json.loads(desktop.cli("history", "--json"))["items"]
    paused = next(row for row in rows if row["id"] == item_id)
    assert paused["transfer_state"] == "paused" and "path" not in paused
    desktop.stop()
    desktop.start()
    assert request("GET", route) == {"state": "paused"}
    for _ in range(2):
        assert request("POST", route, payload={"action": "resume"}) == {"state": "queued"}
    assert request("POST", "/v1/inbox", payload=incoming, version=4)["offset"] == len(partial_bytes)
    request("PUT", f"{upload}?offset={len(partial_bytes)}", payload=payload[len(partial_bytes):], version=4, code=204)
    assert request("POST", route, payload={"action": "cancel"}) == {"state": "delivered"}

    failed_id = str(uuid.uuid4())
    failed_item = {**incoming, "id": failed_id, "name": "transfer-retry.txt"}
    failed_route = f"/v1/transfers/received/{failed_id}"
    failed_upload = f"/v1/inbox/{failed_id}/file?offset=0"
    request("POST", "/v1/inbox", payload=failed_item, version=4)
    request("PUT", failed_upload, payload=b"x" * len(payload), version=4, code=422)
    assert request("GET", failed_route) == {"state": "failed"}
    request("POST", "/v1/inbox", payload=failed_item, version=4, code=424)
    assert request("POST", failed_route, payload={"action": "retry"}) == {"state": "queued"}
    assert request("POST", "/v1/inbox", payload=failed_item, version=4)["offset"] == 0
    desktop.start_interrupted_upload(failed_item, partial_bytes)
    assert request("POST", failed_route, payload={"action": "cancel"}) == {"state": "canceled"}
    assert request("POST", failed_route, payload={"action": "resume"}) == {"state": "canceled"}
    assert not any(partials.glob("*" + failed_id))
    request("POST", "/v1/inbox", payload=failed_item, version=4, code=410)

    source = desktop.directory / "original.txt"
    source.write_bytes(payload)
    sent_id = desktop.cli("send-file", str(source)).split()[-1]
    sent_route = f"/v1/transfers/sent/{sent_id}"
    download = f"/v1/outbox/{sent_id}/file?offset=0"
    assert request("POST", sent_route, payload={"action": "pause"}) == {"state": "paused"}
    for version in (1, 4):
        assert sent_id not in {row["id"] for row in request("GET", "/v1/outbox", version=version)}
    request("GET", download, code=423)
    assert request("POST", sent_route, payload={"action": "resume"}) == {"state": "queued"}
    assert request("GET", download) == payload
    assert json.loads(desktop.cli("transfer-control", "sent", sent_id, "cancel")) == {"state": "canceled"}
    request("GET", download, code=410)
    request("POST", f"/v1/outbox/{sent_id}/ack", code=410)
    assert source.read_bytes() == payload
    print("PASS: durable transfer controls, partial resume, failure retry, and cancel cleanup")


def folders_and_media(desktop):
    request = desktop.request
    source = desktop.directory / "Project"
    (source / "Notes").mkdir(parents=True)
    (source / "readme.txt").write_bytes(b"root")
    (source / "Notes" / "draft.txt").write_bytes(b"nested")
    assert json.loads(desktop.cli("send-folder", str(source))) == {"sent": 2}
    assert not any(row.get("relative_path") for row in request("GET", "/v1/outbox", version=4))
    paths = {
        entry["relative_path"]: entry
        for entry in request("GET", "/v1/outbox", version=5)
        if "relative_path" in entry
    }
    assert set(paths) == {"Project/readme.txt", "Project/Notes/draft.txt"}
    for path, entry in paths.items():
        assert request("GET", f'/v1/outbox/{entry["id"]}/file?offset=0', version=5) == (
            source.parent / path
        ).read_bytes()

    payload = b"GIF89a desktop media fixture"
    item = {
        "id": str(uuid.uuid4()), "kind": "file", "ts": int(time.time() * 1000),
        "name": "preview.gif", "relative_path": "Album/Drafts/preview.gif",
        "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest(),
    }
    for unsafe in ("../outside/preview.gif", "Album/../../preview.gif",
                   "/Album/preview.gif", "Album/Other.gif", "Album/NUL/preview.gif"):
        request("POST", "/v1/inbox", payload={**item, "relative_path": unsafe}, version=5, code=400)
    request("POST", "/v1/inbox", payload=item, version=5)
    request("PUT", f'/v1/inbox/{item["id"]}/file?offset=0', payload=payload, version=5, code=204)
    saved = desktop.directory / "received" / "Folders" / item["relative_path"]
    assert saved.read_bytes() == payload
    gallery = json.loads(desktop.cli("shared-media", "photos-videos"))["items"]
    assert any(row["id"] == item["id"] and Path(row["path"]) == saved for row in gallery)
    media = json.loads(desktop.cli("media-path", item["id"]))
    assert Path(media["path"]) == saved and media["mime"] == "image/gif"
    print("PASS: nested folders, protocol compatibility, path validation, and shared media")

if __name__ == "__main__":
    binary = Path(sys.argv[1] if len(sys.argv) > 1 else "linux/target/debug/portal-drop").resolve()
    with tempfile.TemporaryDirectory(prefix="portal-drop-smoke-") as directory:
        desktop = Desktop(binary, Path(directory))
        try:
            smoke(desktop)
        finally:
            desktop.stop()
