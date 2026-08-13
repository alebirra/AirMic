using System;
using System.Threading;
using System.Windows;

namespace AirMic;

public partial class App : Application
{
    private const string MutexName = "Local\\AirMic_SingleInstance_Mutex_App";
    private const string EventName = "Local\\AirMic_ActivateEvent_App";

    private Mutex? _instanceMutex;
    private EventWaitHandle? _activateEvent;
    private RegisteredWaitHandle? _registeredWaitHandle;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        int ti = Array.IndexOf(e.Args, "--test");
        if (ti >= 0 && ti + 1 < e.Args.Length && int.TryParse(e.Args[ti + 1], out int seconds) && seconds > 0)
        {
            TestRunner.Run(seconds); // non ritorna: termina con Environment.Exit(0)
            return;
        }

        bool createdNew;
        try
        {
            _instanceMutex = new Mutex(true, MutexName, out createdNew);
        }
        catch (Exception)
        {
            createdNew = false;
        }

        if (!createdNew)
        {
            // Another instance is already running: signal it to bring window to front
            try
            {
                using var evt = EventWaitHandle.OpenExisting(EventName);
                evt.Set();
            }
            catch (Exception) { }

            Shutdown();
            return;
        }

        // Set up activation event listener for subsequent launches
        try
        {
            _activateEvent = new EventWaitHandle(false, EventResetMode.AutoReset, EventName);
            _registeredWaitHandle = ThreadPool.RegisterWaitForSingleObject(
                _activateEvent,
                OnActivateSignaled,
                null,
                Timeout.Infinite,
                false);
        }
        catch (Exception) { }

        MainWindow = new MainWindow();
        MainWindow.Show();
    }

    private void OnActivateSignaled(object? state, bool timedOut)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (MainWindow is MainWindow mw)
            {
                mw.RestoreFromTray();
            }
        });
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _registeredWaitHandle?.Unregister(null);
        _activateEvent?.Dispose();
        if (_instanceMutex != null)
        {
            try { _instanceMutex.ReleaseMutex(); } catch { }
            _instanceMutex.Dispose();
        }
        base.OnExit(e);
    }
}

