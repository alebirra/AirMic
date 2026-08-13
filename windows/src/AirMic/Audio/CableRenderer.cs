using System;
using System.Linq;
using System.Threading.Tasks;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace AirMic.Audio;

/// <summary>
/// Rende l'audio ricevuto sull'endpoint di riproduzione "CABLE Input" (VB-Audio Virtual Cable),
/// che Windows espone come microfono di sistema "CABLE Output".
/// </summary>
public sealed class CableRenderer : IDisposable
{
    public const string CableNamePart = "CABLE Input";

    private readonly MMDeviceEnumerator _enumerator = new();
    private WasapiOut? _output;
    private IWaveProvider? _currentSource;
    private bool _isDisposed;

    public MMDevice? Device { get; private set; }
    public string? DeviceName => Device?.FriendlyName;

    /// <summary>Cerca l'endpoint di rendering attivo il cui nome contiene "CABLE Input".</summary>
    public bool Detect()
    {
        Device = _enumerator
            .EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active)
            .FirstOrDefault(d => d.FriendlyName.Contains(CableNamePart, StringComparison.OrdinalIgnoreCase));
        return Device != null;
    }

    public void Start(IWaveProvider source)
    {
        if (Device == null) throw new InvalidOperationException("Dispositivo CABLE Input non trovato.");
        _currentSource = source;
        InitAndPlay();
    }

    private void InitAndPlay()
    {
        if (_currentSource == null || Device == null || _isDisposed) return;
        StopOutput();

        // Latenza minima in shared mode: prova 10 ms, ripiega su 20 ms se il driver la rifiuta.
        try
        {
            _output = new WasapiOut(Device, AudioClientShareMode.Shared, false, 10);
            _output.PlaybackStopped += OnPlaybackStopped;
            _output.Init(_currentSource);
            _output.Play();
        }
        catch (Exception)
        {
            StopOutput();
            try
            {
                _output = new WasapiOut(Device, AudioClientShareMode.Shared, false, 20);
                _output.PlaybackStopped += OnPlaybackStopped;
                _output.Init(_currentSource);
                _output.Play();
            }
            catch (Exception)
            {
                StopOutput();
                throw;
            }
        }
    }

    private void OnPlaybackStopped(object? sender, StoppedEventArgs e)
    {
        if (e.Exception != null && !_isDisposed && _currentSource != null)
        {
            Task.Delay(300).ContinueWith(_ =>
            {
                try
                {
                    if (!_isDisposed && Detect())
                    {
                        InitAndPlay();
                    }
                }
                catch { }
            });
        }
    }

    private void StopOutput()
    {
        if (_output != null)
        {
            _output.PlaybackStopped -= OnPlaybackStopped;
            try { _output.Stop(); } catch { }
            _output.Dispose();
            _output = null;
        }
    }

    public void Stop()
    {
        StopOutput();
    }

    public void Dispose()
    {
        _isDisposed = true;
        Stop();
        _enumerator.Dispose();
    }
}

