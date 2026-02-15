package com.lanrhyme.micyou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import micyou.composeapp.generated.resources.Res
import micyou.composeapp.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource
import java.io.File

/**
 * 托盘菜单配置
 * 提供便捷的方法来创建和管理托盘菜单
 */
object TrayMenu {

    /**
     * 菜单项类型
     */
    enum class ItemType {
        ITEM,
        SEPARATOR
    }

    /**
     * 菜单项配置
     */
    data class ItemConfig(
        val id: String = "",
        val type: ItemType = ItemType.ITEM,
        val text: String = "",
        val enabled: Boolean = true,
        val callback: () -> Unit = {}
    )

    /**
     * 默认菜单项
     */
    object DefaultItems {
        const val SHOW_HIDE = "show_hide"
        const val CONNECT_DISCONNECT = "connect_disconnect"
        const val SETTINGS = "settings"
        const val EXIT = "exit"
    }

    /**
     * 创建默认菜单配置
     */
    fun createDefaultConfig(
        isVisible: Boolean,
        isStreaming: Boolean,
        onShowHideClick: () -> Unit,
        onConnectDisconnectClick: () -> Unit,
        onSettingsClick: () -> Unit,
        onExitClick: () -> Unit
    ): List<ItemConfig> {
        return listOf(
            ItemConfig(
                id = DefaultItems.SHOW_HIDE,
                text = if (isVisible) "Hide Window" else "Show Window",
                enabled = true,
                callback = onShowHideClick
            ),
            ItemConfig(
                id = DefaultItems.CONNECT_DISCONNECT,
                text = if (isStreaming || isStreaming) "Disconnect" else "Connect",
                enabled = true,
                callback = onConnectDisconnectClick
            ),
            ItemConfig(
                id = DefaultItems.SETTINGS,
                text = "Settings",
                enabled = true,
                callback = onSettingsClick
            ),
            ItemConfig(
                type = ItemType.SEPARATOR
            ),
            ItemConfig(
                id = DefaultItems.EXIT,
                text = "Exit",
                enabled = true,
                callback = onExitClick
            )
        )
    }

    /**
     * 获取默认图标路径
     * 按优先级尝试多个可能的图标路径
     */
    fun getDefaultIconPath(): String {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        // jpackage 打包后的应用会设置此属性，指向启动器路径
        val jpackageAppPath = System.getProperty("jpackage.app-path")
        val jpackageAppDir = jpackageAppPath?.let { File(it).parentFile?.absolutePath }
        
        val candidatePaths = if (isWindows) {
            // Windows 托盘需要小尺寸图标 (32x32)，256x256 会显示为占位符
            listOfNotNull(
                jpackageAppDir?.let { "$it\\icon32.ico" },
                System.getProperty("user.dir") + "\\icon32.ico",
                System.getProperty("user.dir") + "/composeApp/src/commonMain/composeResources/drawable/icon32.ico",
                "composeApp/src/commonMain/composeResources/drawable/icon32.ico",
                "src/commonMain/composeResources/drawable/icon32.ico"
            )
        } else {
            listOfNotNull(
                "/opt/micyou/lib/MicYou.png",
                jpackageAppDir?.let { File(it).parentFile?.let { p -> "${p.absolutePath}/lib/MicYou.png" } },
                System.getProperty("user.dir") + "/composeApp/src/commonMain/composeResources/drawable/app_icon.png",
                "composeApp/src/commonMain/composeResources/drawable/app_icon.png",
                "src/commonMain/composeResources/drawable/app_icon.png"
            )
        }

        for (path in candidatePaths) {
            val file = File(path)
            if (file.exists()) {
                return file.absolutePath
            }
        }

        return candidatePaths.first()
    }

    /**
     * 获取图标资源（用于 Compose）
     */
    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun getIconPainter(): androidx.compose.ui.graphics.painter.Painter {
        return painterResource(Res.drawable.app_icon)
    }
}
