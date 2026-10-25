package com.enigmacast.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.account.AccountRepository
import com.enigmacast.data.account.AccountResult
import com.enigmacast.domain.account.AccountError
import com.enigmacast.domain.account.AccountInput
import com.enigmacast.domain.account.AccountSnapshot
import com.enigmacast.domain.account.Entitlements
import com.enigmacast.domain.account.PremiumFeature
import com.enigmacast.live.PremiumGate
import com.enigmacast.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The account form (wizard and Settings › Account) and the account status. */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val repository: AccountRepository,
    val gate: PremiumGate,
) : ViewModel() {
    val account: StateFlow<AccountSnapshot?> = repository.account

    var creating by mutableStateOf(true)
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var confirm by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<UiText?>(null)
        private set
    /** A short confirmation after Refresh. */
    var info by mutableStateOf<UiText?>(null)
        private set

    fun isPremium(snapshot: AccountSnapshot?): Boolean = gate.isPremium()
    fun allows(feature: PremiumFeature): Boolean = gate.allows(feature)
    fun offline(snapshot: AccountSnapshot?): Boolean = Entitlements.inOfflineGrace(snapshot, System.currentTimeMillis())

    fun switchMode(create: Boolean) {
        creating = create
        message = null
    }

    fun submit(onSignedIn: () -> Unit = {}) {
        if (busy) return
        val problem = AccountInput.check(email, password, confirm, creating)
        if (problem != null) {
            message = UiText.res(
                when (problem) {
                    AccountInput.Problem.EMAIL -> R.string.account_error_email
                    AccountInput.Problem.PASSWORD_SHORT -> R.string.account_error_password_short
                    AccountInput.Problem.PASSWORD_LONG -> R.string.account_error_password_long
                    AccountInput.Problem.MISMATCH -> R.string.account_error_mismatch
                },
            )
            return
        }
        busy = true
        message = null
        viewModelScope.launch {
            val result = if (creating) repository.register(email, password) else repository.login(email, password)
            busy = false
            when (result) {
                is AccountResult.Success -> {
                    password = ""
                    confirm = ""
                    onSignedIn()
                }
                is AccountResult.Failure -> message = errorText(result)
            }
        }
    }

    fun refresh() {
        if (busy) return
        busy = true
        info = null
        message = null
        viewModelScope.launch {
            when (val result = repository.refresh()) {
                is AccountResult.Success -> info = UiText.res(R.string.account_refreshed)
                is AccountResult.Failure -> message = errorText(result)
                null -> Unit
            }
            busy = false
        }
    }

    fun logout() {
        if (busy) return
        busy = true
        viewModelScope.launch {
            repository.logout()
            busy = false
            info = null
            message = null
            creating = false
        }
    }

    private fun errorText(failure: AccountResult.Failure): UiText = when (failure.error) {
        AccountError.DEVICE_LIMIT -> UiText.res(R.string.account_error_device_limit, failure.maxDevices ?: 10)
        AccountError.EMAIL_TAKEN -> UiText.res(R.string.account_error_email_taken)
        AccountError.INVALID_CREDENTIALS -> UiText.res(R.string.account_error_credentials)
        AccountError.ACCOUNT_DISABLED -> UiText.res(R.string.account_error_disabled)
        AccountError.UNAUTHORIZED, AccountError.DEVICE_REVOKED -> UiText.res(R.string.account_error_session)
        AccountError.RATE_LIMITED -> UiText.res(R.string.account_error_rate)
        AccountError.NETWORK -> UiText.res(R.string.account_error_network)
        AccountError.INVALID_INPUT -> UiText.res(R.string.account_error_input)
        AccountError.SERVER -> UiText.res(R.string.account_error_server)
    }
}

