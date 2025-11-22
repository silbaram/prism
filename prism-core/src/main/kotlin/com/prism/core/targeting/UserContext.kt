package com.prism.core.targeting

data class UserContext(
    val attributes: Map<String, Any>
) {
    companion object {
        fun from(map: Map<String, Any>) = UserContext(map)
    }
}
