package com.radium.inkwell.ui.sourcemanage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.radium.inkwell.data.repo.SourceAuditReport
import com.radium.inkwell.ui.components.AppAlertDialog
import com.radium.inkwell.ui.components.AppLoadingIndicator
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.PrimaryButton
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.SectionHeader
import com.radium.inkwell.ui.components.SettingFootnote
import com.radium.inkwell.ui.components.SettingGroup
import com.radium.inkwell.ui.components.SettingRow
import com.radium.inkwell.ui.components.SwitchRow
import com.radium.inkwell.ui.components.SettingGroupPosition
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 安全性检查：扫一遍已导入的来源，移除「需要登录」与「含违规内容」的。
 *
 * 判定是启发式的（见 [com.radium.inkwell.core.guard.SourceAudit]），所以移除前把原文写进隔离区，
 * 误删可以恢复。这里只负责触发与展示，判定在 [com.radium.inkwell.data.repo.SourceAuditService]。
 */
@Composable
fun SourceAuditSection(
    running: Boolean,
    report: SourceAuditReport?,
    onRun: () -> Unit,
    onDismissReport: () -> Unit,
    autoCheck: Boolean,
    onToggleAutoCheck: (Boolean) -> Unit,
    wifiOnly: Boolean,
    onToggleWifiOnly: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader("安全性检查")
        SettingGroup {
            SettingRow(
                title = "检查并移除不合规来源",
                subtitle = "移除需要登录的来源，以及含违规内容的来源（可在隔离区恢复）",
                position = SettingGroupPosition.Alone,
                onClick = if (running) null else onRun,
                trailing = {
                    if (running) {
                        AppLoadingIndicator(size = Dimens.buttonSpinner)
                    } else {
                        Text(
                            text = "开始",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }
        SettingFootnote("检查只读本地数据，不联网；几百个来源通常几秒完成。移除的来源会记在隔离区，可随时恢复。")

        SectionHeader("自动校验")
        SettingGroup {
            SwitchRow(
                title = "每天自动校验",
                subtitle = "只测站点还能不能打开，失效的自动移除（可在隔离区恢复）",
                checked = autoCheck,
                onCheckedChange = onToggleAutoCheck,
                position = if (autoCheck) SettingGroupPosition.First else SettingGroupPosition.Alone,
            )
            if (autoCheck) {
                SwitchRow(
                    title = "只在 Wi-Fi 下校验",
                    checked = wifiOnly,
                    onCheckedChange = onToggleWifiOnly,
                    position = SettingGroupPosition.Last,
                )
            }
        }
        SettingFootnote("每天一次轻量校验（DNS + 连通性），十几分钟跑完。确定失效的（域名没了、404）立即移除；疑似失效的（403、5xx、超时）要连续 3 次才移除，避免站点临时抽风被误删。")
    }

    if (report != null) {
        AuditReportDialog(report = report, onDismiss = onDismissReport)
    }
}

@Composable
private fun AuditReportDialog(report: SourceAuditReport, onDismiss: () -> Unit) {
    val login = report.removed.filter { it.kind == SourceAuditReport.Kind.LOGIN }
    val harmful = report.removed.filter { it.kind == SourceAuditReport.Kind.HARMFUL }
    val notNovel = report.removed.filter { it.kind == SourceAuditReport.Kind.NOT_NOVEL }

    AppAlertDialog(
        title = if (report.isEmpty) "检查完毕" else "已移除 ${report.removed.size} 个来源",
        confirmText = "知道了",
        onConfirm = onDismiss,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapS)) {
            Text(
                text = "共检查 ${report.total} 个来源",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (report.isEmpty) {
                Text(
                    text = "没有发现需要移除的来源。",
                    style = MiuixTheme.textStyles.body1,
                )
            }
            if (login.isNotEmpty()) {
                Text(
                    text = "需要登录（${login.size}）",
                    style = MiuixTheme.textStyles.subtitle,
                )
                login.forEach {
                    Text(
                        text = "· ${it.name} — ${it.reason}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (notNovel.isNotEmpty()) {
                Text(
                    text = "非小说类（${notNovel.size}）",
                    style = MiuixTheme.textStyles.subtitle,
                )
                notNovel.forEach {
                    Text(
                        text = "· ${it.name} — ${it.reason}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (harmful.isNotEmpty()) {
                Text(
                    text = "内容违规（${harmful.size}）",
                    style = MiuixTheme.textStyles.subtitle,
                )
                harmful.forEach {
                    Text(
                        text = "· ${it.name} — ${it.reason}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}
