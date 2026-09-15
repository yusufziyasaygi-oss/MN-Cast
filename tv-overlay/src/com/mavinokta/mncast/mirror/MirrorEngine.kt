package com.mavinokta.mncast.mirror

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object MirrorEngine {
    private const val CONTROL_MAGIC = 0x4D4E4331 // MNC1
    private const val VIDEO_MAGIC = 0x4D4E5631   // MNV1
    private const val AUDIO_MAGIC = 0x4D4E4131   // MNA1
    private const val OK_MAGIC = 0x4F4B0001
    private const val CMD_PING = 1
    private const val CMD_PONG = 2
    private const val CMD_KEYFRAME = 3
    private const val VIDEO_HEADER = 28
    private const val AUDIO_HEADER = 20
    private const val MAX_FRAME_AGE_MS = 90L

    @Volatile var connected = false
        private set
    @Volatile var width = 1920
        private set
    @Volatile var height = 1080
        private set
    @Volatile var fps = 60
        private set

    private val active = AtomicBoolean(false)
    private var control: Socket? = null
    private var controlOut: DataOutputStream? = null
    private var udp: DatagramSocket? = null
    private var decoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var surface: Surface? = null
    private var sessionId = 0
    @Volatile private var codecConfig: ByteArray? = null
    @Volatile private var audioConfig: ByteArray? = null
    private val frames = ConcurrentHashMap<Int, Assembly>()
    private val decodeLock = Any()
    private val audioLock = Any()

    fun startClient(socket: Socket, videoPort: Int, onReady: (Int, Int) -> Unit) {
        stop()
        val input = DataInputStream(socket.getInputStream())
        require(input.readInt() == CONTROL_MAGIC) { "MN Cast protocol mismatch" }
        require(input.readInt() == 1) { "Unsupported MN Cast protocol" }
        width = input.readInt().coerceIn(320, 3840)
        height = input.readInt().coerceIn(240, 2160)
        fps = input.readInt().coerceIn(24, 60)
        input.readInt() // bitrate, informational
        sessionId = input.readInt()
        control = socket
        controlOut = DataOutputStream(socket.getOutputStream()).also { it.writeInt(OK_MAGIC); it.flush() }
        udp = DatagramSocket(videoPort).apply { receiveBufferSize = 4 * 1024 * 1024 }
        active.set(true)
        connected = true
        synchronized(audioLock) { createAudioLocked() }
        Thread({ controlLoop(input) }, "MN-Cast-ControlRead").start()
        Thread({ receiveLoop() }, "MN-Cast-AVRx").start()
        onReady(width, height)
    }

    fun attachSurface(s: Surface) {
        surface = s
        synchronized(decodeLock) { createDecoderLocked() }
    }

    fun detachSurface(s: Surface) {
        if (surface === s) surface = null
        synchronized(decodeLock) { releaseDecoderLocked() }
    }

    private fun createDecoderLocked() {
        val target = surface ?: return
        releaseDecoderLocked()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            if (Build.VERSION.SDK_INT >= 30) setInteger("low-latency", 1)
        }
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, target, null, 0)
            start()
        }
        codecConfig?.let { queueVideo(it, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0) }
    }

    private fun createAudioLocked() {
        releaseAudioLocked()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2)
        audioDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
        val min = AudioTrack.getMinBufferSize(48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = if (Build.VERSION.SDK_INT >= 26) {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min * 2, 16_384))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, 48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 16_384), AudioTrack.MODE_STREAM)
        }.also { it.play() }
        audioConfig?.let { queueAudio(it, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, 0) }
    }

    private fun receiveLoop() {
        val socket = udp ?: return
        val buf = ByteArray(1600)
        try {
            while (active.get()) {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                if (packet.length < AUDIO_HEADER) continue
                val b = ByteBuffer.wrap(packet.data, packet.offset, packet.length)
                when (val magic = b.int) {
                    VIDEO_MAGIC -> handleVideoPacket(b, packet.length)
                    AUDIO_MAGIC -> handleAudioPacket(b, packet.length)
                    else -> if (magic == 0) Unit
                }
            }
        } catch (_: SocketException) {
        } catch (_: Throwable) {
            requestKeyframe()
        } finally {
            connected = false
            stop()
        }
    }

    private fun handleVideoPacket(b: ByteBuffer, packetLength: Int) {
        if (packetLength <= VIDEO_HEADER) return
        if (b.int != sessionId) return
        val frameId = b.int
        val seq = b.short.toInt() and 0xffff
        val total = b.short.toInt() and 0xffff
        val flags = b.get().toInt() and 0xff
        b.get(); b.get(); b.get()
        val ptsUs = b.long
        if (total !in 1..2048 || seq >= total) return
        val payload = ByteArray(b.remaining())
        b.get(payload)
        val a = frames.computeIfAbsent(frameId) { Assembly(frameId, total, flags, ptsUs) }
        if (a.total != total) { frames.remove(frameId); return }
        if (a.put(seq, payload) && a.complete()) {
            frames.remove(frameId)
            val data = a.join()
            val codecFlags = if ((a.flags and 0x02) != 0) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            if (codecFlags != 0) codecConfig = data.copyOf()
            queueVideo(data, codecFlags, a.ptsUs)
        }
        cleanup(frameId)
    }

    private fun handleAudioPacket(b: ByteBuffer, packetLength: Int) {
        if (packetLength <= AUDIO_HEADER) return
        if (b.int != sessionId) return
        val flags = b.get().toInt() and 0xff
        b.get(); b.get(); b.get()
        val ptsUs = b.long
        val payload = ByteArray(b.remaining())
        b.get(payload)
        val codecFlags = if ((flags and 0x02) != 0) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
        if (codecFlags != 0) audioConfig = payload.copyOf()
        queueAudio(payload, codecFlags, ptsUs)
    }

    private fun cleanup(currentFrame: Int) {
        val now = SystemClock.elapsedRealtime()
        var dropped = false
        frames.entries.removeIf { (_, a) ->
            val stale = now - a.created > MAX_FRAME_AGE_MS || currentFrame - a.frameHint > 120
            if (stale) dropped = true
            stale
        }
        if (dropped) requestKeyframe()
    }

    private fun queueVideo(data: ByteArray, codecFlags: Int, ptsUs: Long) {
        synchronized(decodeLock) {
            val c = decoder ?: return
            try {
                val index = c.dequeueInputBuffer(0)
                if (index < 0) return
                val inBuf = c.getInputBuffer(index) ?: return
                inBuf.clear()
                if (data.size > inBuf.remaining()) { requestKeyframe(); return }
                inBuf.put(data)
                c.queueInputBuffer(index, 0, data.size, ptsUs, codecFlags)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val out = c.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    c.releaseOutputBuffer(out, true)
                }
            } catch (_: IllegalStateException) {
                requestKeyframe()
            }
        }
    }

    private fun queueAudio(data: ByteArray, codecFlags: Int, ptsUs: Long) {
        synchronized(audioLock) {
            val c = audioDecoder ?: return
            val track = audioTrack ?: return
            try {
                val index = c.dequeueInputBuffer(0)
                if (index >= 0) {
                    val input = c.getInputBuffer(index) ?: return
                    input.clear()
                    if (data.size <= input.remaining()) {
                        input.put(data)
                        c.queueInputBuffer(index, 0, data.size, ptsUs, codecFlags)
                    }
                }
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val out = c.dequeueOutputBuffer(info, 0)
                    if (out < 0) break
                    val ob = c.getOutputBuffer(out)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size); ob.get(pcm)
                        if (Build.VERSION.SDK_INT >= 23) track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                        else @Suppress("DEPRECATION") track.write(pcm, 0, pcm.size)
                    }
                    c.releaseOutputBuffer(out, false)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun controlLoop(input: DataInputStream) {
        try {
            while (active.get()) {
                when (input.readInt()) {
                    CMD_PING -> synchronized(this) { controlOut?.writeInt(CMD_PONG); controlOut?.flush() }
                }
            }
        } catch (_: Throwable) {
            connected = false
            stop()
        }
    }

    private fun requestKeyframe() {
        try { synchronized(this) { controlOut?.writeInt(CMD_KEYFRAME); controlOut?.flush() } } catch (_: Throwable) {}
    }

    fun stop() {
        if (!active.getAndSet(false) && control == null && udp == null) return
        connected = false
        frames.clear(); codecConfig = null; audioConfig = null
        try { udp?.close() } catch (_: Throwable) {}
        try { control?.close() } catch (_: Throwable) {}
        udp = null; control = null; controlOut = null
        synchronized(decodeLock) { releaseDecoderLocked() }
        synchronized(audioLock) { releaseAudioLocked() }
    }

    private fun releaseDecoderLocked() {
        try { decoder?.stop() } catch (_: Throwable) {}
        try { decoder?.release() } catch (_: Throwable) {}
        decoder = null
    }

    private fun releaseAudioLocked() {
        try { audioTrack?.pause() } catch (_: Throwable) {}
        try { audioTrack?.flush() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        try { audioDecoder?.stop() } catch (_: Throwable) {}
        try { audioDecoder?.release() } catch (_: Throwable) {}
        audioTrack = null; audioDecoder = null
    }

    private class Assembly(val frameHint: Int, val total: Int, val flags: Int, val ptsUs: Long) {
        val created = SystemClock.elapsedRealtime()
        private val parts = arrayOfNulls<ByteArray>(total)
        private var count = 0
        @Synchronized fun put(index: Int, data: ByteArray): Boolean {
            if (parts[index] != null) return false
            parts[index] = data; count++; return true
        }
        @Synchronized fun complete() = count == total
        @Synchronized fun join(): ByteArray {
            val size = parts.sumOf { it?.size ?: 0 }
            val out = ByteArray(size)
            var off = 0
            for (p in parts) if (p != null) { System.arraycopy(p, 0, out, off, p.size); off += p.size }
            return out
        }
    }
}
