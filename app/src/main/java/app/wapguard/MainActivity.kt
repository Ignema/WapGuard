package app.wapguard

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive()) {
                GuardScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GuardScreen() {
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(currentAccess()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<Int>?>(null) }
    val context = LocalContext.current
    var auto by remember { mutableStateOf(isAutoClean(context)) }
    var last by remember { mutableStateOf(lastCheck(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    DisposableEffect(Unit) {
        val onBinder = Shizuku.OnBinderReceivedListener { access = currentAccess() }
        val onDead = Shizuku.OnBinderDeadListener { access = currentAccess() }
        val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> access = currentAccess() }
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        onDispose {
            Shizuku.removeBinderReceivedListener(onBinder)
            Shizuku.removeBinderDeadListener(onDead)
            Shizuku.removeRequestPermissionResultListener(onPermission)
        }
    }

    fun run(clear: Boolean) = scope.launch {
        busy = true
        result = runCatching { withContext(Dispatchers.IO) { if (clear) clearWapPush(); pendingWapPush() } }
        last = lastCheck(context)
        busy = false
    }

    LaunchedEffect(access) { if (access == Access.Ready) run(clear = false) }

    val pending = result?.getOrNull() ?: 0
    val alert = pending > 0 || result?.isFailure == true
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (alert) scheme.errorContainer else scheme.primaryContainer)
    val content by animateColorAsState(if (alert) scheme.onErrorContainer else scheme.onPrimaryContainer)

    val message = when (access) {
        Access.NotRunning -> "Shizuku isn't running.\nStart it in the Shizuku app, then retry."
        Access.NoPermission -> "WAP Guard needs Shizuku access to read the pending SMS queue."
        Access.Ready -> result?.fold(
            onSuccess = { if (it == 0) "All clear.\nNo stuck WAP Push messages." else "$it stuck WAP Push message${if (it == 1) "" else "s"}." },
            onFailure = { it.message ?: "Something went wrong." },
        ) ?: "Scanning…"
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(168.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(container),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    LoadingIndicator(Modifier.size(80.dp), color = content)
                } else {
                    Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(72.dp), tint = content)
                }
            }
            Spacer(Modifier.height(40.dp))
            Text("WAP Guard", style = MaterialTheme.typography.headlineLargeEmphasized)
            Spacer(Modifier.height(8.dp))
            Text(
                if (isPatched()) "Security patch ${Build.VERSION.SECURITY_PATCH} includes the fix"
                else "Security patch ${Build.VERSION.SECURITY_PATCH}: vulnerable until the November 2026 patch",
                style = MaterialTheme.typography.labelLarge,
                color = if (isPatched()) scheme.primary else scheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            when (access) {
                Access.NotRunning -> Button({ access = currentAccess() }, shapes = ButtonDefaults.shapes()) { Text("Retry") }
                Access.NoPermission -> Button({ Shizuku.requestPermission(0) }, shapes = ButtonDefaults.shapes()) {
                    Text("Grant access")
                }
                Access.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ run(clear = false) }, enabled = !busy, shapes = ButtonDefaults.shapes()) { Text("Scan") }
                    Button(
                        { run(clear = true) },
                        enabled = !busy && pending > 0,
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                    ) { Text("Clear") }
                }
            }
            if (access == Access.Ready) {
                Spacer(Modifier.height(32.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-clean", style = MaterialTheme.typography.titleMedium)
                        val check = last
                        Text(
                            if (auto && check != null) {
                                "${DateUtils.getRelativeTimeSpanString(check.time)} · ${check.text}"
                            } else {
                                "Checks about every 15 min while Shizuku is running"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (auto && check?.ok == false) scheme.error else scheme.onSurfaceVariant,
                        )
                    }
                    Switch(auto, { on ->
                        if (on) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        setAutoClean(context, on)
                        auto = on
                    })
                }
            }
        }
    }
}
