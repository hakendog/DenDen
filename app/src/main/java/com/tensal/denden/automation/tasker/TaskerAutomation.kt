package com.tensal.denden.automation.tasker

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tensal.denden.R
import com.tensal.denden.codePointLength
import com.tensal.denden.automation.LocalAutomationMode
import com.tensal.denden.automation.taskerAutomationRequest
import com.tensal.denden.automation.triggerLocalAutomation
import com.tensal.denden.withSelectedAppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

class TaskerAutomationActivity : ComponentActivity() {
    private var mode by mutableStateOf(LocalAutomationMode.NOTIFY.value)
    private var title by mutableStateOf("")
    private var message by mutableStateOf("")
    private var durationSeconds by mutableStateOf("30")
    private var validationMessage by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withSelectedAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTrustedTaskerCaller(callingPackage) || intent.action != ACTION_EDIT_SETTING) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        intent.taskerConfigOrNull()?.let(::assignFromInput)
        setContent {
            MaterialTheme {
                TaskerConfigScreen(
                    mode = mode,
                    title = title,
                    message = message,
                    durationSeconds = durationSeconds,
                    validationMessage = validationMessage,
                    onModeChange = { mode = it },
                    onTitleChange = { title = it },
                    onMessageChange = { message = it },
                    onDurationChange = { durationSeconds = it },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onSave = ::saveForTasker
                )
            }
        }
    }

    private fun assignFromInput(saved: TaskerAutomationConfig) {
        mode = saved.mode?.takeIf { value -> LocalAutomationMode.entries.any { it.value == value } }
            ?: LocalAutomationMode.NOTIFY.value
        title = saved.title.orEmpty()
        message = saved.message.orEmpty()
        durationSeconds = saved.durationSeconds ?: "30"
    }

    private fun saveForTasker() {
        validationMessage = validateTaskerConfig(mode, title, message, durationSeconds)
            ?.let { getString(R.string.tasker_invalid_settings) }
        if (validationMessage != null) return

        val config = TaskerAutomationConfig(
            mode = mode,
            title = title,
            message = message,
            durationSeconds = durationSeconds,
            capability = TaskerCapabilityStore(this).getOrCreate()
        )
        setResult(Activity.RESULT_OK, config.toResultIntent())
        finish()
    }
}

class TaskerAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isExplicitTaskerFireIntent(
                context.packageName,
                intent.action,
                intent.`package`,
                intent.component?.packageName
            )
        ) return

        val config = intent.taskerConfigOrNull() ?: return
        val ordered = isOrderedBroadcast
        if (!TaskerCapabilityStore(context).matches(config.capability)) {
            if (ordered) resultCode = TASKER_RESULT_FAILED
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                val request = taskerAutomationRequest(
                    config.mode,
                    config.title,
                    config.message,
                    config.durationSeconds,
                    context.withSelectedAppLanguage().getString(R.string.tasker_channel_name)
                )
                triggerLocalAutomation(context, request).degradedReason == null
            }.getOrDefault(false)
            if (ordered) pendingResult.resultCode = if (result) Activity.RESULT_OK else TASKER_RESULT_FAILED
            pendingResult.finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskerConfigScreen(
    mode: String,
    title: String,
    message: String,
    durationSeconds: String,
    validationMessage: String?,
    onModeChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onDurationChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasker_config_title)) },
                actions = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(stringResource(R.string.tasker_mode), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalAutomationMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option.value,
                        onClick = { onModeChange(option.value) },
                        label = {
                            Text(stringResource(when (option) {
                                LocalAutomationMode.QUIET -> R.string.bixby_quiet_short_label
                                LocalAutomationMode.NOTIFY -> R.string.bixby_notify_short_label
                                LocalAutomationMode.RING -> R.string.bixby_ring_short_label
                            }))
                        }
                    )
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tasker_title)) },
                singleLine = true
            )
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tasker_message)) },
                minLines = 3
            )
            OutlinedTextField(
                value = durationSeconds,
                onValueChange = onDurationChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tasker_duration)) },
                singleLine = true
            )
            Text(
                stringResource(R.string.tasker_variables_hint),
                style = MaterialTheme.typography.bodySmall
            )
            validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tasker_save_action))
            }
        }
    }
}

