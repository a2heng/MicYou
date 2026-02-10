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
import javax.sound.sampled.*

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

    actual suspend fun start(ip: String, port: Int, mode: ConnectionMode, isClient: Boolean) {
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
                    var serverSocket: ServerSocket? = null
                    
                    try {
                        serverSocket = aSocket(selectorManager).tcp().bind(port = port)
                        val msg = "监听端口 $port"
                        println(msg)
                        // 可以选择是否显示监听成功信息，这里暂不视为错误
                        
                        while (isActive) {
                            val socket = serverSocket.accept()
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

        var line: SourceDataLine? = null
        
        try {
            val lengthBytes = ByteArray(4)
            while (currentCoroutineContext().isActive) {
                // 读取长度 (4字节)
                input.readFully(lengthBytes, 0, lengthBytes.size)
                
                // 大端序
                val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                             ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                             ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                             (lengthBytes[3].toInt() and 0xFF)

                if (length <= 0) continue
                
                // 读取数据包
                val packetBytes = ByteArray(length)
                input.readFully(packetBytes, 0, packetBytes.size)
                
                try {
                    val packet: AudioPacketMessage = proto.decodeFromByteArray(AudioPacketMessage.serializer(), packetBytes)
                    
                    // 设置音频输出
                    if (line == null) {
                        val audioFormat = AudioFormat(
                            packet.sampleRate.toFloat(),
                            16,
                            packet.channelCount,
                            true,
                            false 
                        )
                        
                        val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
                        line = AudioSystem.getLine(info) as SourceDataLine
                        line?.open(audioFormat)
                        line?.start()
                    }
                    
                    line?.write(packet.buffer, 0, packet.buffer.size)
                    
                    // 计算音频电平
                    val rms = calculateRMS(packet.buffer)
                    _audioLevels.value = rms
                } catch (e: Exception) {
                    println("Packet decode error: ${e.message}")
                }
            }
        } finally {
            line?.drain()
            line?.close()
        }
    }
    
    private fun calculateRMS(buffer: ByteArray): Float {
        var sum = 0.0
        for (i in 0 until buffer.size step 2) {
             if (i+1 >= buffer.size) break
             val sample = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
             sum += sample * sample
        }
        val mean = sum / (buffer.size / 2)
        val root = kotlin.math.sqrt(mean)
        return (root / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    actual fun stop() {
        CoroutineScope(Dispatchers.IO).launch {
            val jobToCancel = startStopMutex.withLock {
                job?.also { it.cancel() }
            }
            jobToCancel?.join()
            startStopMutex.withLock {
                if (job === jobToCancel) {
                    job = null
                }
            }
        }
    }
}
