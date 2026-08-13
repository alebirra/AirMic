#!/usr/bin/env python
# Simula l'app Android AirMic: discovery broadcast -> streaming PCM -> verifica ACK.
import socket, struct, math, time, sys

# 1) Discovery broadcast (come Discovery.java)
disc = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
disc.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
disc.settimeout(2.0)
disc.sendto(b"AIRMIC_DISCOVER_V1", ("255.255.255.255", 47800))
try:
    data, addr = disc.recvfrom(256)
except socket.timeout:
    print("DISCOVERY: nessuna risposta"); sys.exit(1)
msg = data.decode()
print("DISCOVERY: risposta da", addr[0], "->", msg)
parts = msg.split("|")
assert parts[0] == "AIRMIC_SERVER_V1", "risposta discovery non valida"
pc_ip, audio_port = addr[0], int(parts[2])
disc.close()

# 2) Streaming 600 pacchetti (3 s) identici a MicStreamService
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("0.0.0.0", 0))
s.settimeout(0.2)
for seq in range(600):
    payload = bytearray()
    for i in range(240):
        v = int(0.5 * 32767 * math.sin(2 * math.pi * 440 * (seq * 240 + i) / 48000))
        payload += struct.pack("<h", v)
    s.sendto(b"AM01" + struct.pack("<I", seq) + bytes(payload), (pc_ip, audio_port))
    time.sleep(0.005)

# 3) Raccolta ACK per 3 s
acks = []
end = time.time() + 3.0
while time.time() < end:
    try:
        d, _ = s.recvfrom(256)
        if d.decode().startswith("AIRMIC_ACK_V1|"):
            acks.append(d.decode())
    except socket.timeout:
        pass
print("ACK ricevuti:", acks)
assert acks, "nessun ACK ricevuto"
assert int(acks[-1].split("|")[1]) >= 500, "ultimo seq ACK inatteso: " + acks[-1]
print("INTEGRATION TEST OK")
