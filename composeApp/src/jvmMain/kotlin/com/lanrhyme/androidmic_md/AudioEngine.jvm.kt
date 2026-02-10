package com.lanrhyme.androidmic_md

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.*
import kotlinx.serialization.*
import java.net.BindException
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.sqrt

@OptIn(ExperimentalSerializationApi::class)
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
    private val CHECK_1 = "AndroidMic1"
    private val CHECK_2 = "AndroidMic2"
    
    private var serverSocket: ServerSocket? = null
    private var monitoringLine: SourceDataLine? = null
    private var isUsingCable = false
    
    @Volatile
    private var isMonitoring = false
    
    // Config State
    @Volatile private var enableNS: Boolean = false
    @Volatile private var nsType: NoiseReductionType = NoiseReductionType.RNNoise
    @Volatile private var enableAGC: Boolean = false
    @Volatile private var agcTargetLevel: Int = 32000
    @Volatile private var enableVAD: Boolean = false
    @Volatile private var vadThreshold: Int = 10
    @Volatile private var enableDereverb: Boolean = false
    @Volatile private var dereverbLevel: Float = 0.5f
    @Volatile private var amplification: Float = 10.0f
    
    // Internal Audio Processing State
    private var agcEnvelope: Float = 0f

    actual val installProgress: Flow<String?> = VBCableManager.installProgress
    
    actual suspend fun installDriver() {
        VBCableManager.installVBCable()
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
        this.enableNS = enableNS
        this.nsType = nsType
        this.enableAGC = enableAGC
        this.agcTargetLevel = agcTargetLevel
        this.enableVAD = enableVAD
        this.vadThreshold = vadThreshold
        this.enableDereverb = enableDereverb
        this.dereverbLevel = dereverbLevel
        this.amplification = amplification
        
        println("Config Updated: Amp=$amplification, VAD=$enableVAD ($vadThreshold), AGC=$enableAGC ($agcTargetLevel), NS=$enableNS ($nsType)")
    }

    actual suspend fun start(
        ip: String, 
        port: Int, 
        mode: ConnectionMode, 
        isClient: Boolean,
        sampleRate: SampleRate,
        channelCount: ChannelCount,
        audioFormat: AudioFormat
    ) {
        if (isClient) return 
        _lastError.value = null // 清除之前的错误

        val jobToJoin = startStopMutex.withLock {
            val currentJob = job
            if (currentJob != null && !currentJob.isCompleted) {
                null
            } else {
                _state.value = StreamState.Connecting
                CoroutineScope(Dispatchers.IO).launch {
                    val selectorManager = SelectorManager(Dispatchers.IO)
                    
                    try {
                        serverSocket = aSocket(selectorManager).tcp().bind(port = port)
                        val msg = "监听端口 $port"
                        println(msg)
                        
                        while (isActive) {
                            val socket = serverSocket?.accept() ?: break
                            println("接受来自 ${socket.remoteAddress} 的连接")
                            _state.value = StreamState.Streaming
                            _lastError.value = null
                            
                            try {
                                handleConnection(socket)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                _lastError.value = "连接处理错误: ${e.message}"
                            } finally {
                                socket.close()
                                _state.value = StreamState.Connecting
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: BindException) {
                        if (isActive) {
                            val msg = "端口 $port 已被占用。请关闭其他 AndroidMic 实例。"
                            println(msg)
                            _state.value = StreamState.Error
                            _lastError.value = msg
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            e.printStackTrace()
                            _state.value = StreamState.Error
                            _lastError.value = "服务器错误: ${e.message}"
                        }
                    } finally {
                        serverSocket?.close()
                        if (_state.value != StreamState.Error) {
                            _state.value = StreamState.Idle
                        }
                    }
                }.also { job = it }
            }
        }
        jobToJoin?.join()
    }

    private suspend fun handleConnection(socket: Socket) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)

        // 握手
        val check1Packet = input.readPacket(CHECK_1.length)
        val check1String = check1Packet.readText()
        
        if (!check1String.equals(CHECK_1)) {
            println("握手失败: 收到 $check1String")
            return
        }

        output.writeFully(CHECK_2.encodeToByteArray())
        output.flush()

        try {
            val lengthBytes = ByteArray(4)
            // Reset AGC state on new connection
            agcEnvelope = 0f
            
            while (currentCoroutineContext().isActive) {
                input.readFully(lengthBytes, 0, lengthBytes.size)
                
                val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                             ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                             ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                             (lengthBytes[3].toInt() and 0xFF)

                if (length <= 0) continue
                
                val packetBytes = ByteArray(length)
                input.readFully(packetBytes, 0, packetBytes.size)
                
                try {
                    val packet: AudioPacketMessage = proto.decodeFromByteArray(AudioPacketMessage.serializer(), packetBytes)
                    
                    if (monitoringLine == null) {
                        val audioFormat = javax.sound.sampled.AudioFormat(
                            packet.sampleRate.toFloat(),
                            16,
                            packet.channelCount,
                            true,
                            false 
                        )
                        
                        val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
                        
                        val mixers = AudioSystem.getMixerInfo()
                        val cableMixerInfo = mixers
                            .filter { it.name.contains("CABLE Input", ignoreCase = true) }
                            .find { mixerInfo ->
                                try {
                                    val mixer = AudioSystem.getMixer(mixerInfo)
                                    mixer.isLineSupported(info)
                                } catch (e: Exception) {
                                    false
                                }
                            }
                        
                        if (cableMixerInfo != null) {
                            println("Found VB-Cable Input: ${cableMixerInfo.name}")
                            val mixer = AudioSystem.getMixer(cableMixerInfo)
                            monitoringLine = mixer.getLine(info) as SourceDataLine
                            isUsingCable = true
                        } else {
                            println("VB-Cable Input mixer not found or unsupported, using default audio output.")
                            monitoringLine = AudioSystem.getLine(info) as SourceDataLine
                            isUsingCable = false
                        }
                        
                        monitoringLine?.open(audioFormat)
                        monitoringLine?.start()
                    }
                    
                    // Process Audio
                    val processedBuffer = processAudio(packet.buffer, packet.audioFormat)
                    
                    if (processedBuffer != null) {
                        if (!isUsingCable && !isMonitoring) {
                            // If not using cable and not monitoring, write silence to keep the line active
                            // and prevent buffer underflow/looping glitches.
                            processedBuffer.fill(0.toByte())
                        }
                        
                        monitoringLine?.write(processedBuffer, 0, processedBuffer.size)
                        
                        // Calculate levels (post-process)
                        val rms = calculateRMS(processedBuffer)
                        _audioLevels.value = rms
                    }
                    
                } catch (e: Exception) {
                    println("Packet decode error: ${e.message}")
                }
            }
        } finally {
            monitoringLine?.drain()
            monitoringLine?.close()
            monitoringLine = null
        }
    }
    
    private fun processAudio(buffer: ByteArray, format: Int): ByteArray? {
        // Convert to ShortArray for processing
        val shorts: ShortArray
        
        when (format) {
            4, 32 -> { // PCM_FLOAT (32-bit Float)
                shorts = ShortArray(buffer.size / 4)
                for (i in shorts.indices) {
                    val byteIndex = i * 4
                    // Little Endian
                    val bits = (buffer[byteIndex].toInt() and 0xFF) or
                               ((buffer[byteIndex + 1].toInt() and 0xFF) shl 8) or
                               ((buffer[byteIndex + 2].toInt() and 0xFF) shl 16) or
                               ((buffer[byteIndex + 3].toInt() and 0xFF) shl 24)
                    val sample = Float.fromBits(bits)
                    // Clamp and convert to 16-bit PCM
                    shorts[i] = (sample * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            3, 8 -> { // PCM_8BIT (Unsigned 8-bit)
                 shorts = ShortArray(buffer.size)
                 for (i in shorts.indices) {
                     // 8-bit PCM is usually unsigned 0-255. 128 is 0.
                     val sample = (buffer[i].toInt() and 0xFF) - 128
                     shorts[i] = (sample * 256).toShort()
                 }
            }
            else -> { // PCM_16BIT (Default)
                shorts = ShortArray(buffer.size / 2)
                for (i in shorts.indices) {
                    shorts[i] = ((buffer[i * 2 + 1].toInt() shl 8) or (buffer[i * 2].toInt() and 0xFF)).toShort()
                }
            }
        }
        
        // 1. VAD (Simple RMS Gate)
        if (enableVAD) {
             var sum = 0.0
             for (s in shorts) {
                 sum += s * s
             }
             val rms = sqrt(sum / shorts.size)
             // Threshold is 0-100, we map it to 0-5000 RMS roughly.
             val thresholdRMS = vadThreshold * 50.0
             if (rms < thresholdRMS) {
                 // Return silence buffer to maintain stream continuity and prevent underruns
                 return ByteArray(shorts.size * 2)
             }
        }
        
        // 2. Amplification
        if (amplification != 1.0f) {
            for (i in shorts.indices) {
                var s = (shorts[i] * amplification).toInt()
                if (s > 32767) s = 32767
                if (s < -32768) s = -32768
                shorts[i] = s.toShort()
            }
        }
        
        // 3. AGC (Adaptive Gain Control with Envelope Follower)
        if (enableAGC) {
            var maxAbs = 0
            for (s in shorts) {
                val absS = abs(s.toInt())
                if (absS > maxAbs) maxAbs = absS
            }
            
            // Update Envelope (Fast Attack, Slow Decay)
            if (maxAbs > agcEnvelope) {
                agcEnvelope = maxAbs.toFloat()
            } else {
                agcEnvelope = agcEnvelope * 0.995f + maxAbs * 0.005f
            }
            
            // Avoid division by zero and extreme boosts for silence
            // Floor at 100 (approx -50dB) to avoid boosting background noise too much
            val safeEnvelope = if (agcEnvelope < 100f) 100f else agcEnvelope
            
            val targetGain = agcTargetLevel.toFloat() / safeEnvelope
            // Allow up to 30x boost (approx +30dB) for quiet sources
            val clampedGain = targetGain.coerceIn(0.1f, 30.0f) 
            
            for (i in shorts.indices) {
                 var s = (shorts[i] * clampedGain).toInt()
                 if (s > 32767) s = 32767
                 if (s < -32768) s = -32768
                 shorts[i] = s.toShort()
            }
        }
        
        // 4. Noise Reduction (Placeholder for user requested types)
        if (enableNS) {
             if (nsType != NoiseReductionType.None) {
                 // Placeholder: Do nothing for now to preserve quality.
                 // The previous simple low-pass filter was too aggressive and muffled the sound.
                 // TODO: Implement real RNNoise or Speexdsp
             }
        }
        
        // 5. Dereverb (Placeholder)
        if (enableDereverb) {
             // Pass through
        }

        // Convert back to ByteArray
        val outBuffer = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            outBuffer[i * 2] = (s and 0xFF).toByte()
            outBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return outBuffer
    }
    
    private fun calculateRMS(buffer: ByteArray): Float {
        var sum = 0.0
        for (i in 0 until buffer.size step 2) {
             if (i+1 >= buffer.size) break
             val sample = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
             sum += sample * sample
        }
        val mean = sum / (buffer.size / 2)
        val root = sqrt(mean)
        return (root / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    actual fun stop() {
        CoroutineScope(Dispatchers.IO).launch {
            startStopMutex.withLock {
                serverSocket?.close()
                job?.cancelAndJoin()
                job = null
                
                // Stop monitoring
                monitoringLine?.stop()
                monitoringLine?.close()
                monitoringLine = null
            }
        }
    }

    actual fun setMonitoring(enabled: Boolean) {
        this.isMonitoring = enabled
        // Do not close the line here, as it might be needed for VB-Cable or re-enabled later.
        // Closing it causes glitches or forces a re-open cycle in the loop.
    }
}