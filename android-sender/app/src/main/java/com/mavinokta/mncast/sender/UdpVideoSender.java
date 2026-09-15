package com.mavinokta.mncast.sender;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

final class UdpVideoSender {
    static final int PACKET_MAGIC = 0x4D4E5631; // MNV1
    static final int VIDEO_PORT = 53592;
    static final int HEADER = 28;
    static final int MAX_PAYLOAD = 1200;
    private final DatagramSocket socket;
    private final InetAddress host;
    private final int sessionId;
    private final AtomicInteger frameId = new AtomicInteger(1);

    UdpVideoSender(InetAddress host, int sessionId) throws Exception {
        this.host = host;
        this.sessionId = sessionId;
        socket = new DatagramSocket();
        socket.setSendBufferSize(4 * 1024 * 1024);
    }

    void sendFrame(byte[] data, int flags, long ptsUs) throws Exception {
        int id = frameId.getAndIncrement();
        int total = Math.max(1, (data.length + MAX_PAYLOAD - 1) / MAX_PAYLOAD);
        for (int seq = 0; seq < total; seq++) {
            int off = seq * MAX_PAYLOAD;
            int count = Math.min(MAX_PAYLOAD, data.length - off);
            ByteBuffer b = ByteBuffer.allocate(HEADER + count);
            b.putInt(PACKET_MAGIC);
            b.putInt(sessionId);
            b.putInt(id);
            b.putShort((short) seq);
            b.putShort((short) total);
            b.put((byte) flags);
            b.put((byte) 0).put((byte) 0).put((byte) 0);
            b.putLong(ptsUs);
            b.put(data, off, count);
            byte[] packet = b.array();
            socket.send(new DatagramPacket(packet, packet.length, host, VIDEO_PORT));
        }
    }

    void close() { socket.close(); }
}
