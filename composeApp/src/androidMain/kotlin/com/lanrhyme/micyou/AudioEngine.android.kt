package com.lanrhyme.micyou

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.*

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.content.Intent

actual class AudioEngine actual constructor() {
    private val _state = MutableStateFlow(StreamState.Idle)
    actual val streamState: Flow<StreamState> = _state
    private val _audioLevels = MutableStateFlow(0f)
    actual val audioLevels: Flow<Float> = _audioLevels
    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: Flow<String?> = _lastError
    private var job: Job? = null
    private val startStopMutex = Mutex()
    private val proto = ProtoBuf { }
    
    // Store context reference (In a real app, use dependency injection or Application singleton)
    // For simplicity in this KMM setup, we'll access Application Context via reflection or a static helper if needed.
    // However, since we don't have easy access to context here, we might need to pass it or use a global accessor.
    // Given the structure, we will use a workaround or assume the user has a way to get context.
    // BUT, since we are in `androidMain`, we can't easily get Activity context without passing it.
    // Let's rely on a static ContextHelper.
    
    private val CHECK_1 = "MicYouCheck1"
    private val CHECK_2 = "MicYouCheck2"

    actual suspend fun start(
        ip: String, 
        port: Int, 
        mode: ConnectionMode, 
        isClient: Boolean,
        sampleRate: SampleRate,
        channelCount: ChannelCount,
        audioFormat: com.lanrhyme.micyou.AudioFormat
    ) {
        if (!isClient) return
        _lastError.value = null

        val jobToJoin = startStopMutex.withLock {
            val currentJob = job
            if (currentJob != null && !currentJob.isCompleted) {
                null
            } else {
                _state.value = StreamState.Connecting
                CoroutineScope(Dispatchers.IO).launch {
                    var socket: Socket? = null
                    var recorder: AudioRecord? = null
                    
                    try {
                        // 音频设置
                        val androidSampleRate = sampleRate.value
                        val androidChannelConfig = if (channelCount == ChannelCount.Stereo) 
                            AudioFormat.CHANNEL_IN_STEREO 
                        else 
                            AudioFormat.CHANNEL_IN_MONO
                            
                        val androidAudioFormat = when(audioFormat) {
                            com.lanrhyme.micyou.AudioFormat.PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
                            com.lanrhyme.micyou.AudioFormat.PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
                            com.lanrhyme.micyou.AudioFormat.PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
                            else -> AudioFormat.ENCODING_PCM_16BIT // Default fallback
                        }
                        
                        val minBufSize = AudioRecord.getMinBufferSize(androidSampleRate, androidChannelConfig, androidAudioFormat)

                        try {
                            recorder = try {
                                // 尝试使用 UNPROCESSED (无处理源，以获得最高音质 (Android 7.0+)
                                // 这能避免系统自带的激进降噪和回声消除破坏音质
                                AudioRecord(
                                    MediaRecorder.AudioSource.UNPROCESSED,
                                    androidSampleRate,
                                    androidChannelConfig,
                                    androidAudioFormat,
                                    minBufSize * 2
                                )
                            } catch (e: Exception) {
                                // 如果不支持 UNPROCESSED，回退到 MIC
                                println("UNPROCESSED source failed, falling back to MIC: ${e.message}")
                                AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    androidSampleRate,
                                    androidChannelConfig,
                                    androidAudioFormat,
                                    minBufSize * 2
                                )
                            }
                        } catch (e: SecurityException) {
                            e.printStackTrace()
                            _state.value = StreamState.Error
                            _lastError.value = "录音权限不足"
                            return@launch
                        }
                        
                        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                            val msg = "AudioRecord 初始化失败"
                            println(msg)
                            _state.value = StreamState.Error
                            _lastError.value = msg
                            return@launch
                        }
                        
                        // 网络设置
                        val selectorManager = SelectorManager(Dispatchers.IO)
                        val socketBuilder = aSocket(selectorManager)
                        
                        if (mode == ConnectionMode.WifiUdp) {
                             throw UnsupportedOperationException("UDP Not fully implemented yet")
                        } else {
                            socket = socketBuilder.tcp().connect(ip, port)
                        }
                        
                        val output = socket!!.openWriteChannel(autoFlush = true)
                        val input = socket!!.openReadChannel()

                        // Start Foreground Service
                        val context = ContextHelper.getContext()
                        if (context != null) {
                            val intent = Intent(context, AudioService::class.java).apply {
                                action = AudioService.ACTION_START
                            }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        }

                        // 握手
                        output.writeFully(CHECK_1.encodeToByteArray())
                        output.flush()

                        val responseBuffer = ByteArray(CHECK_2.length)
                        input.readFully(responseBuffer, 0, responseBuffer.size)

                        if (!responseBuffer.decodeToString().equals(CHECK_2)) {
                            val msg = "握手失败"
                            println(msg)
                            _state.value = StreamState.Error
                            _lastError.value = msg
                            socket.close()
                            return@launch
                        }

                        recorder.startRecording()
                        _state.value = StreamState.Streaming
                        _lastError.value = null

                        val buffer = ByteArray(minBufSize)
                        val floatBuffer = if (androidAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) FloatArray(minBufSize / 4) else null
                        
                        while (isActive) {
                            var readBytes = 0
                            val audioData: ByteArray

                            if (androidAudioFormat == AudioFormat.ENCODING_PCM_FLOAT && floatBuffer != null) {
                                val readFloats = recorder.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_BLOCKING)
                                if (readFloats > 0) {
                                    readBytes = readFloats * 4
                                    audioData = ByteArray(readBytes)
                                    ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floatBuffer, 0, readFloats)
                                } else {
                                    audioData = ByteArray(0)
                                }
                            } else {
                                readBytes = recorder.read(buffer, 0, buffer.size)
                                audioData = if (readBytes > 0) buffer.copyOfRange(0, readBytes) else ByteArray(0)
                            }

                            if (readBytes > 0) {
                                // 计算电平
                                val rms = calculateRMS(audioData, audioFormat)
                                _audioLevels.value = rms

                                // 创建数据包
                                val packet = AudioPacketMessage(
                                    buffer = audioData,
                                    sampleRate = androidSampleRate,
                                    channelCount = if (channelCount == ChannelCount.Stereo) 2 else 1,
                                    audioFormat = audioFormat.value
                                )
                                
                                // 序列化
                                val packetBytes = proto.encodeToByteArray(AudioPacketMessage.serializer(), packet)
                                
                                // 写入长度 (大端序)
                                val length = packetBytes.size
                                output.writeByte((length shr 24).toByte())
                                output.writeByte((length shr 16).toByte())
                                output.writeByte((length shr 8).toByte())
                                output.writeByte(length.toByte())
                                
                                // 写入数据
                                output.writeFully(packetBytes)
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isActive) {
                            // Check if it's likely a normal disconnection (EOF, socket closed, etc.)
                            val msg = e.message ?: ""
                            val isDisconnect = msg.contains("broken pipe", ignoreCase = true) ||
                                              msg.contains("connection reset", ignoreCase = true) ||
                                              msg.contains("closed", ignoreCase = true) ||
                                              e is java.io.EOFException

                            if (!isDisconnect) {
                                e.printStackTrace()
                                _state.value = StreamState.Error
                                _lastError.value = "连接断开: ${e.message}"
                            } else {
                                // Treated as normal stop
                                println("Disconnected by remote: ${e.message}")
                            }
                        }
                    } finally {
                        try {
                            recorder?.stop()
                            recorder?.release()
                            socket?.close()
                            
                            // Stop Foreground Service
                            val context = ContextHelper.getContext()
                            if (context != null) {
                                val intent = Intent(context, AudioService::class.java).apply {
                                    action = AudioService.ACTION_STOP
                                }
                                context.startService(intent)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        _state.value = StreamState.Idle
                    }
                }.also { job = it }
            }
        }
        jobToJoin?.join()
    }
    
    actual fun updateConfig(
        enableNS: Boolean,
        nsType: NoiseReductionType,
        enableAGC: Boolean,
        agcTargetLevel: Int,
        enableVAD: Boolean,
        vadThreshold: Int,
        enableDereverb: Boolean,
        dereverbLevel: Float,
        amplification: Float
    ) {
        // Android 端无需处理，功能仅存在于桌面端
    }
    
    private fun calculateRMS(buffer: ByteArray, format: com.lanrhyme.micyou.AudioFormat): Float {
        var sum = 0.0
        var sampleCount = 0

        when (format) {
            com.lanrhyme.micyou.AudioFormat.PCM_FLOAT -> {
                sampleCount = buffer.size / 4
                for (i in 0 until sampleCount) {
                     val byteIndex = i * 4
                     val bits = (buffer[byteIndex].toInt() and 0xFF) or
                                ((buffer[byteIndex + 1].toInt() and 0xFF) shl 8) or
                                ((buffer[byteIndex + 2].toInt() and 0xFF) shl 16) or
                                ((buffer[byteIndex + 3].toInt() and 0xFF) shl 24)
                     val sample = Float.fromBits(bits)
                     sum += sample * sample
                }
            }
            com.lanrhyme.micyou.AudioFormat.PCM_8BIT -> {
                sampleCount = buffer.size
                for (i in 0 until sampleCount) {
                    // 8-bit PCM unsigned 0-255 -> center 128
                    val sample = (buffer[i].toInt() and 0xFF) - 128
                    val normalized = sample / 128.0
                    sum += normalized * normalized
                }
            }
            else -> { // 16-bit
                sampleCount = buffer.size / 2
                for (i in 0 until sampleCount) {
                     val byteIndex = i * 2
                     val sample = ((buffer[byteIndex+1].toInt() shl 8) or (buffer[byteIndex].toInt() and 0xFF)).toShort()
                     val normalized = sample / 32768.0
                     sum += normalized * normalized
                }
            }
        }
        
        val mean = if (sampleCount > 0) sum / sampleCount else 0.0
        return kotlin.math.sqrt(mean).toFloat().coerceIn(0f, 1f)
    }

    actual fun stop() {
        CoroutineScope(Dispatchers.IO).launch {
            startStopMutex.withLock {
                job?.cancelAndJoin()
                job = null
            }
        }
    }

    actual fun setMonitoring(enabled: Boolean) {
        // Android 端不需要本地监听
    }
    
    // 安装驱动进度（仅桌面端有效）
    actual val installProgress: Flow<String?> = kotlinx.coroutines.flow.flowOf(null)

    // 安装驱动（仅桌面端有效）
    actual suspend fun installDriver() {
        // Android 端不需要安装驱动
    }
}
