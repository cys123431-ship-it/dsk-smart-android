package com.dskapp.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.dskapp.data.export.ExportFormat

/**
 * Bottom sheet listing every exportable format. Selecting one generates the file on-device
 * and opens the system share sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    onDismiss: () -> Unit,
    onExport: (ExportFormat) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "문서로 내보내기",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Text(
                text = "마지막 AI 답변을 파일로 만들어 공유합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            Spacer(Modifier.size(8.dp))

            ExportFormat.entries.forEach { format ->
                ExportRow(format = format, onClick = { onExport(format) })
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            Text(
                text = "모든 파일은 기기에서 생성되며 외부 서버로 전송되지 않습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun ExportRow(format: ExportFormat, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = format.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(text = format.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = format.description(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun ExportFormat.icon(): ImageVector = when (this) {
    ExportFormat.MARKDOWN -> Icons.AutoMirrored.Filled.TextSnippet
    ExportFormat.TEXT -> Icons.AutoMirrored.Filled.TextSnippet
    ExportFormat.WORD -> Icons.Filled.Description
    ExportFormat.EXCEL -> Icons.Filled.TableChart
    ExportFormat.POWERPOINT -> Icons.Filled.Slideshow
    ExportFormat.PDF -> Icons.Filled.PictureAsPdf
}

private fun ExportFormat.description(): String = when (this) {
    ExportFormat.MARKDOWN -> "구조를 유지한 텍스트 (.md)"
    ExportFormat.TEXT -> "서식 없는 순수 텍스트"
    ExportFormat.WORD -> "제목·목록·표가 서식으로 유지됩니다"
    ExportFormat.EXCEL -> "표를 시트별로 분리합니다"
    ExportFormat.POWERPOINT -> "제목마다 슬라이드 1장"
    ExportFormat.PDF -> "인쇄·공유용 문서"
}
