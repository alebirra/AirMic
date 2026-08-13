using System;
using System.Net;
using System.Threading;
using NAudio.Wave;

namespace AirMic.Audio;

/// <summary>
/// Orchestrazione condivisa tra UI e modalità test: ricevitore UDP → jitter buffer →
/// BufferedWaveProvider (48 kHz, 16 bit, mono, buffer 80 ms). Un timer da ~5 ms svuota
/// il jitter buffer e calcola il livello RMS per il meter.
/// </summary>
public sealed class AudioEngine : IDisposable
{
    private readonly UdpAudioReceiver _receiver = new();
    private readonly JitterBuffer _jitter = new();
    private readonly BufferedWaveProvider _wave;
    private readonly Timer _pump;
    private int _pumpBusy;
    private double _level;

    /// <summary>Se true il pump scrive nel provider (rendering attivo); altrimenti i frame vengono solo misurati.</summary>
    public volatile bool RendererActive;

    public AudioEngine()
    {
        _wave = new BufferedWaveProvider(new WaveFormat(48000, 16, 1))
        {
            BufferDuration = TimeSpan.FromMilliseconds(80),
            DiscardOnBufferOverflow = false // le perdite le gestisce il jitter buffer
        };
        _pump = new Timer(PumpTick, null, Timeout.Infinite, Timeout.Infinite);
    }

    public BufferedWaveProvider WaveProvider => _wave;

    public bool IsListening => _receiver.IsRunning;
    public bool IsStreamActive => (DateTime.UtcNow - _receiver.LastPacketUtc).TotalMilliseconds < 500;
    public string? PhoneAddress => _receiver.LastSource?.Address.ToString();
    public long PacketsReceived => _receiver.PacketsReceived;
    public long LastSeq => _receiver.LastSeq;
    public long LostPackets => Math.Max(_receiver.LostPackets, _jitter.SilenceFills);
    public double Level => _level;

    public void Start()
    {
        if (IsListening) return;
        _jitter.Reset();
        _wave.ClearBuffer();
        _receiver.PacketReceived += OnPacket;
        _receiver.Start();
        _pump.Change(0, 5);
    }

    public void Stop()
    {
        _pump.Change(Timeout.Infinite, Timeout.Infinite);
        _receiver.PacketReceived -= OnPacket;
        _receiver.Stop();
        _level = 0;
    }

    private void OnPacket(uint seq, byte[] pcm, IPEndPoint source) => _jitter.Push(seq, pcm);

    private void PumpTick(object? state)
    {
        if (Interlocked.Exchange(ref _pumpBusy, 1) == 1) return;
        try
        {
            double peak = 0;
            byte[]? frame;
            while ((frame = _jitter.Pop()) != null)
            {
                double rms = Rms(frame);
                if (rms > peak) peak = rms;
                if (RendererActive && _wave.BufferedBytes + frame.Length <= _wave.BufferLength)
                    _wave.AddSamples(frame, 0, frame.Length);
            }
            _level = Math.Max(peak, _level * 0.9);
        }
        finally
        {
            _pumpBusy = 0;
        }
    }

    private static double Rms(byte[] frame)
    {
        long sum = 0;
        for (int i = 0; i + 1 < frame.Length; i += 2)
        {
            short s = (short)(frame[i] | (frame[i + 1] << 8));
            sum += s * s;
        }
        return Math.Sqrt(sum / (frame.Length / 2.0)) / 32768.0;
    }

    public void Dispose()
    {
        Stop();
        _pump.Dispose();
    }
}
