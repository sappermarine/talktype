@file:OptIn(ExperimentalMaterial3Api::class)

package app.talktype

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) { App() }
        }
    }
}

private fun Context.micGranted() =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Samsung freezes idle background apps, which stops the bubble from noticing the keyboard. */
private fun Context.staysAwake() =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

private fun Context.bubbleEnabled(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val mine = ComponentName(this, DictationService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var mic by remember { mutableStateOf(ctx.micGranted()) }
    var bubble by remember { mutableStateOf(ctx.bubbleEnabled()) }
    var awake by remember { mutableStateOf(ctx.staysAwake()) }
    var engine by remember { mutableStateOf(Prefs.engine) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        engine = Prefs.engine // may have been switched from the bubble
        mic = ctx.micGranted()
        bubble = ctx.bubbleEnabled()
        awake = ctx.staysAwake()
    }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mic = ctx.micGranted()
    }

    var apiKey by remember { mutableStateOf(Prefs.apiKey) }
    var cleanup by remember { mutableStateOf(Prefs.cleanup) }

    Scaffold(topBar = { TopAppBar(title = { Text("TalkType") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Setup") {
                StatusRow("Microphone", mic, "Allow") {
                    val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
                    askPermissions.launch(wanted.toTypedArray())
                }
                StatusRow("Floating mic bubble", bubble, "Turn on") {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                StatusRow("Stay awake in the background", awake, "Allow") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                    )
                }
                if (!awake) Hint("Without this the phone pauses TalkType after a few seconds and the bubble stops appearing.", warn = true)
                if (!bubble) {
                    Hint("In Accessibility, open Installed apps, choose TalkType dictation bubble and switch it on.")
                } else {
                    Hint("Open any app and tap a text field. Tap the bubble to talk, tap again to insert. Drag to move it. Long-press to switch between on-device and cloud (a blue dot means cloud); long-press while recording to discard.")
                }
            }

            Section("Speech engine") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(ENGINE_LOCAL to "On device", ENGINE_CLOUD to "Cloud").forEachIndexed { i, (id, label) ->
                        SegmentedButton(
                            selected = engine == id,
                            onClick = {
                                engine = id
                                Prefs.engine = id
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                if (engine == ENGINE_LOCAL) {
                    Hint("Audio never leaves the phone. Works offline.")
                    ModelList()
                } else {
                    Hint("Audio is sent to the API below for transcription. Fastest and most accurate; needs internet.")
                    if (apiKey.isBlank()) Hint("Add an API key under Cloud account to use this engine.", warn = true)
                    PrefField("Transcription model", Prefs.sttModel) { Prefs.sttModel = it }
                    PrefField("Language code (blank = auto)", Prefs.language) { Prefs.language = it }
                }
            }

            Section("AI cleanup") {
                SwitchRow("Polish the transcript", cleanup) {
                    cleanup = it
                    Prefs.cleanup = it
                }
                Hint("Removes filler words, fixes punctuation and applies self-corrections using a language model. The transcript text is sent to the API below.")
                if (cleanup) {
                    if (apiKey.isBlank()) Hint("Add an API key under Cloud account, otherwise the raw transcript is inserted.", warn = true)
                    PrefField("Cleanup model", Prefs.cleanupModel) { Prefs.cleanupModel = it }
                    PrefField("Instructions", Prefs.cleanupPrompt, singleLine = false) { Prefs.cleanupPrompt = it }
                }
            }

            Section("Cloud account") {
                Hint("Any OpenAI-compatible API. The default is Groq, which has a free tier - create a key at console.groq.com.")
                PrefField("API base URL", Prefs.apiBase) { Prefs.apiBase = it }
                PrefField("API key", apiKey, secret = true) {
                    apiKey = it
                    Prefs.apiKey = it
                }
            }

            Section("Dictionary") {
                Hint("Fixes for things it keeps getting wrong, one per line, as: what it writes -> what you meant. Works with every engine.")
                PrefField("Corrections", Prefs.replacements, singleLine = false) { Prefs.replacements = it }
                Hint("Example:  cloud code -> Claude Code")
                Hint("Uncommon names and terms, separated by commas. Helps the cloud engine and AI cleanup recognise them in the first place.")
                PrefField("Uncommon words", Prefs.vocabulary, singleLine = false) { Prefs.vocabulary = it }
            }

            Section("Bubble") {
                var always by remember { mutableStateOf(Prefs.bubbleAlways) }
                var paste by remember { mutableStateOf(Prefs.pasteInsert) }
                SwitchRow("Always show (not only with the keyboard)", always) {
                    always = it
                    Prefs.bubbleAlways = it
                }
                SwitchRow("Insert by pasting", paste) {
                    paste = it
                    Prefs.pasteInsert = it
                }
                Hint("Turn pasting on if text lands in the wrong place or loses formatting in some app. It overwrites your clipboard.")
            }

            Section("Try it") {
                var scratch by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = scratch,
                    onValueChange = { scratch = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Tap here, then tap the bubble and speak") },
                    minLines = 3,
                )
            }

            HistorySection()
        }
    }
}

@Composable
private fun ModelList() {
    val installed by ModelStore.installed.collectAsState()
    val progress by ModelStore.progress.collectAsState()
    val error by ModelStore.lastError.collectAsState()
    var selected by remember { mutableStateOf(Prefs.localModel) }
    // The first finished download selects itself.
    if (selected.isEmpty() && Prefs.localModel.isNotEmpty()) selected = Prefs.localModel

    if (installed.isEmpty() && progress.isEmpty()) {
        Hint("Download a model to get started. Parakeet v2 is the best choice for English.", warn = true)
    }
    error?.let { Hint(it, warn = true) }
    ModelStore.catalog.forEachIndexed { i, m ->
        if (i > 0) HorizontalDivider()
        val downloading = progress[m.id]
        val isInstalled = m.id in installed
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isInstalled) {
                RadioButton(
                    selected = selected == m.id,
                    onClick = {
                        selected = m.id
                        Prefs.localModel = m.id
                    },
                )
            }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${m.blurb} ${m.sizeMb} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloading != null) {
                    LinearProgressIndicator(
                        progress = { downloading },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
            when {
                downloading != null -> TextButton(onClick = { ModelStore.cancel(m) }) {
                    Text("${(downloading * 100).toInt()}%  Cancel")
                }
                isInstalled -> TextButton(onClick = {
                    ModelStore.delete(m)
                    if (selected == m.id) {
                        selected = ""
                        Prefs.localModel = ""
                    }
                }) { Text("Delete") }
                else -> TextButton(onClick = { ModelStore.download(m) }) { Text("Download") }
            }
        }
    }
    if (progress.isNotEmpty()) Hint("Keep TalkType open until the download finishes.")
}

@Composable
private fun HistorySection() {
    val ctx = LocalContext.current
    val items by History.items.collectAsState()
    if (items.isEmpty()) return
    Section("Recent dictations") {
        Hint("Kept on this phone only. Tap one to copy it.")
        items.take(20).forEachIndexed { i, text ->
            if (i > 0) HorizontalDivider()
            Text(
                text,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        ctx.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Dictation", text))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 10.dp),
            )
        }
        TextButton(onClick = { History.clear() }) { Text("Clear history") }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@Composable
private fun Hint(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (ok) "Ready" else "Needs setup",
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (!ok) Button(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A text field that writes through to a preference on every edit. */
@Composable
private fun PrefField(
    label: String,
    initial: String,
    singleLine: Boolean = true,
    secret: Boolean = false,
    save: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            save(it)
        },
        label = { Text(label) },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 8,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}
