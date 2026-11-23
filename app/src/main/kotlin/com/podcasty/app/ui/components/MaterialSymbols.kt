/*
 * Podcasty — Android Podcast Player
 * Adapted from Airmedy (https://github.com/misa198/airmedy), licensed under GNU GPL v3.0.
 */
@file:OptIn(ExperimentalTextApi::class)

package com.podcasty.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.podcasty.app.R
import com.podcasty.app.ui.theme.LocalPodcastyColors

val MaterialSymbolsFilledFontFamily = FontFamily(
    Font(
        resId = R.font.material_symbols_rounded,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("FILL", 1f),
        ),
    ),
)

val MaterialSymbolsOutlinedFontFamily = FontFamily(
    Font(
        resId = R.font.material_symbols_rounded,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("FILL", 0f),
        ),
    ),
)

object MaterialSymbols {
    const val Home = "home"
    const val Library = "podcasts"
    const val Podcasts = "podcasts"
    const val Headphones = "headphones"
    const val GraphicEq = "graphic_eq"
    const val BarChart = "bar_chart"
    const val LegendToggle = "legend_toggle"
    const val Search = "search"
    const val Settings = "settings"
    const val Schedule = "schedule"
    const val History = "history"
    const val PlayCircle = "play_circle"
    const val QueueMusic = "queue_music"
    const val Sensors = "sensors"
    const val PlaylistAdd = "playlist_add"
    const val PlaylistRemove = "playlist_remove"
    const val QueuePlayNext = "queue_play_next"
    const val AddToQueue = "add_to_queue"
    const val RemoveFromQueue = "remove_from_queue"
    const val Tune = "tune"
    const val Subwoofer = "subwoofer"
    const val ChevronRight = "chevron_right"
    const val ChevronLeft = "chevron_left"
    const val UnfoldMore = "unfold_more"
    const val Check = "check"
    const val CheckCircle = "check_circle"
    const val Close = "close"
    const val Bolt = "bolt"
    const val Menu = "menu"
    const val MoreVert = "more_vert"
    const val ArrowUpward = "arrow_upward"
    const val ArrowDownward = "arrow_downward"
    const val SkipPrevious = "skip_previous"
    const val PlayArrow = "play_arrow"
    const val Pause = "pause"
    const val SkipNext = "skip_next"
    const val Replay10 = "replay_10"
    const val Forward30 = "forward_30"
    const val Speed = "speed"
    const val Bedtime = "bedtime"
    const val Timer = "timer"
    const val Shuffle = "shuffle"
    const val Repeat = "repeat"
    const val RepeatOne = "repeat_one"
    const val Reorder = "reorder"
    const val Mic = "mic"
    const val Chat = "chat"
    const val Airplay = "airplay"
    const val VolumeDown = "volume_down"
    const val VolumeUp = "volume_up"
    const val People = "group"
    const val Person = "person"
    const val Label = "label"
    const val Palette = "palette"
    const val Refresh = "refresh"
    const val Info = "info"
    const val Add = "add"
    const val Image = "image"
    const val Star = "star"
    val StarMinus = "star_minus" // Composed below; exclude from const glyph subset.
    const val FilterList = "filter_list"
    const val GridView = "grid_view"
    const val ViewList = "view_list"
    const val Edit = "edit"
    const val Delete = "delete"
    const val Download = "download"
    const val DownloadDone = "download_done"
    const val Storage = "storage"
    const val Notifications = "notifications"
    const val Album = "album"
    const val MusicNote = "music_note"
    const val Share = "share"
    const val Lyrics = "lyrics"
    const val FolderOpen = "folder_open"
    const val ContentCopy = "content_copy"
    const val DriveFileMove = "drive_file_move"
    const val PlaylistPlay = "playlist_play"
    const val SelectAll = "select_all"
    const val VisibilityOff = "visibility_off"
    val Folder = "folder" // Custom drawn below; excluded from const glyph subset.
}

@Composable
fun MaterialSymbol(
    symbol: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalPodcastyColors.current.textMain,
    filled: Boolean = false,
) {
    if (isCustomVectorSymbol(symbol)) {
        CustomMaterialSymbolVector(
            symbol = symbol,
            contentDescription = contentDescription,
            modifier = modifier,
            size = size,
            tint = tint,
            filled = filled,
        )
        return
    }
    Text(
        text = if (symbol == MaterialSymbols.StarMinus) MaterialSymbols.Star else symbol,
        fontFamily = if (filled) MaterialSymbolsFilledFontFamily else MaterialSymbolsOutlinedFontFamily,
        style = TextStyle(
            fontSize = size.value.sp,
            color = tint,
            textAlign = TextAlign.Center,
        ),
        modifier = modifier.then(
            if (symbol == MaterialSymbols.StarMinus) {
                Modifier.drawWithContent {
                    clipRect(
                        left = this.size.width * 0.55f,
                        top = this.size.height * 0.55f,
                        clipOp = ClipOp.Difference,
                    ) {
                        this@drawWithContent.drawContent()
                    }
                    drawLine(
                        color = tint,
                        start = Offset(this.size.width * 0.64f, this.size.height * 0.75f),
                        end = Offset(this.size.width * 0.94f, this.size.height * 0.75f),
                        strokeWidth = size.toPx() / 12f,
                        cap = StrokeCap.Round,
                    )
                }
            } else {
                Modifier
            },
        ).then(
            if (contentDescription != null) {
                Modifier.semantics { this.contentDescription = contentDescription }
            } else {
                Modifier
            },
        ),
    )
}

