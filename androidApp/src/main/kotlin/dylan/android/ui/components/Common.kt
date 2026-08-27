package dylan.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dylan.android.ui.Copy
import dylan.android.ui.LocalDylanTokens

@Composable
fun SectionTitle(text: String) {
    val t = LocalDylanTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.6.sp),
            color = t.textSecondary,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(1.dp)
                .background(t.divider),
        )
    }
}

@Composable
fun OfflineBanner() {
    val t = LocalDylanTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(t.surfaceVariant)
            .padding(1.dp)
            .background(t.background)
            .padding(12.dp),
    ) {
        Text(
            Copy.OFFLINE.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            color = t.textSecondary,
        )
    }
}
