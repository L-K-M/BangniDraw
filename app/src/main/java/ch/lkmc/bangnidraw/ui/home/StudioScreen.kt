package ch.lkmc.bangnidraw.ui.home

import android.content.Intent
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.bangnidraw.BuildConfig
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.data.ImageEncode

/**
 * The Studio: the shelf of paintings, newest first, and the way to start a
 * new one (PLAN.md §5; `docs/plan/08-ui-and-layout.md` §2). Roadmap 3c: the
 * shelf is real — a grid of thumbnails with the hold menu (delete with
 * confirm, rename, and — since step 4 — duplicate; share stays a stub until
 * ShareCache lands), the storage readout, and the New Canvas dialog.
 *
 * Two 08 §2 refinements deliberately wait: the width-class `gridMinCell`
 * table (one adaptive minimum serves every class until `LayoutSpec` lands
 * with the panels in step 6+) and the "+ as first tile" on wide screens (the
 * FAB opens the same dialog everywhere).
 */
@Composable
fun StudioScreen(
    onOpenPainting: (String) -> Unit,
    viewModel: StudioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showNewCanvas by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // Re-list on every return to the foreground of this screen — coming back
    // from the Canvas is the case that matters: its leave checkpoint has
    // finished by the time navigation pops (CanvasViewModel.leave), so the
    // shelf lists the write, not the state before it.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewCanvas = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.studio_new)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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
                IconButton(onClick = { showAbout = true }) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.studio_about),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.loaded && state.paintings.isNotEmpty()) {
                // 08 §2's readout: it answers the only question that ever
                // justifies deleting.
                Text(
                    text = stringResource(
                        R.string.studio_storage,
                        state.paintings.size,
                        Formatter.formatShortFileSize(context, state.totalBytes),
                        Formatter.formatShortFileSize(context, state.freeBytes),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (state.loaded && state.paintings.isEmpty()) {
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
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(GRID_MIN_CELL_DP.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(state.paintings, key = { it.id }) { painting ->
                        PaintingCell(
                            painting = painting,
                            onOpen = { onOpenPainting(painting.id) },
                            onRename = { title -> viewModel.rename(painting.id, title) },
                            onDuplicate = { viewModel.duplicate(painting.id) },
                            onSaveAs = {
                                viewModel.saveAsNewGalleryItem(painting.id) { ok ->
                                    Toast.makeText(
                                        context,
                                        if (ok) R.string.studio_saved_to_gallery
                                        else R.string.studio_save_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onShare = { format ->
                                viewModel.share(painting.id, format) { uri, mime ->
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = mime
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(send, painting.title),
                                    )
                                }
                            },
                            onDelete = { alsoGallery ->
                                viewModel.delete(painting.id, alsoGallery, painting.galleryUri)
                                Toast.makeText(
                                    context, R.string.studio_deleted, Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }
            }
        }
    }

    if (showNewCanvas) {
        NewCanvasDialog(
            budget = viewModel.budget,
            onDismiss = { showNewCanvas = false },
            onCreate = { size, paper ->
                showNewCanvas = false
                viewModel.createPainting(size, paper) { id -> onOpenPainting(id) }
            },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_title, BuildConfig.VERSION_NAME)) },
            text = { Text(stringResource(R.string.about_body)) },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.about_close))
                }
            },
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
    val title = painting.title.ifEmpty { stringResource(R.string.studio_untitled) }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(4.dp),
                )
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        menuOpen = true
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Decoded off the main thread, keyed by the file: a checkpoint
            // rewrites thumb.png in place, but the shelf re-lists (and this
            // recomposes) on every resume, so the stale-bitmap window is one
            // screen visit at most.
            val thumbFile = painting.thumbnail
            val bitmap by produceState<android.graphics.Bitmap?>(null, thumbFile) {
                value = thumbFile?.let { file ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        BitmapFactory.decodeFile(file.path)
                    }
                }
            }
            val decoded = bitmap
            if (decoded != null) {
                Image(
                    bitmap = decoded.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
        Text(
            text = DateUtils.getRelativeTimeSpanString(painting.updatedAtMillis).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = deleteGalleryToo,
                                onCheckedChange = { deleteGalleryToo = it },
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
        var text by remember { mutableStateOf(painting.title) }
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

/**
 * 08 §2 names 150/180/220 dp per width class; one adaptive minimum stands in
 * until the width-class `LayoutSpec` lands (see the screen KDoc).
 */
private const val GRID_MIN_CELL_DP = 150
