using System;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using AirMic.Audio;

namespace AirMic.Net;

/// <summary>
/// Risponde su UDP 47800 a "AIRMIC_DISCOVER_V1" con "AIRMIC_SERVER_V1|&lt;nomePc&gt;|&lt;portaAudio&gt;",
/// in unicast all'endpoint sorgente della richiesta.
/// </summary>
public sealed class DiscoveryResponder : IDisposable
{
    public const int Port = 47800;
    private static readonly byte[] Query = Encoding.ASCII.GetBytes("AIRMIC_DISCOVER_V1");

    private UdpClient? _udp;
    private CancellationTokenSource? _cts;

    public void Start()
    {
        if (_udp != null) return;
        _udp = new UdpClient(Port) { EnableBroadcast = true };
        _cts = new CancellationTokenSource();
        _ = Task.Run(() => Loop(_udp, _cts.Token));
    }

    private static async Task Loop(UdpClient udp, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            UdpReceiveResult r;
            try { r = await udp.ReceiveAsync(ct); }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) { continue; }

            if (r.Buffer.Length != Query.Length) continue;
            bool match = true;
            for (int i = 0; i < Query.Length; i++)
                if (r.Buffer[i] != Query[i]) { match = false; break; }
            if (!match) continue;

            try
            {
                byte[] reply = Encoding.ASCII.GetBytes(
                    $"AIRMIC_SERVER_V1|{Environment.MachineName}|{UdpAudioReceiver.Port}");
                udp.Send(reply, reply.Length, r.RemoteEndPoint);
            }
            catch (SocketException) { }
            catch (ObjectDisposedException) { break; }
        }
    }

    public void Dispose()
    {
        _cts?.Cancel();
        _udp?.Close();
        _udp = null;
    }
}
