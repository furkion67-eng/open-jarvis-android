package com.openjarvis.deviceaccess.capabilities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun DeviceCapabilityStatusPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reader = remember(context) { AndroidCapabilityStatusReader(context) }
    var items by remember { mutableStateOf(reader.snapshot()) }

    LaunchedEffect(Unit) { items = reader.snapshot() }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("JARVIS izin ve yetenek durumu", style = MaterialTheme.typography.titleMedium)
            Text(
                "JARVIS yalnızca Android'in verdiği izinler ve senin seçtiğin dosya/klasörler içinde işlem yapabilir. İzinleri Android ayarlarından istediğin zaman geri alabilirsin.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { items = reader.snapshot() }) { Text("Durumu yenile") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Text(stateLabel(item.state), color = stateColor(item.state))
                            Text(item.detail, style = MaterialTheme.typography.bodySmall)
                            if (item.state != CapabilityState.ENABLED && item.state != CapabilityState.NOT_AVAILABLE &&
                                item.id != "shared-storage"
                            ) {
                                androidx.compose.material3.TextButton(
                                    onClick = { openCapabilitySettings(context, item.id) }
                                ) { Text("İlgili Android ayarını aç") }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.packageName)
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Android uygulama izinlerini aç") }
            Text(
                "Erişilebilirlik, üstte görünme ve pil ayarları ayrı Android ekranlarıdır. JARVIS bu onayları atlayamaz ve diğer uygulamaların özel dosyalarına erişemez.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun stateLabel(state: CapabilityState): String = when (state) {
    CapabilityState.ENABLED -> "Etkin"
    CapabilityState.NOT_REQUESTED -> "Henüz istenmedi"
    CapabilityState.DENIED -> "Reddedildi"
    CapabilityState.REVOKED -> "Geri alındı"
    CapabilityState.REQUIRES_SETTINGS -> "Android ayarları gerekiyor"
    CapabilityState.NOT_AVAILABLE -> "Bu sürümde kullanılamıyor"
}

@Composable
private fun stateColor(state: CapabilityState) = when (state) {
    CapabilityState.ENABLED -> MaterialTheme.colorScheme.primary
    CapabilityState.NOT_REQUESTED -> MaterialTheme.colorScheme.onSurfaceVariant
    CapabilityState.DENIED, CapabilityState.REVOKED -> MaterialTheme.colorScheme.error
    CapabilityState.REQUIRES_SETTINGS, CapabilityState.NOT_AVAILABLE -> MaterialTheme.colorScheme.tertiary
}


private fun openCapabilitySettings(context: Context, id: String) {
    val appDetails = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + context.packageName)
    )
    val intent = when (id) {
        "overlay" -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName)
        )
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "background" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> appDetails
    }
    runCatching { context.startActivity(intent) }
}
