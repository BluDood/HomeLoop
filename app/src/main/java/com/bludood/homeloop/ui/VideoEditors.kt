package com.bludood.homeloop.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bludood.homeloop.R
import com.bludood.homeloop.data.CropInsets
import com.bludood.homeloop.data.VirtualTimeline
import com.bludood.homeloop.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrimSheet(
    sourceDurationMs: Long,
    startMs: Long,
    endMs: Long,
    sourceFrameRate: Float,
    videoWidth: Int,
    videoHeight: Int,
    crop: CropInsets,
    loadFrameAt: suspend (Long, Int) -> Bitmap?,
    onApply: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val minimumMs = minOf(MIN_LOOP_MS, sourceDurationMs)
    val frameMs =
        if (sourceFrameRate > 0f) (1_000f / sourceFrameRate).roundToInt().toLong() else 33L
    var start by rememberSaveable { mutableLongStateOf(startMs) }
    var end by rememberSaveable { mutableLongStateOf(endMs) }
    var editingEnd by rememberSaveable { mutableStateOf(false) }
    fun setStart(value: Long) {
        start = value.coerceIn(0L, end - minimumMs)
    }

    fun setEnd(value: Long) {
        end = value.coerceIn(start + minimumMs, sourceDurationMs)
    }

    val previewMs = if (editingEnd) (end - frameMs).coerceAtLeast(start) else start

    EditorSheet(
        title = "Trim",
        description = "Choose where the loop starts and ends.",
        canReset = start != 0L || end != sourceDurationMs,
        resetLabel = "Full length",
        onReset = { start = 0L; end = sourceDurationMs },
        onApply = { onApply(start, end) },
        onDismiss = onDismiss,
    ) {
        ConnectedChoices(
            labels = listOf("Start", "End"),
            selectedIndex = if (editingEnd) 1 else 0,
            onSelected = { editingEnd = it == 1 },
            groupLabel = "Trim point",
        )
        FramePreview(
            positionMs = previewMs,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            crop = crop,
            loadFrameAt = loadFrameAt,
        )
        Text(
            "${if (editingEnd) "Ends" else "Starts"} at ${formatPreciseClock(if (editingEnd) end else start)}",
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            val nudge: (Long) -> Unit =
                { delta -> if (editingEnd) setEnd(end + delta) else setStart(start + delta) }
            NudgeButton("−1 s") { nudge(-1_000L) }
            NudgeButton("−1 frame") { nudge(-frameMs) }
            NudgeButton("+1 frame") { nudge(frameMs) }
            NudgeButton("+1 s") { nudge(1_000L) }
        }
        RangeSlider(
            value = start.toFloat()..end.toFloat(),
            onValueChange = { range ->
                val newStart = range.start.toLong()
                val newEnd = range.endInclusive.toLong()
                if (newEnd - newStart >= minimumMs) {
                    if (newStart != start) editingEnd = false else if (newEnd != end) editingEnd = true
                    start = newStart
                    end = newEnd
                }
            },
            valueRange = 0f..sourceDurationMs.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            TrimTimeField(
                label = "Start",
                valueMs = start,
                onCommit = ::setStart,
                modifier = Modifier.weight(1f),
            )
            TrimTimeField(
                label = "End",
                valueMs = end,
                onCommit = ::setEnd,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "Loop length ${formatPreciseClock(end - start)} of ${formatClock(sourceDurationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FramePreview(
    positionMs: Long,
    videoWidth: Int,
    videoHeight: Int,
    crop: CropInsets,
    loadFrameAt: suspend (Long, Int) -> Bitmap?,
) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(positionMs) {
        loading = true
        delay(PREVIEW_SETTLE_MS)
        loadFrameAt(positionMs, PREVIEW_SIZE_PX)?.asImageBitmap()?.let { frame = it }
        loading = false
    }
    val sizeKnown = videoWidth > 0 && videoHeight > 0
    val frameWidth = if (sizeKnown) videoWidth else 16
    val frameHeight = if (sizeKnown) videoHeight else 9
    val appliedCrop = if (sizeKnown) crop else CropInsets()
    val visibleWidth = (frameWidth - appliedCrop.left - appliedCrop.right).coerceAtLeast(1)
    val visibleHeight = (frameHeight - appliedCrop.top - appliedCrop.bottom).coerceAtLeast(1)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(max = PREVIEW_MAX_HEIGHT)
                .aspectRatio(visibleWidth.toFloat() / visibleHeight)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { contentDescription = "Frame at ${formatPreciseClock(positionMs)}" },
            contentAlignment = Alignment.Center,
        ) {
            frame?.let { image ->
                Canvas(Modifier.matchParentSize()) {
                    val scaleX = image.width.toFloat() / frameWidth
                    val scaleY = image.height.toFloat() / frameHeight
                    drawImage(
                        image = image,
                        srcOffset = IntOffset(
                            (appliedCrop.left * scaleX).roundToInt(),
                            (appliedCrop.top * scaleY).roundToInt(),
                        ),
                        srcSize = IntSize(
                            (visibleWidth * scaleX).roundToInt().coerceIn(1, image.width),
                            (visibleHeight * scaleY).roundToInt().coerceIn(1, image.height),
                        ),
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    )
                }
            }
            if (loading) LoadingIndicator()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CropSheet(
    videoWidth: Int,
    videoHeight: Int,
    crop: CropInsets,
    loadPreviewFrame: suspend (Int) -> Bitmap?,
    detectBlackBars: suspend () -> CropInsets?,
    onApply: (CropInsets) -> Unit,
    onDismiss: () -> Unit,
) {
    var left by rememberSaveable { mutableIntStateOf(crop.left) }
    var right by rememberSaveable { mutableIntStateOf(crop.right) }
    var top by rememberSaveable { mutableIntStateOf(crop.top) }
    var bottom by rememberSaveable { mutableIntStateOf(crop.bottom) }
    var centered by rememberSaveable { mutableStateOf(crop.left == crop.right && crop.top == crop.bottom) }
    var detecting by remember { mutableStateOf(false) }
    var detectResult by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var previewLoaded by remember { mutableStateOf(false) }
    val preview by produceState<ImageBitmap?>(null) {
        value = loadPreviewFrame(PREVIEW_SIZE_PX)?.asImageBitmap()
        previewLoaded = true
    }
    val maxHorizontal = (videoWidth - MIN_VISIBLE_PX).coerceAtLeast(0)
    val maxVertical = (videoHeight - MIN_VISIBLE_PX).coerceAtLeast(0)

    EditorSheet(
        title = "Crop",
        description = "Remove black bars or edges from the video.",
        canReset = left != 0 || right != 0 || top != 0 || bottom != 0,
        resetLabel = "Full frame",
        onReset = { left = 0; right = 0; top = 0; bottom = 0; detectResult = null },
        onApply = { onApply(CropInsets(top = top, right = right, bottom = bottom, left = left)) },
        onDismiss = onDismiss,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .heightIn(max = PREVIEW_MAX_HEIGHT)
                    .aspectRatio(videoWidth.toFloat() / videoHeight)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
                val outline = MaterialTheme.colorScheme.primary
                Canvas(Modifier.matchParentSize()) {
                    preview?.let {
                        drawImage(
                            it,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        )
                    }
                    val l = size.width * left / videoWidth
                    val r = size.width * (videoWidth - right) / videoWidth
                    val t = size.height * top / videoHeight
                    val b = size.height * (videoHeight - bottom) / videoHeight
                    drawRect(scrim, Offset.Zero, Size(size.width, t))
                    drawRect(scrim, Offset(0f, b), Size(size.width, size.height - b))
                    drawRect(scrim, Offset(0f, t), Size(l, b - t))
                    drawRect(scrim, Offset(r, t), Size(size.width - r, b - t))
                    drawRect(outline, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                }
                if (!previewLoaded) LoadingIndicator()
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            FilledTonalButton(
                onClick = {
                    detecting = true
                    scope.launch {
                        val bars = detectBlackBars()
                        detecting = false
                        if (bars == null) {
                            detectResult = "Couldn't read the video to look for bars"
                        } else if (bars == CropInsets()) {
                            detectResult = "No black bars found"
                        } else {
                            left = bars.left; right = bars.right; top = bars.top; bottom = bars.bottom
                            centered = bars.left == bars.right && bars.top == bars.bottom
                            detectResult = "Found ${describeBars(bars)}"
                        }
                    }
                },
                enabled = !detecting,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(
                    ButtonDefaults.MinHeight,
                    hasStartIcon = true
                ),
            ) {
                ButtonLabel(
                    R.drawable.ms_auto_fix_high_24,
                    if (detecting) "Looking for bars…" else "Detect black bars"
                )
            }
            detectResult?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SettingsSwitchRow(
            icon = R.drawable.ms_crop_filled_24,
            title = "Keep centered",
            supporting = "Crop opposite edges by the same amount",
            checked = centered,
            onChecked = { value ->
                centered = value
                if (value) {
                    left = maxOf(left, right).coerceAtMost(maxHorizontal / 2); right = left
                    top = maxOf(top, bottom).coerceAtMost(maxVertical / 2); bottom = top
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        if (centered) {
            EdgeControl("Left and right", left, maxHorizontal / 2) { left = it; right = it }
            EdgeControl("Top and bottom", top, maxVertical / 2) { top = it; bottom = it }
        } else {
            EdgeControl("Top", top, maxVertical - bottom) { top = it }
            EdgeControl("Bottom", bottom, maxVertical - top) { bottom = it }
            EdgeControl("Left", left, maxHorizontal - right) { left = it }
            EdgeControl("Right", right, maxHorizontal - left) { right = it }
        }
        Text(
            "Showing ${videoWidth - left - right} × ${videoHeight - top - bottom} of $videoWidth × $videoHeight px",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EdgeControl(title: String, pixels: Int, maxPixels: Int, onChange: (Int) -> Unit) {
    val max = maxPixels.coerceAtLeast(0)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "$pixels px",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            FilledTonalIconButton(
                onClick = { onChange((pixels - 1).coerceIn(0, max)) },
                enabled = pixels > 0,
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(painterResource(R.drawable.ms_remove_24), "Crop 1 px less from $title") }
            Slider(
                value = pixels.toFloat(),
                onValueChange = { onChange(it.roundToInt().coerceIn(0, max)) },
                valueRange = 0f..max.toFloat().coerceAtLeast(1f),
                enabled = max > 0,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = { onChange((pixels + 1).coerceIn(0, max)) },
                enabled = pixels < max,
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(painterResource(R.drawable.ms_add_24), "Crop 1 px more from $title") }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(label) }
}

private fun describeBars(bars: CropInsets): String = buildList {
    if (bars.top > 0 || bars.bottom > 0) {
        add(if (bars.top == bars.bottom) "${bars.top} px top and bottom" else "${bars.top} px top, ${bars.bottom} px bottom")
    }
    if (bars.left > 0 || bars.right > 0) {
        add(if (bars.left == bars.right) "${bars.left} px left and right" else "${bars.left} px left, ${bars.right} px right")
    }
}.joinToString(" · ")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EditorSheet(
    title: String,
    description: String,
    canReset: Boolean,
    resetLabel: String,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var hiding by remember { mutableStateOf(false) }
    fun close(sheetState: SheetState) {
        if (hiding) return
        hiding = true
        scope.launch {
            try {
                sheetState.hide()
                if (!sheetState.isVisible) onDismiss()
            } finally {
                hiding = false
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val sheetScroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(sheetScroll, enabled = sheetScroll.maxValue > 0)
                .padding(horizontal = Space.screen, vertical = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.End),
            ) {
                OutlinedButton(
                    onClick = onReset,
                    enabled = canReset && !hiding,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(resetLabel)
                }
                Button(
                    onClick = { onApply(); close(sheetState) },
                    enabled = !hiding,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun TrimTimeField(
    label: String,
    valueMs: Long,
    onCommit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val shown = VirtualTimeline.format(valueMs)
    var text by remember(valueMs) { mutableStateOf(shown) }
    var wasFocused by remember { mutableStateOf(false) }
    fun commit() {
        if (text == shown) return
        val parsed = parseTrimTime(text)
        text = shown
        if (parsed != null && parsed != valueMs) onCommit(parsed)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            if (input.length <= MAX_TRIM_TIME_LENGTH && input.all { it.isDigit() || it == ':' }) {
                text = input
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged { focusState ->
            if (wasFocused && !focusState.isFocused) commit()
            wasFocused = focusState.isFocused
        },
    )
}

internal fun parseTrimTime(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.isEmpty() || parts.size > 3 || parts.any(String::isBlank)) return null
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    val totalSeconds = when (numbers.size) {
        1 -> numbers[0]
        2 -> {
            if (numbers[1] >= 60L) return null
            numbers[0] * 60L + numbers[1]
        }

        3 -> {
            if (numbers[1] >= 60L || numbers[2] >= 60L) return null
            numbers[0] * 3_600L + numbers[1] * 60L + numbers[2]
        }

        else -> return null
    }
    if (totalSeconds < 0L || totalSeconds > Long.MAX_VALUE / 1_000L) return null
    return totalSeconds * 1_000L
}

private const val MIN_LOOP_MS = 1_000L
private const val MIN_VISIBLE_PX = 2
private const val MAX_TRIM_TIME_LENGTH = 12
private const val PREVIEW_SIZE_PX = 720
private const val PREVIEW_SETTLE_MS = 150L
private val PREVIEW_MAX_HEIGHT = 240.dp
