using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace AirMic.Audio;

/// <summary>
/// Riceve lo stream UDP del telefono sulla porta 47811 (magic "AM01" + seq uint32 LE + 960 byte PCM).
/// Senza stato: qualunque pacchetto valido riprende lo stream. Invia "AIRMIC_ACK_V1|seq" ~1/s al mittente.
/// </summary>
public sealed class UdpAudioReceiver : IDisposable
{
    public const int Port = 47811;
    public const int HeaderBytes = 8;
    public const int PayloadBytes = 480;

    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private long _prevSeq = -1;
    private long _lastAckTick = -2000;
    private long _packets;
    private long _lost;
    private long _lastSeq = -1;

    public bool IsRunning { get; private set; }
    public DateTime LastPacketUtc { get; private set; } = DateTime.MinValue;
    public IPEndPoint? LastSource { get; private set; }

    public long PacketsReceived => Interlocked.Read(ref _packets);
    public long LostPackets => Interlocked.Read(ref _lost);
    public long LastSeq => Interlocked.Read(ref _lastSeq);

    public event Action<uint, byte[], IPEndPoint>? PacketReceived;

    public void Start()
    {
        if (IsRunning) return;
        _udp = new UdpClient(Port);
        TryDisableUdpConnReset(_udp.Client);
        _cts = new CancellationTokenSource();
        IsRunning = true;
        _ = Task.Run(() => ReceiveLoop(_udp, _cts.Token));
    }

    public void Stop()
    {
        if (!IsRunning) return;
        IsRunning = false;
        _cts?.Cancel();
        _udp?.Close();
        _udp = null;
        _prevSeq = -1;
    }

    private async Task ReceiveLoop(UdpClient udp, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try { result = await udp.ReceiveAsync(ct); }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) { if (!IsRunning) break; continue; }
            catch (Exception) { if (!IsRunning) break; continue; }

            byte[] d = result.Buffer;
            if (d.Length < HeaderBytes + PayloadBytes) continue;
            if (d[0] != 'A' || d[1] != 'M' || d[2] != '0' || d[3] != '1') continue;

            uint seq = BitConverter.ToUInt32(d, 4);
            var pcm = new byte[PayloadBytes];
            Buffer.BlockCopy(d, HeaderBytes, pcm, 0, PayloadBytes);

            long s = seq;
            long prev = Interlocked.Exchange(ref _prevSeq, s);
            if (prev >= 0 && s > prev + 1)
            {
                long gap = s - prev - 1;
                if (gap < 100_000) Interlocked.Add(ref _lost, gap); // gap enorme = riavvio stream, non perdita
            }
            Interlocked.Increment(ref _packets);
            Interlocked.Exchange(ref _lastSeq, s);
            LastPacketUtc = DateTime.UtcNow;
            LastSource = result.RemoteEndPoint;

            MaybeSendAck(udp, result.RemoteEndPoint, seq);
            PacketReceived?.Invoke(seq, pcm, result.RemoteEndPoint);
        }
    }

    private void MaybeSendAck(UdpClient udp, IPEndPoint target, uint lastSeq)
    {
        long now = Environment.TickCount64;
        if (now - Interlocked.Read(ref _lastAckTick) < 500) return;
        Interlocked.Exchange(ref _lastAckTick, now);
        try
        {
            byte[] ack = Encoding.ASCII.GetBytes("AIRMIC_ACK_V1|" + lastSeq);
            udp.Send(ack, ack.Length, target);
        }
        catch (SocketException) { }
        catch (ObjectDisposedException) { }
        catch (Exception) { }
    }

    // Evita che ICMP "port unreachable" su un ACK uccida il loop di ricezione (UDP_CONNRESET).
    private static void TryDisableUdpConnReset(Socket socket)
    {
        const int SioUdpConnReset = -1744830452; // SIO_UDP_CONNRESET
        try { socket.IOControl(SioUdpConnReset, new byte[] { 0 }, null); }
        catch (SocketException) { }
    }

    public void Dispose() => Stop();
}
