"""Mittente sintetico AirMic: invia pacchetti AM01 (sine 440 Hz) a 127.0.0.1:47811
e verifica la ricezione degli AIRMIC_ACK_V1 sulla porta sorgente."""
import math
import socket
import struct
import sys
import time

DEST = ("127.0.0.1", 47811)
SECONDS = float(sys.argv[1]) if len(sys.argv) > 1 else 5.5
FREQ = 440.0
RATE = 48000
AMP = 0.6

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", 0))
print(f"source port: {sock.getsockname()[1]}")

total = int(SECONDS * 200)  # un pacchetto ogni 5 ms
t0 = time.perf_counter()
for seq in range(total):
    payload = bytearray(480)
    for i in range(240):
        v = int(32767 * AMP * math.sin(2 * math.pi * FREQ * (seq * 240 + i) / RATE))
        struct.pack_into("<h", payload, i * 2, v)
    sock.sendto(b"AM01" + struct.pack("<I", seq) + bytes(payload), DEST)
    target = t0 + (seq + 1) * 0.005
    delay = target - time.perf_counter()
    if delay > 0:
        time.sleep(delay)

print(f"sent {total} packets in {time.perf_counter() - t0:.2f}s")

# Gli ACK (~1/s) arrivano sulla porta sorgente: leggi quelli in coda.
sock.settimeout(2.0)
acks = 0
try:
    while True:
        data, addr = sock.recvfrom(1024)
        print(f"ACK from {addr}: {data.decode()}")
        acks += 1
except (socket.timeout, OSError):
    # OSError/10054: il server di test può chiudere mentre il mittente è ancora attivo
    pass
print(f"ACK totali ricevuti: {acks}")
sys.exit(0 if acks > 0 else 1)
