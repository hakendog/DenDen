package com.tensal.denden.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tensal.denden.DenDenColors
import com.tensal.denden.R
import com.tensal.denden.branding.DirectBrandCandidate

@Composable
fun BrandCandidateDialog(
    candidate: DirectBrandCandidate,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val title = stringResource(if (candidate.isReset) R.string.brand_restore_title else R.string.brand_apply_title)
    val message = stringResource(if (candidate.isReset) R.string.brand_restore_message else R.string.brand_apply_message)
    val previewDescription = stringResource(
        if (candidate.mascot != null) R.string.brand_new_preview else R.string.brand_builtin_preview
    )
    val applyLabel = stringResource(R.string.apply)
    val rejectLabel = stringResource(R.string.reject)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (candidate.mascot != null) {
                    Image(
                        bitmap = candidate.mascot.asImageBitmap(),
                        contentDescription = previewDescription,
                        modifier = Modifier
                            .size(180.dp)
                            .background(candidate.backgroundColor?.let(::Color) ?: DenDenColors.mascotBackground)
                            .padding(12.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.denden_builtin_logo_transparent),
                        contentDescription = previewDescription,
                        modifier = Modifier.size(180.dp).padding(12.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text(applyLabel) } },
        dismissButton = { TextButton(onClick = onReject) { Text(rejectLabel) } }
    )
}
