package com.raddstream.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.raddstream.app.viewmodel.KidsLock

/**
 * MX idea #7 — Kids Lock: the parent PIN pad.
 *
 * A numeric pad, not a keyboard: a keyboard invites the child to type
 * words, and a pad keeps the interaction to four taps. The digits are
 * never painted — four dots fill as they are entered — and the entered
 * PIN is handed to [onPinComplete] once, when the fourth digit lands;
 * the pad then clears itself, so a wrong PIN lets the parent retry
 * without dismissing. A locked-out pad ([lockedOutSeconds] > 0)
 * disables every key and says how long is left.
 *
 * The pad holds no PIN logic: format, hashing and the throttle live in
 * [KidsLock] and the [com.raddstream.app.viewmodel.KidsLockViewModel].
 * It is deliberately dumb, so a Paparazzi shot can pin it without a
 * store.
 */
@Composable
fun PinPadDialog(
    title: String,
    subtitle: String,
    error: String?,
    lockedOutSeconds: Int,
    onPinComplete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var digits by remember { mutableStateOf("") }
    val lockedOut = lockedOutSeconds > 0
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // Four dots: filled jade for entered digits, hollow for
                // the rest. The digits themselves are never painted.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(KidsLock.PIN_LENGTH) { index ->
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index < digits.length) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                ),
                        )
                    }
                }
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                if (lockedOut) {
                    Text(
                        "Too many wrong tries — try again in $lockedOutSeconds seconds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                // 3×4 pad: 1–9, blank, 0, backspace. 64dp keys — the
                // same touch target the bottom bar's tabs keep.
                val keys: List<String?> = listOf(
                    "1", "2", "3",
                    "4", "5", "6",
                    "7", "8", "9",
                    null, "0", "⌫",
                )
                keys.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { key ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(64.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (key == null) MaterialTheme.colorScheme.surface
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    )
                                    .then(
                                        if (key == null || lockedOut) Modifier
                                        else Modifier.clickable(role = Role.Button) {
                                            when (key) {
                                                "⌫" -> digits = digits.dropLast(1)
                                                else -> {
                                                    if (digits.length < KidsLock.PIN_LENGTH) {
                                                        val next = digits + key
                                                        digits = next
                                                        if (next.length == KidsLock.PIN_LENGTH) {
                                                            onPinComplete(next)
                                                            digits = ""
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (key == "⌫") {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else if (key != null) {
                                    Text(
                                        key,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    }
}
