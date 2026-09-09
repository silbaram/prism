package io.github.silbaram.prism.sdk

/** A single masking policy shared by SDK and starter logging. */
fun maskUserId(userId: String): String =
    if (userId.isBlank() || userId.length <= 4) "***" else "${userId.take(2)}***${userId.takeLast(2)}"
