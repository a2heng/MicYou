package com.lanrhyme.micyou

import java.io.File

/**
 * 平台工具类
 * 提供操作系统检测和相关功能
 */
object PlatformUtils {
    /**
     * 操作系统类型枚举
     */
    enum class OS {
        WINDOWS, LINUX, MACOS, OTHER
    }

    /**
     * 当前操作系统
     */
    val currentOS: OS by lazy {
        val osName = System.getProperty("os.name", "").lowercase()
        when {
            osName.contains("win") -> OS.WINDOWS
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> OS.LINUX
            osName.contains("mac") -> OS.MACOS
            else -> OS.OTHER
        }
    }

    /**
     * 是否运行在Windows系统
     */
    val isWindows: Boolean get() = currentOS == OS.WINDOWS

    /**
     * 是否运行在Linux系统
     */
    val isLinux: Boolean get() = currentOS == OS.LINUX

    /**
     * 是否运行在macOS系统
     */
    val isMacOS: Boolean get() = currentOS == OS.MACOS

    /**
     * 检测PipeWire是否可用
     */
    fun isPipeWireAvailable(): Boolean {
        if (!isLinux) return false
        
        // 方法1: 检查pw-cli命令是否可用
        return try {
            val process = ProcessBuilder("pw-cli", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测PulseAudio是否可用
     */
    fun isPulseAudioAvailable(): Boolean {
        if (!isLinux) return false
        
        // 方法1: 检查pactl命令是否可用
        return try {
            val process = ProcessBuilder("pactl", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测是否存在虚拟音频设备
     */
    fun hasVirtualAudioDevice(): Boolean {
        return when (currentOS) {
            OS.WINDOWS -> {
                // Windows: 检查VB-Cable设备
                VBCableManager.isVBCableInstalled()
            }
            OS.LINUX -> {
                // Linux: 检查是否存在虚拟设备
                checkLinuxVirtualDevice()
            }
            OS.MACOS -> {
                // macOS: 检查是否存在BlackHole或类似虚拟设备
                checkMacOSVirtualDevice()
            }
            else -> false
        }
    }

    /**
     * 检查Linux虚拟音频设备
     */
    private fun checkLinuxVirtualDevice(): Boolean {
        // 尝试通过pactl列出设备，查找虚拟设备
        return try {
            val process = ProcessBuilder("pactl", "list", "sinks", "short")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            // 查找包含"virtual"、"null"、"loopback"等关键词的设备
            val lines = output.lines()
            lines.any { line ->
                val lowerLine = line.lowercase()
                lowerLine.contains("virtual") || 
                lowerLine.contains("null") || 
                lowerLine.contains("loopback") ||
                lowerLine.contains("MicYouVirtual")
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查macOS虚拟音频设备
     */
    private fun checkMacOSVirtualDevice(): Boolean {
        // 尝试通过系统命令检查音频设备
        return try {
            val process = ProcessBuilder("system_profiler", "SPAudioDataType")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            output.contains("BlackHole") || output.contains("virtual")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 创建Linux虚拟音频设备（如果不存在）
     * 返回是否成功创建或已存在
     */
    fun setupLinuxVirtualDevice(): Boolean {
        if (!isLinux) return false
        
        // 检查是否已存在
        if (checkLinuxVirtualDevice()) {
            println("虚拟音频设备已存在")
            return true
        }
        
        // 根据可用的音频系统创建设备
        return when {
            isPipeWireAvailable() -> setupPipeWireVirtualDevice()
            isPulseAudioAvailable() -> setupPulseAudioVirtualDevice()
            else -> {
                println("无法创建虚拟设备：未找到PipeWire或PulseAudio")
                false
            }
        }
    }

    /**
     * 创建PipeWire虚拟设备
     */
    private fun setupPipeWireVirtualDevice(): Boolean {
        println("正在创建PipeWire虚拟设备...")
        
        return try {
            // 创建虚拟设备
            // 方法1: 使用pw-cli创建节点
            val createProcess = ProcessBuilder(
                "pw-cli", "create-node",
                "adapter", "factory.name=support.null-audio-sink", 
                "node.name=MicYouVirtualSink", 
                "media.class=Audio/Sink", 
                "object.linger=true", 
                "audio.position=[FL FR]"
            ).redirectErrorStream(true).start()
            
            val createOutput = createProcess.inputStream.bufferedReader().readText()
            val createExitCode = createProcess.waitFor()
            
            if (createExitCode != 0) {
                println("创建PipeWire虚拟设备失败: $createOutput")
                return false
            }
            
            // 等待设备就绪
            Thread.sleep(1000)
            
            // 验证设备是否创建成功
            val checkProcess = ProcessBuilder("pw-cli", "info", "MicYouVirtualSink")
                .redirectErrorStream(true).start()
            val checkOutput = checkProcess.inputStream.bufferedReader().readText()
            checkProcess.waitFor()
            
            val success = checkOutput.contains("MicYouVirtualSink")
            if (success) {
                println("PipeWire虚拟设备创建成功")
            } else {
                println("无法验证PipeWire虚拟设备创建")
            }
            
            success
        } catch (e: Exception) {
            println("创建PipeWire虚拟设备时出错: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 创建PulseAudio虚拟设备
     */
    private fun setupPulseAudioVirtualDevice(): Boolean {
        println("正在创建PulseAudio虚拟设备...")
        
        return try {
            // 创建虚拟设备（null sink）
            val createProcess = ProcessBuilder(
                "pactl", "load-module", "module-null-sink",
                "sink_name=MicYouVirtualSink"
            ).redirectErrorStream(true).start()
            
            val createOutput = createProcess.inputStream.bufferedReader().readText()
            val createExitCode = createProcess.waitFor()
            
            if (createExitCode != 0) {
                println("创建PulseAudio虚拟设备失败: $createOutput")
                return false
            }
            
            // 等待设备就绪
            Thread.sleep(1000)
            
            // 验证设备是否创建成功
            val checkProcess = ProcessBuilder("pactl", "list", "sinks", "short")
                .redirectErrorStream(true).start()
            val checkOutput = checkProcess.inputStream.bufferedReader().readText()
            checkProcess.waitFor()
            
            val success = checkOutput.contains("MicYouVirtualSink")
            if (success) {
                println("PulseAudio虚拟设备创建成功")
            } else {
                println("无法验证PulseAudio虚拟设备创建")
            }
            
            success
        } catch (e: Exception) {
            println("创建PulseAudio虚拟设备时出错: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 获取当前默认的音频输出设备（sink）
     * 仅Linux平台有效，返回设备名称，失败返回null
     */
    fun getDefaultSink(): String? {
        if (!isLinux) return null
        
        return try {
            val process = ProcessBuilder("pactl", "get-default-sink")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0 && output.isNotBlank()) {
                output
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 设置默认音频输出设备（sink）
     * 仅Linux平台有效，返回是否成功
     */
    fun setDefaultSink(sinkName: String): Boolean {
        if (!isLinux) return false
        
        return try {
            val process = ProcessBuilder("pactl", "set-default-sink", sinkName)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0) {
                println("成功设置默认输出设备为: $sinkName")
                true
            } else {
                println("设置默认输出设备失败: $output")
                false
            }
        } catch (e: Exception) {
            println("设置默认输出设备时出错: ${e.message}")
            false
        }
    }
    
    /**
     * 重定向系统音频输出到虚拟设备
     * 仅在Linux平台有效，返回是否成功
     * 注意：此函数会更改系统全局音频设置，需要在应用停止时恢复
     */
    fun redirectAudioToVirtualDevice(): Boolean {
        if (!isLinux) return false
        
        // 确保虚拟设备存在
        if (!hasVirtualAudioDevice()) {
            if (!setupLinuxVirtualDevice()) {
                println("无法创建虚拟音频设备")
                return false
            }
        }
        
        // 设置虚拟设备为默认sink
        return setDefaultSink("MicYouVirtualSink")
    }
    
    /**
     * 恢复系统默认音频输出设备
     * 仅在Linux平台有效，需要配合redirectAudioToVirtualDevice使用
     */
    fun restoreDefaultSink(originalSink: String?): Boolean {
        if (!isLinux || originalSink.isNullOrBlank()) return false
        
        return try {
            val process = ProcessBuilder("pactl", "set-default-sink", originalSink)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0) {
                println("成功恢复默认输出设备为: $originalSink")
                true
            } else {
                println("恢复默认输出设备失败: $output")
                false
            }
        } catch (e: Exception) {
            println("恢复默认输出设备时出错: ${e.message}")
            false
        }
    }
    
    /**
     * 获取虚拟sink的monitor源名称
     * 仅Linux平台有效，用于设置默认音频输入源
     */
    fun getVirtualSinkMonitor(): String {
        return "MicYouVirtualSink.monitor"
    }
    
    /**
     * 清理临时文件
     */
    fun cleanupTempFiles() {
        // 清理可能创建的临时安装文件
        val tempDir = System.getProperty("java.io.tmpdir")
        if (tempDir != null) {
            val tempDirFile = File(tempDir)
            tempDirFile.listFiles()?.forEach { file ->
                if (file.name.startsWith("vbcable_") || file.name.startsWith("micyou_")) {
                    try {
                        if (file.isDirectory) {
                            file.deleteRecursively()
                        } else {
                            file.delete()
                        }
                    } catch (e: Exception) {
                        // 忽略删除错误
                    }
                }
            }
        }
    }
}