package com.github.springeye.diskraidpower.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.koin.androidx.compose.koinViewModel

/**
 * Screen's coordinator which is responsible for handling actions from the UI layer
 * and one-shot actions based on the new UI state
 */
class HomeCoordinator(
    val viewModel: HomeViewModel
) {
    val screenStateFlow = viewModel.stateFlow
    fun handle(action: HomeAction) {
        when (action) {
            HomeAction.OnClick -> { /* Handle action */
            }
        }
    }


}

@Composable
fun rememberHomeCoordinator(
    viewModel: HomeViewModel = koinViewModel()
): HomeCoordinator {
    return remember(viewModel) {
        HomeCoordinator(
            viewModel = viewModel
        )
    }
}