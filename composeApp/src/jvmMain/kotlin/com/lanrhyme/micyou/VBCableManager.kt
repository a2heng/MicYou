package com.lanrhyme.micyou

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.sound.sampled.AudioSystem

object VBCableManager {
    private const val CABLE_OUTPUT_NAME = "CABLE Output"
    private const val CABLE_INPUT_NAME = "CABLE Input"
    private const val INSTALLER_NAME = "VBCABLE_Setup_x64.exe"

    private val _installProgress = MutableStateFlow<String?>(null)
    val installProgress = _installProgress.asStateFlow()

    /**
     * 检查虚拟音频设备是否已安装
     * 多平台兼容版本：根据操作系统类型进行检查
     */
    fun isVBCableInstalled(): Boolean {
        return when (PlatformUtils.currentOS) {
            PlatformUtils.OS.WINDOWS -> {
                // Windows: 检查 VB-Cable 设备
                val mixers = AudioSystem.getMixerInfo()
                mixers.any { it.name.contains(CABLE_OUTPUT_NAME, ignoreCase = true) || it.name.contains(CABLE_INPUT_NAME, ignoreCase = true) }
            }
            PlatformUtils.OS.LINUX -> {
                // Linux: 检查虚拟音频设备是否存在
                PlatformUtils.hasVirtualAudioDevice()
            }
            PlatformUtils.OS.MACOS -> {
                // macOS: 检查虚拟音频设备是否存在
                PlatformUtils.hasVirtualAudioDevice()
            }
            PlatformUtils.OS.OTHER -> {
                // 其他平台暂不支持
                false
            }
        }
    }

    /**
     * 安装虚拟音频设备
     * 多平台兼容版本：根据操作系统类型执行不同的安装逻辑
     */
    suspend fun installVBCable() = withContext(Dispatchers.IO) {
        // 检查是否已安装
        if (isVBCableInstalled()) {
            println("虚拟音频设备已安装。")
            setSystemDefaultMicrophone()
            return@withContext
        }
        
        // 根据操作系统执行不同的安装逻辑
        when (PlatformUtils.currentOS) {
            PlatformUtils.OS.WINDOWS -> installWindowsVBCable()
            PlatformUtils.OS.LINUX -> installLinuxVirtualDevice()
            PlatformUtils.OS.MACOS -> {
                _installProgress.value = "macOS平台暂不支持自动安装虚拟设备，请手动安装BlackHole等虚拟音频驱动"
                kotlinx.coroutines.delay(3000)
                _installProgress.value = null
            }
            PlatformUtils.OS.OTHER -> {
                _installProgress.value = "当前操作系统不支持自动安装虚拟音频设备"
                kotlinx.coroutines.delay(3000)
                _installProgress.value = null
            }
        }
    }
    
