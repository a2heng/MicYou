package com.lanrhyme.micyou

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.protobuf.*
import kotlinx.serialization.*
import java.net.BindException
import javax.sound.sampled.*
import kotlin.math.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import de.maxhenkel.rnnoise4j.Denoiser

@OptIn(ExperimentalSerializationApi::class)
actual class AudioEngine actual constructor() {
    private val _state = MutableStateFlow(StreamState.Idle)
    actual val streamState: Flow<StreamState> = _state
    private val _audioLevels = MutableStateFlow(0f)
    actual val audioLevels: Flow<Float> = _audioLevels
    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: Flow<String?> = _lastError
    
    private val _isMuted = MutableStateFlow(false)
    actual val isMuted: Flow<Boolean> = _isMuted
    
    private var job: Job? = null
    private val startStopMutex = Mutex()
    private val proto = ProtoBuf { }
    private val CHECK_1 = "MicYouCheck1"
    private val CHECK_2 = "MicYouCheck2"
    
    private var serverSocket: ServerSocket? = null
    private var monitoringLine: SourceDataLine? = null
    private var isUsingCable = false
    
    // Channel for outgoing messages (Control)
    private var sendChannel: Channel<MessageWrapper>? = null
    
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
    
    // RNNoise instances
    private var denoiserLeft: Denoiser? = null
    private var denoiserRight: Denoiser? = null

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

        sendChannel = Channel(Channel.UNLIMITED)
        
        // --- Writer Loop (Send Channel -> Socket) ---
        val writerJob = CoroutineScope(Dispatchers.IO).launch {
            for (msg in sendChannel!!) {
                try {
                    val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), msg)
                    val length = packetBytes.size
                    output.writeInt(PACKET_MAGIC)
                    output.writeInt(length)
                    output.writeFully(packetBytes)
                    output.flush()
                } catch (e: Exception) {
                    println("Error writing to socket: ${e.message}")
                    break
                }
            }
        }
        
        // Send initial mute state
        sendChannel?.send(MessageWrapper(mute = MuteMessage(_isMuted.value)))

        try {
            // Reset AGC state on new connection
            agcEnvelope = 0f
            
            while (currentCoroutineContext().isActive) {
                val magic = input.readInt()
                if (magic != PACKET_MAGIC) {
                    println("Invalid Magic: ${magic.toString(16)}. Attempting to resync...")
                    // Resync: Read bytes one by one until we find the magic sequence
                    // This is a simple implementation. For better performance, use a buffer.
                    // But since we are desynced, performance is secondary to recovery.
                    var resyncMagic = magic
                    while (currentCoroutineContext().isActive) {
                         // Shift left by 8 and read new byte
                         val byte = input.readByte().toInt() and 0xFF
                         resyncMagic = (resyncMagic shl 8) or byte
                         if (resyncMagic == PACKET_MAGIC) {
                             println("Resynced!")
                             break
                         }
                    }
                }

                val length = input.readInt()
                
                if (length > 2 * 1024 * 1024) { // 2MB limit
                    println("Packet too large: $length. Skipping.")
                    continue
                }

                if (length <= 0) continue
                
                val packetBytes = ByteArray(length)
                input.readFully(packetBytes)
                
                try {
                    val wrapper: MessageWrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), packetBytes)
                    
                    // Handle Mute
                    if (wrapper.mute != null) {
                        _isMuted.value = wrapper.mute.isMuted
                        println("Received Mute Command: ${wrapper.mute.isMuted}")
                    }
                    
                    // Handle Audio
                    val audioPacket = wrapper.audioPacket?.audioPacket
                    if (audioPacket != null) {
                        if (monitoringLine == null) {
                            val audioFormat = javax.sound.sampled.AudioFormat(
                                audioPacket.sampleRate.toFloat(),
                                16,
                                audioPacket.channelCount,
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
                        val processedBuffer = processAudio(audioPacket.buffer, audioPacket.audioFormat, audioPacket.channelCount)
                        
                        if (processedBuffer != null) {
                            if (!isUsingCable && !isMonitoring) {
                                // If not using cable and not monitoring, write silence to keep the line active
                                processedBuffer.fill(0.toByte())
                            }
                            
                            monitoringLine?.write(processedBuffer, 0, processedBuffer.size)
                            
                            // Calculate levels (post-process)
                            val rms = calculateRMS(processedBuffer)
                            _audioLevels.value = rms
                        }
                    }
                    
                } catch (e: Exception) {
                    println("Packet decode error: ${e.message}")
                    println("Hex: ${packetBytes.joinToString("") { "%02x".format(it) }}")
                }
            }
        } finally {
                        writerJob.cancel()
                        sendChannel?.close()
                        monitoringLine?.drain()
                        monitoringLine?.close()
                        monitoringLine = null
                        
                        // Close denoisers
                        try {
                            denoiserLeft?.close()
                            denoiserLeft = null
                            denoiserRight?.close()
                            denoiserRight = null
                        } catch (e: Exception) {
                            println("Error closing denoisers: ${e.message}")
                        }
                    }
    }
    
    actual suspend fun setMute(muted: Boolean) {
        _isMuted.value = muted
        // If connected, send message
        try {
            sendChannel?.send(MessageWrapper(mute = MuteMessage(muted)))
        } catch (e: Exception) {
            println("Failed to send mute message: ${e.message}")
        }
    }
    
    actual fun setMonitoring(enabled: Boolean) {
        isMonitoring = enabled
    }

    actual fun stop() {
         try {
             serverSocket?.close()
             // Job cancellation will be handled by the scope in start() or UI
         } catch (e: Exception) {
             e.printStackTrace()
         }
    }
    
    private fun processAudio(buffer: ByteArray, format: Int, channelCount: Int): ByteArray? {
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
                     val byteIndex = i * 2
                     val sample = (buffer[byteIndex].toInt() and 0xFF) or
                                  ((buffer[byteIndex + 1].toInt()) shl 8)
                     shorts[i] = sample.toShort()
                }
            }
        }
        
        // --- Audio Processing Chain ---
        var processedShorts = shorts
        
        // Amplification
        if (amplification != 1.0f) {
            for (i in processedShorts.indices) {
                 val sample = processedShorts[i].toInt()
                 val amplified = (sample * amplification).toInt()
                 processedShorts[i] = amplified.coerceIn(-32768, 32767).toShort()
            }
        }

        // Convert back to ByteArray
        val resultBuffer = ByteArray(processedShorts.size * 2)
        ByteBuffer.wrap(resultBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(processedShorts)
        return resultBuffer
    }

    private fun calculateRMS(buffer: ByteArray): Float {
        var sum = 0.0
        val shorts = ShortArray(buffer.size / 2)
        ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        
        for (sample in shorts) {
            val normalized = sample / 32768.0
            sum += normalized * normalized
        }
        return if (shorts.isNotEmpty()) Math.sqrt(sum / shorts.size).toFloat() else 0f
    }
}
