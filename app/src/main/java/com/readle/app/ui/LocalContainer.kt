package com.readle.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.readle.app.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
