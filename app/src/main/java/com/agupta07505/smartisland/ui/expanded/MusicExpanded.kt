/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui.expanded

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material3.Icon
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import com.agupta07505.smartisland.ui.IslandText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.data.SmartIslandSettings
import com.agupta07505.smartisland.di.SmartIslandRepositories
import com.agupta07505.smartisland.model.IslandNotification
import com.agupta07505.smartisland.ui.LocalCardShown
import com.agupta07505.smartisland.ui.bounceClick
import com.agupta07505.smartisland.ui.WavyMusicSeekBar
import com.agupta07505.smartisland.util.MediaArt
import com.agupta07505.smartisland.util.runCatchingLogged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun MusicExpanded(
    notification: IslandNotification?,
    bottomPadding: Dp,
    settings: SmartIslandSettings = SmartIslandSettings.Default,
    // The landscape split dips the card's FOREGROUND through its re-wrap; the artwork and its scrim stay at full strength. Null = no dip.
    foregroundAlpha: (() -> Float)? = null
) {
    val context = LocalContext.current
    val positionMs = notification?.mediaPositionMs
    val durationMs = notification?.mediaDurationMs
    
    val controller = remember(notification?.mediaToken) {
        notification?.mediaToken?.let { token ->
            runCatchingLogged("MusicExpanded", "Failed to create MediaController") {
                android.media.session.MediaController(context, token)
            }
        }
    }

    // The playhead is a State that is only read while drawing (the seek bar) or by two small texts, so a tick does
    // not recompose the whole card (artwork, blur, buttons).
    var playbackState by remember(controller) { mutableStateOf(controller?.playbackState) }
    val playbackStateNow = rememberUpdatedState(playbackState)
    val getEstimatedPosition = remember {
        { fallbackPos: Long? ->
            val state = playbackStateNow.value
            if (state != null && state.state == android.media.session.PlaybackState.STATE_PLAYING) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                (state.position + (elapsed * state.playbackSpeed).toLong()).coerceAtLeast(0L)
            } else {
                state?.position ?: fallbackPos ?: 0L
            }
        }
    }

    val lastSeekTimeMs = remember { longArrayOf(0L) }

    var localIsPlaying by remember(notification?.key, notification?.mediaIsPlaying) {
        mutableStateOf(notification?.mediaIsPlaying == true)
    }

    val livePosition = remember(positionMs, controller) { mutableLongStateOf(getEstimatedPosition(positionMs)) }

    // A pre-composed card that waits collapsed (LocalCardShown = false) ticks slowly: nothing is drawn, and the position is only
    // a few hundred ms stale when the card opens, which cannot be seen.
    val cardShown by rememberUpdatedState(LocalCardShown.current)
    if (localIsPlaying) {
        LaunchedEffect(controller, positionMs) {
            while (true) {
                if (System.currentTimeMillis() - lastSeekTimeMs[0] > 1500L) {
                    livePosition.longValue = getEstimatedPosition(positionMs)
                }
                kotlinx.coroutines.delay(if (cardShown) 33 else WAITING_CARD_TICK_MS)
            }
        }
    }

    val progressNow: () -> Float = {
        when {
            durationMs != null && durationMs > 0 ->
                (livePosition.longValue.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            notification?.progressMax?.let { it > 0 } == true ->
                (notification.progress.toFloat() / notification.progressMax.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    // The buttons come from what the app's media session offers (prev / next only when supported, plus its own custom
    // buttons), not a fixed set.
    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose {}
        val callback = object : android.media.session.MediaController.Callback() {
            override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                playbackState = state
            }
        }
        playbackState = controller.playbackState
        controller.registerCallback(callback)
        onDispose { controller.unregisterCallback(callback) }
    }
    val outputScope = androidx.compose.runtime.rememberCoroutineScope()
    val controls = remember(playbackState, notification?.actionIntents) {
        MediaControls.resolve(playbackState, notification)
    }

    // The artwork, its colour and the explicit flag come from [MediaArt]: read and reduced (and blurred, when the
    // media blur is on) off the main thread, usually already while the pill was collapsed.
    val mediaSettings = remember(settings) { settings.forMedia() }
    val blurRadiusDp = if (mediaSettings.expandedBlurEnabled) mediaSettings.expandedBlurRadius else null
    val artKey = MediaArt.keyFor(notification, blurRadiusDp)
    // A track change posts the title first and the session's picture a moment later, so the key above (title and
    // artist) can stay the same while the picture arrives: the notification's own large icon is a second key (it
    // changes when the picture is read), and while only a fallback picture (the app's icon, the 128 px one) is shown
    // the session is read again a few times.
    val art by androidx.compose.runtime.produceState(
        initialValue = MediaArt.cached(artKey) ?: MediaArt.quick(notification, blurRadiusDp),
        artKey,
        notification?.largeIcon
    ) {
        val n = notification ?: return@produceState
        if (n.mediaToken == null) return@produceState
        MediaArt.cached(artKey)?.let { value = it; return@produceState }
        // The app's icon is only a stand-in: while a real cover was shown before, it stays on screen until the new
        // one is read (the card does not flip to the app icon, drawn enlarged as the backdrop, on every track
        // change).
        val hadCover = MediaArt.lastGood(n.key, blurRadiusDp) != null
        var loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            MediaArt.load(context.applicationContext, n, blurRadiusDp)
        }
        if (!loaded.isFallbackIcon || !hadCover) value = loaded
        for (waitMs in MEDIA_ART_RETRY_MS) {
            if (loaded.fromSession) break
            kotlinx.coroutines.delay(waitMs)
            loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                MediaArt.load(context.applicationContext, n, blurRadiusDp)
            }
            if (loaded.fromSession || !loaded.isFallbackIcon) value = loaded
        }
        // The retries are over and there is still no picture: this track has none, so the stand-in is the honest answer.
        if (loaded.isFallbackIcon) value = loaded
    }
    val artworkImage = art.image
    val artwork = if (art.hasArtwork) art else null
    // Artwork behind the card keeps its own look: the same black dim and white text as on the dark island, whatever the island's theme.
    // Only a card with no artwork backdrop follows the light / dark palette.
    val palette = IslandPalette.Dark

    androidx.compose.runtime.CompositionLocalProvider(LocalIslandPalette provides palette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        // Plain black card, no artwork backdrop: the visualizer is the only decoration.
        Box(Modifier.matchParentSize().background(Color.Black))

        // Behind the buttons, in front of the artwork backdrop.
        MediaVisualizerBackground(
            isPlaying = localIsPlaying,
            color = mediaVisualizerColor(settings, art.tint),
            modifier = Modifier.matchParentSize()
        )

        val resetTimer = { SmartIslandRepositories.notificationRepository(context).resetTimer() }
        val artworkTint = art.tint
        val shadeColors = remember(artworkTint, artwork != null, settings.musicVisualizerColor, palette.isLight) {
            mediaShadeColors(settings, artworkTint, artwork != null, palette.isLight)
        }
        val isExplicit = art.explicit
        val launcherIcon = remember(notification?.icon) { notification?.icon?.asImageBitmap() }
        val smallIcon = remember(notification?.smallIcon) { notification?.smallIcon?.asImageBitmap() }
        // No labels for streams without a length: the bar cannot seek there either.
        val hasDuration = durationMs != null && durationMs > 0

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .then(
                    if (foregroundAlpha != null) {
                        Modifier.graphicsLayer { alpha = foregroundAlpha().coerceIn(0f, 1f) }
                    } else Modifier
                )
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomPadding)
        ) {
            // Thumbnail + title / artist.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The notification's own picture is never blurred, unlike the backdrop image.
                val thumb = remember(notification?.largeIcon) { notification?.largeIcon?.asImageBitmap() } ?: artworkImage
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.text.copy(alpha = 0.12f))
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        notification?.title?.takeIf { it.isNotBlank() } ?: "Song",
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isExplicit) ExplicitBadge(palette.textSecondary)
                        Text(
                            notification?.text?.takeIf { it.isNotBlank() } ?: notification?.appName ?: "Artist",
                            color = palette.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp
                        )
                    }
                }
                // Output switch: opens the playing app's output picker.
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .bounceClick {
                            resetTimer()
                            notification?.packageName?.let { pkg ->
                                outputScope.launch {
                                    com.agupta07505.smartisland.util.MediaOutputSwitcher.open(context.applicationContext, pkg)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Podcasts, "Switch output", tint = palette.textSecondary, modifier = Modifier.size(22.dp))
                }
            }
            // elapsed - thin bar - total. No labels for streams without a length: the bar cannot seek there either.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasDuration) MusicPositionText(livePosition)
                WavyMusicSeekBar(
                    progress = progressNow,
                    isPlaying = localIsPlaying,
                    onSeek = { newProgress ->
                        if (durationMs != null && durationMs > 0) {
                            val newPosition = (newProgress * durationMs).toLong()
                            livePosition.longValue = newPosition
                            lastSeekTimeMs[0] = System.currentTimeMillis()
                            resetTimer()
                            if (controller != null) {
                                runCatchingLogged("MusicExpanded", "Failed to seekTo position") {
                                    controller.transportControls.seekTo(newPosition)
                                }
                            } else {
                                notification?.packageName?.let { pkg ->
                                    SmartIslandRepositories.notificationRepository(context).sendCommand(
                                        com.agupta07505.smartisland.data.SmartIslandCommand.SeekTo(pkg, newPosition)
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    trackColor = palette.text.copy(alpha = 0.3f),
                    waveColor = palette.text,
                    thumbColor = palette.text,
                    // Flat thin line with a small dot, no wave.
                    waveHeight = 0.dp,
                    trackThickness = 3.dp,
                    thumbRadius = 4.dp,
                )
                if (hasDuration) {
                    Text(
                        formatDuration(durationMs),
                        color = palette.text.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        lineHeight = 12.sp
                    )
                }
            }
            // the app's own buttons (left end) | rewind, previous, play / pause, next, fast-forward | the app's own buttons (right end). Was: app badge + the app's own buttons | previous, play / pause, next | the app's own buttons. Equal weights keep the transport centred.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    controls.leftCustom.forEach { custom ->
                        CustomControlButton(custom, controller, notification, 40.dp, resetTimer)
                    }
                }
                controls.beforePrevious.forEach { custom ->
                    CustomControlButton(custom, controller, notification, 40.dp, resetTimer)
                }
                if (controls.hasPrevious) {
                    MediaShadeIconButton(Icons.Rounded.SkipPrevious, "Previous", onClick = {
                        resetTimer()
                        if (controller != null) {
                            runCatchingLogged("MusicExpanded", "Failed to skipToPrevious") {
                                controller.transportControls.skipToPrevious()
                            }
                        } else {
                            notification.sendFirstAction(context, "previous", "prev", "rewind")
                        }
                    })
                }
                if (controls.hasPlayPause) {
                    MediaShadeIconButton(
                        if (localIsPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (localIsPlaying) "Pause" else "Play",
                        onClick = {
                            resetTimer()
                            val targetState = !localIsPlaying
                            localIsPlaying = targetState
                            if (controller != null) {
                                runCatchingLogged("MusicExpanded", "Failed to play/pause") {
                                    if (targetState) controller.transportControls.play()
                                    else controller.transportControls.pause()
                                }
                            } else {
                                notification.sendFirstAction(context, "play", "pause", "resume")
                            }
                        }
                    )
                }
                if (controls.hasNext) {
                    MediaShadeIconButton(Icons.Rounded.SkipNext, "Next", onClick = {
                        resetTimer()
                        if (controller != null) {
                            runCatchingLogged("MusicExpanded", "Failed to skipToNext") {
                                controller.transportControls.skipToNext()
                            }
                        } else {
                            notification.sendFirstAction(context, "next", "skip", "forward")
                        }
                    })
                }
                controls.afterNext.forEach { custom ->
                    CustomControlButton(custom, controller, notification, 40.dp, resetTimer)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    controls.rightCustom.forEach { custom ->
                        CustomControlButton(custom, controller, notification, 40.dp, resetTimer)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun MusicPositionText(position: androidx.compose.runtime.LongState) {
    val seconds by remember(position) { derivedStateOf { position.longValue / 1000L } }
    Text(
        formatDuration(seconds * 1000L),
        color = LocalIslandPalette.current.text.copy(alpha = 0.75f),
        fontSize = 10.sp,
        lineHeight = 12.sp
    )
}

private val MEDIA_ART_RETRY_MS = longArrayOf(400L, 1_200L, 3_000L)

private const val WAITING_CARD_TICK_MS = 250L
