#!/usr/bin/env python3
"""Cari HP dari homelab: bunyikan Mocipet & ambil lokasi GPS-nya.

Pakai: python3 find_phone.py <ip-hp> <token> [find_phone|ping|stop_ring] [detik]
Frame LanLink (Java DataOutputStream): byte tipe=3, UTF nama, int panjang, JSON.
"""
import json, socket, struct, sys

def utf(s: str) -> bytes:
    b = s.encode()
    return struct.pack(">H", len(b)) + b

def send(ip, token, cmd="find_phone", seconds=30, port=47474):
    body = json.dumps({"cmd": cmd, "token": token, "seconds": seconds}).encode()
    with socket.create_connection((ip, port), timeout=40) as s:
        s.sendall(struct.pack(">b", 3) + utf("homelab") + struct.pack(">i", len(body)) + body)
        n = struct.unpack(">H", s.recv(2))[0]
        data = b""
        while len(data) < n:
            data += s.recv(n - len(data))
    return json.loads(data)

if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    r = send(sys.argv[1], sys.argv[2], *(sys.argv[3:4] or ["find_phone"]),
             *([int(sys.argv[4])] if len(sys.argv) > 4 else []))
    print(json.dumps(r, indent=2))
    if "lat" in r:
        print(f"https://maps.google.com/?q={r['lat']},{r['lon']}")
