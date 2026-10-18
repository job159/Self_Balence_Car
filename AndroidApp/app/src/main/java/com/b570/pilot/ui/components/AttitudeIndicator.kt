package com.b570.pilot.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.b570.pilot.ui.theme.B570Theme
import com.b570.pilot.ui.theme.glass
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 姿態儀：平衡車剪影隨傾角傾斜（前傾 = 向右傾），背景為人工地平線（隨橫滾微轉），下方為航向帶。
 *
 * 數值以 lambda 傳入、只在繪製階段讀取（左上角讀數也在繪製階段畫、最多每 0.1 秒更新文字）
 * → 50 Hz 更新也不會觸發重組：
 * `AttitudeIndicator(pitchDeg = { telem.value?.pitchDeg ?: 0f }, headingDeg = { telem.value?.headingDeg ?: 0f })`
 *
 * @param pitchDeg 遙測的原始傾角（未扣零點）
 * @param fallAngle 傾倒保護角度（畫紅色危險區）
 * @param zeroAngle 機械零點 ZERO_ANGLE：韌體以 |pitch − zeroAngle| > fallAngle 判定傾倒，危險區跟著平移
 * @param active 馬達運轉中（剪影發光）
 * @param showReadout 左上角顯示傾角數字
 */
@Composable
fun AttitudeIndicator(
    pitchDeg: () -> Float,
    modifier: Modifier = Modifier,
    headingDeg: () -> Float = { 0f },
    rollDeg: () -> Float = { 0f },
    fallAngle: Float = 40f,
    zeroAngle: Float = 0f,
    active: Boolean = true,
    showHeadingTape: Boolean = true,
    showReadout: Boolean = true,
) {
    val c = B570Theme.colors
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val readoutMeasurer = rememberTextMeasurer(cacheSize = 0)
    val readoutStyle = B570Theme.type.readoutS
    val readout = remember(readoutStyle, c.danger, c.textPrimary) { ReadoutCache() }
    val shape = RoundedCornerShape(B570Theme.radii.l)
    Box(modifier.aspectRatio(1f).glass(shape).clip(shape)) {
        Box(
            Modifier.matchParentSize().drawWithCache {
                val tapeH = if (showHeadingTape) 30.dp.toPx() else 0f
                val attH = size.height - tapeH
                val pivot = Offset(size.width / 2f, attH * 0.80f)
                val labelStyle = TextStyle(color = c.textMuted, fontSize = 10.sp, fontFeatureSettings = "tnum")
                val pitchLabels = intArrayOf(-40, -20, 20, 40).associateWith { measurer.measure("${abs(it)}°", labelStyle) }
                val readoutPos = Offset(12.dp.toPx(), 12.dp.toPx())
                // 小尺寸（手機橫向的中控台）：左上角讀數不能壓到刻度字 / 刻度線
                // → 刻度字會撞到讀數就不畫（讀數本身就是精確角度），刻度線撞到就把刻度弧縮小
                var scaleR = minOf(size.width * 0.44f, attH * 0.70f)
                var showPitchLabels = true
                if (showReadout) {
                    val ro = measurer.measure("+00.0°", readoutStyle).size
                    val pad = 3.dp.toPx()
                    val box = Rect(readoutPos.x - pad, readoutPos.y - pad, readoutPos.x + ro.width + pad, readoutPos.y + ro.height + pad)
                    fun at(deg: Int, r: Float): Offset {
                        val a = Math.toRadians((deg - 90).toDouble())
                        return Offset(pivot.x + r * cos(a).toFloat(), pivot.y + r * sin(a).toFloat())
                    }
                    showPitchLabels = pitchLabels.none { (deg, tl) ->
                        val p = at(deg, scaleR + 10.dp.toPx())
                        Rect(Offset(p.x - tl.size.width / 2f, p.y - tl.size.height / 2f), Size(tl.size.width.toFloat(), tl.size.height.toFloat())).overlaps(box)
                    }
                    val tick = 9.dp.toPx()
                    var guard = 0
                    while (guard++ < 30 && (-60..60 step 10).any { d -> at(d, scaleR).let { box.contains(it) } || at(d, scaleR - tick).let { box.contains(it) } }) {
                        scaleR *= 0.95f
                    }
                }
                val bodyW = scaleR * 0.30f
                val bodyH = scaleR * 0.62f
                val wheelR = scaleR * 0.17f
                val headingLabels = Array(12) { measurer.measure("${it * 30}", labelStyle) }
                val sky = Brush.verticalGradient(listOf(c.accent2.copy(alpha = 0.22f), c.accent.copy(alpha = 0.04f)), 0f, attH * 0.8f)
                val ground = Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.10f), Color.Transparent), attH * 0.8f, attH)
                val bodyBrush = Brush.verticalGradient(listOf(c.glassHigh, c.surface))
                val thin = Stroke(1.dp.toPx())
                val arcStroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                val bodyStroke = Stroke(1.5.dp.toPx())
                val wheelStroke = Stroke(3.dp.toPx())
                val markerPath = Path().apply {
                    val mx = size.width / 2f
                    moveTo(mx - 6.dp.toPx(), attH); lineTo(mx + 6.dp.toPx(), attH); lineTo(mx, attH + 7.dp.toPx()); close()
                }
                onDrawBehind {
                    val rawPitch = pitchDeg()
                    val pitch = if (rawPitch.isNaN()) 0f else rawPitch.coerceIn(-90f, 90f)
                    val roll = rollDeg().let { if (it.isNaN()) 0f else it.coerceIn(-45f, 45f) }
                    val heading = headingDeg().let { if (it.isNaN()) 0f else it }
                    // 韌體（Safety_Check / UPRIGHT）用扣掉零點後的傾角判斷傾倒
                    val over = abs(pitch - zeroAngle) >= fallAngle
                    val glowCol = when {
                        over -> c.danger
                        !active -> c.textMuted
                        else -> c.accent
                    }

                    // 人工地平線（隨 roll 微轉）
                    clipRect(0f, 0f, size.width, attH) {
                        rotate(-roll, Offset(size.width / 2f, pivot.y)) {
                            drawRect(sky, Offset(-size.width, -size.height), Size(size.width * 3, pivot.y + size.height))
                            drawRect(ground, Offset(-size.width, pivot.y), Size(size.width * 3, size.height))
                            drawLine(c.accent.copy(alpha = 0.45f), Offset(-size.width, pivot.y), Offset(size.width * 2, pivot.y), 1.dp.toPx())
                        }
                        // 角度刻度弧（-60..60），危險區紅色（以零點為中心 ±fallAngle 之外）
                        val tl = Offset(pivot.x - scaleR, pivot.y - scaleR)
                        val arcSize = Size(scaleR * 2, scaleR * 2)
                        drawArc(c.textMuted.copy(alpha = 0.35f), -90f - 60f, 120f, false, tl, arcSize, style = thin)
                        val hiEdge = (zeroAngle + fallAngle).coerceIn(-60f, 60f)
                        val loEdge = (zeroAngle - fallAngle).coerceIn(-60f, 60f)
                        if (hiEdge < 60f) drawArc(c.danger.copy(alpha = 0.6f), -90f + hiEdge, 60f - hiEdge, false, tl, arcSize, style = arcStroke)
                        if (loEdge > -60f) drawArc(c.danger.copy(alpha = 0.6f), -90f - 60f, loEdge + 60f, false, tl, arcSize, style = arcStroke)
                        for (deg in -60..60 step 10) {
                            val a = Math.toRadians((deg - 90).toDouble())
                            val major = deg % 20 == 0
                            val r1 = scaleR
                            val r2 = scaleR - (if (major) 9.dp.toPx() else 5.dp.toPx())
                            drawLine(
                                c.textSecondary.copy(alpha = if (major) 0.7f else 0.35f),
                                Offset(pivot.x + r1 * cos(a).toFloat(), pivot.y + r1 * sin(a).toFloat()),
                                Offset(pivot.x + r2 * cos(a).toFloat(), pivot.y + r2 * sin(a).toFloat()),
                                1.dp.toPx(),
                            )
                            if (showPitchLabels) pitchLabels[deg]?.let { tl2 ->
                                val rl = scaleR + 10.dp.toPx()
                                drawText(tl2, topLeft = Offset(pivot.x + rl * cos(a).toFloat() - tl2.size.width / 2f, pivot.y + rl * sin(a).toFloat() - tl2.size.height / 2f))
                            }
                        }
                        // 目前角度指示弧
                        drawArc(glowCol.copy(alpha = 0.8f), -90f, pitch, false, tl, arcSize, style = arcStroke)

                        // 平衡車剪影（繞輪軸旋轉）
                        rotate(pitch, pivot) {
                            // 光暈
                            drawCircle(glowCol.copy(alpha = 0.10f), bodyH * 0.75f, Offset(pivot.x, pivot.y - bodyH * 0.55f))
                            // 車身
                            drawRoundRect(
                                bodyBrush, Offset(pivot.x - bodyW / 2f, pivot.y - bodyH), Size(bodyW, bodyH),
                                CornerRadius(bodyW * 0.28f),
                            )
                            drawRoundRect(
                                glowCol.copy(alpha = 0.85f), Offset(pivot.x - bodyW / 2f, pivot.y - bodyH), Size(bodyW, bodyH),
                                CornerRadius(bodyW * 0.28f), style = bodyStroke,
                            )
                            // 面板 / 眼睛（朝前方 = 右）
                            val eyeY = pivot.y - bodyH * 0.80f
                            drawRoundRect(c.background, Offset(pivot.x - bodyW * 0.05f, eyeY - bodyW * 0.14f), Size(bodyW * 0.48f, bodyW * 0.28f), CornerRadius(bodyW * 0.12f))
                            drawCircle(glowCol.copy(alpha = 0.35f), bodyW * 0.16f, Offset(pivot.x + bodyW * 0.26f, eyeY))
                            drawCircle(glowCol, bodyW * 0.07f, Offset(pivot.x + bodyW * 0.26f, eyeY))
                            // 電池 / 主機板線條
                            drawLine(glowCol.copy(alpha = 0.4f), Offset(pivot.x - bodyW * 0.3f, pivot.y - bodyH * 0.5f), Offset(pivot.x + bodyW * 0.3f, pivot.y - bodyH * 0.5f), 1.dp.toPx())
                            drawLine(glowCol.copy(alpha = 0.25f), Offset(pivot.x - bodyW * 0.3f, pivot.y - bodyH * 0.42f), Offset(pivot.x + bodyW * 0.15f, pivot.y - bodyH * 0.42f), 1.dp.toPx())
                            // 天線
                            drawLine(c.textSecondary, Offset(pivot.x - bodyW * 0.25f, pivot.y - bodyH), Offset(pivot.x - bodyW * 0.25f, pivot.y - bodyH - bodyW * 0.45f), 1.5.dp.toPx())
                            drawCircle(glowCol, 2.5.dp.toPx(), Offset(pivot.x - bodyW * 0.25f, pivot.y - bodyH - bodyW * 0.45f))
                        }
                        // 輪子（不隨車身旋轉）
                        drawCircle(c.surface, wheelR, pivot)
                        drawCircle(c.textSecondary.copy(alpha = 0.8f), wheelR, pivot, style = wheelStroke)
                        drawCircle(glowCol, wheelR * 0.28f, pivot)
                    }

                    // 航向帶
                    if (showHeadingTape) {
                        val y0 = attH
                        drawRect(c.background.copy(alpha = 0.55f), Offset(0f, y0), Size(size.width, tapeH))
                        drawLine(c.divider, Offset(0f, y0), Offset(size.width, y0), 1f)
                        val pxPerDeg = size.width / 120f
                        val hdg = ((heading % 360f) + 360f) % 360f
                        val firstTick = (((hdg - 60f) / 5f).toInt() - 1) * 5
                        var d = firstTick
                        while (d <= hdg + 65f) {
                            val x = size.width / 2f + (d - hdg) * pxPerDeg
                            val norm = ((d % 360) + 360) % 360
                            val major = norm % 30 == 0
                            drawLine(
                                c.textSecondary.copy(alpha = if (major) 0.8f else 0.35f),
                                Offset(x, y0 + 2.dp.toPx()), Offset(x, y0 + if (major) 9.dp.toPx() else 5.dp.toPx()), 1.dp.toPx(),
                            )
                            if (major) {
                                val tl3 = headingLabels[norm / 30]
                                val lx = x - tl3.size.width / 2f
                                // 只畫完整落在航向帶內的數字（貼邊被切一半的「0」「6」看起來像壞掉）
                                if (lx >= 2f && lx + tl3.size.width <= size.width - 2f) {
                                    drawText(tl3, topLeft = Offset(lx, y0 + 11.dp.toPx()))
                                }
                            }
                            d += 5
                        }
                        drawPath(markerPath, c.accent)
                    }

                    // 左上角傾角讀數（原始值，與遙測一致）
                    if (showReadout) {
                        val danger = !rawPitch.isNaN() && abs(rawPitch - zeroAngle) >= fallAngle
                        val layout = readout.layoutFor(rawPitch, danger, readoutMeasurer) { d ->
                            readoutStyle.copy(color = if (d) c.danger else c.textPrimary)
                        }
                        drawText(layout, topLeft = readoutPos)
                    }
                }
            },
        )
    }
}

/**
 * 傾角讀數的文字排版快取（繪製階段使用，不重組）。文字最多每 [INTERVAL_NS] 換一次
 * （與 telemetrySlow 相同節奏，數字才讀得清楚）；進出危險區或有無資料改變時立即更新。
 */
private class ReadoutCache {
    private var text = ""
    private var danger = false
    private var layout: TextLayoutResult? = null
    private var updatedNs = 0L

    fun layoutFor(pitch: Float, danger: Boolean, measurer: TextMeasurer, style: (Boolean) -> TextStyle): TextLayoutResult {
        val now = System.nanoTime()
        val cur = layout
        val noData = pitch.isNaN()
        if (cur != null && danger == this.danger && noData == (text == NO_DATA) && now - updatedNs < INTERVAL_NS) return cur
        val t = if (noData) NO_DATA else String.format(Locale.US, "%+.1f°", pitch)
        updatedNs = now
        if (cur != null && t == text && danger == this.danger) return cur
        text = t
        this.danger = danger
        return measurer.measure(t, style(danger)).also { layout = it }
    }

    private companion object {
        const val INTERVAL_NS = 100_000_000L
        const val NO_DATA = "--"
    }
}
