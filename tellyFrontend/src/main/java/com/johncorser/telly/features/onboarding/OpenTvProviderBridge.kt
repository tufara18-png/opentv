package com.johncorser.telly.features.onboarding

/**
 * Backend seam for provider types that Telly's upstream wizard renders but does
 * not persist itself. The Android app installs the OpenTV implementation.
 */
enum class OpenTvProviderKind { XTREAM, STALKER }

data class OpenTvProviderDraft(
    val kind: OpenTvProviderKind,
    val name: String,
    val url: String,
    val username: String = "",
    val password: String = "",
    val macAddress: String = "",
)

object OpenTvProviderBridge {
    @Volatile
    private var addHandler: (suspend (OpenTvProviderDraft) -> Result<Unit>)? = null

    fun install(handler: suspend (OpenTvProviderDraft) -> Result<Unit>) {
        addHandler = handler
    }

    suspend fun add(draft: OpenTvProviderDraft): Result<Unit> =
        addHandler?.invoke(draft)
            ?: Result.failure(IllegalStateException("Provider backend is not installed"))
}
