package com.airmic.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Foreground service that captures the microphone and streams PCM over UDP
 * (AirMic protocol v1): "AM01" + uint32 LE seq + 480 bytes of 48 kHz mono PCM
 * per datagram, sent to the PC on the negotiated audio port (default 47811).
 *
 * The same socket receives "AIRMIC_ACK_V1|<seq>" heartbeats from the PC;
 * the UI reports "connected" only while ACKs keep arriving (fresh within 4 s).
 */
public class MicStreamService extends Service {

    public static final String ACTION_START = "com.airmic.app.action.START";
    public static final String ACTION_STOP = "com.airmic.app.action.STOP";

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_PC_NAME = "pcName";

    public static final String BROADCAST_STATE = "com.airmic.app.broadcast.STATE";
    public static final String EXTRA_STREAMING = "streaming";
    public static final String EXTRA_ACK_FRESH = "ackFresh";
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_ERROR = "error";
    public static final String ERROR_MIC_INIT = "mic_init";

    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SAMPLES = 240;          // 5 ms @ 48 kHz
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final int HEADER_BYTES = 8;             // "AM01" + uint32 seq
    private static final long ACK_FRESH_MS = 4000;
    private static final long STATE_BROADCAST_INTERVAL_MS = 66; // ~15 fps level meter

    private static final String CHANNEL_ID = "airmic_stream";
    private static final int NOTIF_ID = 1;
    private static final String ACK_PREFIX = "AIRMIC_ACK_V1|";

    // Snapshot of the last known state, readable by the activity on resume.
    public static volatile boolean sStreaming;
    public static volatile long sLastAckAt;
    public static volatile String sPcName = "";

