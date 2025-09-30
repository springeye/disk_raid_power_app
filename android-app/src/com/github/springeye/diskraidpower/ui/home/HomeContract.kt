package com.github.springeye.diskraidpower.ui.home


/**
 * UI State that represents HomeScreen
 **/
class HomeState

/**
 * Home Actions emitted from the UI Layer
 * passed to the coordinator to handle
 **/

sealed interface HomeAction {
    data object OnClick : HomeAction
}

