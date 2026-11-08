#!/usr/bin/env python3
"""One owner-only, read-only Angel One WebSocket V2 LTP evidence probe.

Runs on the existing EC2 host with the verified registered Elastic IP. Uses
the same four SSM owner parameters in RAM and the officially required client
network headers for login/profile. Subscribes to only one NSE cash equity LTP.
Never requests, modifies, cancels, or authorizes any live order.
No JWT, feed token, API key, MAC, TOTP or broker profile is printed or stored.
"""
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
import socket
import ssl
import struct
import time

import probe_owner_angelone_auth as auth

BROKER_HOST = "smartapisocket.angelone.in"
BROKER_PATH = "/smart-stream"
NSE_TOKEN = "2885"  # RELIANCE-EQ NSE symbol token; no private watchlist
MAX_TICK_SECONDS = 15
MAX_WAIT_SECONDS = 40


class SocketFailure(Exception):
    pass


def receive(sock, size):
    out = bytearray()
    while len(out) < size:
        chunk = sock.recv(size - len(out))
        if not chunk:
            raise SocketFailure("BROKER_WS_CONNECTION: CLOSED")
        out.extend(chunk)
    return bytes(out)


def send_frame(sock, opcode, payload):
    if len(payload) > 125:
        raise SocketFailure("BROKER_WS_SUBSCRIPTION: INVALID")
    mask = os.urandom(4)
    masked = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
    sock.sendall(bytes([0x80 | opcode, 0x80 | len(payload)]) + mask + masked)


