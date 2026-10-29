package app.backup.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.backup.core.AuthMessages
import app.backup.core.Session
import app.backup.core.UsernameAuth
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** بوابة المصادقة المبسّطة: تسجيل الدخول أو إنشاء حساب باسم المستخدم وكلمة المرور وحفظ الجلسة بأمان. */
@Composable
fun AuthGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val activeUid by Session.uidFlow.collectAsState()
    var checking by remember { mutableStateOf(activeUid.isNullOrBlank() || Session.key == null) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val current = firebaseAuth.currentUser
            if (current != null && Session.key != null && Session.uid.isNullOrBlank()) {
                Session.uid = current.uid
            }
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    LaunchedEffect(Unit) {
        if (activeUid.isNullOrBlank() || Session.key == null) {
            withContext(Dispatchers.IO) {
                Session.ensureLoaded(ctx.applicationContext)
            }
        }
        checking = false
    }

    val isAuthenticated = !activeUid.isNullOrBlank() && Session.key != null

    when {
        checking -> Loading()
        !isAuthenticated -> UsernameAuthScreen(auth) {}
        else -> content()
    }
}

@Composable
private fun UsernameAuthScreen(auth: FirebaseAuth, onAuthenticated: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRegister by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf(UsernameAuth.savedUsername(ctx).orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = if (isRegister) "إنشاء حساب" else "تسجيل الدخول",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text("اسم المستخدم") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("كلمة المرور") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    enabled = !busy && username.trim().isNotEmpty() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                if (isRegister) {
                                    UsernameAuth.register(ctx, username, password, auth)
                                } else {
                                    UsernameAuth.login(ctx, username, password, auth)
                                }
                                onAuthenticated()
                            } catch (e: Exception) {
                                error = AuthMessages.of(e)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(
                        when {
                            busy -> "جارٍ التحقق..."
                            isRegister -> "إنشاء حساب"
                            else -> "تسجيل الدخول"
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    onClick = {
                        isRegister = !isRegister
                        password = ""
                        error = null
                    },
                ) {
                    Text(if (isRegister) "تسجيل الدخول" else "إنشاء حساب جديد")
                }
            }
        }
    }
}
