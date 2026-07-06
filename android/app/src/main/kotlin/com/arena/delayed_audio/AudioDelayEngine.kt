package com.arena.delayed_audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class AudioDelayEngine(private val context: Context) {
    private val running = AtomicBoolean(false)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var latencyMs: Int = 500
    @Volatile private var selectedRouteId: Int = -1
    private var worker: Thread? = null

    fun start(initialLatencyMs: Int, routeId: Int) {
        latencyMs = initialLatencyMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        selectedRouteId = routeId
        if (!running.compareAndSet(false, true)) {
            setLatencyMs(initialLatencyMs)
            selectOutputDevice(routeId)
            return
        }
        clearStatus()
        setSnapshot(running = true, latencyMs = latencyMs, level = 0.0, sampleRate = 0, underruns = 0, error = "")
        worker = Thread(::audioLoop, "DelayedAudioEngine").apply { start() }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker?.join(700)
        worker = null
        setSnapshot(running = false, latencyMs = latencyMs, level = 0.0, sampleRate = lastSampleRate, underruns = lastUnderruns, error = "")
    }

    fun setLatencyMs(value: Int) {
        latencyMs = value.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        updateLatency(latencyMs)
    }

    fun selectOutputDevice(routeId: Int) {
        selectedRouteId = routeId
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Permission micro refusée.")
            return
        }

        val config = chooseConfig()
        if (config == null) {
            fail("Impossible de créer une configuration audio compatible sur ce téléphone.")
            return
        }

        lastSampleRate = config.sampleRate
        val inputFrames = max(config.sampleRate / 50, 256) // environ 20 ms.
        val input = ShortArray(inputFrames)
        val output = ShortArray(inputFrames)
        val ring = ShortArray(config.sampleRate * ((MAX_DELAY_MS / 1000) + 2))
        var writePos = 0
        var underruns = 0

        val record = buildAudioRecord(config.sampleRate, config.minRecordBufferBytes)
        val track = buildAudioTrack(config.sampleRate, config.minTrackBufferBytes)

        try {
            if (record.state != AudioRecord.STATE_INITIALIZED || track.state != AudioTrack.STATE_INITIALIZED) {
                fail("AudioRecord/AudioTrack non initialisé.")
                return
            }

            applyPreferredOutput(track, selectedRouteId)
            record.startRecording()
            track.play()

            while (running.get() && !Thread.currentThread().isInterrupted) {
                applyPreferredOutput(track, selectedRouteId)
                val read = record.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    underruns++
                    lastUnderruns = underruns
                    setSnapshot(running = true, latencyMs = latencyMs, level = 0.0, sampleRate = config.sampleRate, underruns = underruns, error = "Lecture micro interrompue ($read).")
                    continue
                }

                val delaySamples = ((latencyMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS) * config.sampleRate) / 1000).coerceIn(0, ring.size - inputFrames - 1)
                var sum = 0.0

                for (i in 0 until read) {
                    val sample = input[i]
                    sum += (sample.toDouble() * sample.toDouble())
                    ring[writePos] = sample
                    if (delaySamples == 0) {
                        output[i] = sample
                    } else {
                        var readPos = writePos - delaySamples
                        if (readPos < 0) readPos += ring.size
                        output[i] = ring[readPos]
                    }
                    writePos++
                    if (writePos >= ring.size) writePos = 0
                }

                val written = track.write(output, 0, read, AudioTrack.WRITE_BLOCKING)
                if (written < read) {
                    underruns++
                }
                lastUnderruns = underruns
                val rms = sqrt(sum / max(1, read)) / Short.MAX_VALUE.toDouble()
                setSnapshot(
                    running = true,
                    latencyMs = latencyMs,
                    level = min(1.0, rms * 5.0),
                    sampleRate = config.sampleRate,
                    underruns = underruns,
                    error = "",
                )
            }
        } catch (throwable: Throwable) {
            fail(throwable.message ?: throwable.javaClass.simpleName)
        } finally {
            try { record.stop() } catch (_: Throwable) { }
            try { track.stop() } catch (_: Throwable) { }
            record.release()
            track.release()
            if (!running.get()) {
                setSnapshot(running = false, latencyMs = latencyMs, level = 0.0, sampleRate = config.sampleRate, underruns = underruns, error = "")
            }
        }
    }

    private fun chooseConfig(): AudioConfig? {
        val candidates = intArrayOf(48000, 44100, 32000, 16000)
        for (rate in candidates) {
            val minRecord = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val minTrack = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minRecord > 0 && minTrack > 0) return AudioConfig(rate, minRecord * 2, minTrack * 2)
        }
        return null
    }

    private fun buildAudioRecord(sampleRate: Int, bufferBytes: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        }
    }

    private fun buildAudioTrack(sampleRate: Int, bufferBytes: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            builder.build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM)
        }
    }

    private var lastAppliedRouteId = Int.MIN_VALUE
    private fun applyPreferredOutput(track: AudioTrack, routeId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (routeId == lastAppliedRouteId) return
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == routeId }
        track.preferredDevice = device
        lastAppliedRouteId = routeId
    }

    private fun fail(message: String) {
        running.set(false)
        setSnapshot(running = false, latencyMs = latencyMs, level = 0.0, sampleRate = lastSampleRate, underruns = lastUnderruns, error = message)
    }

    private data class AudioConfig(val sampleRate: Int, val minRecordBufferBytes: Int, val minTrackBufferBytes: Int)

    companion object {
        private const val MIN_DELAY_MS = 0
        private const val MAX_DELAY_MS = 5000
        @Volatile private var lastRunning: Boolean = false
        @Volatile private var lastLatencyMs: Int = 500
        @Volatile private var lastLevel: Double = 0.0
        @Volatile private var lastSampleRate: Int = 0
        @Volatile private var lastUnderruns: Int = 0
        @Volatile private var lastError: String = ""

        fun statusSnapshot(): Map<String, Any> = mapOf(
            "running" to lastRunning,
            "latencyMs" to lastLatencyMs,
            "level" to lastLevel,
            "sampleRate" to lastSampleRate,
            "underruns" to lastUnderruns,
            "error" to lastError,
        )

        private fun clearStatus() {
            lastError = ""
            lastUnderruns = 0
            lastLevel = 0.0
        }

        private fun updateLatency(latencyMs: Int) {
            lastLatencyMs = latencyMs
        }

        private fun setSnapshot(running: Boolean, latencyMs: Int, level: Double, sampleRate: Int, underruns: Int, error: String) {
            lastRunning = running
            lastLatencyMs = latencyMs
            lastLevel = level
            lastSampleRate = sampleRate
            lastUnderruns = underruns
            lastError = error
        }
    }
}
