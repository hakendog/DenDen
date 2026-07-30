package com.tensal.denden.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.revenuecat.purchases.slidetounlock.DefaultSlideToUnlockColors
import com.revenuecat.purchases.slidetounlock.HintTexts
import com.revenuecat.purchases.slidetounlock.SlideToUnlock
import com.tensal.denden.DenDenColors
import com.tensal.denden.R
import com.tensal.denden.alarm.AlarmRuntimeSnapshot
import com.tensal.denden.alarm.isActiveFor
import com.tensal.denden.alarm.isTerminalFor
import kotlinx.coroutines.delay
import kotlin.math.abs

data class ActiveAlarmPayload(
    val channelId: String,
    val title: String,
    val message: String,
    val eventId: String,
    val channelName: String? = null,
    val durationSeconds: Int? = null
) {
    val channelDisplayName: String
        get() = channelName?.takeIf { it.isNotBlank() } ?: channelId
}

class AlarmScreenState(initiallyAlarming: Boolean = false, initialPayload: ActiveAlarmPayload? = null) {
    var onStopAlarm: (String) -> Unit = {}
    var isAlarming by mutableStateOf(initiallyAlarming)
        private set
    var payload by mutableStateOf(initialPayload)
        private set
    var slideProgress by mutableStateOf(0f)
        private set
    var isStopped by mutableStateOf(false)
        private set

    fun onAlarmStarted(newPayload: ActiveAlarmPayload? = null) {
        isAlarming = true
        isStopped = false
        slideProgress = 0f
        payload = newPayload
    }

    fun onSlideProgress(progress: Float) {
        slideProgress = progress
        if (progress >= 0.8f) {
            stop()
        }
    }

    fun onAlarmTerminated(eventId: String) {
        if (payload?.eventId != eventId) return
        isAlarming = false
        isStopped = true
        slideProgress = 0f
    }

    fun stop() {
        val wasAlarming = isAlarming
        isAlarming = false
        isStopped = true
        slideProgress = 0f
        if (wasAlarming) {
            payload?.eventId?.let(onStopAlarm)
        }
    }

}

private const val SNAIL_MAX_VELOCITY = 2.4f
private const val SNAIL_SAMPLE_TIMEOUT_MILLIS = 120L

internal class SnailMotionTracker {
    private var previousProgress: Float? = null
    private var previousTimestampMillis = 0L
    private var filteredVelocity = 0f

    fun update(progress: Float, timestampMillis: Long): Float {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val elapsedMillis = timestampMillis - previousTimestampMillis
        filteredVelocity = if (previousProgress == null || elapsedMillis !in 1..SNAIL_SAMPLE_TIMEOUT_MILLIS) {
            0f
        } else {
            val measuredVelocity = (clampedProgress - previousProgress!!) * 1_000f / elapsedMillis
            (filteredVelocity * 0.35f + measuredVelocity * 0.65f)
                .coerceIn(-SNAIL_MAX_VELOCITY, SNAIL_MAX_VELOCITY)
        }
        previousProgress = clampedProgress
        previousTimestampMillis = timestampMillis
        return filteredVelocity
    }

    fun settle() {
        previousProgress = null
        filteredVelocity = 0f
    }
}

internal data class SnailMotionTransform(
    val lagDp: Float,
    val scaleX: Float,
    val scaleY: Float
)

internal fun snailMotionTransform(velocity: Float): SnailMotionTransform {
    val normalizedVelocity = velocity.coerceIn(-SNAIL_MAX_VELOCITY, SNAIL_MAX_VELOCITY) / SNAIL_MAX_VELOCITY
    val speed = abs(normalizedVelocity)
    return SnailMotionTransform(
        lagDp = -normalizedVelocity * 14f,
        scaleX = 1f + speed * 0.12f,
        scaleY = 1f - speed * 0.08f
    )
}

