package neonsync.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import neonsync.AppPaths
import neonsync.AudioColorMode
import neonsync.NeonSyncApp
import neonsync.HomeAssistantConfig

@Composable
fun SettingsScreen(app: NeonSyncApp) {
    val ui by app.ui.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Home Assistant") {
            var url by remember(ui.haConfig) { mutableStateOf(ui.haConfig.url) }
            var token by remember(ui.haConfig) { mutableStateOf(ui.haConfig.token) }
            var showToken by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = url, onValueChange = { url = it }, label = { Text("URL") },
                placeholder = { Text("http://homeassistant.local:8123") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it.trim() }, label = { Text("Long-lived access token") },
                supportingText = { Text("Home Assistant → your profile → Security → Long-lived access tokens") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = "Show token")
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val valid = (url.startsWith("http://") || url.startsWith("https://")) && token.isNotBlank()
                Button(onClick = { app.saveHomeAssistant(HomeAssistantConfig(url.trimEnd('/'), token)) }, enabled = valid) {
                    Text("Save & connect")
                }
                Box(Modifier.size(10.dp).background(if (ui.haConnected) Color(0xFF5BD17A) else Color(0xFFE5675B), CircleShape))
                Text(
                    if (ui.haConnected) "Connected" else ui.haError ?: "Not connected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("Audio-only colour") {
            Text(
                "In “Audio only” mode the brightness follows the music (silence = lights off). The colour comes from:",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val setup = ui.setup
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = setup.audioColorMode == AudioColorMode.SPECTRUM, onClick = {
                    app.changeSetup { it.copy(audioColorMode = AudioColorMode.SPECTRUM) }
                })
                Text("The sound itself — each band adds its colour by how loud it is")
            }
            Column(Modifier.padding(start = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorChooser("Bass", setup.audioBassColor, enabled = setup.audioColorMode == AudioColorMode.SPECTRUM) { hex ->
                    app.changeSetup { it.copy(audioBassColor = hex) }
                }
                ColorChooser("Mids", setup.audioMidColor, enabled = setup.audioColorMode == AudioColorMode.SPECTRUM) { hex ->
                    app.changeSetup { it.copy(audioMidColor = hex) }
                }
                ColorChooser("Highs", setup.audioHighColor, enabled = setup.audioColorMode == AudioColorMode.SPECTRUM) { hex ->
                    app.changeSetup { it.copy(audioHighColor = hex) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = setup.audioColorMode == AudioColorMode.FIXED, onClick = {
                    app.changeSetup { it.copy(audioColorMode = AudioColorMode.FIXED) }
                })
                Text("A fixed colour")
            }
            Column(Modifier.padding(start = 48.dp)) {
                ColorChooser("Colour", setup.audioFixedColor, enabled = setup.audioColorMode == AudioColorMode.FIXED) { hex ->
                    app.changeSetup { it.copy(audioFixedColor = hex) }
                }
            }
        }

        Section("Screen") {
            Text(
                "KDE asks which screen to share the first time and remembers the choice.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { app.selectScreen() }) { Text("Select a different screen…") }
        }

        Section("Advanced") {
            Text(
                "Update rate, smoothing, saturation, brightness range and audio envelope are in\n${AppPaths.configFile}\n(restart the app after editing).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { runCatching { ProcessBuilder("xdg-open", AppPaths.configFile.toString()).start() } }) {
                Text("Open config file")
            }
        }
    }
}

private val SWATCHES = listOf("#ff0000", "#ff5a1f", "#ffd21f", "#00ff00", "#22c7ff", "#0000ff", "#9b3cff", "#ff2fa8", "#ffffff")

/** Swatches plus a hex field for any other colour. Changes apply immediately. */
@Composable
private fun ColorChooser(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val alpha = if (enabled) 1f else 0.4f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(56.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        SWATCHES.forEach { hex ->
            val selected = value.equals(hex, ignoreCase = true)
            Box(
                Modifier.size(26.dp)
                    .background(hexColor(hex)!!.copy(alpha = alpha), CircleShape)
                    .border(if (selected) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0x33FFFFFF)), CircleShape)
                    .clickable(enabled = enabled) { onChange(hex) },
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                if (hexColor(it) != null) onChange(normalizeHex(it))
            },
            enabled = enabled,
            singleLine = true,
            isError = hexColor(text) == null,
            leadingIcon = { Box(Modifier.size(18.dp).background(hexColor(value) ?: Color.Transparent, CircleShape)) },
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(150.dp),
        )
    }
}

private fun normalizeHex(s: String) = "#" + s.trim().removePrefix("#").lowercase()

private fun hexColor(s: String): Color? {
    val h = s.trim().removePrefix("#")
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.let { Color(it or 0xFF000000) }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
