using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Linq;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Media;
using System.Windows.Navigation;
using System.Windows.Threading;
using AirMic.Audio;
using AirMic.Net;
using WF = System.Windows.Forms;

namespace AirMic;

public partial class MainWindow : Window
{
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(System.IntPtr hIcon);

    private static readonly Brush GrayDot = new SolidColorBrush(Color.FromRgb(0x8E, 0x8E, 0x98));
    private static readonly Brush AmberDot = new SolidColorBrush(Color.FromRgb(0xFF, 0x9F, 0x0A));
    private static readonly Brush GreenDot = new SolidColorBrush(Color.FromRgb(0x30, 0xD1, 0x58));

    private readonly AudioEngine _engine = new();
    private readonly CableRenderer _renderer = new();
    private readonly DiscoveryResponder _discovery = new();
    private readonly DispatcherTimer _uiTimer;
    private WF.NotifyIcon? _tray;
    private System.Drawing.Icon? _trayIcon;
    private bool _realExit;

    public MainWindow()
    {
        InitializeComponent();

        IpText.Text = string.Join(Environment.NewLine, GetLanAddresses());

        StartListener();
        try { _discovery.Start(); }
        catch (Exception) { /* porta di discovery occupata: lo streaming resta utilizzabile */ }
        InitRenderer();

        SetupTray();

        _uiTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        _uiTimer.Tick += (_, _) => RefreshUi();
        _uiTimer.Start();
        RefreshUi();
    }

    private void StartListener()
    {
        try { _engine.Start(); }
        catch (Exception ex)
        {
            StatusHeadline.Text = "Errore avvio ascolto";
            StatusDetail.Text = ex.Message;
        }
    }

    private void InitRenderer()
    {
        if (!_renderer.Detect())
        {
            CableStatusText.Text = "Cavo virtuale non rilevato su questo PC.";
            CableWarning.Visibility = Visibility.Visible;
            return;
        }
        try
        {
            _renderer.Start(_engine.WaveProvider);
            _engine.RendererActive = true;
            CableStatusText.Text = "Cavo virtuale rilevato: " + _renderer.DeviceName;
            CableWarning.Visibility = Visibility.Collapsed;
        }
        catch (Exception ex)
        {
            CableStatusText.Text = "Errore apertura dispositivo: " + ex.Message;
            CableWarning.Visibility = Visibility.Visible;
        }
    }

    private void ToggleButton_Click(object sender, RoutedEventArgs e)
    {
        if (_engine.IsListening)
            _engine.Stop();
        else
            StartListener();
        RefreshUi();
    }

    private void RefreshUi()
    {
        if (!_engine.IsListening)
        {
            StatusDot.Fill = GrayDot;
            StatusHeadline.Text = "Ascolto disattivato";
            StatusDetail.Text = "Attiva l'ascolto per ricevere l'audio dal telefono.";
            if (_tray != null) _tray.Text = "AirMic — Ascolto disattivato";
        }
        else if (_engine.IsStreamActive)
        {
            StatusDot.Fill = GreenDot;
            StatusHeadline.Text = "Connesso";
            StatusDetail.Text = "Streaming da " + _engine.PhoneAddress + " — porta UDP 47811";
            if (_tray != null)
            {
                string txt = "AirMic — Connesso (" + (_engine.PhoneAddress ?? "") + ")";
                _tray.Text = txt.Length > 63 ? txt.Substring(0, 63) : txt;
            }
        }
        else
        {
            StatusDot.Fill = AmberDot;
            StatusHeadline.Text = "In attesa di connessione…";
            StatusDetail.Text = "Ascolto attivo — porta UDP 47811. Connetti l'app AirMic dal telefono.";
            if (_tray != null) _tray.Text = "AirMic — In attesa di connessione";
        }

        ToggleButton.Content = _engine.IsListening ? "Disattiva ascolto" : "Attiva ascolto";
    }

    private static string[] GetLanAddresses()
    {
        var ips = NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up
                        && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(a => a.Address.ToString())
            .Distinct()
            .ToArray();
        return ips.Length > 0 ? ips : new[] { "(nessuna interfaccia LAN attiva)" };
    }

    private void CableLink_RequestNavigate(object sender, RequestNavigateEventArgs e)
    {
        Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true });
        e.Handled = true;
    }

    // --- Barra delle applicazioni / tray ---

    private void SetupTray()
    {
        var menu = new WF.ContextMenuStrip();
        menu.Items.Add("Apri AirMic", null, (_, _) => RestoreFromTray());
        menu.Items.Add(new WF.ToolStripSeparator());
        menu.Items.Add("Esci", null, (_, _) => ExitApp());

        _tray = new WF.NotifyIcon
        {
            Icon = LoadTrayIcon(),
            Text = "AirMic",
            ContextMenuStrip = menu,
            Visible = true
        };
        _tray.DoubleClick += (_, _) => RestoreFromTray();
    }

    public void RestoreFromTray()
    {
        Show();
        if (WindowState == WindowState.Minimized)
            WindowState = WindowState.Normal;
        Activate();
        Topmost = true;
        Topmost = false;
        Focus();
    }

    private void ExitApp()
    {
        _realExit = true;
        System.Windows.Application.Current.Shutdown();
    }

    protected override void OnStateChanged(EventArgs e)
    {
        if (WindowState == WindowState.Minimized)
            Hide(); // minimizza nella tray
        base.OnStateChanged(e);
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_realExit)
        {
            e.Cancel = true; // chiudi = riduci a icona, non uscire
            Hide();
            return;
        }
        base.OnClosing(e);
    }

    protected override void OnClosed(EventArgs e)
    {
        _uiTimer.Stop();
        if (_tray != null)
        {
            _tray.Visible = false;
            _tray.Dispose();
        }
        if (_trayIcon != null)
        {
            DestroyIcon(_trayIcon.Handle); // FromHandle non trasferisce la proprietà dell'handle
            _trayIcon.Dispose();
            _trayIcon = null;
        }
        _engine.Dispose();
        _renderer.Dispose();
        _discovery.Dispose();
        base.OnClosed(e);
    }

    private System.Drawing.Icon LoadTrayIcon()
    {
        try
        {
            var uri = new Uri("pack://application:,,,/Assets/icon.ico", UriKind.Absolute);
            var streamInfo = System.Windows.Application.GetResourceStream(uri);
            if (streamInfo != null)
            {
                using var stream = streamInfo.Stream;
                var smallSize = WF.SystemInformation.SmallIconSize;
                return new System.Drawing.Icon(stream, smallSize);
            }
        }
        catch (Exception) { }

        try
        {
            var uriPng = new Uri("pack://application:,,,/Assets/icon.png", UriKind.Absolute);
            var streamInfo = System.Windows.Application.GetResourceStream(uriPng);
            if (streamInfo != null)
            {
                using var stream = streamInfo.Stream;
                using var src = new System.Drawing.Bitmap(stream);
                var size = WF.SystemInformation.SmallIconSize;
                using var bmp = new System.Drawing.Bitmap(src, size.Width, size.Height);
                IntPtr hIcon = bmp.GetHicon();
                var icon = System.Drawing.Icon.FromHandle(hIcon);
                _trayIcon = icon;
                return icon;
            }
        }
        catch (Exception) { }

        return System.Drawing.SystemIcons.Application;
    }
}

