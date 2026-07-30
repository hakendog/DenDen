package com.tensal.denden.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tensal.denden.DenDenColors
import com.tensal.denden.R

enum class SetupStep { WELCOME, PERMISSIONS, DEMO, PAIRING }

data class SetupDisplayState(
    val loading: Boolean = false,
    val status: String? = null,
    val confirmFirebaseProjectId: String? = null,
    val confirmDomain: String? = null
)

@Composable
fun SetupScreen(
    step: SetupStep,
    input: String,
    state: SetupDisplayState,
    localTestInProgress: Boolean,
    localTestMessage: String?,
    isNotificationPermissionGranted: Boolean,
    canUseFullScreenIntent: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    isNotificationPolicyAccessGranted: Boolean,
    onStepChange: (SetupStep) -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanQr: () -> Unit,
    onConfirmPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onRunLocalTest: () -> Unit,
    onRequestNotification: () -> Unit,
    onOpenFullScreenIntentSettings: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onOpenDndSettings: () -> Unit,
    onUseLocalMode: () -> Unit = {}
) {
    SetupPage(
        step = step,
        title = when (step) {
            SetupStep.WELCOME -> stringResource(R.string.setup_first_time)
            SetupStep.PERMISSIONS -> stringResource(R.string.setup_permissions)
            SetupStep.DEMO -> stringResource(R.string.setup_demo)
            SetupStep.PAIRING -> stringResource(R.string.setup_pairing)
        },
        onBack = when (step) {
            SetupStep.WELCOME -> null
            SetupStep.PERMISSIONS -> { { onStepChange(SetupStep.WELCOME) } }
            SetupStep.DEMO -> { { onStepChange(SetupStep.PERMISSIONS) } }
            SetupStep.PAIRING -> { { onStepChange(SetupStep.DEMO) } }
        }
    ) {
        when (step) {
            SetupStep.WELCOME -> WelcomeStep(onStart = { onStepChange(SetupStep.PERMISSIONS) })
            SetupStep.PERMISSIONS -> PermissionsStep(
                isNotificationPermissionGranted = isNotificationPermissionGranted,
                canUseFullScreenIntent = canUseFullScreenIntent,
                isBatteryOptimizationIgnored = isBatteryOptimizationIgnored,
                isNotificationPolicyAccessGranted = isNotificationPolicyAccessGranted,
                onRequestNotification = onRequestNotification,
                onOpenFullScreenIntentSettings = onOpenFullScreenIntentSettings,
                onOpenBatteryOptimizationSettings = onOpenBatteryOptimizationSettings,
                onOpenDndSettings = onOpenDndSettings,
                onNext = { onStepChange(SetupStep.DEMO) }
            )
            SetupStep.DEMO -> DemoStep(
                inProgress = localTestInProgress,
                message = localTestMessage,
                onRun = onRunLocalTest,
                onNext = { onStepChange(SetupStep.PAIRING) }
            )
            SetupStep.PAIRING -> PairingStep(
                input = input,
                state = state,
                onInputChange = onInputChange,
                onSubmit = onSubmit,
                onScanQr = onScanQr,
                onConfirmPairing = onConfirmPairing,
                onCancelPairing = onCancelPairing,
                onUseLocalMode = onUseLocalMode
            )
        }
    }
}

@Composable
private fun SetupPage(
    step: SetupStep,
    title: String,
    onBack: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DenDenColors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = DenDenColors.onSurface
                    )
                }
            } else {
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = DenDenColors.onSurface
            )
            Text(
                text = stringResource(R.string.setup_step, step.ordinal + 1, SetupStep.entries.size),
                modifier = Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = DenDenColors.onSurfaceVariant
            )
        }

        key(step) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = RoundedCornerShape(16.dp),
            color = DenDenColors.mascotBackground,
            border = BorderStroke(1.dp, DenDenColors.mascotBorder(DenDenColors.mascotBackground))
        ) {
            Image(
                painter = painterResource(R.drawable.denden_builtin_logo_transparent),
                contentDescription = stringResource(R.string.denden_logo),
                modifier = Modifier.padding(14.dp),
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(DenDenColors.mascotForeground)
            )
        }
        Text(
            text = stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = DenDenColors.onSurface
        )
        Text(
            text = stringResource(R.string.welcome_subtitle),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            color = DenDenColors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.start_setup))
        }
    }
}

