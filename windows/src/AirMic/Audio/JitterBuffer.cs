using System.Collections.Generic;
using System.Linq;
using System.Threading;

namespace AirMic.Audio;

/// <summary>
/// Buffer di riordino per seq (PROTOCOL.md §4): avvio dopo 2 pacchetti (10 ms),
/// 5 ms di silenzio sui buchi di sequenza, scarto dei pacchetti più vecchi dell'ultimo
/// riprodotto, coda limitata a 4 pacchetti (overflow: scarta i più vecchi).
/// </summary>
public sealed class JitterBuffer
{
    public const int FrameBytes = 480; // 240 campioni × 2 byte = 5 ms @ 48 kHz mono 16 bit
    private const int PrerollFrames = 2;
    private const int MaxFrames = 4;
    private const int MaxSilenceRun = 24; // oltre: riallinea allo stream invece di riempire

    private readonly object _gate = new();
    private readonly SortedDictionary<uint, byte[]> _pending = new();
    private readonly byte[] _silence = new byte[FrameBytes];
    private bool _started;
    private uint _nextSeq;
    private long _silenceFills;

    public long SilenceFills => Interlocked.Read(ref _silenceFills);

    public void Push(uint seq, byte[] pcm)
    {
        lock (_gate)
        {
            if (_started && seq < _nextSeq) return; // più vecchio dell'ultimo riprodotto
            if (_pending.ContainsKey(seq)) return;
            while (_pending.Count >= MaxFrames)
                _pending.Remove(_pending.Keys.Min());
            _pending[seq] = pcm;
        }
    }

    /// <summary>Prossimo frame da riprodurre, oppure null se non ancora pronto / buffer vuoto.</summary>
    public byte[]? Pop()
    {
        lock (_gate)
        {
            if (!_started)
            {
                if (_pending.Count < PrerollFrames) return null;
                _started = true;
                _nextSeq = _pending.Keys.Min();
            }
            if (_pending.Count == 0) return null;

            uint min = _pending.Keys.Min();
            if (min > _nextSeq && min - _nextSeq > MaxSilenceRun)
                _nextSeq = min; // salto di stream: risincronizza

            byte[] frame;
            if (_pending.Remove(_nextSeq, out byte[]? present))
                frame = present;
            else
            {
                frame = _silence;
                Interlocked.Increment(ref _silenceFills);
            }
            _nextSeq++;
            return frame;
        }
    }

    public void Reset()
    {
        lock (_gate)
        {
            _pending.Clear();
            _started = false;
            _nextSeq = 0;
        }
    }
}
