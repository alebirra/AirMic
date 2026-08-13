# AirMic

Usa il microfono del telefono Android come microfono di sistema del PC Windows, in tempo reale su rete locale (WiFi).

L'audio viaggia in **PCM non compresso** (48 kHz, 16 bit, mono) su UDP: nessuna perdita di qualità o volume, latenza end-to-end tipica di **20–40 ms** — ottimizzata per il gaming (frame da 5 ms, jitter buffer da 10 ms, WASAPI a 10 ms), impercettibile in conversazione su Discord, WhatsApp Desktop e videoconferenze.

## Come funziona

```
[Telefono Android]                 [PC Windows]
 AudioRecord 48kHz mono ──UDP──▶ AirMic.exe ──▶ CABLE Input (VB-Cable)
 (app AirMic)            47811   jitter buffer        │
                                     │               ▼
                                     │        CABLE Output = microfono
                                     │        di sistema per Discord,
 ◀── AIRMIC_ACK_V1 ~1/s ─────────────┘        WhatsApp, browser, giochi…

 Scoperta automatica: UDP broadcast porta 47800
```

Il protocollo completo è documentato in [PROTOCOL.md](PROTOCOL.md).

## Requisiti

**PC (Windows)**
- [VB-CABLE](https://vb-audio.com/Cable/) installato (driver gratuito, una tantum, richiede riavvio). È ciò che crea il microfono virtuale di sistema.
- .NET 6 Desktop Runtime **solo** se usi la build framework-dependent; la build self-contained non richiede nulla.
- Al primo avvio, consenti AirMic nel prompt del firewall di Windows (UDP in ingresso).

**Telefono**
- Android 8.0 (API 26) o superiore, stessa WiFi del PC.

## Uso

1. Avvia **AirMic.exe** sul PC. Verifica che indichi "Cavo virtuale rilevato". L'app resta in ascolto e si riduce nell'area di notifica.
2. Apri **AirMic** sul telefono e tocca **Ricerca automatica** — trova il PC da solo. Se la rete blocca il broadcast (es. AP isolation), inserisci l'IP del PC (mostrato nella finestra di AirMic sul PC) e tocca **Connetti**.
3. Premi **AVVIA** sul telefono: lo stato diventa "Connesso a \<nome PC\>".
4. Nelle app Windows (Discord, WhatsApp, …) seleziona come microfono **"CABLE Output (VB-Audio Virtual Cable)"**.
5. **STOP** per terminare. Se la WiFi cade e ritorna, lo stream riprende da solo.

## Build

### Script di Release Rapido (Windows)

Puoi generare contemporaneamente sia la versione **Plug & Play (Self-Contained, zero requisiti)** che la versione **Leggera (Framework-Dependent)** con un singolo comando:

- **Doppio click su:** `build-release.bat` oppure da PowerShell:
  ```powershell
  .\build-release.ps1
  ```
Gli archivi `.zip` pronti per GitHub vengono salvati direttamente nella cartella `release/`:
- `AirMic-v1.0.0-windows-x64-selfcontained.zip` (Plug & Play, non richiede .NET installato)
- `AirMic-v1.0.0-windows-x64-lightweight.zip` (Leggera ~0.9 MB, richiede .NET Desktop Runtime)
- `SHA256SUMS.txt` (Checksum di integrità)

### Build manuale (CLI)

```bash
cd windows
dotnet build -c Release
```

Test headless del ricevitore (senza UI):

```
AirMic.exe --test 10          # una riga di statistiche al secondo
python ../tools/simulate_phone.py   # simula il telefono: discovery + stream + verifica ACK
```

### App Android

Richiede JDK 17+ e Android SDK (compileSdk 34). Nessuna dipendenza esterna: APK ≈ **45 KB**.

```
cd android
gradle assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

Per la release: `gradle assembleRelease` dopo aver configurato la firma.

## Struttura

```
airmic/
├── PROTOCOL.md            # contratto di rete v1 (porte, pacchetti, jitter buffer)
├── android/               # app Android (Java puro, zero dipendenze)
│   └── app/src/main/java/com/airmic/app/
│       ├── MainActivity.java      # UI: stato, AVVIA/STOP, livello, ricerca, IP manuale
│       ├── MicStreamService.java  # cattura mic + invio UDP + ricezione ACK (foreground service)
│       ├── Discovery.java         # scoperta automatica via broadcast
│       └── LevelMeterView.java    # misuratore di livello
├── windows/               # app Windows (WPF, .NET 6, unica dipendenza: NAudio)
│   └── src/AirMic/
│       ├── Audio/UdpAudioReceiver.cs  # ricezione + ACK
│       ├── Audio/JitterBuffer.cs      # riordino pacchetti (30 ms, silence-fill)
│       ├── Audio/AudioEngine.cs       # orchestrazione condivisa UI/test
│       ├── Audio/CableRenderer.cs     # rendering WASAPI → CABLE Input
│       ├── Net/DiscoveryResponder.cs  # risposta scoperta automatica
│       ├── MainWindow.xaml(.cs)       # UI + tray icon
│       └── TestRunner.cs              # modalità --test headless
└── tools/
    ├── simulate_phone.py  # test end-to-end: simula il telefono
    └── send_test.py       # invio pacchetti sintetici rapido
```

## Note e limiti

- **Firewall**: al primo avvio Windows chiede di autorizzare AirMic (UDP 47800/47811 in ingresso) — necessario.
- **Reti con AP isolation** (molti hotspot e reti ospiti): la scoperta automatica fallisce; usa la connessione manuale con IP. Se anche quella fallisce, la rete blocca il traffico tra client: nessuna app di questo tipo può funzionarci.
- Il cavo virtuale lavora a 48 kHz: stesso sample rate dello stream, nessun resampling né conversione.
- Il volume è pass-through 1:1. Se serve regolarlo, usa il mixer di Windows o dell'app di destinazione.