@Composable
private fun PermissionsStep(
    isNotificationPermissionGranted: Boolean,
    canUseFullScreenIntent: Boolean,
    isBatteryOptimizationIgnored: Boolean,
    isNotificationPolicyAccessGranted: Boolean,
    onRequestNotification: () -> Unit,
    onOpenFullScreenIntentSettings: () -> Unit,
    onOpenBatteryOptimizationSettings: () -> Unit,
    onOpenDndSettings: () -> Unit,
    onNext: () -> Unit
) {
    PermissionGroup(
        title = stringResource(R.string.required_permissions),
        items = listOf(
            PermissionItem(
                title = stringResource(R.string.notification_permission),
                granted = isNotificationPermissionGranted,
                grantedText = stringResource(R.string.required_granted),
                deniedText = stringResource(R.string.required_not_granted),
                onClick = onRequestNotification
            ),
            PermissionItem(
                title = stringResource(R.string.lock_screen_alarm),
                granted = canUseFullScreenIntent,
                grantedText = stringResource(R.string.fullscreen_granted),
                deniedText = stringResource(R.string.fullscreen_not_granted),
                onClick = onOpenFullScreenIntentSettings
            )
        )
    )
    Spacer(Modifier.height(16.dp))
    PermissionGroup(
        title = stringResource(R.string.recommended_permissions),
        items = listOf(
            PermissionItem(
                title = stringResource(R.string.ignore_battery_optimization),
                granted = isBatteryOptimizationIgnored,
                grantedText = stringResource(R.string.battery_granted),
                deniedText = stringResource(R.string.battery_not_granted),
                onClick = onOpenBatteryOptimizationSettings
            ),
            PermissionItem(
                title = stringResource(R.string.do_not_disturb),
                granted = isNotificationPolicyAccessGranted,
                grantedText = stringResource(R.string.dnd_granted),
                deniedText = stringResource(R.string.dnd_not_granted),
                onClick = onOpenDndSettings
            )
        )
    )
    Text(
        text = stringResource(R.string.overlay_not_required),
        modifier = Modifier.padding(top = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = DenDenColors.onSurfaceVariant
    )
    Button(
        onClick = onNext,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Text(stringResource(R.string.next))
    }
}

private data class PermissionItem(
    val title: String,
    val granted: Boolean,
    val grantedText: String,
    val deniedText: String,
    val onClick: () -> Unit
)

@Composable
private fun PermissionGroup(title: String, items: List<PermissionItem>) {
    Text(
        text = title,
        modifier = Modifier.padding(bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = DenDenColors.primary
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DenDenColors.surfaceContainerLowest),
        border = BorderStroke(1.dp, DenDenColors.outlineVariant)
    ) {
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = DenDenColors.onSurface
                    )
                    Text(
                        text = if (item.granted) item.grantedText else item.deniedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.granted) DenDenColors.success else DenDenColors.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = item.onClick, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.configure))
                }
            }
            if (index != items.lastIndex) HorizontalDivider(color = DenDenColors.outlineVariant)
        }
    }
}

@Composable
private fun DemoStep(
    inProgress: Boolean,
    message: String?,
    onRun: () -> Unit,
    onNext: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DenDenColors.surfaceContainerLowest),
        border = BorderStroke(1.dp, DenDenColors.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.local_demo_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = DenDenColors.onSurface
                )
                Text(
                    text = if (inProgress) stringResource(R.string.operation_in_progress) else "00:10",
                    style = MaterialTheme.typography.labelLarge,
                    color = DenDenColors.primary
                )
            }
            Text(
                text = stringResource(R.string.local_demo_description),
                style = MaterialTheme.typography.bodyMedium,
                color = DenDenColors.onSurfaceVariant
            )
            message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = DenDenColors.onSurface)
            }
            Button(
                onClick = onRun,
                enabled = !inProgress,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (inProgress) R.string.demo_running else R.string.start_demo))
            }
            OutlinedButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (message == null) R.string.skip_demo else R.string.continue_to_pairing))
            }
        }
    }
}

@Composable
private fun PairingStep(
    input: String,
    state: SetupDisplayState,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanQr: () -> Unit,
    onConfirmPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onUseLocalMode: () -> Unit
) {
    Text(
        text = stringResource(R.string.device_identity),
        modifier = Modifier.padding(bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = DenDenColors.primary
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DenDenColors.surfaceContainerLowest),
        border = BorderStroke(1.dp, DenDenColors.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.pairing_code)) },
                placeholder = { Text(stringResource(R.string.pairing_code_hint)) },
                singleLine = true,
                enabled = !state.loading
            )
            OutlinedButton(
                onClick = onScanQr,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Text(stringResource(R.string.scan_qr_code), modifier = Modifier.padding(start = 8.dp))
            }
            Button(
                onClick = onSubmit,
                enabled = input.isNotBlank() && !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (state.loading) R.string.checking_pairing else R.string.check_pairing))
            }
        }
    }

    OutlinedButton(
        onClick = onUseLocalMode,
        enabled = !state.loading,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
    ) {
        Text(stringResource(R.string.use_local_automation_only))
    }
    Text(
        text = stringResource(R.string.use_local_automation_only_description),
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = DenDenColors.onSurfaceVariant
    )

    if (state.loading) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
        }
    }
    state.status?.let {
        Text(
            text = it,
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = DenDenColors.onSurfaceVariant
        )
    }

    if (state.confirmFirebaseProjectId != null && state.confirmDomain != null) {
        val confirmButtonFocus = remember(state.confirmFirebaseProjectId, state.confirmDomain) { FocusRequester() }
        val confirmTitle = stringResource(R.string.confirm_pairing)
        val firebaseProjectLabel = stringResource(R.string.firebase_project)
        val scopeLabel = stringResource(R.string.scope)
        val confirmationNote = stringResource(R.string.pairing_confirmation_note)
        val confirmLabel = stringResource(R.string.confirm_and_pair)
        val cancelLabel = stringResource(R.string.enter_again)
        AlertDialog(
            onDismissRequest = onCancelPairing,
            title = { Text(confirmTitle) },
            text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = confirmationNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = DenDenColors.onSurfaceVariant
                )
                ConfirmationRow(firebaseProjectLabel, state.confirmFirebaseProjectId)
                ConfirmationRow(scopeLabel, state.confirmDomain)
            }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirmPairing,
                    modifier = Modifier
                        .focusRequester(confirmButtonFocus)
                        .focusable()
                        .onGloballyPositioned { confirmButtonFocus.requestFocus() }
                ) {
                    Text(confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelPairing) { Text(cancelLabel) }
            }
        )
    }
}

@Composable
private fun ConfirmationRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DenDenColors.onSurfaceVariant)
        Text(
            text = value,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = DenDenColors.onSurface,
            textAlign = TextAlign.End
        )
    }
}
