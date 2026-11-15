package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ui.theme.AvatarColors
import com.example.ui.theme.LocalDingColors
import kotlin.math.abs

@Composable
fun DingAvatar(
    name: String,
    photoUrl: String?,
    size: Dp = 48.dp,
    isOnline: Boolean = false,
    isSelfChat: Boolean = false
) {
    val dingColors = LocalDingColors.current
    val bgColor = AvatarColors[abs(name.hashCode()) % AvatarColors.size]
    val initials = name.trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifBlank { "D" }

    Box(modifier = Modifier.size(size)) {
        if (isSelfChat) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = "Saved messages avatar",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(size * 0.48f)
                )
            }
        } else if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "$name avatar",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(bgColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    color = Color.White,
                    style = if (size >= 52.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (isOnline && !isSelfChat) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.28f)
                    .clip(CircleShape)
                    .background(dingColors.onlineGreen)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

@Composable
fun MessageStatusTicks(
    status: String,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp
) {
    val dingColors = LocalDingColors.current
    val tint = when (status) {
        "read" -> dingColors.readReceiptBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    }
    val icon = when (status) {
        "read", "delivered" -> Icons.Filled.DoneAll
        else -> Icons.Filled.Check
    }
    if (status.isNotBlank()) {
        Icon(
            imageVector = icon,
            contentDescription = "Message $status",
            tint = tint,
            modifier = modifier.size(size)
        )
    }
}

@Composable
fun SafetyIdenticonGrid(
    safetyGroups: List<String>,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val joined = safetyGroups.joinToString("")
    Canvas(modifier = modifier) {
        val cells = 7
        val cellSize = size.minDimension / cells
        for (row in 0 until cells) {
            for (col in 0 until cells) {
                val mirrorCol = if (col < cells / 2) col else cells - 1 - col
                val idx = (row * 4 + mirrorCol) % joined.length.coerceAtLeast(1)
                val digit = joined.getOrNull(idx)?.digitToIntOrNull() ?: 0
                val isCorner = (row < 2 && col < 2) || (row < 2 && col >= cells - 2) || (row >= cells - 2 && col < 2)
                val filled = isCorner || (digit % 2 == 0)
                drawRect(
                    color = if (filled) primary else surfaceVariant,
                    topLeft = Offset(col * cellSize + 2f, row * cellSize + 2f),
                    size = Size(cellSize - 4f, cellSize - 4f)
                )
            }
        }
    }
}