@Composable
fun AlarmScreen(
    alarmActivationCount: Int = 0,
    payload: ActiveAlarmPayload? = null,
    mascot: Bitmap? = null,
    mascotBackgroundColor: Color? = null,
    runtimeSnapshot: AlarmRuntimeSnapshot = AlarmRuntimeSnapshot.Unknown,
    onStopAlarm: (String) -> Unit = {},
    onTerminalShown: () -> Unit = {},
    state: AlarmScreenState = remember(alarmActivationCount, payload) { AlarmScreenState(alarmActivationCount > 0, payload) }
) {
    state.onStopAlarm = onStopAlarm
    val currentOnTerminalShown by rememberUpdatedState(onTerminalShown)

    LaunchedEffect(runtimeSnapshot, payload, state) {
        when {
            runtimeSnapshot.isActiveFor(payload?.eventId) -> state.onAlarmStarted(payload)
            runtimeSnapshot.isTerminalFor(payload?.eventId) -> {
                state.onAlarmTerminated(payload!!.eventId)
                delay(1_200)
                currentOnTerminalShown()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DenDenColors.background)
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when {
                    state.isAlarming -> AlarmingContent(payload = state.payload)
                    state.isStopped -> StoppedContent()
                    else -> StandbyContent()
                }
            }
        }

        if (state.isAlarming) {
            SlideToStop(
                state = state,
                mascot = mascot,
                mascotBackgroundColor = mascotBackgroundColor,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (!state.isStopped) {
            StatusPill(text = stringResource(R.string.standby))
        }
    }
}

@Composable
private fun AlarmingContent(payload: ActiveAlarmPayload?) {
    val usesLargeTextLayout = LocalDensity.current.fontScale >= 1.5f
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (usesLargeTextLayout) 12.dp else 18.dp)
    ) {
        ActiveIndicator()

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = DenDenColors.surfaceContainerLowest,
            border = androidx.compose.foundation.BorderStroke(1.dp, DenDenColors.error.copy(alpha = 0.2f)),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (usesLargeTextLayout) 14.dp else 20.dp),
                verticalArrangement = Arrangement.spacedBy(if (usesLargeTextLayout) 8.dp else 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(DenDenColors.error, CircleShape)
                    )
                    Text(
                        text = payload?.channelDisplayName ?: stringResource(R.string.alarm_channel_fallback),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Medium,
                            color = DenDenColors.error
                        )
                    )
                }

                Text(
                    text = payload?.title ?: stringResource(R.string.alarm_default_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Medium,
                        color = DenDenColors.onSurface
                    )
                )

                Text(
                    text = payload?.message ?: "",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = DenDenColors.onSurfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun ActiveIndicator() {
    val context = LocalContext.current
    val usesLargeTextLayout = LocalDensity.current.fontScale >= 1.5f
    val indicatorSize = if (usesLargeTextLayout) 104.dp else 150.dp
    val indicatorCoreSize = if (usesLargeTextLayout) 64.dp else 88.dp
    val bellSize = if (usesLargeTextLayout) 32.dp else 42.dp
    val motionEnabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) > 0f
        }.getOrDefault(true)
    }
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale = if (motionEnabled) {
        val animated by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
        animated
    } else 1f
    val rotation = if (motionEnabled) {
        val animated by infiniteTransition.animateFloat(
            initialValue = -8f,
            targetValue = 8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bell rotation"
        )
        animated
    } else 0f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(indicatorSize),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(scale)
                    .background(DenDenColors.error.copy(alpha = 0.1f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(indicatorCoreSize)
                    .background(DenDenColors.error.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = DenDenColors.error,
                    modifier = Modifier.size(bellSize).rotate(rotation)
                )
            }
        }
        Text(
            text = stringResource(R.string.alarm_ringing),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Medium,
                color = DenDenColors.error
            )
        )
    }
}

