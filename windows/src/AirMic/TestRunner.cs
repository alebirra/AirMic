using System;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Threading;
using AirMic.Audio;

namespace AirMic;

/// <summary>Modalità headless: AirMic.exe --test &lt;secondi&gt;. Una riga di statistiche al secondo, exit code 0.</summary>
internal static class TestRunner
{
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AttachConsole(int dwProcessId);

    public static void Run(int seconds)
    {
        AttachConsole(-1); // ATTACH_PARENT_PROCESS

        using var engine = new AudioEngine();

        CableRenderer? renderer = null;
        try
        {
            renderer = new CableRenderer();
            if (renderer.Detect())
            {
                renderer.Start(engine.WaveProvider);
                engine.RendererActive = true;
            }
        }
        catch
        {
            renderer = null; // rendering opzionale in test: ricezione/jitter/ACK restano attivi
        }

        using var discovery = new AirMic.Net.DiscoveryResponder();
        discovery.Start();

        engine.Start();
        for (int t = 1; t <= seconds; t++)
        {
            Thread.Sleep(1000);
            Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                "TEST t={0}s packets={1} lastSeq={2} lost={3} level={4:0.00}",
                t, engine.PacketsReceived, Math.Max(engine.LastSeq, 0), engine.LostPackets, engine.Level));
            Console.Out.Flush();
        }

        engine.Stop();
        renderer?.Dispose();
        Console.Out.Flush();
        Environment.Exit(0);
    }
}
