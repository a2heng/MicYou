package com.lanrhyme.androidmic_md

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    MaterialTheme {
        val platform = remember { getPlatform() }
        val isClient = platform.type == PlatformType.Android
        val viewModel = if (isClient) viewModel { MainViewModel() } else remember { MainViewModel() }
        val state by viewModel.uiState.collectAsState()
        val audioLevel by viewModel.audioLevels.collectAsState(initial = 0f)

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("AndroidMic") },
                    actions = {
                        IconButton(onClick = { /* TODO: 设置 */ }) {
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
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 连接模式
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("连接模式", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.mode == ConnectionMode.Wifi,
                                onClick = { viewModel.setMode(ConnectionMode.Wifi) },
                                label = { Text("Wi-Fi") }
                            )
                            FilterChip(
                                selected = state.mode == ConnectionMode.Usb,
                                onClick = { viewModel.setMode(ConnectionMode.Usb) },
                                label = { Text("USB") }
                            )
                        }

                        if (state.mode == ConnectionMode.Wifi) {
                            if (isClient) {
                                OutlinedTextField(
                                    value = state.ipAddress,
                                    onValueChange = { viewModel.setIp(it) },
                                    label = { Text("目标 IP 地址") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text("监听端口: ${state.port}")
                                SelectionContainer {
                                    Text("本机 IP: ${platform.ipAddress}")
                                }
                            }
                            OutlinedTextField(
                                value = state.port,
                                onValueChange = { viewModel.setPort(it) },
                                label = { Text("端口") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (state.mode == ConnectionMode.Usb) {
                             if (isClient) {
                                Text("USB 模式：请确保已连接 USB 并配置 ADB 端口转发", style = MaterialTheme.typography.bodyMedium)
                                Text("adb reverse tcp:6000 tcp:6000", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = state.ipAddress,
                                    onValueChange = { viewModel.setIp(it) },
                                    label = { Text("目标 IP 地址 (127.0.0.1)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text("USB 模式：等待 ADB 连接转发至端口 ${state.port}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 错误信息
                if (state.errorMessage != null && state.streamState == StreamState.Error) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "错误: ${state.errorMessage}",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 可视化
                if (state.streamState == StreamState.Streaming) {
                    Text("音频电平")
                    LinearProgressIndicator(
                        progress = { audioLevel },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 大启动按钮
                val isRunning = state.streamState == StreamState.Streaming || state.streamState == StreamState.Connecting
                FilledTonalButton(
                    onClick = { viewModel.toggleStream() },
                    modifier = Modifier.size(120.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (isRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (isRunning) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(if (isRunning) "停止" else "开始")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 状态
                Text(
                    text = "状态: ${state.streamState}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
