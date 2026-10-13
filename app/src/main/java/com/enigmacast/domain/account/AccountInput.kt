package com.enigmacast.domain.account

/** Client-side checks before the account form is sent; the server checks the same again. */
object AccountInput {
    const val MIN_PASSWORD = 8
    const val MAX_PASSWORD = 128
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

    enum class Problem { EMAIL, PASSWORD_SHORT, PASSWORD_LONG, MISMATCH }

    fun normalizeEmail(email: String): String = email.trim().lowercase()

    fun check(email: String, password: String, confirm: String?, creating: Boolean): Problem? = when {
        !EMAIL.matches(normalizeEmail(email)) || normalizeEmail(email).length > 191 -> Problem.EMAIL
        creating && password.length < MIN_PASSWORD -> Problem.PASSWORD_SHORT
        !creating && password.isEmpty() -> Problem.PASSWORD_SHORT
        password.length > MAX_PASSWORD -> Problem.PASSWORD_LONG
        creating && password != confirm -> Problem.MISMATCH
        else -> null
    }
}