@Composable
private fun StoppedContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = DenDenColors.success.copy(alpha = 0.14f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = DenDenColors.success,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
        Text(
            text = stringResource(R.string.alarm_stopped),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = DenDenColors.success
        )
        Text(
            text = stringResource(R.string.alarm_stopped_description),
            style = MaterialTheme.typography.bodyMedium,
            color = DenDenColors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StandbyContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(200.dp),
            shape = CircleShape,
            color = DenDenColors.surfaceContainerHigh
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.standby),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Medium,
                        color = DenDenColors.onSurfaceVariant
                    )
                )
            }
        }
        Text(
            text = stringResource(R.string.standby_description),
            style = MaterialTheme.typography.bodyLarge,
            color = DenDenColors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(32.dp),
        color = DenDenColors.surfaceContainerHigh,
        contentColor = DenDenColors.onSurfaceVariant
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(vertical = 18.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun SlideToStop(
    state: AlarmScreenState,
    mascot: Bitmap?,
    mascotBackgroundColor: Color?,
    modifier: Modifier = Modifier
) {
    val background = mascotBackgroundColor ?: DenDenColors.mascotBackground
    val density = LocalDensity.current
    val motionTracker = remember { SnailMotionTracker() }
    var motionVelocity by remember { mutableFloatStateOf(0f) }
    var motionSample by remember { mutableIntStateOf(0) }
    LaunchedEffect(motionSample) {
        if (motionSample == 0) return@LaunchedEffect
        delay(160)
        motionTracker.settle()
        motionVelocity = 0f
    }
    val motion = snailMotionTransform(motionVelocity)
    val animatedLagDp by animateFloatAsState(
        targetValue = motion.lagDp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        label = "DenDen horizontal inertia"
    )
    val animatedScaleX by animateFloatAsState(
        targetValue = motion.scaleX,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "DenDen horizontal stretch"
    )
    val animatedScaleY by animateFloatAsState(
        targetValue = motion.scaleY,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "DenDen vertical compression"
    )
    val slideDescription = stringResource(R.string.slide_alarm_description)
    val stopLabel = stringResource(R.string.stop_alarm)
    val draggableDescription = stringResource(R.string.draggable_denden)
    SlideToUnlock(
        isSlided = false,
        modifier = modifier.semantics {
            contentDescription = slideDescription
            onClick(label = stopLabel) {
                state.stop()
                true
            }
        },
        onSlideCompleted = state::stop,
        colors = DefaultSlideToUnlockColors(
            startTrackColor = DenDenColors.surfaceContainerHigh,
            endTrackColor = DenDenColors.surfaceContainerHigh,
            thumbColor = DenDenColors.error,
            thumbIconColor = Color.White,
            slidedHintColor = Color.White,
            startHintColor = DenDenColors.onSurfaceVariant.copy(alpha = 0.6f),
            endHintColor = DenDenColors.onSurfaceVariant.copy(alpha = 0.1f),
            progressColor = DenDenColors.error.copy(alpha = 0.3f)
        ),
        hintTexts = HintTexts(
            defaultText = stringResource(R.string.slide_to_stop),
            slidedText = stringResource(R.string.stopping)
        ),
        trackShape = RoundedCornerShape(9999.dp),
        thumbSize = DpSize(64.dp, 64.dp),
        fractionalThreshold = 0.8f,
        onSlideFractionChanged = { progress ->
            motionVelocity = motionTracker.update(progress, SystemClock.uptimeMillis())
            motionSample++
            state.onSlideProgress(progress)
        },
        thumb = { _, _, _, size, _ ->
            val mascotMotionModifier = Modifier.graphicsLayer {
                translationX = with(density) { animatedLagDp.dp.toPx() }
                scaleX = animatedScaleX
                scaleY = animatedScaleY
            }
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(background)
                    .border(
                        1.dp,
                        DenDenColors.mascotBorder(background),
                        CircleShape
                    )
                    .semantics { contentDescription = draggableDescription },
                contentAlignment = Alignment.Center
            ) {
                if (mascot != null) {
                    Image(
                        bitmap = mascot.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp).then(mascotMotionModifier)
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.denden_builtin_logo_transparent),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(8.dp).then(mascotMotionModifier),
                        colorFilter = ColorFilter.tint(DenDenColors.mascotForeground)
                    )
                }
            }
        }
    )
}
