package com.mavinokta.mncast.sender;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

final class UdpAudioSender {
    static final int AUDIO_MAGIC = 0x4D4E4131; // MNA1
    static final int HEADER = 20;
    private final DatagramSocket socket;
    private final InetAddress host;
    private final int sessionId;

    UdpAudioSender(InetAddress host, int sessionId) throws Exception {
        this.host = host; this.sessionId = sessionId;
        this.socket = new DatagramSocket();
        socket.setSendBufferSize(512 * 1024);
    }

    void send(byte[] data, int flags, long ptsUs) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(HEADER + data.length);
        b.putInt(AUDIO_MAGIC); b.putInt(sessionId); b.put((byte)flags);
        b.put((byte)0).put((byte)0).put((byte)0); b.putLong(ptsUs); b.put(data);
        byte[] p=b.array(); socket.send(new DatagramPacket(p,p.length,host,UdpVideoSender.VIDEO_PORT));
    }
    void close(){ socket.close(); }
}
