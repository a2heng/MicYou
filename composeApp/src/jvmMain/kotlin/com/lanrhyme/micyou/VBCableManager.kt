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

    fun isVBCableInstalled(): Boolean {
        // Method 1: Check AudioSystem for "CABLE Output" (Microphone) or "CABLE Input" (Speaker)
        val mixers = AudioSystem.getMixerInfo()
        return mixers.any { it.name.contains(CABLE_OUTPUT_NAME, ignoreCase = true) || it.name.contains(CABLE_INPUT_NAME, ignoreCase = true) }
    }

    suspend fun installVBCable() = withContext(Dispatchers.IO) {
        if (isVBCableInstalled()) {
            println("VB-Cable already installed.")
            setSystemDefaultMicrophone()
            return@withContext
        }
        
        _installProgress.value = "正在检查安装包..."

        // 1. Extract installer from resources or download it
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
            return@withContext
        }

        println("Installing VB-Cable...")
        _installProgress.value = "正在安装 VB-Cable 驱动..."
        
        try {
            // 2. Run installer silently using PowerShell Start-Process with RunAs (Admin)
            // Use -Wait to wait for completion
            // Note: We cannot capture stdout/stderr easily from RunAs, but we can check if it finishes
            
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
            
            // Note: Start-Process -Wait returns when the process exits.
            // If the user cancels UAC, Start-Process might throw an error or return non-zero in PowerShell.
            // But ProcessBuilder will likely see 0 unless powershell itself crashes.
            // We should re-check isVBCableInstalled() to verify success.
            
            println("PowerShell execution finished. Exit code: $exitCode. Output: $output")
            
            // Give it a moment to register devices
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

    private fun extractInstaller(): File? {
        try {
            val resourceStream = this::class.java.classLoader.getResourceAsStream(INSTALLER_NAME)
                ?: this::class.java.classLoader.getResourceAsStream("vbcable/$INSTALLER_NAME")
            
            if (resourceStream == null) {
                // Try looking in current directory (dev mode)
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
            // Download using simple Java URL connection
            val url = java.net.URI(downloadUrl).toURL()
            val connection = url.openConnection()
            connection.connect()
            
            connection.getInputStream().use { input ->
                FileOutputStream(zipFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            println("Download complete. Extracting...")
            
            // Extract zip
            if (!outputDir.exists()) outputDir.mkdirs()
            
            java.util.zip.ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryFile = File(outputDir, entry.name)
                    
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        // Create parent dirs if needed
                        entryFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(entryFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
            
            // Find the setup file
            val setupFile = File(outputDir, INSTALLER_NAME)
            if (setupFile.exists()) {
                println("Found installer at ${setupFile.absolutePath}")
                return setupFile
            }
            
            // Try recursive search if not in root
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

    fun setSystemDefaultMicrophone() {
        val script = """
${'$'}csharpSource = @"
using System;
using System.Runtime.InteropServices;

namespace AudioSwitcher {
    using System;
    using System.Runtime.InteropServices;

    // --- COM Interfaces (Simplified for VTable usage) ---

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

    // Only keep IPolicyConfig as ComImport because we instantiate it directly
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
        // Delegates for VTable methods
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
                    // IUnknown::Release is always slot 2
                    var release = GetMethod<ReleaseDelegate>(ptr, 2);
                    release(ptr);
                } catch { }
            }
        }

        public static void SetDefaultCableOutput() {
            IntPtr enumerator = IntPtr.Zero;
            IntPtr collection = IntPtr.Zero;
            
            try {
                // Create MMDeviceEnumerator
                Type enumeratorType = Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E"));
                object enumeratorObj = Activator.CreateInstance(enumeratorType);
                enumerator = Marshal.GetIUnknownForObject(enumeratorObj);

                // EnumAudioEndpoints (Slot 3)
                var enumEndpoints = GetMethod<EnumAudioEndpointsDelegate>(enumerator, 3);
                int hr = enumEndpoints(enumerator, 1, 1, out collection); // eCapture=1, Active=1
                if (hr != 0) throw new Exception("EnumAudioEndpoints failed: " + hr);

                // GetCount (Slot 3 of Collection)
                var getCount = GetMethod<GetCountDelegate>(collection, 3);
                int count;
                getCount(collection, out count);

                // Prepare Item delegate (Slot 4)
                var getItem = GetMethod<ItemDelegate>(collection, 4);

                Guid PKEY_Device_FriendlyName_FmtId = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");
                uint PKEY_Device_FriendlyName_Pid = 14;

                for (int i = 0; i < count; i++) {
                    IntPtr device = IntPtr.Zero;
                    IntPtr store = IntPtr.Zero;
                    IntPtr idPtr = IntPtr.Zero;
                    
                    try {
                        getItem(collection, i, out device);
                        
                        // OpenPropertyStore (Slot 4 of Device)
                        var openStore = GetMethod<OpenPropertyStoreDelegate>(device, 4);
                        openStore(device, 0, out store); // STGM_READ=0
                        
                        // GetValue (Slot 5 of Store)
                        var getValue = GetMethod<GetValueDelegate>(store, 5);
                        
                        PropertyKey key;
                        key.fmtid = PKEY_Device_FriendlyName_FmtId;
                        key.pid = PKEY_Device_FriendlyName_Pid;
                        
                        PropVariant var;
                        getValue(store, ref key, out var);
                        
                        if (var.vt == 31) { // VT_LPWSTR
                            string name = Marshal.PtrToStringUni(var.pwszVal);
                            // PropVariantClear not strictly needed for read-only if we don't own the buffer, 
                            // but for VT_LPWSTR we should be careful. 
                            // In this simple script we rely on process cleanup mostly, 
                            // but technically we should clear it if we were doing this in a loop for long time.
                            
                            if (name != null && name.Contains("CABLE Output")) {
                                // GetId (Slot 5 of Device)
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
                                    Console.WriteLine("Error setting default endpoint: " + ex.Message);
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
            tempScript.writeText(script)
            
            val process = ProcessBuilder("powershell.exe", "-Sta", "-ExecutionPolicy", "Bypass", "-File", tempScript.absolutePath)
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
    
    fun uninstallVBCable() {
         println("Uninstall functionality not fully implemented. Please uninstall from Control Panel.")
    }
}

