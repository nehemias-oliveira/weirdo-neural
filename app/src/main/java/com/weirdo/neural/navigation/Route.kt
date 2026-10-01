package com.weirdo.neural.navigation

sealed class Route(val path: String) {
    data object Chat : Route("chat")
    data object Terminal : Route("terminal")
    data object Settings : Route("settings")
}
