package com.lanrhyme.micyou

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object FirewallManager {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    suspend fun isPortAllowed(port: Int, protocol: String = "TCP"): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!isWindows) return@withContext true

        return@withContext try {
            // 1. 首先检查是否有显式的阻止规则，因为阻止规则优先级更高
            val blockCheckCmd = "if (Get-NetFirewallRule -Enabled True -Direction Inbound -Action Block | Get-NetFirewallPortFilter | Where-Object { \$_.LocalPort -eq '$port' -and \$_.Protocol -eq '$protocol' }) { exit 0 } else { exit 1 }"
            val blockProcess = ProcessBuilder("powershell", "-NoProfile", "-Command", blockCheckCmd).start()
            if (blockProcess.waitFor(5, TimeUnit.SECONDS) && blockProcess.exitValue() == 0) {
                Logger.w("FirewallManager", "Found explicit BLOCK rule for port $port/$protocol")
                return@withContext false
            }

            // 2. 检查是否有允许规则
            // 这种方式比解析 netsh 输出更可靠，且支持检查特定端口
            val allowCheckCmd = "if (Get-NetFirewallRule -Enabled True -Direction Inbound -Action Allow | Get-NetFirewallPortFilter | Where-Object { \$_.LocalPort -eq '$port' -and \$_.Protocol -eq '$protocol' }) { exit 0 } else { exit 1 }"
            val allowProcess = ProcessBuilder("powershell", "-NoProfile", "-Command", allowCheckCmd).start()

            val finished = allowProcess.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                allowProcess.destroy()
                Logger.w("FirewallManager", "Firewall allow check timed out, assuming allowed")
                return@withContext true // 超时则保守认为已放行
            }
            
            val isAllowed = allowProcess.exitValue() == 0
            if (!isAllowed) {
                Logger.w("FirewallManager", "No explicit ALLOW rule found for port $port/$protocol")
                
                // 3. 检查当前网络配置（如果是公用网络，通常更严格）
                val profileCmd = "Get-NetConnectionProfile | Select-Object -ExpandProperty NetworkCategory"
                val profileProcess = ProcessBuilder("powershell", "-NoProfile", "-Command", profileCmd).start()
                if (profileProcess.waitFor(2, TimeUnit.SECONDS) && profileProcess.exitValue() == 0) {
                    val profile = profileProcess.inputStream.bufferedReader().readText().trim()
                    Logger.i("FirewallManager", "Current network profile: $profile")
                    if (profile == "Public") {
                        Logger.w("FirewallManager", "User is on a PUBLIC network. Connection is likely blocked by default.")
                    }
                }
            } else {
                Logger.i("FirewallManager", "Found ALLOW rule for port $port/$protocol")
            }
            
            isAllowed
        } catch (e: Exception) {
            Logger.e("FirewallManager", "Failed to check firewall status", e)
            // 如果检查失败（例如系统不支持 PowerShell 4.0+ 的网络命令），返回 true 避免干扰
            true
        }
    }

    suspend fun addFirewallRule(port: Int, protocol: String = "TCP"): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!isWindows) return@withContext Result.success(Unit)

        return@withContext try {
            val ruleName = "MicYou ($protocol-In-$port)"
            // 使用 PowerShell 以管理员身份运行 netsh 命令添加规则
            // 明确指定 profile=any 以确保在公用网络下也能生效
            val command = "Start-Process netsh -ArgumentList 'advfirewall firewall add rule name=\"$ruleName\" dir=in action=allow protocol=$protocol localport=$port profile=any' -Verb RunAs -Wait"
            
            Logger.i("FirewallManager", "Adding firewall rule: $ruleName for port $port/$protocol (all profiles)")
            val process = ProcessBuilder("powershell", "-NoProfile", "-Command", command)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                Logger.e("FirewallManager", "Timeout while adding firewall rule")
                return@withContext Result.failure(IOException("Add firewall rule timeout"))
            }

            if (process.exitValue() == 0) {
                // 再次检查是否真的添加成功了
                if (isPortAllowed(port, protocol)) {
                    Logger.i("FirewallManager", "Firewall rule verified successfully")
                    Result.success(Unit)
                } else {
                    Logger.e("FirewallManager", "Firewall rule verification failed after 'successful' addition")
                    Result.failure(IOException("Failed to verify firewall rule after adding. Please try adding it manually."))
                }
            } else {
                val output = process.inputStream.bufferedReader().readText()
                Logger.e("FirewallManager", "Failed to add firewall rule. Exit code: ${process.exitValue()}, Output: $output")
                Result.failure(IOException("Failed to add firewall rule: $output"))
            }
        } catch (e: Exception) {
            Logger.e("FirewallManager", "Exception while adding firewall rule", e)
            Result.failure(e)
        }
    }
}
