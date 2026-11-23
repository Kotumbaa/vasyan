package `fun`.tryliner.android.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `fun`.tryliner.android.ui.components.LinerPanel
import `fun`.tryliner.android.ui.theme.LinerColors
import `fun`.tryliner.android.ui.theme.LinerShapes

@Composable
fun LoginScreen(
    viewModel: LoginViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        LinerColors.LoginGlow.copy(alpha = 0.24f),
                        LinerColors.LoginBackdrop,
                        LinerColors.BgCanvas
                    )
                )
            )
            .imePadding()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(LinerShapes.BigArt)
                    .background(LinerColors.HeroGlass),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = LinerColors.TextPrimary,
                    modifier = Modifier.size(30.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (state.mode) {
                        AuthMode.LOGIN -> "Вход в vasyan"
                        AuthMode.REGISTER -> "Регистрация в vasyan"
                        AuthMode.TOKEN_IMPORT -> "Вход по токену"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = LinerColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ваша музыка, синхронизированная везде",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LinerColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinerPanel(
                modifier = Modifier.fillMaxWidth(),
                shape = LinerShapes.Panel,
                containerColor = Color.White.copy(alpha = 0.07f),
                borderColor = Color.Transparent
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = LinerColors.FillHover,
                        unfocusedContainerColor = LinerColors.Fill,
                        focusedTextColor = LinerColors.TextPrimary,
                        unfocusedTextColor = LinerColors.TextPrimary,
                        focusedLabelColor = LinerColors.TextSecondary,
                        unfocusedLabelColor = LinerColors.TextTertiary,
                        cursorColor = LinerColors.TextPrimary
                    )

                    if (!state.showTokenImport) {
                        OutlinedTextField(
                            value = state.email,
                            onValueChange = viewModel::onEmailChange,
                            label = { Text("Email") },
                            singleLine = true,
                            enabled = !state.isLoading,
                            shape = LinerShapes.Item,
                            colors = fieldColors,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (state.isRegister) {
                            OutlinedTextField(
                                value = state.username,
                                onValueChange = viewModel::onUsernameChange,
                                label = { Text("Никнейм (a-z, 0-9, _)") },
                                singleLine = true,
                                enabled = !state.isLoading,
                                shape = LinerShapes.Item,
                                colors = fieldColors,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = state.displayName,
                                onValueChange = viewModel::onDisplayNameChange,
                                label = { Text("Отображаемое имя (необязательно)") },
                                singleLine = true,
                                enabled = !state.isLoading,
                                shape = LinerShapes.Item,
                                colors = fieldColors,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::onPasswordChange,
                            label = { Text(if (state.isRegister) "Пароль (от 6 символов)" else "Пароль") },
                            singleLine = true,
                            enabled = !state.isLoading,
                            shape = LinerShapes.Item,
                            colors = fieldColors,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = null,
                                        tint = LinerColors.TextTertiary
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (state.isRegister) viewModel.register() else viewModel.login()
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = state.sessionJson,
                            onValueChange = viewModel::onSessionJsonChange,
                            label = { Text("JSON сессии ПК или accessToken") },
                            placeholder = {
                                Text("{\"accessToken\":\"...\",\"refreshToken\":\"...\"}")
                            },
                            minLines = 2,
                            maxLines = 4,
                            enabled = !state.isLoading,
                            shape = LinerShapes.Item,
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (!state.errorMessage.isNullOrBlank()) {
                        Text(
                            text = state.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LinerColors.AccentWarning
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = when (state.mode) {
                            AuthMode.LOGIN -> viewModel::login
                            AuthMode.REGISTER -> viewModel::register
                            AuthMode.TOKEN_IMPORT -> viewModel::importSession
                        },
                        enabled = !state.isLoading,
                        shape = LinerShapes.Pill,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LinerColors.BtnPrimaryBg,
                            contentColor = LinerColors.BtnPrimaryText
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = LinerColors.BtnPrimaryText
                            )
                        } else {
                            Text(
                                text = when (state.mode) {
                                    AuthMode.LOGIN -> "Войти"
                                    AuthMode.REGISTER -> "Создать аккаунт"
                                    AuthMode.TOKEN_IMPORT -> "Импортировать сессию"
                                },
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }

                    if (!state.showTokenImport) {
                        TextButton(
                            onClick = viewModel::toggleRegisterMode,
                            enabled = !state.isLoading,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(
                                text = if (state.isRegister) {
                                    "Уже есть аккаунт? Войти"
                                } else {
                                    "Нет аккаунта? Зарегистрироваться"
                                },
                                color = LinerColors.TextPrimary
                            )
                        }
                    }

                    TextButton(
                        onClick = viewModel::toggleTokenImport,
                        enabled = !state.isLoading,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = if (state.showTokenImport) {
                                "Вернуться ко входу по почте"
                            } else {
                                "Войти по токену с ПК"
                            },
                            color = LinerColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}
