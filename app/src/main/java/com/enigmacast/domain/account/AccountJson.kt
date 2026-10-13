package com.enigmacast.domain.account

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** /api/enigmacast/{register,login,me} answers: {success, code?, message, data}. */
@Serializable
data class AccountEnvelope(
    val success: Boolean = false,
    val code: String? = null,
    val message: String? = null,
    val data: AccountData? = null,
)

@Serializable
data class AccountData(
    val token: String? = null,
    val email: String = "",
    val plan: String = "free",
    @SerialName("is_trial") val isTrial: Boolean = false,
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("premium_until") val premiumUntil: String? = null,
    @SerialName("max_devices") val maxDevices: Int = 10,
    @SerialName("devices_used") val devicesUsed: Int = 1,
)

fun AccountData.toSnapshot(nowMs: Long): AccountSnapshot = AccountSnapshot(
    email = email,
    plan = if (plan.equals("premium", ignoreCase = true)) Plan.PREMIUM else Plan.FREE,
    serverPremium = isPremium,
    premiumUntilMs = premiumUntil?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
    isTrial = isTrial,
    verifiedAtMs = nowMs,
    maxDevices = maxDevices,
    devicesUsed = devicesUsed,
)

/** Server error codes the app explains in its own words. */
enum class AccountError {
    INVALID_INPUT, EMAIL_TAKEN, INVALID_CREDENTIALS, ACCOUNT_DISABLED, DEVICE_LIMIT,
    UNAUTHORIZED, DEVICE_REVOKED, RATE_LIMITED, NETWORK, SERVER;

    /** Errors after which the stored session is no good any more. */
    val endsSession: Boolean get() = this == UNAUTHORIZED || this == DEVICE_REVOKED || this == ACCOUNT_DISABLED

    companion object {
        fun fromCode(code: String?, httpStatus: Int): AccountError = when (code) {
            "invalid_input" -> INVALID_INPUT
            "email_taken" -> EMAIL_TAKEN
            "invalid_credentials" -> INVALID_CREDENTIALS
            "account_disabled" -> ACCOUNT_DISABLED
            "device_limit" -> DEVICE_LIMIT
            "unauthorized" -> UNAUTHORIZED
            "device_revoked" -> DEVICE_REVOKED
            else -> when (httpStatus) {
                429 -> RATE_LIMITED
                401 -> UNAUTHORIZED
                in 400..499 -> INVALID_INPUT
                else -> SERVER
            }
        }
    }
}
