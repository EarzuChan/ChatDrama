package me.earzuchan.chatdrama.client.utils

import kotlin.time.Clock

actual fun currentTimeMillis() = Clock.System.now().toEpochMilliseconds()