internal fun validateTaskerConfig(mode: String, title: String, message: String, duration: String): String? {
    if (LocalAutomationMode.entries.none { it.value == mode }) return "mode"
    if (title.codePointLength() > 200) return "title"
    if (message.codePointLength() > 1000) return "message"
    if (duration.codePointLength() > 100) return "duration"
    if ('%' !in duration && (duration.toIntOrNull() !in 0..300)) return "duration"
    return null
}

internal fun isTrustedTaskerCaller(packageName: String?): Boolean = packageName == TASKER_PACKAGE

internal fun isExplicitTaskerFireIntent(
    ownPackage: String,
    action: String?,
    targetPackage: String?,
    componentPackage: String?
): Boolean = action == ACTION_FIRE_SETTING &&
    (targetPackage == ownPackage || componentPackage == ownPackage)

private data class TaskerAutomationConfig(
    val mode: String?,
    val title: String?,
    val message: String?,
    val durationSeconds: String?,
    val capability: String?
) {
    fun toResultIntent(): Intent {
        val bundle = Bundle().apply {
            putString(MODE_KEY, mode)
            putString(TITLE_KEY, title)
            putString(MESSAGE_KEY, message)
            putString(DURATION_KEY, durationSeconds)
            putString(CAPABILITY_KEY, capability)
            putString(TASKER_VARIABLE_REPLACE_KEYS, "$TITLE_KEY $MESSAGE_KEY $DURATION_KEY")
        }
        return Intent()
            .putExtra(EXTRA_BUNDLE, bundle)
            .putExtra(EXTRA_BLURB, "$mode: ${title.orEmpty()} · ${message.orEmpty()}")
            .putExtra(TASKER_REQUESTED_TIMEOUT, TASKER_TIMEOUT_MILLIS)
    }
}

private fun Intent.taskerConfigOrNull(): TaskerAutomationConfig? {
    val bundle = runCatching { getBundleExtra(EXTRA_BUNDLE) }.getOrNull() ?: return null
    return runCatching {
        TaskerAutomationConfig(
            mode = bundle.getString(MODE_KEY),
            title = bundle.getString(TITLE_KEY),
            message = bundle.getString(MESSAGE_KEY),
            durationSeconds = bundle.getString(DURATION_KEY),
            capability = bundle.getString(CAPABILITY_KEY)
        )
    }.getOrNull()
}

private class TaskerCapabilityStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreate(): String = prefs.getString(CAPABILITY_KEY, null) ?: synchronized(TaskerCapabilityStore::class.java) {
        prefs.getString(CAPABILITY_KEY, null) ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
        }.let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) }.also {
            check(prefs.edit().putString(CAPABILITY_KEY, it).commit())
        }
    }

    fun matches(candidate: String?): Boolean = MessageDigest.isEqual(
        getOrCreate().toByteArray(Charsets.UTF_8),
        candidate.orEmpty().toByteArray(Charsets.UTF_8)
    )
}

private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
private const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
private const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
private const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
private const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
private const val TASKER_VARIABLE_REPLACE_KEYS = "net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS"
private const val TASKER_REQUESTED_TIMEOUT = "net.dinglisch.android.tasker.extras.REQUESTED_TIMEOUT"
private const val TASKER_TIMEOUT_MILLIS = 60_000
private const val TASKER_RESULT_FAILED = 2
private const val PREFS_NAME = "denden_tasker"
private const val MODE_KEY = "mode"
private const val TITLE_KEY = "title"
private const val MESSAGE_KEY = "message"
private const val DURATION_KEY = "duration"
private const val CAPABILITY_KEY = "capability"