def read_frame(sock):
    b1, b2 = receive(sock, 2)
    if (b2 & 0x80) != 0:
        raise SocketFailure("BROKER_WS_FRAME: INVALID")
    length = b2 & 0x7f
    if length == 126:
        length = struct.unpack("!H", receive(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", receive(sock, 8))[0]
    if length > 8192:
        raise SocketFailure("BROKER_WS_FRAME: OVERSIZED")
    return (b1 & 0x0f, (b1 & 0x80) != 0, receive(sock, length))


def verified_ltp(payload):
    if len(payload) != 51 or payload[0] != 1 or payload[1] != 1:
        return False
    raw_token = payload[2:27].split(b"\x00", 1)[0]
    if raw_token != NSE_TOKEN.encode():
        return False
    try:
        sequence = struct.unpack_from("<q", payload, 27)[0]
        exchange_ms = struct.unpack_from("<q", payload, 35)[0]
        price_paise = struct.unpack_from("<q", payload, 43)[0]
    except struct.error:
        return False
    age = time.time() - exchange_ms / 1000
    return sequence >= 0 and price_paise > 0 and -2 <= age <= MAX_TICK_SECONDS


def authenticate():
    auth.egress()
    headers = auth.verified_client_network_headers()
    print("BROKER_NETWORK_HEADERS: VERIFIED", flush=True)
    creds = auth.ssm_params()
    otp = auth.totp(creds["totp_secret"])
    login_headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "X-PrivateKey": creds["api_key"],
        "X-UserType": "USER",
        "X-SourceID": "WEB",
        **headers,
    }
    data = auth.request_json(auth.API_ROOT +
        "/rest/auth/angelbroking/user/v1/loginByPassword",
        "POST", login_headers, json.dumps({
            "clientcode": creds["client_code"],
            "password": creds["pin"],
            "totp": otp
        }, separators=(",", ":")).encode())
    jwt, feed = data.get("jwtToken"), data.get("feedToken")
    if not isinstance(jwt, str) or not jwt or not isinstance(feed, str) or not feed:
        raise SocketFailure("BROKER_LOGIN: MISSING_FEED_TOKEN")
    profile = auth.request_json(auth.API_ROOT +
        "/rest/secure/angelbroking/user/v1/getProfile",
        "GET", {**login_headers, "Authorization": "Bearer " + jwt})
    code = profile.get("clientcode", profile.get("clientCode"))
    if not isinstance(code, str) or code.upper() != creds["client_code"].upper():
        raise SocketFailure("BROKER_LOGIN: PROFILE_MISMATCH")
    print("BROKER_LOGIN: VERIFIED_WITH_FEED_TOKEN", flush=True)
    return jwt, feed, creds["api_key"], creds["client_code"]


def main():
    jwt, feed, api_key, client_code = authenticate()
    ws_key = base64.b64encode(os.urandom(16)).decode()
    lines = [
        "GET " + BROKER_PATH + " HTTP/1.1",
        "Host: " + BROKER_HOST,
        "Connection: Upgrade",
        "Upgrade: websocket",
        "Sec-WebSocket-Version: 13",
        "Sec-WebSocket-Key: " + ws_key,
        "Authorization: " + jwt,
        "x-api-key: " + api_key,
        "x-client-code: " + client_code,
        "x-feed-token: " + feed,
        "", ""
    ]
    with socket.create_connection((BROKER_HOST, 443), timeout=10) as tcp:
        with ssl.create_default_context().wrap_socket(tcp, server_hostname=BROKER_HOST) as sock:
            sock.settimeout(12)
            sock.sendall("\r\n".join(lines).encode("utf-8"))
            head = bytearray()
            while not head.endswith(b"\r\n\r\n") and len(head) < 8192:
                head.extend(receive(sock, 1))
            header_lines = bytes(head).split(b"\r\n")
            if not header_lines[0].startswith(b"HTTP/1.1 101 "):
                raise SocketFailure("BROKER_WS_HANDSHAKE: REJECTED")
            accept = None
            for row in header_lines[1:]:
                k, _, v = row.partition(b":")
                if k.lower() == b"sec-websocket-accept":
                    accept = v.strip()
            expected = base64.b64encode(hashlib.sha1(
                (ws_key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
            if accept != expected:
                raise SocketFailure("BROKER_WS_HANDSHAKE: INVALID_ACCEPT")
            print("BROKER_WS_HANDSHAKE: VERIFIED", flush=True)

            # One read-only LTP subscription for a public NSE cash equity.
            message = json.dumps({"correlationID": "minttickEipTick",
                "action": 1, "params": {"mode": 1, "tokenList": [
                    {"exchangeType": 1, "tokens": [NSE_TOKEN]}]}}, separators=(",", ":")).encode()
            send_frame(sock, 0x1, message)
            print("BROKER_WS_SUBSCRIPTION: SENT", flush=True)

            end = time.monotonic() + MAX_WAIT_SECONDS
            next_heartbeat = time.monotonic() + 22
            while time.monotonic() < end:
                if time.monotonic() >= next_heartbeat:
                    send_frame(sock, 0x1, b"ping")
                    next_heartbeat = time.monotonic() + 22
                sock.settimeout(min(5, max(0.5, end - time.monotonic())))
                try:
                    opcode, fin, frame = read_frame(sock)
                except socket.timeout:
                    continue
                if opcode == 0x9:  # reply to provider control ping
                    send_frame(sock, 0xA, frame)
                elif opcode == 0x8:
                    raise SocketFailure("BROKER_WS_CONNECTION: SERVER_CLOSED")
                elif opcode == 0x2 and fin and verified_ltp(frame):
                    print("BROKER_WS_LIVE_TICK: VERIFIED", flush=True)
                    print("BROKER_WS_TEST: READ_ONLY_NO_ORDERS", flush=True)
                    return
                elif opcode == 0x1 and fin:
                    # Neither control text nor responses are trustworthy prices.
                    try:
                        note = json.loads(frame)
                        if isinstance(note, dict) and note.get("status") is False:
                            raise SocketFailure("BROKER_WS_SUBSCRIPTION: REJECTED")
                    except (UnicodeDecodeError, ValueError):
                        pass
            raise SocketFailure("BROKER_WS_LIVE_TICK: NOT_OBSERVED")


if __name__ == "__main__":
    try:
        main()
    except auth.ProbeFailure as exc:
        print(str(exc), flush=True)
        raise SystemExit(2)
    except SocketFailure as exc:
        print(str(exc), flush=True)
        raise SystemExit(2)
    except (OSError, ssl.SSLError, TimeoutError):
        print("BROKER_WS_CONNECTION: UNAVAILABLE", flush=True)
        raise SystemExit(2)
    except Exception:
        print("BROKER_WS_TEST: INTERNAL_FAILURE", flush=True)
        raise SystemExit(2)
