package com.srvmanager.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.srvmanager.app.ui.theme.StatusColors

/** Шаг операции для журнала на экране. ok = null — шаг выполняется. */
data class Step(val text: String, val ok: Boolean?, val output: String = "")

@Composable
fun StepsCard(steps: List<Step>, title: String = "Ход выполнения") {
    SectionCard(title = title) {
        steps.forEach { s ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
                when (s.ok) {
                    null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    true -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StatusColors.ok, modifier = Modifier.size(18.dp))
                    false -> Icon(Icons.Filled.Error, contentDescription = null, tint = StatusColors.bad, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.text, style = MaterialTheme.typography.bodyMedium)
                    if (s.output.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        OutputBlock(s.output, maxLines = 20)
                    }
                }
            }
        }
    }
}