    private volatile boolean running;
    private Thread streamThread;
    private Thread ackThread;
    private DatagramSocket socket;
    private AudioRecord audioRecord;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private String pcName = "";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopStreaming();
        } else if (ACTION_START.equals(intent.getAction())) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                broadcastState(0f, ERROR_MIC_INIT);
                stopSelf();
                return START_NOT_STICKY;
            }
            String host = intent.getStringExtra(EXTRA_HOST);
            int port = intent.getIntExtra(EXTRA_PORT, Discovery.DEFAULT_AUDIO_PORT);
            String name = intent.getStringExtra(EXTRA_PC_NAME);
            Notification notif = buildNotification(name != null ? name : host);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, notif);
            }
            startStreaming(host, port, name != null ? name : host);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        super.onDestroy();
    }

    private synchronized void startStreaming(String host, int port, String name) {
        if (streamThread != null) {
            return;
        }
        pcName = name;
        running = true;
        sStreaming = true;
        sPcName = name;
        sLastAckAt = 0;
        acquireLocks();
        streamThread = new Thread(() -> runStreamer(host, port), "airmic-audio");
        streamThread.start();
        broadcastState(0f, null);
    }

    private synchronized void stopStreaming() {
        running = false;
        // The stream thread exits its read loop and performs cleanup itself.
    }

    private void runStreamer(String host, int port) {
        String error = null;
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            DatagramSocket sock = new DatagramSocket();
            sock.connect(InetAddress.getByName(host), port);
            sock.setSoTimeout(500);
            socket = sock;

            ackThread = new Thread(this::runAckListener, "airmic-ack");
            ackThread.start();

            AudioRecord rec = buildRecorder();
            if (rec == null) {
                error = ERROR_MIC_INIT;
                return;
            }
            audioRecord = rec;
            rec.startRecording();

            byte[] pcm = new byte[FRAME_BYTES];
            byte[] packet = new byte[HEADER_BYTES + FRAME_BYTES];
            packet[0] = 'A';
            packet[1] = 'M';
            packet[2] = '0';
            packet[3] = '1';
            // Socket is connected: no destination needed on the packet.
            DatagramPacket dp = new DatagramPacket(packet, packet.length);

            long seq = 0;
            long lastBroadcast = 0;
            int consecutiveErrors = 0;
            while (running) {
                int off = 0;
                while (off < FRAME_BYTES && running) {
                    int n = rec.read(pcm, off, FRAME_BYTES - off);
                    if (n > 0) {
                        off += n;
                        consecutiveErrors = 0;
                    } else if (n < 0) {
                        consecutiveErrors++;
                        if (consecutiveErrors > 20) {
                            // Transient error recovery: attempt to recreate recorder
                            try {
                                rec.stop();
                            } catch (Exception ignored) { }
                            rec.release();
                            SystemClock.sleep(50);
                            rec = buildRecorder();
                            if (rec != null) {
                                try {
                                    rec.startRecording();
                                    audioRecord = rec;
                                    consecutiveErrors = 0;
                                } catch (Exception e) {
                                    rec = null;
                                }
                            }
                            if (rec == null) {
                                running = false;
                                error = ERROR_MIC_INIT;
                                break;
                            }
                        } else {
                            SystemClock.sleep(5);
                        }
                    } else {
                        SystemClock.sleep(2);
                    }
                }
                if (!running) {
                    break;
                }

                float level = rms(pcm);

                packet[4] = (byte) seq;
                packet[5] = (byte) (seq >>> 8);
                packet[6] = (byte) (seq >>> 16);
                packet[7] = (byte) (seq >>> 24);
                System.arraycopy(pcm, 0, packet, HEADER_BYTES, FRAME_BYTES);
                try {
                    sock.send(dp);
                } catch (IOException ignored) {
                    // Network down or unreachable: keep capturing, packets
                    // resume flowing as soon as connectivity is back.
                }
                seq++;

                long now = SystemClock.elapsedRealtime();
                if (now - lastBroadcast >= STATE_BROADCAST_INTERVAL_MS) {
                    lastBroadcast = now;
                    broadcastState(level, null);
                }
            }
        } catch (Exception e) {
            error = ERROR_MIC_INIT;
        } finally {
            running = false;
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (IllegalStateException ignored) {
                }
                audioRecord.release();
                audioRecord = null;
            }
            if (socket != null) {
                socket.close(); // also unblocks the ACK listener
                socket = null;
            }
            if (ackThread != null) {
                try {
                    ackThread.join(1000);
                } catch (InterruptedException ignored) {
                }
                ackThread = null;
            }
            releaseLocks();
            sStreaming = false;
            broadcastState(0f, error);
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            streamThread = null;
        }
    }

    /** Listens for "AIRMIC_ACK_V1|<seq>" heartbeats on the streaming socket. */
    private void runAckListener() {
        byte[] buf = new byte[64];
        DatagramSocket sock = socket;
        while (running && sock != null && !sock.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                String msg = new String(p.getData(), p.getOffset(), p.getLength(),
                        StandardCharsets.US_ASCII);
                if (msg.startsWith(ACK_PREFIX)) {
                    sLastAckAt = SystemClock.elapsedRealtime();
                }
            } catch (SocketTimeoutException ignored) {
                // No ACK within the timeout: just poll the running flag.
            } catch (IOException e) {
                if (running) {
                    SystemClock.sleep(100); // transient network failure: don't spin
                }
            }
        }
    }

    /** MIC source first, VOICE_COMMUNICATION only as a fallback. */
    private AudioRecord buildRecorder() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(minBuf, FRAME_BYTES * 4);
        int[] sources = {MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION};
        for (int source : sources) {
            AudioRecord rec = null;
            try {
                rec = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    return rec;
                }
            } catch (Exception ignored) {
            }
            if (rec != null) {
                rec.release();
            }
        }
        return null;
    }

    /** RMS level of a 16-bit LE PCM frame, normalized to 0..1 for the meter. */
    private static float rms(byte[] pcm) {
        long sum = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) s * s;
        }
        double rms = Math.sqrt(sum / (pcm.length / 2.0));
        return (float) Math.min(1.0, rms / 9000.0);
    }

    private void broadcastState(float level, String error) {
        boolean ackFresh = sLastAckAt != 0
                && SystemClock.elapsedRealtime() - sLastAckAt < ACK_FRESH_MS;
        Intent i = new Intent(BROADCAST_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STREAMING, running)
                .putExtra(EXTRA_ACK_FRESH, ackFresh)
                .putExtra(EXTRA_PC_NAME, pcName)
                .putExtra(EXTRA_LEVEL, level);
        if (error != null) {
            i.putExtra(EXTRA_ERROR, error);
        }
        sendBroadcast(i);
    }

    private Notification buildNotification(String targetName) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, MicStreamService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text, targetName))
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notif_stop), stop).build())
                .setOngoing(true)
                .build();
    }

    private void acquireLocks() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airmic:stream");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "airmic:wifi");
        } else {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airmic:wifi");
        }
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wifiLock = null;
    }
}
