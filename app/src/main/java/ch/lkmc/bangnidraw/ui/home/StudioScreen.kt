package ch.lkmc.bangnidraw.ui.home

import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.ImageEncode
import ch.lkmc.bangnidraw.data.GalleryExportOutcome
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.WidthClass
import ch.lkmc.bangnidraw.ui.theme.LocalAppTheme
import kotlin.math.ceil

/**
 * The Studio: the shelf of paintings, newest first, and the way to start a
 * new one (PLAN.md §5; `docs/plan/08-ui-and-layout.md` §2). Roadmap 3c: the
 * shelf is real — a grid of thumbnails with the hold menu (delete with
 * confirm, rename, and — since step 4 — duplicate; share stays a stub until
 * ShareCache lands), the storage readout, and the New Canvas dialog.
 *
 * The same [LayoutSpec] that places Canvas chrome supplies the shelf's
 * adaptive cell width. Wide windows put New first in reading order; compact
 * windows keep it thumb-reachable as a FAB.
 */
@Composable
fun StudioScreen(
    onOpenPainting: (String) -> Unit,
    openSettings: Boolean = false,
    onSettingsOpened: () -> Unit = {},
    viewModel: StudioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNewCanvas by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val screenSizePx = LocalWindowInfo.current.containerSize

    LaunchedEffect(openSettings) {
        if (!openSettings) return@LaunchedEffect
        showSettings = true
        onSettingsOpened()
    }

    // Re-list on every return to the foreground of this screen — coming back
    // from the Canvas is the case that matters: its leave checkpoint has
    // finished by the time navigation pops (CanvasViewModel.leave), so the
    // shelf lists the write, not the state before it.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthClass = WidthClass.forWidth(maxWidth.value.toInt())
        val layout = LayoutSpec.forWindow(widthClass, maxHeight.value.toInt(), Hand.RIGHT)
        val compact = widthClass == WidthClass.COMPACT

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            floatingActionButton = {
                if (compact) {
                    ExtendedFloatingActionButton(
                        onClick = { showNewCanvas = true },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.studio_new)) },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            },
        ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.studio_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.loaded && state.paintings.isNotEmpty()) {
                // 08 §2's readout: it answers the only question that ever
                // justifies deleting.
                Text(
                    text = pluralStringResource(
                        R.plurals.studio_storage,
                        state.paintings.size,
                        state.paintings.size,
                        Formatter.formatShortFileSize(context, state.totalBytes),
                        Formatter.formatShortFileSize(context, state.freeBytes),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (compact && state.loaded && state.paintings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 96.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.studio_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            // The one place the app explains autosave: the
                            // Canvas will never prompt (08 §2).
                            text = stringResource(R.string.studio_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(layout.gridMinCellDp.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    if (!compact) {
                        item(key = NEW_PAINTING_KEY) {
                            NewPaintingCell { showNewCanvas = true }
                        }
                        // 08 §2's empty state is for every width; on
                        // medium/expanded it sits above the + tile (the grid
                        // never drops the way in).
                        if (state.loaded && state.paintings.isEmpty()) {
                            item(key = EMPTY_PAINTING_KEY, span = { GridItemSpan(maxLineSpan) }) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.studio_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                    Text(
                                        // The one place the app explains
                                        // autosave: the Canvas will never
                                        // prompt (08 §2).
                                        text = stringResource(R.string.studio_empty_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    items(state.paintings, key = { it.id }) { painting ->
                        // Read in composition so a locale change re-renders it.
                        val untitledName = stringResource(R.string.studio_untitled)
                        PaintingCell(
                            painting = painting,
                            hapticsMode = state.hapticsMode,
                            loadThumbnail = viewModel::thumbnailFor,
                            onOpen = { onOpenPainting(painting.id) },
                            onRename = { title ->
                                viewModel.rename(painting.id, title) { renamed ->
                                    if (renamed) return@rename
                                    Toast.makeText(
                                        context,
                                        R.string.studio_rename_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onDuplicate = {
                                viewModel.duplicate(painting.id) { duplicated ->
                                    if (duplicated) return@duplicate
                                    Toast.makeText(
                                        context,
                                        R.string.studio_duplicate_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onSaveAs = {
                                viewModel.saveAsNewGalleryItem(painting.id) { outcome ->
                                    Toast.makeText(
                                        context,
                                        if (outcome == GalleryExportOutcome.SUCCESS) {
                                            R.string.studio_saved_to_gallery
                                        } else {
                                            R.string.studio_save_failed
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onShare = { format ->
                                viewModel.share(
                                    painting.id,
                                    format,
                                    onReady = { uri, mime ->
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = mime
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(
                                                send,
                                                painting.title.orEmpty().ifBlank { untitledName },
                                            ),
                                        )
                                    },
                                    onFailed = {
                                        Toast.makeText(
                                            context,
                                            R.string.studio_share_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            },
                            onDelete = { alsoGallery ->
                                viewModel.delete(
                                    painting.id,
                                    alsoGallery,
                                    painting.galleryUri,
                                ) { deleted ->
                                    Toast.makeText(
                                        context,
                                        if (deleted) {
                                            R.string.studio_deleted
                                        } else {
                                            R.string.studio_delete_failed
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                }
            }
        }
        }
    }

    if (showNewCanvas) {
        NewCanvasDialog(
            budget = viewModel.budget,
            screenSizePx = screenSizePx,
            lastCustomSize = state.lastCustomSize,
            onDismiss = { showNewCanvas = false },
            onCreate = { size, paper ->
                showNewCanvas = false
                viewModel.createPainting(
                    size,
                    paper,
                    onCreated = { id -> onOpenPainting(id) },
                    onFailed = {
                        Toast.makeText(
                            context,
                            R.string.studio_create_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            },
            onCustomSizeCreated = viewModel::rememberCustomSize,
        )
    }

    if (showSettings) {
        SettingsSheet(
            state = state,
            appTheme = LocalAppTheme.current,
            historyMaxSteps = viewModel.budget.historyMaxSteps,
            historyMaxBytes = viewModel.budget.historyMaxBytes,
            onAppTheme = viewModel::setAppTheme,
            onHandedness = viewModel::setHandedness,
            onTouchDrawingMode = viewModel::setTouchDrawingMode,
            onPenButtonAction = viewModel::setPenButtonAction,
            onEraserEndPreset = viewModel::setEraserEndPreset,
            onPressurePreference = viewModel::setPressurePreference,
            onSnapRightAngles = viewModel::setSnapRightAngles,
            onHapticsMode = viewModel::setHapticsMode,
            onGallerySync = viewModel::setGallerySync,
            onMixerChoice = viewModel::setMixerChoice,
            onDebugLatency = viewModel::setDebugLatency,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun NewPaintingCell(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PAINTING_ASPECT)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(CELL_RADIUS_DP.dp),
                ),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.studio_new),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(R.string.studio_new),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * One shelf tile: thumbnail in a 4:3 box, title, relative time; the hold
 * menu hangs off a long press (08 §2).
 */
@Composable
private fun PaintingCell(
    painting: StudioViewModel.Painting,
    hapticsMode: HapticsMode,
    loadThumbnail: suspend (StudioThumbnailKey) -> android.graphics.Bitmap?,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: () -> Unit,
    onSaveAs: () -> Unit,
    onShare: (ImageEncode.Format) -> Unit,
    onDelete: (alsoGallery: Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val view = LocalView.current
    val available = painting.availability == StudioViewModel.PaintingAvailability.AVAILABLE
    val unavailableReason = when (painting.availability) {
        StudioViewModel.PaintingAvailability.AVAILABLE -> null
        StudioViewModel.PaintingAvailability.NEWER_VERSION ->
            stringResource(R.string.canvas_newer_version)
        StudioViewModel.PaintingAvailability.UNREADABLE ->
            stringResource(R.string.studio_painting_unreadable)
    }
    val title = painting.title?.takeIf { it.isNotBlank() } ?: stringResource(
        if (available) R.string.studio_untitled else R.string.studio_painting_unavailable,
    )
    val cellShape = RoundedCornerShape(CELL_RADIUS_DP.dp)

    val interactionModifier = if (available) {
        Modifier.combinedClickable(
            onClick = onOpen,
            onLongClick = {
                if (hapticsMode == HapticsMode.ENABLED) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                menuOpen = true
            },
        )
    } else {
        Modifier.semantics(mergeDescendants = true) { disabled() }
    }

    Column(modifier = interactionModifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PAINTING_ASPECT)
                .clip(cellShape)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    cellShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            ThumbnailCheckerboard()

            // Checkpoints rewrite thumb.png in place, so path alone is stale.
            val thumbKey = StudioThumbnailKey(
                path = painting.thumbnail?.path,
                revision = painting.updatedAtMillis ?: UNAVAILABLE_THUMBNAIL_REVISION,
            )
            val bitmap by produceState<android.graphics.Bitmap?>(null, thumbKey) {
                value = loadThumbnail(thumbKey)
            }
            val decoded = bitmap
            if (decoded != null) {
                Image(
                    bitmap = decoded.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (!available) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = UNAVAILABLE_OVERLAY_ALPHA,
                            ),
                        ),
                ) {
                    Text(
                        text = stringResource(R.string.studio_painting_unavailable_badge),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.studio_painting_actions),
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (available) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.studio_open)) },
                        onClick = { menuOpen = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.studio_rename)) },
                        onClick = { menuOpen = false; renaming = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.studio_duplicate)) },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.studio_share)) },
                        onClick = { menuOpen = false; sharing = true },
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.studio_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { menuOpen = false; confirmDelete = true },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (unavailableReason != null) {
            Text(
                text = unavailableReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            val updatedAt = painting.updatedAtMillis
            if (updatedAt != null) {
                Text(
                    text = DateUtils.getRelativeTimeSpanString(updatedAt).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        // 08 §2's confirm: no undo-delete exists in v1, which is why the
        // dialog does. The gallery checkbox appears only once a gallery copy
        // can exist (step 4 sets galleryUri); it changes nothing today.
        var deleteGalleryToo by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.studio_delete_title, title)) },
            text = {
                Column {
                    Text(stringResource(R.string.studio_delete_body))
                    if (painting.galleryUri != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = deleteGalleryToo,
                                    role = Role.Checkbox,
                                    onValueChange = { deleteGalleryToo = it },
                                ),
                        ) {
                            Checkbox(
                                checked = deleteGalleryToo,
                                onCheckedChange = null,
                            )
                            Text(
                                stringResource(R.string.studio_delete_gallery_too),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(deleteGalleryToo) }) {
                    Text(
                        stringResource(R.string.studio_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.new_canvas_cancel))
                }
            },
        )
    }

    if (sharing) {
        // §9.5's small export sheet: the format choice the system share
        // sheet cannot offer, plus nothing else. JPEG is matted over white
        // (no alpha in the format).
        AlertDialog(
            onDismissRequest = { sharing = false },
            title = { Text(stringResource(R.string.studio_share)) },
            text = {
                Column {
                    TextButton(onClick = {
                        sharing = false
                        onShare(ImageEncode.Format.PNG)
                    }) { Text(stringResource(R.string.share_as_png)) }
                    TextButton(onClick = {
                        sharing = false
                        onShare(ImageEncode.Format.JPEG)
                    }) { Text(stringResource(R.string.share_as_jpeg)) }
                    // §9.5's "Save as…": a new gallery item, not the mirror.
                    TextButton(onClick = {
                        sharing = false
                        onSaveAs()
                    }) { Text(stringResource(R.string.save_to_gallery)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { sharing = false }) {
                    Text(stringResource(R.string.new_canvas_cancel))
                }
            },
        )
    }

    if (renaming) {
        var text by remember { mutableStateOf(painting.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.studio_rename)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { renaming = false; onRename(text) },
                    enabled = text.isNotBlank(),
                ) { Text(stringResource(R.string.studio_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) {
                    Text(stringResource(R.string.new_canvas_cancel))
                }
            },
        )
    }
}

@Composable
private fun ThumbnailCheckerboard() {
    val checkerA = MaterialTheme.colorScheme.surface
    val checkerB = MaterialTheme.colorScheme.surfaceVariant

    Canvas(Modifier.fillMaxSize()) {
        drawRect(checkerA)
        if (size.width <= 0f) return@Canvas

        val cell = size.width / THUMBNAIL_CHECKER_COLUMNS
        val rows = ceil(size.height / cell).toInt()
        for (y in 0 until rows) {
            for (x in 0 until THUMBNAIL_CHECKER_COLUMNS) {
                if ((x + y) % 2 == 0) continue

                drawRect(
                    color = checkerB,
                    topLeft = Offset(x * cell, y * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

private const val NEW_PAINTING_KEY = "new-painting"
private const val EMPTY_PAINTING_KEY = "empty-state"
private const val THUMBNAIL_CHECKER_COLUMNS = 12
private const val PAINTING_ASPECT = 4f / 3f
private const val CELL_RADIUS_DP = 4
private const val UNAVAILABLE_THUMBNAIL_REVISION = 0L
private const val UNAVAILABLE_OVERLAY_ALPHA = 0.9f
