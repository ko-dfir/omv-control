package com.omvcompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun ExpressiveTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp)) {
        labels.forEachIndexed { i, label ->
            Surface(color = if (i == selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                contentColor = if (i == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f).clickable { onSelect(i) }) {
                Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 4.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable fun ExpressiveSearch(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp), modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
}

@Composable fun TonalHero(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer))).padding(22.dp), content = content)
}

@Composable fun StorageGlyph(modifier: Modifier = Modifier) {
    Box(modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF85C3EC), Color(0xFF1B7BBA)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Storage, null, Modifier.size(46.dp), tint = Color(0xFFFFFFFF))
    }
}

@Composable fun StatusPill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = color)
    }
}
