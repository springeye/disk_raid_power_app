package com.github.springeye.diskraidpower.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember


@Composable
fun HomeRoute(
    coordinator: HomeCoordinator = rememberHomeCoordinator()
) {
    // State observing and declarations
    val uiState by coordinator.screenStateFlow.collectAsState()

    // UI Actions
    val actionsHandler: (HomeAction) -> Unit = { action ->
        coordinator.handle(action)
    }

    // UI Rendering
    HomeScreen(
        state = uiState,
        onAction = actionsHandler
    )
}