private fun isCustomVectorSymbol(symbol: String): Boolean = when (symbol) {
    MaterialSymbols.Folder,
    MaterialSymbols.FolderOpen,
    MaterialSymbols.Lyrics,
    MaterialSymbols.DriveFileMove,
    MaterialSymbols.ContentCopy,
    MaterialSymbols.Share,
    MaterialSymbols.Album,
    MaterialSymbols.MusicNote,
    MaterialSymbols.PlaylistPlay,
    MaterialSymbols.SelectAll,
    MaterialSymbols.VisibilityOff,
    -> true
    else -> false
}

/**
 * Vector fallback for Material Symbols whose ligatures are not present in the subsetted
 * `material_symbols_rounded.ttf` font, preventing raw ASCII fallback text overflow.
 */
@Composable
private fun CustomMaterialSymbolVector(
    symbol: String,
    contentDescription: String?,
    modifier: Modifier,
    size: Dp,
    tint: Color,
    filled: Boolean,
) {
    val semMod = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.size(size).then(semMod)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val thinStroke = Stroke(width = w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (symbol) {
            MaterialSymbols.Folder -> {
                val path = folderOutlinePath(w, h)
                if (filled) drawPath(path, tint) else drawPath(path, tint, style = stroke)
            }

            MaterialSymbols.FolderOpen -> {
                val back = Path().apply {
                    moveTo(w * 0.12f, h * 0.78f)
                    lineTo(w * 0.12f, h * 0.24f)
                    lineTo(w * 0.42f, h * 0.24f)
                    lineTo(w * 0.50f, h * 0.34f)
                    lineTo(w * 0.84f, h * 0.34f)
                    lineTo(w * 0.84f, h * 0.46f)
                }
                val front = Path().apply {
                    moveTo(w * 0.12f, h * 0.78f)
                    lineTo(w * 0.24f, h * 0.48f)
                    lineTo(w * 0.90f, h * 0.48f)
                    lineTo(w * 0.78f, h * 0.78f)
                    close()
                }
                drawPath(back, tint, style = stroke)
                if (filled) drawPath(front, tint) else drawPath(front, tint, style = stroke)
            }

            MaterialSymbols.DriveFileMove -> {
                drawPath(folderOutlinePath(w, h), tint, style = stroke)
                // Right arrow inside folder
                drawLine(tint, Offset(w * 0.34f, h * 0.56f), Offset(w * 0.66f, h * 0.56f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.55f, h * 0.45f)
                    lineTo(w * 0.67f, h * 0.56f)
                    lineTo(w * 0.55f, h * 0.67f)
                }
                drawPath(head, tint, style = thinStroke)
            }

            MaterialSymbols.ContentCopy -> {
                // Back sheet
                val back = Path().apply {
                    moveTo(w * 0.22f, h * 0.68f)
                    lineTo(w * 0.22f, h * 0.16f)
                    lineTo(w * 0.64f, h * 0.16f)
                }
                drawPath(back, tint, style = stroke)
                // Front sheet
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.36f, h * 0.30f),
                    size = Size(w * 0.46f, h * 0.54f),
                    cornerRadius = CornerRadius(w * 0.07f, w * 0.07f),
                    style = stroke,
                )
            }

            MaterialSymbols.Share -> {
                val r = w * 0.105f
                val left = Offset(w * 0.24f, h * 0.50f)
                val topRight = Offset(w * 0.74f, h * 0.24f)
                val bottomRight = Offset(w * 0.74f, h * 0.76f)
                drawLine(tint, left, topRight, strokeWidth = w * 0.08f, cap = StrokeCap.Round)
                drawLine(tint, left, bottomRight, strokeWidth = w * 0.08f, cap = StrokeCap.Round)
                drawCircle(tint, radius = r, center = left)
                drawCircle(tint, radius = r, center = topRight)
                drawCircle(tint, radius = r, center = bottomRight)
            }

            MaterialSymbols.Lyrics -> {
                // Speech/lyrics bubble with horizontal text lines + small note accent
                val bubble = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = w * 0.12f,
                            top = h * 0.16f,
                            right = w * 0.76f,
                            bottom = h * 0.68f,
                            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                        ),
                    )
                }
                drawPath(bubble, tint, style = stroke)
                // Bubble tail
                val tail = Path().apply {
                    moveTo(w * 0.24f, h * 0.68f)
                    lineTo(w * 0.16f, h * 0.84f)
                    lineTo(w * 0.38f, h * 0.68f)
                }
                drawPath(tail, tint, style = thinStroke)
                // Text lines inside
                drawLine(tint, Offset(w * 0.26f, h * 0.36f), Offset(w * 0.60f, h * 0.36f), strokeWidth = w * 0.075f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.26f, h * 0.52f), Offset(w * 0.52f, h * 0.52f), strokeWidth = w * 0.075f, cap = StrokeCap.Round)
                // Small musical note on right
                drawLine(tint, Offset(w * 0.86f, h * 0.32f), Offset(w * 0.86f, h * 0.66f), strokeWidth = w * 0.075f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.86f, h * 0.32f), Offset(w * 0.94f, h * 0.36f), strokeWidth = w * 0.075f, cap = StrokeCap.Round)
                drawCircle(tint, radius = w * 0.075f, center = Offset(w * 0.80f, h * 0.68f))
            }

            MaterialSymbols.Album -> {
                val center = Offset(w * 0.5f, h * 0.5f)
                drawCircle(tint, radius = w * 0.38f, center = center, style = stroke)
                drawCircle(tint, radius = w * 0.14f, center = center, style = thinStroke)
                drawCircle(tint, radius = w * 0.045f, center = center)
            }

            MaterialSymbols.MusicNote -> {
                val stemX = w * 0.54f
                drawLine(tint, Offset(stemX, h * 0.20f), Offset(stemX, h * 0.68f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
                drawLine(tint, Offset(stemX, h * 0.20f), Offset(w * 0.78f, h * 0.20f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
                drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.40f, h * 0.70f))
            }

            MaterialSymbols.PlaylistPlay -> {
                drawLine(tint, Offset(w * 0.14f, h * 0.28f), Offset(w * 0.70f, h * 0.28f), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.14f, h * 0.48f), Offset(w * 0.70f, h * 0.48f), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.14f, h * 0.68f), Offset(w * 0.50f, h * 0.68f), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
                val play = Path().apply {
                    moveTo(w * 0.64f, h * 0.56f)
                    lineTo(w * 0.88f, h * 0.70f)
                    lineTo(w * 0.64f, h * 0.84f)
                    close()
                }
                drawPath(play, tint)
            }

            MaterialSymbols.SelectAll -> {
                // Dashed outer frame + solid inner square
                val corners = listOf(
                    Offset(w * 0.16f, h * 0.16f) to Offset(w * 0.30f, h * 0.16f),
                    Offset(w * 0.16f, h * 0.16f) to Offset(w * 0.16f, h * 0.30f),
                    Offset(w * 0.70f, h * 0.16f) to Offset(w * 0.84f, h * 0.16f),
                    Offset(w * 0.84f, h * 0.16f) to Offset(w * 0.84f, h * 0.30f),
                    Offset(w * 0.16f, h * 0.70f) to Offset(w * 0.16f, h * 0.84f),
                    Offset(w * 0.16f, h * 0.84f) to Offset(w * 0.30f, h * 0.84f),
                    Offset(w * 0.84f, h * 0.70f) to Offset(w * 0.84f, h * 0.84f),
                    Offset(w * 0.70f, h * 0.84f) to Offset(w * 0.84f, h * 0.84f),
                )
                corners.forEach { (a, b) -> drawLine(tint, a, b, strokeWidth = w * 0.08f, cap = StrokeCap.Round) }
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.34f, h * 0.34f),
                    size = Size(w * 0.32f, h * 0.32f),
                    cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
                    style = stroke,
                )
            }

            MaterialSymbols.VisibilityOff -> {
                val eye = Path().apply {
                    moveTo(w * 0.12f, h * 0.50f)
                    quadraticTo(w * 0.50f, h * 0.18f, w * 0.88f, h * 0.50f)
                    quadraticTo(w * 0.50f, h * 0.82f, w * 0.12f, h * 0.50f)
                    close()
                }
                drawPath(eye, tint, style = thinStroke)
                drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.50f, h * 0.50f), style = thinStroke)
                drawLine(tint, Offset(w * 0.18f, h * 0.18f), Offset(w * 0.82f, h * 0.82f), strokeWidth = w * 0.085f, cap = StrokeCap.Round)
            }
        }
    }
}

private fun folderOutlinePath(w: Float, h: Float): Path {
    val left = w * 0.10f
    val right = w * 0.90f
    val top = h * 0.22f
    val tabBottom = h * 0.34f
    val bottom = h * 0.80f
    val r = w * 0.08f
    return Path().apply {
        moveTo(left + r, top)
        lineTo(w * 0.40f, top)
        lineTo(w * 0.48f, tabBottom)
        lineTo(right - r, tabBottom)
        quadraticTo(right, tabBottom, right, tabBottom + r)
        lineTo(right, bottom - r)
        quadraticTo(right, bottom, right - r, bottom)
        lineTo(left + r, bottom)
        quadraticTo(left, bottom, left, bottom - r)
        lineTo(left, top + r)
        quadraticTo(left, top, left + r, top)
        close()
    }
}
