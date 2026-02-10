package com.lanrhyme.micyou

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopHome(
    viewModel: MainViewModel,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val audioLevel by viewModel.audioLevels.collectAsState(initial = 0f)
    val platform = remember { getPlatform() }
    
    // Startup Animation
    var visible by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = tween(500)
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(500)
    )
    
    LaunchedEffect(Unit) {
        visible = true
    }

    if (state.installMessage != null) {
        AlertDialog(
            onDismissRequest = { /* Prevent dismissal */ },
            title = { Text("系统配置中") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(state.installMessage ?: "")
                }
            },
            confirmButton = {}
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 左侧：状态与信息
            Card(
                modifier = Modifier.weight(1.2f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("MicYou Desktop", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    SelectionContainer {
                         Text("本机 IP: ${platform.ipAddress}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                                modifier = Modifier.width(200.dp)
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    readOnly = true,
                                    value = when (state.mode) {
                                        ConnectionMode.Wifi -> "Wi-Fi (TCP)"
                                        ConnectionMode.WifiUdp -> "Wi-Fi (UDP)"
                                        ConnectionMode.Usb -> "USB (ADB)"
                                    },
                                    onValueChange = {},
                                    label = { Text("连接方式") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                            expanded = expanded
                                        )
                                    },
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Wi-Fi (TCP)") },
                                        onClick = {
                                            viewModel.setMode(ConnectionMode.Wifi)
                                            expanded = false
                                        },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Wi-Fi (UDP)") },
                                        onClick = {
                                            viewModel.setMode(ConnectionMode.WifiUdp)
                                            expanded = false
                                        },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                    )
                                    DropdownMenuItem(
                                        text = { Text("USB (ADB)") },
                                        onClick = {
                                            viewModel.setMode(ConnectionMode.Usb)
                                            expanded = false
                                        },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                    )
                                }
                            }
                        }


                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = state.port,
                            onValueChange = { viewModel.setPort(it) },
                            label = { Text("端口") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall,
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val statusText = when(state.streamState) {
                        StreamState.Idle -> "空闲"
                        StreamState.Connecting -> "连接中..."
                        StreamState.Streaming -> "正在串流"
                        StreamState.Error -> "错误"
                    }

                    Text("状态: $statusText", style = MaterialTheme.typography.bodyMedium, color = Color.Unspecified)

                    if (state.errorMessage != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(state.errorMessage ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // 中间：控制与可视化
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(22.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val isRunning = state.streamState == StreamState.Streaming
                    val isConnecting = state.streamState == StreamState.Connecting
                    
                    // 背景可视化(简单模拟)
                    if (isRunning) {
                        CircularProgressIndicator(
                            progress = { audioLevel },
                            modifier = Modifier.fillMaxSize(0.8f),
                            strokeWidth = 8.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                            trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.1f),
                        )
                    }

                    // 开关按钮
                    val buttonSize by animateDpAsState(if (isRunning) 72.dp else 64.dp)
                    val buttonColor by animateColorAsState(
                        when {
                            isRunning -> MaterialTheme.colorScheme.error
                            isConnecting -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    
                    // Rotation Animation for Connecting
                    val infiniteTransition = rememberInfiniteTransition()
                    val angle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing)
                        ),
                        label = "ConnectionSpinner"
                    )
                    
                    FloatingActionButton(
                        onClick = {
                            if (isRunning || isConnecting) {
                                viewModel.stopStream()
                            } else {
                                viewModel.startStream()
                            }
                        },
                        containerColor = buttonColor,
                        modifier = Modifier.size(buttonSize)
                    ) {
                        if (isConnecting) {
                            Icon(Icons.Filled.Refresh, "连接中", modifier = Modifier.rotate(angle))
                        } else {
                            Icon(
                                if (isRunning) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (isRunning) "停止" else "开始"
                            )
                        }
                    }
                }
            }

            // 右侧：功能按钮
            Column(
                modifier = Modifier.weight(0.3f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FilledTonalIconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Filled.Settings, "设置")
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Filled.Minimize, "最小化")
                }
                
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.Close, "关闭")
                }
            }
        }
    }
}
