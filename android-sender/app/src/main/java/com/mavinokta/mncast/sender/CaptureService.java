package com.mavinokta.mncast.sender;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    private static final int CONTROL_MAGIC = 0x4D4E4331;
    private static final int OK_MAGIC = 0x4F4B0001;
    private static final int CMD_PING = 1, CMD_PONG = 2, CMD_KEYFRAME = 3;
    private static final String CHANNEL = "mncast_capture";
    private static final int ID = 5358;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private Surface inputSurface;
    private Socket control;
    private DataOutputStream controlOut;
    private UdpVideoSender udp;
    private UdpAudioSender audioUdp;
    private AudioRecord audioRecord;
    private MediaCodec audioEncoder;
    private Thread audioThread;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if ("STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        startForegroundNow();
        if (!running.compareAndSet(false, true)) return START_STICKY;
        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent resultData = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra("resultData", Intent.class)
                : intent.getParcelableExtra("resultData");
        String host = intent.getStringExtra("host");
        int port = intent.getIntExtra("port", 53591);
        new Thread(() -> startCapture(resultCode, resultData, host, port), "MN-Cast-Capture").start();
        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData, String hostName, int port) {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            int srcW = dm.widthPixels, srcH = dm.heightPixels;
            float scale = Math.min(1f, Math.min(1920f/srcW, 1080f/srcH));
            int w = even(Math.max(640, Math.round(srcW*scale)));
            int h = even(Math.max(360, Math.round(srcH*scale)));
            int fps = 60;
            int bitrate = Math.max(4_000_000, Math.min(12_000_000, w*h*5));
            int session = new Random().nextInt();

            InetAddress host = InetAddress.getByName(hostName);
            control = new Socket(host, port);
            control.setTcpNoDelay(true);
            control.setKeepAlive(true);
            controlOut = new DataOutputStream(control.getOutputStream());
            DataInputStream controlIn = new DataInputStream(control.getInputStream());
            controlOut.writeInt(CONTROL_MAGIC);
            controlOut.writeInt(1);
            controlOut.writeInt(w); controlOut.writeInt(h); controlOut.writeInt(fps); controlOut.writeInt(bitrate); controlOut.writeInt(session);
            controlOut.flush();
            if (controlIn.readInt() != OK_MAGIC) throw new IllegalStateException("MN Cast receiver rejected connection");
            udp = new UdpVideoSender(host, session);
            audioUdp = new UdpAudioSender(host, session);

            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (Build.VERSION.SDK_INT >= 29) fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            if (Build.VERSION.SDK_INT >= 30) fmt.setInteger("low-latency", 1);
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();

            MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, resultData);
            projection.registerCallback(new MediaProjection.Callback(){ @Override public void onStop(){ stopSelf(); } }, null);
            virtualDisplay = projection.createVirtualDisplay("MN Cast", w, h, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null);

            if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startAudio(projection);
            }
            new Thread(() -> controlLoop(controlIn), "MN-Cast-ControlRead").start();
            drainEncoder();
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            stopSelf();
        }
    }

    private void startAudio(MediaProjection projection) {
        try {
            final int sampleRate = 48000;
            final int channelMask = AudioFormat.CHANNEL_IN_STEREO;
            final int pcm = AudioFormat.ENCODING_PCM_16BIT;
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            int min = AudioRecord.getMinBufferSize(sampleRate, channelMask, pcm);
            AudioFormat inFormat = new AudioFormat.Builder()
                    .setEncoding(pcm).setSampleRate(sampleRate).setChannelMask(channelMask).build();
            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(inFormat)
                    .setBufferSizeInBytes(Math.max(min*2, 16384))
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
            audioRecord.startRecording();
            audioThread = new Thread(this::audioLoop, "MN-Cast-Audio");
            audioThread.start();
        } catch (Throwable t) {
            stopAudio();
        }
    }

    private void audioLoop() {
        byte[] pcm = new byte[8192];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long ptsUs = 0;
        try {
            while (running.get() && audioRecord != null && audioEncoder != null) {
                int read = audioRecord.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (read > 0) {
                    int in = audioEncoder.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer b = audioEncoder.getInputBuffer(in);
                        if (b != null) {
                            b.clear(); int n=Math.min(read,b.remaining()); b.put(pcm,0,n);
                            audioEncoder.queueInputBuffer(in,0,n,ptsUs,0);
                            ptsUs += (long)(n / 4.0 / 48000.0 * 1_000_000.0); // stereo PCM16 = 4 bytes/frame
                        }
                    }
                }
                while (true) {
                    int out = audioEncoder.dequeueOutputBuffer(info, 0);
                    if (out >= 0) {
                        ByteBuffer ob = audioEncoder.getOutputBuffer(out);
                        if (ob != null && info.size > 0) {
                            ob.position(info.offset); ob.limit(info.offset+info.size);
                            byte[] data=new byte[info.size]; ob.get(data);
                            int f=(info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0?0x02:0;
                            audioUdp.send(data,f,info.presentationTimeUs);
                        }
                        audioEncoder.releaseOutputBuffer(out,false);
                    } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        ByteBuffer csd=audioEncoder.getOutputFormat().getByteBuffer("csd-0");
                        if(csd!=null){ ByteBuffer d=csd.duplicate(); byte[] data=new byte[d.remaining()]; d.get(data); audioUdp.send(data,0x02,0); }
                    } else break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void drainEncoder() throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastPing = 0;
        while (running.get()) {
            int out = encoder.dequeueOutputBuffer(info, 5_000);
            if (out >= 0) {
                ByteBuffer buf = encoder.getOutputBuffer(out);
                if (buf != null && info.size > 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size);
                    byte[] data = new byte[info.size]; buf.get(data);
                    int f = 0;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) f |= 0x01;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) f |= 0x02;
                    udp.sendFrame(data, f, info.presentationTimeUs);
                }
                encoder.releaseOutputBuffer(out, false);
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = encoder.getOutputFormat();
                sendCsdPair(of.getByteBuffer("csd-0"), of.getByteBuffer("csd-1"));
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastPing > 2000) {
                synchronized (this) { if (controlOut != null) { controlOut.writeInt(CMD_PING); controlOut.flush(); } }
                lastPing = now;
            }
        }
    }

    private void sendCsdPair(ByteBuffer a, ByteBuffer b) throws Exception {
        int as = a == null ? 0 : a.remaining();
        int bs = b == null ? 0 : b.remaining();
        if (as + bs == 0) return;
        byte[] data = new byte[as + bs];
        int off = 0;
        if (a != null) { ByteBuffer d=a.duplicate(); d.get(data,off,as); off += as; }
        if (b != null) { ByteBuffer d=b.duplicate(); d.get(data,off,bs); }
        udp.sendFrame(data,0x02,0);
    }

    private void controlLoop(DataInputStream in) {
        try {
            while (running.get()) {
                int cmd=in.readInt();
                if(cmd==CMD_KEYFRAME && encoder!=null){
                    Bundle b=new Bundle(); b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0); encoder.setParameters(b);
                } else if(cmd==CMD_PONG) { /* liveness */ }
            }
        } catch(Exception e){ stopSelf(); }
    }

    private void startForegroundNow() {
        if(Build.VERSION.SDK_INT>=26){
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,"MN Cast yansıtma",NotificationManager.IMPORTANCE_LOW));
        }
        Intent stop=new Intent(this, CaptureService.class).setAction("STOP");
        PendingIntent spi=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification n=new android.app.Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("MN Cast")
                .setContentText("Ekran ve izin verilen uygulama sesi TV’ye yansıtılıyor")
                .setOngoing(true)
                .addAction(new android.app.Notification.Action.Builder(null,"Durdur",spi).build())
                .build();
        startForeground(ID,n);
    }

    private void stopAudio() {
        try{ if(audioRecord!=null) audioRecord.stop(); }catch(Throwable ignored){}
        try{ if(audioRecord!=null) audioRecord.release(); }catch(Throwable ignored){}
        try{ if(audioEncoder!=null) audioEncoder.stop(); }catch(Throwable ignored){}
        try{ if(audioEncoder!=null) audioEncoder.release(); }catch(Throwable ignored){}
        try{ if(audioUdp!=null) audioUdp.close(); }catch(Throwable ignored){}
        audioRecord=null; audioEncoder=null; audioUdp=null;
    }

    private static int even(int v){ return (v & 1)==0 ? v : v-1; }

    @Override public void onDestroy() {
        running.set(false);
        stopAudio();
        try{if(virtualDisplay!=null)virtualDisplay.release();}catch(Throwable ignored){}
        try{if(projection!=null)projection.stop();}catch(Throwable ignored){}
        try{if(encoder!=null)encoder.stop();}catch(Throwable ignored){}
        try{if(encoder!=null)encoder.release();}catch(Throwable ignored){}
        try{if(inputSurface!=null)inputSurface.release();}catch(Throwable ignored){}
        try{if(udp!=null)udp.close();}catch(Throwable ignored){}
        try{if(control!=null)control.close();}catch(Throwable ignored){}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