    /**
     * 安装Windows平台的VB-Cable驱动
     */
    private suspend fun installWindowsVBCable() {
        _installProgress.value = "正在检查安装包..."
        
        // 1. 从资源中提取安装程序或下载它
        var installerFile = extractInstaller()
        
        if (installerFile == null || !installerFile.exists()) {
            println("Installer not found in resources. Attempting to download...")
            _installProgress.value = "正在下载 VB-Cable 驱动..."
            installerFile = downloadAndExtractInstaller()
        }

        if (installerFile == null || !installerFile.exists()) {
            println("VB-Cable installer not found. Please place '$INSTALLER_NAME' in resources or ensure internet access.")
            _installProgress.value = "安装失败：无法下载或找到驱动"
            kotlinx.coroutines.delay(2000)
            _installProgress.value = null
            return
        }

        println("Installing VB-Cable...")
        _installProgress.value = "正在安装 VB-Cable 驱动..."
        
        try {
            // 2. 使用 PowerShell Start-Process 以 RunAs（管理员）静默运行安装程序
            // 使用 -Wait 等待完成
            // 注意：我们无法轻易从 RunAs 捕获 stdout/stderr，但我们可以检查它是否完成
            
            val powerShellCommand = "Start-Process -FilePath '${installerFile.absolutePath}' -ArgumentList '-i -h' -Verb RunAs -Wait"
            println("Executing: $powerShellCommand")

            val processBuilder = ProcessBuilder(
                "powershell.exe",
                "-Command",
                powerShellCommand
            )
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            // 注意：Start-Process -Wait 在进程退出时返回
            // 如果用户取消 UAC，Start-Process 可能会抛出错误或在 PowerShell 中返回非零值
            // 但除非 PowerShell 本身崩溃，否则 ProcessBuilder 可能会看到 0
            // 我们应该重新检查 isVBCableInstalled() 以验证成功
            
            println("PowerShell execution finished. Exit code: $exitCode. Output: $output")
            
            // 等待片刻以注册设备
            kotlinx.coroutines.delay(2000)
            
            if (isVBCableInstalled()) {
                println("VB-Cable installation verified.")
                _installProgress.value = "安装完成，正在配置.."
                setSystemDefaultMicrophone()
                _installProgress.value = "配置完成"
            } else {
                println("VB-Cable installation could not be verified.")
                _installProgress.value = "安装未完成或被取销"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _installProgress.value = "安装错误: ${e.message}"
        } finally {
            kotlinx.coroutines.delay(2000)
            _installProgress.value = null
        }
    }
    
    /**
     * 安装Linux平台的虚拟音频设备
     */
    private suspend fun installLinuxVirtualDevice() {
        _installProgress.value = "正在检查Linux音频系统..."
        
        try {
            // 检查是否已存在虚拟设备
            if (PlatformUtils.hasVirtualAudioDevice()) {
                _installProgress.value = "虚拟音频设备已存在，正在配置..."
                kotlinx.coroutines.delay(1000)
                setSystemDefaultMicrophone()
                _installProgress.value = "配置完成"
                kotlinx.coroutines.delay(1000)
                _installProgress.value = null
                return
            }
            
            // 尝试创建虚拟设备
            _installProgress.value = "正在创建虚拟音频设备..."
            
            val success = PlatformUtils.setupLinuxVirtualDevice()
            
            if (success) {
                _installProgress.value = "虚拟设备创建成功，正在配置..."
                kotlinx.coroutines.delay(1000)
                setSystemDefaultMicrophone()
                _installProgress.value = "配置完成"
                kotlinx.coroutines.delay(1000)
                _installProgress.value = null
            } else {
                _installProgress.value = "虚拟设备创建失败，请检查系统权限和音频服务"
                kotlinx.coroutines.delay(3000)
                _installProgress.value = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _installProgress.value = "安装错误: ${e.message}"
            kotlinx.coroutines.delay(2000)
            _installProgress.value = null
        }
    }

    private fun extractInstaller(): File? {
        try {
            val resourceStream = this::class.java.classLoader.getResourceAsStream(INSTALLER_NAME)
                ?: this::class.java.classLoader.getResourceAsStream("vbcable/$INSTALLER_NAME")
            
            if (resourceStream == null) {
                // 尝试在当前目录查找（开发模式）
                val localFile = File(INSTALLER_NAME)
                if (localFile.exists()) return localFile
                return null
            }

            val tempFile = File.createTempFile("vbcable_setup", ".exe")
            tempFile.deleteOnExit()
            
            FileOutputStream(tempFile).use { output ->
                resourceStream.copyTo(output)
            }
            return tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun downloadAndExtractInstaller(): File? {
        val downloadUrl = "https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack43.zip"
        val zipFile = File.createTempFile("vbcable_pack", ".zip")
        val outputDir = File(System.getProperty("java.io.tmpdir"), "vbcable_extracted_${System.currentTimeMillis()}")
        
        println("Downloading VB-Cable driver from $downloadUrl...")
        
        try {
            // 使用简单的 Java URL 连接下载
            val url = java.net.URI(downloadUrl).toURL()
            val connection = url.openConnection()
            connection.connect()
            
            connection.getInputStream().use { input ->
                FileOutputStream(zipFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            println("Download complete. Extracting...")
            
            // 解压 zip
            if (!outputDir.exists()) outputDir.mkdirs()
            
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryFile = File(outputDir, entry.name)
                    
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        // 如果需要，创建父目录
                        entryFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(entryFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
            
            // 查找安装文件
            val setupFile = File(outputDir, INSTALLER_NAME)
            if (setupFile.exists()) {
                println("Found installer at ${setupFile.absolutePath}")
                return setupFile
            }
            
            // 如果不在根目录，尝试递归搜索
            val found = outputDir.walkTopDown().find { it.name.equals(INSTALLER_NAME, ignoreCase = true) }
            if (found != null) {
                println("Found installer at ${found.absolutePath}")
                return found
            }
            
        } catch (e: Exception) {
            println("Failed to download or extract VB-Cable driver: ${e.message}")
            e.printStackTrace()
        } finally {
            zipFile.delete()
        }
        
        return null
    }

    /**
     * 设置虚拟音频设备为默认麦克风
     * 多平台兼容版本：根据操作系统类型执行不同的配置逻辑
     * 
     * @param toCable 是否切换到虚拟音频设备（true：切换到Cable，false：恢复原始设备）
     */
    suspend fun setSystemDefaultMicrophone(toCable: Boolean = true) = withContext(Dispatchers.IO) {
        when (PlatformUtils.currentOS) {
            PlatformUtils.OS.WINDOWS -> {
                if (toCable) setWindowsDefaultMicrophone() else restoreWindowsDefaultMicrophone()
            }
            PlatformUtils.OS.LINUX -> {
                if (toCable) setLinuxDefaultMicrophone() else restoreLinuxDefaultMicrophone()
            }
            PlatformUtils.OS.MACOS -> {
                if (toCable) setMacOSDefaultMicrophone() else println("macOS平台暂不支持恢复原始设备")
            }
            PlatformUtils.OS.OTHER -> println("当前操作系统不支持设置默认麦克风")
        }
    }
    
    /**
     * Windows平台：恢复原始默认麦克风
     */
    private fun restoreWindowsDefaultMicrophone() {
        println("Windows平台：恢复原始默认麦克风功能暂未实现")
    }
    
    /**
     * Linux平台：恢复原始默认音频源
     */
    private fun restoreLinuxDefaultMicrophone() {
        println("Linux平台：恢复原始默认音频源")
        // 尝试恢复默认音频源
        try {
            // 尝试获取之前保存的原始sink并恢复
            // 注意：这里需要实现在AudioEngine.jvm.kt中保存的originalDefaultSink的逻辑
            // 目前先简单地尝试设置默认源为默认设备
            val process = ProcessBuilder("pactl", "set-default-source", "auto_null")
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0) {
                println("成功恢复默认音频源")
            } else {
                println("恢复默认音频源失败: $output")
            }
        } catch (e: Exception) {
            println("恢复默认音频源时出错: ${e.message}")
        }
    }
    
    /**
     * Windows平台：设置VB-Cable为默认麦克风
     */
    private fun setWindowsDefaultMicrophone() {
        val script = """
${'$'}csharpSource = @"
using System;
using System.Runtime.InteropServices;

namespace AudioSwitcher {
    using System;
    using System.Runtime.InteropServices;

    // --- COM 接口（简化用于 VTable 使用）---

    [StructLayout(LayoutKind.Sequential)]
    public struct PropertyKey {
        public Guid fmtid;
        public uint pid;
    }

    [StructLayout(LayoutKind.Explicit)]
    public struct PropVariant {
        [FieldOffset(0)] public short vt;
        [FieldOffset(2)] public short wReserved1;
        [FieldOffset(4)] public short wReserved2;
        [FieldOffset(6)] public short wReserved3;
        [FieldOffset(8)] public IntPtr pwszVal;
        [FieldOffset(8)] public int iVal;
    }

    // 仅保留 IPolicyConfig 作为 ComImport，因为我们直接实例化它
    [ComImport, Guid("870af99c-171d-4f9e-af0d-e63df40c2bc9")]
    public class PolicyConfigClient { }

    [ComImport, Guid("f8679f50-850a-41cf-9c72-430f290290c8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IPolicyConfig {
        [PreserveSig] int GetMixFormat(string pszDeviceName, out IntPtr ppFormat);
        [PreserveSig] int GetDeviceFormat(string pszDeviceName, int bDefault, out IntPtr ppFormat);
        [PreserveSig] int ResetDeviceFormat(string pszDeviceName);
        [PreserveSig] int SetDeviceFormat(string pszDeviceName, IntPtr pEndpointFormat, IntPtr mixFormat);
        [PreserveSig] int GetProcessingPeriod(string pszDeviceName, int bDefault, out long pmftDefault, out long pmftMinimum);
        [PreserveSig] int SetProcessingPeriod(string pszDeviceName, long pmftDefault);
        [PreserveSig] int GetShareMode(string pszDeviceName, out IntPtr pDeviceShareMode);
        [PreserveSig] int SetShareMode(string pszDeviceName, IntPtr deviceShareMode);
        [PreserveSig] int GetPropertyValue(string pszDeviceName, IntPtr key, out IntPtr value);
        [PreserveSig] int SetPropertyValue(string pszDeviceName, IntPtr key, IntPtr value);
        [PreserveSig] int SetDefaultEndpoint(string pszDeviceName, int role);
        [PreserveSig] int SetEndpointVisibility(string pszDeviceName, int bVisible);
    }

    public class AudioHelper {
        // VTable 方法的委托
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int EnumAudioEndpointsDelegate(IntPtr enumerator, int dataFlow, int state, out IntPtr collection);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int GetCountDelegate(IntPtr collection, out int count);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int ItemDelegate(IntPtr collection, int index, out IntPtr device);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int GetIdDelegate(IntPtr device, out IntPtr idStr);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int OpenPropertyStoreDelegate(IntPtr device, int storageAccess, out IntPtr store);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int GetValueDelegate(IntPtr store, ref PropertyKey key, out PropVariant variant);

        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        private delegate int ReleaseDelegate(IntPtr unknown);

        private static T GetMethod<T>(IntPtr ptr, int slot) {
            IntPtr vtable = Marshal.ReadIntPtr(ptr);
            IntPtr methodPtr = Marshal.ReadIntPtr(vtable, slot * IntPtr.Size);
            return Marshal.GetDelegateForFunctionPointer<T>(methodPtr);
        }

        private static void Release(IntPtr ptr) {
            if (ptr != IntPtr.Zero) {
                try {
                    // IUnknown::Release 总是插槽 2
                    var release = GetMethod<ReleaseDelegate>(ptr, 2);
                    release(ptr);
                } catch { }
            }
        }

        public static void SetDefaultCableOutput() {
            IntPtr enumerator = IntPtr.Zero;
            IntPtr collection = IntPtr.Zero;
            
            try {
                // 创建 MMDeviceEnumerator
                Type enumeratorType = Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E"));
                object enumeratorObj = Activator.CreateInstance(enumeratorType);
                enumerator = Marshal.GetIUnknownForObject(enumeratorObj);

                // EnumAudioEndpoints（插槽 3）
                var enumEndpoints = GetMethod<EnumAudioEndpointsDelegate>(enumerator, 3);
                int hr = enumEndpoints(enumerator, 1, 1, out collection); // eCapture=1, Active=1
                if (hr != 0) throw new Exception("EnumAudioEndpoints failed: " + hr);

                // GetCount（Collection 的插槽 3）
                var getCount = GetMethod<GetCountDelegate>(collection, 3);
                int count;
                getCount(collection, out count);

                // 准备 Item 委托（插槽 4）
                var getItem = GetMethod<ItemDelegate>(collection, 4);

                Guid PKEY_Device_FriendlyName_FmtId = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");
                uint PKEY_Device_FriendlyName_Pid = 14;

                for (int i = 0; i < count; i++) {
                    IntPtr device = IntPtr.Zero;
                    IntPtr store = IntPtr.Zero;
                    IntPtr idPtr = IntPtr.Zero;
                    
                    try {
                        getItem(collection, i, out device);
                        
                        // OpenPropertyStore（Device 的插槽 4）
                        var openStore = GetMethod<OpenPropertyStoreDelegate>(device, 4);
                        openStore(device, 0, out store); // STGM_READ=0
                        
                        // GetValue（Store 的插槽 5）
                        var getValue = GetMethod<GetValueDelegate>(store, 5);
                        
                        PropertyKey key;
                        key.fmtid = PKEY_Device_FriendlyName_FmtId;
                        key.pid = PKEY_Device_FriendlyName_Pid;
                        
                        PropVariant propVar;
                        getValue(store, ref key, out propVar);
                        
                        if (propVar.vt == 31) { // VT_LPWSTR
                            string name = Marshal.PtrToStringUni(propVar.pwszVal);
                            // 如果我们要读取且不拥有缓冲区，严格来说不需要 PropVariantClear
                            // 但对于 VT_LPWSTR 我们应该小心
                            // 在这个简单的脚本中，我们主要依赖进程清理
                            // 但从技术上讲，如果我们长时间在循环中执行此操作，我们应该清除它
                            
                            if (name != null && name.Contains("CABLE Output")) {
                                // GetId（Device 的插槽 5）
                                var getId = GetMethod<GetIdDelegate>(device, 5);
                                getId(device, out idPtr);
                                string id = Marshal.PtrToStringUni(idPtr);
                                
                                Console.WriteLine("Found device: " + name);
                                
                                try {
                                    IPolicyConfig policyConfig = new PolicyConfigClient() as IPolicyConfig;
                                    policyConfig.SetDefaultEndpoint(id, 0); // eConsole
                                    policyConfig.SetDefaultEndpoint(id, 1); // eMultimedia
                                    policyConfig.SetDefaultEndpoint(id, 2); // eCommunications
                                    Console.WriteLine("Set as default successfully.");
                                } catch (Exception ex) {
                                    Console.WriteLine("Error setting default endpoint: " + ex.Message)
                                }
                                return;
                            }
                        }
                    } finally {
                        if (idPtr != IntPtr.Zero) Marshal.FreeCoTaskMem(idPtr);
                        Release(store);
                        Release(device);
                    }
                }
                Console.WriteLine("CABLE Output not found in active devices.");
            } catch (Exception e) {
                Console.WriteLine("Error in AudioHelper: " + e.Message);
                Console.WriteLine(e.StackTrace);
            } finally {
                Release(collection);
                Release(enumerator);
            }
        }
    }
}
"@

Add-Type -TypeDefinition ${'$'}csharpSource
[AudioSwitcher.AudioHelper]::SetDefaultCableOutput()
""".trimIndent()
        
        try {
            val tempScript = File.createTempFile("setdefaultmic", ".ps1")
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            tempScript.writeBytes(bom + script.toByteArray(Charsets.UTF_8))
            
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Sta",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                tempScript.absolutePath
            )
            process.redirectErrorStream(true)
            val p = process.start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            println("SetDefaultMic Output: $output")
            tempScript.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Linux平台：设置虚拟设备为默认音频源
     */
    private fun setLinuxDefaultMicrophone() {
        println("尝试设置Linux虚拟音频设备为默认源...")
        
        // 尝试使用pactl设置默认源
        try {
            val process = ProcessBuilder(
                "pactl", "set-default-source", "MicYouVirtualSink.monitor"
            ).redirectErrorStream(true).start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                println("成功设置MicYouVirtualSink.monitor为默认音频源")
            } else {
                println("设置默认源失败: $output")
                
                // 尝试其他方法：检查是否存在虚拟设备并尝试不同的名称
                try {
                    val listProcess = ProcessBuilder("pactl", "list", "sources", "short")
                        .redirectErrorStream(true).start()
                    val listOutput = listProcess.inputStream.bufferedReader().readText()
                    listProcess.waitFor()
                    
                    // 查找虚拟设备
                    val lines = listOutput.lines()
                    val virtualSource = lines.find { line ->
                        val lowerLine = line.lowercase()
                        lowerLine.contains("virtual") || 
                        lowerLine.contains("null") || 
                        lowerLine.contains("loopback") ||
                        lowerLine.contains("micyou")
                    }
                    
                    if (virtualSource != null) {
                        val sourceName = virtualSource.split("\\s+".toRegex()).getOrNull(1)
                        if (sourceName != null) {
                            val setProcess = ProcessBuilder("pactl", "set-default-source", sourceName)
                                .redirectErrorStream(true).start()
                            val setOutput = setProcess.inputStream.bufferedReader().readText()
                            setProcess.waitFor()
                            
                            if (setProcess.exitValue() == 0) {
                                println("成功设置 $sourceName 为默认音频源")
                            } else {
                                println("设置 $sourceName 失败: $setOutput")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("查找虚拟设备时出错: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("设置Linux默认音频源时出错: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * macOS平台：设置虚拟设备为默认麦克风
     */
    private fun setMacOSDefaultMicrophone() {
        println("macOS平台暂不支持自动设置默认麦克风，请在系统偏好设置中手动设置")
    }
    
    fun uninstallVBCable() {
         println("Uninstall functionality not fully implemented. Please uninstall from Control Panel.")
    }
}
