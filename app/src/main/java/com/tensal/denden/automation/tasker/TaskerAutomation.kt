package com.tensal.denden.automation.tasker

import android.app.Activity
import android.content.Context
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
import com.joaomgcd.taskerpluginlibrary.action.TaskerPluginRunnerActionNoOutput
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelperNoOutput
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputField
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputRoot
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResult
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultError
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultSucess
import com.tensal.denden.R
import com.tensal.denden.automation.LocalAutomationMode
import com.tensal.denden.automation.taskerAutomationRequest
import com.tensal.denden.automation.triggerLocalAutomation
import com.tensal.denden.withSelectedAppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.security.SecureRandom

@TaskerInputRoot
class DenDenTaskerInput @JvmOverloads constructor(
    @field:TaskerInputField("mode", labelResIdName = "tasker_mode")
    var mode: String? = LocalAutomationMode.NOTIFY.value,
    @field:TaskerInputField("title", labelResIdName = "tasker_title")
    var title: String? = null,
    @field:TaskerInputField("message", labelResIdName = "tasker_message")
    var message: String? = null,
    @field:TaskerInputField("duration", labelResIdName = "tasker_duration")
    var durationSeconds: String? = "30",
    @field:TaskerInputField(
        "capability",
        labelResIdName = "tasker_capability",
        ignoreInStringBlurb = true
    )
    var capability: String? = null
)

class DenDenTaskerHelper(config: TaskerPluginConfig<DenDenTaskerInput>) :
    TaskerPluginConfigHelperNoOutput<DenDenTaskerInput, DenDenTaskerRunner>(config) {
    override val runnerClass = DenDenTaskerRunner::class.java
    override val inputClass = DenDenTaskerInput::class.java
    override val addDefaultStringBlurb = false

    override fun addToStringBlurb(input: TaskerInput<DenDenTaskerInput>, blurbBuilder: StringBuilder) {
        val values = input.regular
        blurbBuilder.append(values.mode).append(": ").append(values.title.orEmpty()).append(" · ")
            .append(values.message.orEmpty())
    }
}

class TaskerAutomationActivity : ComponentActivity(), TaskerPluginConfig<DenDenTaskerInput> {
    override val context: Context get() = applicationContext
    private val helper by lazy { DenDenTaskerHelper(this) }
    private var mode by mutableStateOf(LocalAutomationMode.NOTIFY.value)
    private var title by mutableStateOf("")
    private var message by mutableStateOf("")
    private var durationSeconds by mutableStateOf("30")
    private var validationMessage by mutableStateOf<String?>(null)

    override val inputForTasker: TaskerInput<DenDenTaskerInput>
        get() = TaskerInput(
            DenDenTaskerInput(
                mode = mode,
                title = title,
                message = message,
                durationSeconds = durationSeconds,
                capability = TaskerCapabilityStore(this).getOrCreate()
            )
        )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withSelectedAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTrustedTaskerCaller(callingPackage)) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
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
        helper.onCreate()
    }

    override fun assignFromInput(input: TaskerInput<DenDenTaskerInput>) {
        val saved = input.regular
        mode = saved.mode?.takeIf { value -> LocalAutomationMode.entries.any { it.value == value } }
            ?: LocalAutomationMode.NOTIFY.value
        title = saved.title.orEmpty()
        message = saved.message.orEmpty()
        durationSeconds = saved.durationSeconds ?: "30"
    }

    private fun saveForTasker() {
        validationMessage = validateTaskerConfig(mode, title, message, durationSeconds)
            ?.let { getString(R.string.tasker_invalid_settings) }
        if (validationMessage == null) helper.finishForTasker()
    }
}

class DenDenTaskerRunner : TaskerPluginRunnerActionNoOutput<DenDenTaskerInput>() {
    override val notificationProperties
        get() = NotificationProperties(
            titleResId = R.string.tasker_running_title,
            textResId = R.string.tasker_running_text,
            iconResId = R.drawable.ic_notification
        )

    override fun run(context: Context, input: TaskerInput<DenDenTaskerInput>): TaskerPluginResult<Unit> {
        val values = input.regular
        if (!TaskerCapabilityStore(context).matches(values.capability)) {
            return TaskerPluginResultError(1, "Tasker action authorization is invalid")
        }
        return runCatching {
            val request = taskerAutomationRequest(
                values.mode,
                values.title,
                values.message,
                values.durationSeconds,
                context.withSelectedAppLanguage().getString(R.string.tasker_channel_name)
            )
            val result = runBlocking(Dispatchers.IO) { triggerLocalAutomation(context, request) }
            check(result.degradedReason == null) { "DenDen event stored, but alert delivery is unavailable" }
            TaskerPluginResultSucess<Unit>()
        }.getOrElse { TaskerPluginResultError(it) }
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
    if (title.length > 200) return "title"
    if (message.length > 1000) return "message"
    if (duration.length > 100) return "duration"
    if ('%' !in duration && (duration.toIntOrNull() !in 0..300)) return "duration"
    return null
}

internal fun isTrustedTaskerCaller(packageName: String?): Boolean = packageName == TASKER_PACKAGE

private class TaskerCapabilityStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreate(): String = prefs.getString(CAPABILITY_KEY, null) ?: ByteArray(32).also {
        SecureRandom().nextBytes(it)
    }.let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) }.also {
        prefs.edit().putString(CAPABILITY_KEY, it).apply()
    }

    fun matches(candidate: String?): Boolean = MessageDigest.isEqual(
        getOrCreate().toByteArray(Charsets.UTF_8),
        candidate.orEmpty().toByteArray(Charsets.UTF_8)
    )
}

private const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
private const val PREFS_NAME = "denden_tasker"
private const val CAPABILITY_KEY = "capability"
