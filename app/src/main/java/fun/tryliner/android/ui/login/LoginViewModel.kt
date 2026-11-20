package `fun`.tryliner.android.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `fun`.tryliner.android.core.model.ApiError
import `fun`.tryliner.android.core.network.LinerApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val sessionJson: String = "",
    val showTokenImport: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class LoginViewModel(
    private val apiClient: LinerApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(email: String) {
        _state.update { it.copy(email = email, errorMessage = null) }
    }

    fun onPasswordChange(password: String) {
        _state.update { it.copy(password = password, errorMessage = null) }
    }

    fun onSessionJsonChange(value: String) {
        _state.update { it.copy(sessionJson = value, errorMessage = null) }
    }

    fun toggleTokenImport() {
        _state.update { it.copy(showTokenImport = !it.showTokenImport, errorMessage = null) }
    }

    fun importSession() {
        val current = _state.value
        val raw = current.sessionJson.trim()
        if (raw.isEmpty()) {
            _state.update { it.copy(errorMessage = "Paste session JSON or accessToken first.") }
            return
        }
        if (current.isLoading) return

        _state.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    apiClient.importSession(raw)
                }
            }.onSuccess {
                _state.update { it.copy(isLoading = false) }
            }.onFailure { error ->
                val message = when (error) {
                    is ApiError -> error.message
                    else -> error.message ?: "Failed to import session."
                }
                _state.update { it.copy(isLoading = false, errorMessage = message) }
            }
        }
    }

    fun login() {
        val current = _state.value
        val email = current.email.trim()
        val password = current.password
        if (email.isEmpty() || password.isEmpty()) {
            _state.update { it.copy(errorMessage = "Please enter your email and password.") }
            return
        }
        if (current.isLoading) return

        _state.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    apiClient.login(email = email, password = password)
                }
            }.onSuccess {
                _state.update { it.copy(isLoading = false) }
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { apiClient.fetchEdgeKey() }
                }
            }.onFailure { error ->
                val message = when (error) {
                    is ApiError -> error.message
                    else -> error.message ?: "Sign in failed. Please check your credentials."
                }
                _state.update { it.copy(isLoading = false, errorMessage = message) }
            }
        }
    }

    class Factory(private val apiClient: LinerApiClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(apiClient) as T
        }
    }
}
