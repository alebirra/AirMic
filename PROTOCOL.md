# AirMic — Protocollo di rete (v1)

Streaming audio microfono Android → PC Windows su LAN. UDP, PCM non compresso, latenza minima.

Tutti i campi multi-byte sono **little-endian**.

## Porte

| Uso | Protocollo | Porta |
|---|---|---|
| Scoperta (discovery) | UDP broadcast/unicast | **47800** |
| Streaming audio | UDP | **47811** |

## 1. Scoperta automatica

Il telefono cerca il PC; il PC è in ascolto solo quando l'app Windows è avviata.

1. Il telefono invia in broadcast (`255.255.255.255`, porta **47800**) il pacchetto:

   ```
   "AIRMIC_DISCOVER_V1"        (18 byte ASCII, nessun terminatore)
   ```

   Ripetuto ogni 500 ms, fino a 10 tentativi (5 s totali).

2. Il PC, in ascolto su UDP **47800**, risponde in **unicast** al mittente (IP/porta sorgente del pacchetto di discovery):

   ```
   "AIRMIC_SERVER_V1|<nomePc>|<portaAudio>"
   ```

   Esempio: `AIRMIC_SERVER_V1|DESKTOP-ALESS|47811`

3. Il telefono usa IP sorgente della risposta + `portaAudio` come destinazione dello streaming.

### Connessione manuale

L'utente inserisce l'IP del PC nell'app; il telefono invia lo stesso pacchetto
`AIRMIC_DISCOVER_V1` in **unicast** a quell'IP:47800. Se riceve risposta usa la porta
indicata, altrimenti ripiega sulla porta di default 47811 (lo streaming funziona anche
senza handshake di discovery: il PC accetta pacchetti audio da chiunque in LAN).

## 2. Streaming audio (telefono → PC, UDP porta 47811)

Formato audio: **PCM signed 16-bit, mono, 48000 Hz** (≈ 768 kbit/s, trascurabile su WiFi).
Nessuna compressione: qualità e volume invariati.

Ogni datagramma UDP:

| Offset | Dimensione | Contenuto |
|---|---|---|
| 0 | 4 | Magic ASCII `"AM01"` |
| 4 | 4 | `seq` uint32 — numero di sequenza, parte da 0 a ogni avvio stream, +1 per pacchetto |
| 8 | N | Payload PCM: **240 campioni × 2 byte = 480 byte** (5 ms di audio) |

Dimensione totale pacchetto: 488 byte. Il telefono invia un pacchetto ogni 5 ms
(frequenza dettata dal ritmo di lettura di `AudioRecord`).

Lo stream è **senza connessione**: il telefono inizia a inviare alla pressione di Start
e smette alla pressione di Stop. Il PC considera lo stream attivo se riceve pacchetti
validi negli ultimi 500 ms. La "riconnessione" è implicita: se la rete cade e torna,
i pacchetti riprendono a fluire senza handshake.

## 3. ACK / stato connessione (PC → telefono)

Mentre riceve audio, il PC invia circa **1 volta al secondo** un pacchetto unicast
alla porta sorgente del telefono (quella da cui arrivano i pacchetti audio):

```
"AIRMIC_ACK_V1|<ultimoSeqRicevuto>"
```

Il telefono mostra lo stato **Connesso** solo se ha ricevuto un ACK negli ultimi 3 s;
altrimenti mostra "In attesa del PC…". Questo garantisce che l'indicatore di stato
rifletta una ricezione reale e non solo l'invio cieco di pacchetti.

## 4. Jitter buffer (lato PC)

Buffer di riordino basato su `seq`:

- profondità nominale **2 pacchetti (10 ms)** prima di iniziare la riproduzione;
- in caso di buco di sequenza, inserire 5 ms di silenzio e andare avanti;
- in caso di overflow (> 4 pacchetti accodati), scartare i più vecchi;
- su `seq` minore dell'ultimo riprodotto, scartare il pacchetto.

Latenza end-to-end attesa su WiFi domestica: **20–40 ms**, impercettibile in conversazione
e adatta al gaming. Il WASAPI lato PC lavora con buffer da 10 ms (fallback 20 ms).

## 5. Integrazione Windows

Il PC renderizza l'audio ricevuto sul dispositivo di riproduzione **"CABLE Input"
(VB-Audio Virtual Cable)**. Il driver espone automaticamente **"CABLE Output"** come
dispositivo di **registrazione** (microfono) di sistema: Discord, WhatsApp Desktop,
browser e giochi lo vedono come un normale microfono.

Requisito: [VB-CABLE](https://vb-audio.com/Cable/) installato (gratuito). L'app Windows
rileva la presenza del dispositivo all'avvio e mostra istruzioni chiare se assente.
La porta del cavo lavora a 48 kHz: stesso sample rate dello stream, nessun resampling.
