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

enum class AuthMode {
    LOGIN,
    REGISTER,
    TOKEN_IMPORT
}

data class LoginUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val username: String = "",
    val displayName: String = "",
    val password: String = "",
    val sessionJson: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val showTokenImport: Boolean get() = mode == AuthMode.TOKEN_IMPORT
    val isRegister: Boolean get() = mode == AuthMode.REGISTER
}

class LoginViewModel(
    private val apiClient: LinerApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(email: String) {
        _state.update { it.copy(email = email, errorMessage = null) }
    }

    fun onUsernameChange(username: String) {
        _state.update { it.copy(username = username, errorMessage = null) }
    }

    fun onDisplayNameChange(displayName: String) {
        _state.update { it.copy(displayName = displayName, errorMessage = null) }
    }

    fun onPasswordChange(password: String) {
        _state.update { it.copy(password = password, errorMessage = null) }
    }

    fun onSessionJsonChange(value: String) {
        _state.update { it.copy(sessionJson = value, errorMessage = null) }
    }

    fun setMode(mode: AuthMode) {
        _state.update { it.copy(mode = mode, errorMessage = null) }
    }

    fun toggleTokenImport() {
        _state.update {
            val next = if (it.mode == AuthMode.TOKEN_IMPORT) AuthMode.LOGIN else AuthMode.TOKEN_IMPORT
            it.copy(mode = next, errorMessage = null)
        }
    }

    fun toggleRegisterMode() {
        _state.update {
            val next = if (it.mode == AuthMode.REGISTER) AuthMode.LOGIN else AuthMode.REGISTER
            it.copy(mode = next, errorMessage = null)
        }
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
            _state.update { it.copy(errorMessage = "Введите email и пароль.") }
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
                    else -> error.message ?: "Не удалось войти. Проверьте данные."
                }
                _state.update { it.copy(isLoading = false, errorMessage = message) }
            }
        }
    }

    fun register() {
        val current = _state.value
        val email = current.email.trim().lowercase()
        val username = current.username.trim().lowercase()
        val displayName = current.displayName.trim().ifEmpty { username }
        val password = current.password

        if (!EMAIL_REGEX.matches(email)) {
            _state.update { it.copy(errorMessage = "Введите корректный email.") }
            return
        }
        if (!USERNAME_REGEX.matches(username)) {
            _state.update {
                it.copy(errorMessage = "Ник: 3–24 символа (латиница a-z, цифры 0-9, _).")
            }
            return
        }
        if (password.length < 6) {
            _state.update { it.copy(errorMessage = "Пароль должен быть не короче 6 символов.") }
            return
        }
        if (current.isLoading) return

        _state.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    apiClient.register(
                        email = email,
                        password = password,
                        username = username,
                        displayName = displayName
                    )
                }
            }.onSuccess {
                _state.update { it.copy(isLoading = false) }
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { apiClient.fetchEdgeKey() }
                }
            }.onFailure { error ->
                val message = when (error) {
                    is ApiError -> error.message
                    else -> error.message ?: "Не удалось зарегистрироваться."
                }
                _state.update { it.copy(isLoading = false, errorMessage = message) }
            }
        }
    }

    companion object {
        private val USERNAME_REGEX = Regex("^[a-z0-9_]{3,24}$")
        private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }

    class Factory(private val apiClient: LinerApiClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(apiClient) as T
        }
    }
}
