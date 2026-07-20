package io.shiron.honamisensorproxy

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform