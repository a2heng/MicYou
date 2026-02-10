package com.lanrhyme.micyou

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileHome(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val audioLevel by viewModel.audioLevels.collectAsState(initial = 0f)
    val platform = remember { getPlatform() }
    val isClient = platform.type == PlatformType.Android
    
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
             // Wrap in Box to give it some height if needed, or just let it fill
             Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                 DesktopSettings(viewModel = viewModel, onClose = { showSettings = false })
             }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("MicYou") },
                actions = {
                    IconButton(onClick = { viewModel.toggleMute() }) {
                        Icon(
                            if (state.isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = if (state.isMuted) "取消静音" else "静音",
                            tint = if (state.isMuted) MaterialTheme.colorScheme.error else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
             // Card 1: Status & Info
             Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // IP Info
                    SelectionContainer {
                         Text("本机 IP: ${platform.ipAddress}", style = MaterialTheme.typography.bodySmall)
                    }

                    // Mode Selection
                    Text("连接方式", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.mode == ConnectionMode.Wifi,
                            onClick = { viewModel.setMode(ConnectionMode.Wifi) },
                            label = { Text("Wi-Fi (TCP)") },
                            leadingIcon = { if (state.mode == ConnectionMode.Wifi) Icon(Icons.Filled.Check, null) else null }
                        )
                        FilterChip(
                            selected = state.mode == ConnectionMode.WifiUdp,
                            onClick = { viewModel.setMode(ConnectionMode.WifiUdp) },
                            label = { Text("Wi-Fi (UDP)") },
                            leadingIcon = { if (state.mode == ConnectionMode.WifiUdp) Icon(Icons.Filled.Check, null) else null }
                        )
                        FilterChip(
                            selected = state.mode == ConnectionMode.Usb,
                            onClick = { viewModel.setMode(ConnectionMode.Usb) },
                            label = { Text("USB (ADB)") },
                            leadingIcon = { if (state.mode == ConnectionMode.Usb) Icon(Icons.Filled.Check, null) else null }
                        )
                    }

                    // Inputs
                    if (state.mode == ConnectionMode.Wifi) {
                        if (isClient) {
                             OutlinedTextField(
                                value = state.ipAddress,
                                onValueChange = { viewModel.setIp(it) },
                                label = { Text("目标 IP") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                         OutlinedTextField(
                            value = state.port,
                            onValueChange = { viewModel.setPort(it) },
                            label = { Text("端口") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        // USB Instructions
                         if (isClient) {
                            Text("请连接 USB 并执行 adb reverse tcp:6000 tcp:6000", style = MaterialTheme.typography.bodySmall)
                             OutlinedTextField(
                                value = state.ipAddress,
                                onValueChange = { viewModel.setIp(it) },
                                label = { Text("目标 IP (127.0.0.1)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        } else {
                            Text("等待 ADB 连接...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    
                    // Status
                    val statusText = when(state.streamState) {
                        StreamState.Idle -> "空闲"
                        StreamState.Connecting -> "连接中..."
                        StreamState.Streaming -> "正在串流"
                        StreamState.Error -> "错误"
                    }
                    Text("状态: $statusText", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)

                    if (state.errorMessage != null) {
                        Text(state.errorMessage ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
             }
             
             // Card 2: Control
             Card(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                 Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val isRunning = state.streamState == StreamState.Streaming
                    val isConnecting = state.streamState == StreamState.Connecting
                    
                    // Visuals
                    if (isRunning) {
                        CircularProgressIndicator(
                            progress = { audioLevel },
                            modifier = Modifier.fillMaxWidth(0.6f).aspectRatio(1f),
                            strokeWidth = 8.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
                            trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.1f),
                        )
                    }
                    
                    // Button
                    val buttonSize by animateDpAsState(if (isRunning) 96.dp else 80.dp)
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
                    val rotation = if (isConnecting) angle else 0f
                    
                    var rippleTrigger by remember { mutableStateOf(0) }

                    Box(contentAlignment = Alignment.Center) {
                        WaterRippleEffect(
                            trigger = rippleTrigger,
                            modifier = Modifier.size(buttonSize),
                            color = Color.White
                        )
                        
                        IconButton(
                            onClick = { 
                                rippleTrigger++
                                viewModel.toggleStream() 
                            },
                            modifier = Modifier.size(buttonSize).background(buttonColor, CircleShape)
                        ) {
                            val icon = when {
                                isRunning -> Icons.Filled.MicOff
                                isConnecting -> Icons.Filled.Refresh
                                else -> Icons.Filled.Mic
                            }

                            Icon(
                                icon,
                                contentDescription = "Toggle Stream",
                                modifier = Modifier.size(40.dp).rotate(rotation),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                 }
            }
        }
    }
}
