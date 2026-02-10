package com.lanrhyme.androidmic_md

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopSettings(
    viewModel: MainViewModel,
    onClose: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    
    // 预设种子颜色
    val seedColors = listOf(
        0xFF6750A4L, // Purple (Default)
        0xFFB3261EL, // Red
        0xFFFBC02DL, // Yellow
        0xFF388E3CL, // Green
        0xFF006C51L, // Teal
        0xFF2196F3L, // Blue
        0xFFE91E63L  // Pink
    )

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("设置", style = MaterialTheme.typography.headlineSmall)
            }
            
            item { HorizontalDivider() }
            
            // 主题模式
            item {
                Text("主题模式", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.name) }
                        )
                    }
                }
            }
            
            item { HorizontalDivider() }
            
            // 主题颜色
            item {
                Text("主题颜色", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    seedColors.forEach { colorHex ->
                        val color = Color(colorHex.toInt())
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(color, CircleShape)
                                .clickable { viewModel.setSeedColor(colorHex) }
                                .then(
                                    if (state.seedColor == colorHex) {
                                        Modifier.padding(2.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape).padding(2.dp).background(color, CircleShape)
                                    } else Modifier
                                )
                        )
                    }
                }
            }
            
            item { HorizontalDivider() }
            
            // 音频参数 (仅移动端，桌面端自动适配)
            if (getPlatform().type == PlatformType.Android) {
                // 音频参数
                item {
                    Text("音频参数", style = MaterialTheme.typography.titleMedium)
                }
                
                // 采样率
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("采样率", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        // Use a dropdown or just chips for simplicity
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text("${state.sampleRate.value} Hz")
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                SampleRate.entries.forEach { rate ->
                                    DropdownMenuItem(
                                        text = { Text("${rate.value} Hz") },
                                        onClick = { 
                                            viewModel.setSampleRate(rate)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 通道数
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("通道数", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(state.channelCount.label)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                ChannelCount.entries.forEach { count ->
                                    DropdownMenuItem(
                                        text = { Text(count.label) },
                                        onClick = { 
                                            viewModel.setChannelCount(count)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                
                // 音频格式
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("音频格式", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { expanded = true }) {
                                Text(state.audioFormat.label)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                AudioFormat.entries.forEach { format ->
                                    DropdownMenuItem(
                                        text = { Text(format.label) },
                                        onClick = { 
                                            viewModel.setAudioFormat(format)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item { HorizontalDivider() }
            }
            
            // 音频处理 (仅桌面端)
            if (getPlatform().type == PlatformType.Desktop) {
                item {
                    Text("音频处理", style = MaterialTheme.typography.titleMedium)
                }

                // 降噪
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setEnableNS(!state.enableNS) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("降噪 (Noise Suppression)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = state.enableNS,
                                onCheckedChange = { viewModel.setEnableNS(it) }
                            )
                        }
                        if (state.enableNS) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp)) {
                                NoiseReductionType.entries.filter { it != NoiseReductionType.None }.forEach { type ->
                                    FilterChip(
                                        selected = state.nsType == type,
                                        onClick = { viewModel.setNsType(type) },
                                        label = { Text(type.label) }
                                    )
                                }
                            }
                        }
                    }
                }

                // 增益控制
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setEnableAGC(!state.enableAGC) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("自动增益控制 (AGC)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = state.enableAGC,
                                onCheckedChange = { viewModel.setEnableAGC(it) }
                            )
                        }
                        if (state.enableAGC) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                                Text("目标电平: ${state.agcTargetLevel}", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = state.agcTargetLevel.toFloat(),
                                    onValueChange = { viewModel.setAgcTargetLevel(it.toInt()) },
                                    valueRange = 8000f..65535f
                                )
                            }
                        }
                    }
                }

                // 语音活动检测
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setEnableVAD(!state.enableVAD) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("语音活动检测 (VAD)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = state.enableVAD,
                                onCheckedChange = { viewModel.setEnableVAD(it) }
                            )
                        }
                        if (state.enableVAD) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                                Text("阈值: ${state.vadThreshold}", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = state.vadThreshold.toFloat(),
                                    onValueChange = { viewModel.setVadThreshold(it.toInt()) },
                                    valueRange = 0f..100f
                                )
                            }
                        }
                    }
                }

                // 去混响
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setEnableDereverb(!state.enableDereverb) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("去混响 (Dereverb)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = state.enableDereverb,
                                onCheckedChange = { viewModel.setEnableDereverb(it) }
                            )
                        }
                        if (state.enableDereverb) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                                Text("强度: ${((state.dereverbLevel * 100).toInt()) / 100f}", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = state.dereverbLevel,
                                    onValueChange = { viewModel.setDereverbLevel(it) },
                                    valueRange = 0.0f..1.0f
                                )
                            }
                        }
                    }
                }

                // 放大
                item {
                    Column {
                        Text("信号放大 (Amplification)", style = MaterialTheme.typography.bodyMedium)
                        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                            Text("倍数: ${((state.amplification * 10).toInt()) / 10f}x", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = state.amplification,
                                onValueChange = { viewModel.setAmplification(it) },
                                valueRange = 0.0f..30.0f
                            )
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            
            // 监听设置 (仅桌面端)
            if (getPlatform().type == PlatformType.Desktop) {
                item {
                    Text("监听设置", style = MaterialTheme.typography.titleMedium)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.setMonitoringEnabled(!state.monitoringEnabled) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("监听设备 (播放收到的声音)", modifier = Modifier.weight(1f))
                        Switch(
                            checked = state.monitoringEnabled,
                            onCheckedChange = { viewModel.setMonitoringEnabled(it) }
                        )
                    }
                }
                item { HorizontalDivider() }
            }
            
            // System Actions (Only on Desktop)
            if (getPlatform().type == PlatformType.Desktop) {
                item {
                    Text("系统", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { uninstallVBCable() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("卸载音频驱动 (VB-Cable)")
                    }
                }
                item { HorizontalDivider() }
            }
            
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}
