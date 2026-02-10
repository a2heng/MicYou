package com.lanrhyme.micyou

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun App(
    viewModel: MainViewModel? = null,
    onMinimize: () -> Unit = {},
    onClose: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val platform = remember { getPlatform() }
    val isClient = platform.type == PlatformType.Android
    
    // Use passed viewModel or create one
    val finalViewModel = viewModel ?: if (isClient) viewModel { MainViewModel() } else remember { MainViewModel() }

    val themeMode by finalViewModel.uiState.collectAsState().let { state ->
        derivedStateOf { state.value.themeMode }
    }
    val seedColor by finalViewModel.uiState.collectAsState().let { state ->
        derivedStateOf { state.value.seedColor }
    }
    
    // Convert Long color to Color object
    val seedColorObj = androidx.compose.ui.graphics.Color(seedColor.toInt())

    AppTheme(themeMode = themeMode, seedColor = seedColorObj) {
        if (platform.type == PlatformType.Android) {
            MobileHome(finalViewModel)
        } else {
            DesktopHome(
                viewModel = finalViewModel,
                onMinimize = onMinimize,
                onClose = onClose,
                onOpenSettings = onOpenSettings
            )
        }
    }
}

