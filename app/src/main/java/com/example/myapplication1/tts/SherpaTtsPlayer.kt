package com.example.myapplication1.tts

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import com.example.myapplication1.debug.AgentDebugLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

/**
 * 单例离线 TTS：中文用 vits-icefall-zh-aishell3，英文用 vits-vctk，均通过 [GenerationConfig.sid] 切换说话人。
 */
class SherpaTtsPlayer(private val app: Application) {

    private val lock = Any()
    private var offlineTtsZh: OfflineTts? = null
    private var offlineTtsEn: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private val speakGeneration = AtomicInteger(0)

    fun isModelInstalled(kind: TtsEngineKind): Boolean =
        TtsModelPaths.isModelFilesPresent(app, kind)

    private fun ensureTts(kind: TtsEngineKind): OfflineTts? {
        synchronized(lock) {
            when (kind) {
                TtsEngineKind.Chinese -> {
                    if (offlineTtsZh != null) return offlineTtsZh
                    if (!isModelInstalled(TtsEngineKind.Chinese)) return null
                    val root = TtsModelPaths.zhModelRoot(app)
                    val config = getOfflineTtsConfig(
                        modelDir = root.absolutePath,
                        modelName = "model.onnx",
                        acousticModelName = "",
                        vocoder = "",
                        voices = "",
                        lexicon = "lexicon.txt",
                        dataDir = "",
                        dictDir = "",
                        ruleFsts = "",
                        ruleFars = File(root, "rule.far").absolutePath,
                        numThreads = 2,
                        isKitten = false,
                    )
                    offlineTtsZh = OfflineTts(assetManager = null, config = config)
                    return offlineTtsZh
                }
                TtsEngineKind.English -> {
                    if (offlineTtsEn != null) return offlineTtsEn
                    if (!isModelInstalled(TtsEngineKind.English)) return null
                    val root = TtsModelPaths.vctkModelRoot(app)
                    val config = getOfflineTtsConfig(
                        modelDir = root.absolutePath,
                        modelName = "vits-vctk.onnx",
                        acousticModelName = "",
                        vocoder = "",
                        voices = "",
                        lexicon = "lexicon.txt",
                        dataDir = "",
                        dictDir = "",
                        ruleFsts = "",
                        ruleFars = "",
                        numThreads = 2,
                        isKitten = false,
                    )
                    offlineTtsEn = OfflineTts(assetManager = null, config = config)
                    return offlineTtsEn
                }
            }
        }
    }

    /**
     * 避免 sid 越界导致 JNI/native 崩溃；[OfflineTts.numSpeakers] 不可用时退回 0。
     */
    private fun clampSpeakerId(tts: OfflineTts, speakerId: Int): Int {
        val n = runCatching { tts.numSpeakers() }.getOrDefault(0)
        if (n <= 0) return 0
        return speakerId.coerceIn(0, n - 1)
    }

    private fun newAudioTrack(sampleRate: Int): AudioTrack {
        synchronized(lock) {
            audioTrack?.release()
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            ).coerceAtLeast(1024)
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setSampleRate(sampleRate)
                    .build(),
                minBuf,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            audioTrack = track
            return track
        }
    }

    /**
     * 打断上一次未播完的合成，合成后播放。
     * 使用 [OfflineTts.generateWithConfig] 而非带回调 API：库中回调类型为 `(FloatArray) -> Int`，
     * 与 JNI 侧查找的签名不一致会导致 NoSuchMethodError / 进程退出。
     * 对同一 [OfflineTts] 实例串行执行合成：多线程并发调用 native 易导致进程直接崩溃。
     */
    fun speak(text: String, speakerId: Int, speed: Float = 1f, engine: TtsEngineKind = TtsEngineKind.Chinese) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val gen = speakGeneration.incrementAndGet()
        Thread {
            // #region agent log
            AgentDebugLog.emit(
                "H1",
                "SherpaTtsPlayer.kt:speak",
                "tts_thread_start",
                mapOf(
                    "gen" to gen,
                    "engine" to engine.name,
                    "thread" to Thread.currentThread().name,
                    "speakerIdIn" to speakerId,
                    "textLen" to trimmed.length,
                ),
            )
            // #endregion
            val tts = ensureTts(engine) ?: return@Thread
            synchronized(tts) {
                if (speakGeneration.get() != gen) return@synchronized
                val numSpeakersProbe = runCatching { tts.numSpeakers() }.getOrDefault(-1)
                val sid = clampSpeakerId(tts, speakerId)
                // #region agent log
                AgentDebugLog.emit(
                    "H2",
                    "SherpaTtsPlayer.kt:speak",
                    "after_clamp_before_audio",
                    mapOf(
                        "numSpeakers" to numSpeakersProbe,
                        "sidIn" to speakerId,
                        "sidOut" to sid,
                        "textLen" to trimmed.length,
                    ),
                )
                // #endregion
                val sr = tts.sampleRate()
                val minBufRaw = AudioTrack.getMinBufferSize(
                    sr,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                )
                // #region agent log
                AgentDebugLog.emit(
                    "H3",
                    "SherpaTtsPlayer.kt:speak",
                    "audiotrack_min_buffer",
                    mapOf(
                        "sampleRate" to sr,
                        "minBufRaw" to minBufRaw,
                    ),
                )
                // #endregion
                val track = newAudioTrack(sr)
                track.pause()
                track.flush()
                track.play()
                try {
                    val generated = tts.generateWithConfig(
                        text = trimmed,
                        config = GenerationConfig(sid = sid, speed = speed),
                    )
                    val samples = generated.samples
                    var offset = 0
                    val chunkFloats = 8192
                    while (offset < samples.size) {
                        if (speakGeneration.get() != gen) break
                        val n = minOf(chunkFloats, samples.size - offset)
                        track.write(samples, offset, n, AudioTrack.WRITE_BLOCKING)
                        offset += n
                    }
                    // #region agent log
                    AgentDebugLog.emit(
                        "H2",
                        "SherpaTtsPlayer.kt:speak",
                        "synth_finished_ok",
                        mapOf("gen" to gen),
                    )
                    // #endregion
                } catch (t: Throwable) {
                    // #region agent log
                    AgentDebugLog.emit(
                        "H2",
                        "SherpaTtsPlayer.kt:speak",
                        "synth_exception",
                        mapOf(
                            "type" to t.javaClass.name,
                            "msg" to (t.message ?: "").take(300),
                        ),
                    )
                    // #endregion
                    // 模型或 JNI 异常时静默失败，避免拖垮采集线程
                }
            }
        }.start()
    }

    fun stop() {
        speakGeneration.incrementAndGet()
        synchronized(lock) {
            audioTrack?.pause()
            audioTrack?.flush()
        }
    }

    fun release() {
        speakGeneration.incrementAndGet()
        synchronized(lock) {
            offlineTtsZh?.release()
            offlineTtsZh = null
            offlineTtsEn?.release()
            offlineTtsEn = null
            audioTrack?.release()
            audioTrack = null
        }
    }
}
