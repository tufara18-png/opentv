package com.johncorser.telly.core

/**
 * Optional OpenTV tune-time resolver. Normal Telly URLs never use it; OpenTV registers
 * a resolver for short-lived provider markers such as stalker://.
 */
object OpenTvStreamResolver {
    @Volatile
    var resolve: suspend (String) -> String = { it }
}
