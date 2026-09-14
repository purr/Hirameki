/*
 * Copyright (c) 2026 Hirameki contributors
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.reviewer.compose

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import anki.scheduler.CardAnswer
import com.ichi2.anki.R
import com.ichi2.anki.reviewer.ReviewerJavascriptCommand
import com.ichi2.anki.ui.compose.theme.LocalAnkiColors
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

private val CardInset = 14.dp
private val WellLabelInset = 14.dp
private val CardCornerRadius = 26.dp
private val RestElevation = 2.dp
private val DragElevation = 14.dp
private const val CAMERA_DISTANCE_DP = 14f
private const val HALF_TURN = 90f
private const val FULL_TURN = 180f
private const val MAGNET_CURVE = 1.6f
private const val WELL_FRACTION = 0.62f
private const val FADE_STARTS_AT = 0.9f
private const val TINT_ALPHA = 0.16f
private const val ENTER_FROM_SCALE = 0.94f

/** What the card is doing right now. */
private enum class CardPhase { Idle, Drag, Flight }

/**
 * A corner the card can be dropped into, and the rating that records.
 *
 * Left is negative and right is positive; the two most-used ratings sit along the bottom, where a
 * thumb already rests.
 */
private enum class GradeCorner(
    val rating: CardAnswer.Rating,
    val alignment: Alignment,
    val labelRes: Int,
    val towardsStart: Boolean,
    val towardsTop: Boolean,
) {
    AGAIN(CardAnswer.Rating.AGAIN, Alignment.BottomStart, R.string.ease_button_again, true, false),
    HARD(CardAnswer.Rating.HARD, Alignment.TopStart, R.string.ease_button_hard, true, true),
    GOOD(CardAnswer.Rating.GOOD, Alignment.BottomEnd, R.string.ease_button_good, false, false),
    EASY(CardAnswer.Rating.EASY, Alignment.TopEnd, R.string.ease_button_easy, false, true),
}

/**
 * The reviewer's card as a card: tap to turn it over, then drag it into a corner to answer.
 *
 * Both sides fill the same fixed rectangle so nothing reflows when the answer appears, and grading is
 * the drag itself, which is why this replaces [AnswerButtons] rather than sitting beside it. Dragging
 * only arms once the answer is shown, matching Anki's rule that you rate after looking.
 *
 * Motion lives entirely in [spec]; this function only applies it.
 */
@Composable
fun DraggableFlashcard(
    baseUrl: String,
    questionHtml: String,
    answerHtml: String,
    bodyClass: String,
    isMediaAutoplayEnabled: Boolean,
    javascriptCommand: ReviewerJavascriptCommand?,
    onJavascriptCommandConsumed: (Int) -> Unit,
    onLinkClick: (String) -> Unit,
    isAnswerShown: Boolean,
    tapToFlip: Boolean,
    dragToGrade: Boolean,
    onShowAnswer: () -> Unit,
    onRateCard: (CardAnswer.Rating) -> Unit,
    modifier: Modifier = Modifier,
    spec: CardMotionSpec = CardMotionSpec.Default,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val ratingColors = LocalAnkiColors.current.ratings

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var rawOffset by remember { mutableStateOf(Offset.Zero) }
    var cardOffset by remember { mutableStateOf(Offset.Zero) }
    var activeCorner by remember { mutableStateOf<GradeCorner?>(null) }
    var phase by remember { mutableStateOf(CardPhase.Idle) }
    var throwiness by remember { mutableFloatStateOf(0f) }
    var lastQuestionHtml by remember { mutableStateOf(questionHtml) }

    // a flight is the arc into a corner, or the drift back to the middle
    val flight = remember { Animatable(0f) }
    var flightFrom by remember { mutableStateOf(Offset.Zero) }
    var flightVia by remember { mutableStateOf(Offset.Zero) }
    var flightTo by remember { mutableStateOf(Offset.Zero) }

    val flip = remember { Animatable(0f) }
    val entrance = remember { Animatable(1f) }

    /** Mirror the geometry in RTL so "start" corners stay under the labels that name them. */
    fun anchorFor(corner: GradeCorner): Offset {
        if (containerSize == IntSize.Zero) return Offset.Zero
        val inset = min(containerSize.width, containerSize.height) * spec.cornerInset
        val leftwards = corner.towardsStart != isRtl
        return Offset(
            x = if (leftwards) inset - containerSize.width / 2f else containerSize.width / 2f - inset,
            y = if (corner.towardsTop) inset - containerSize.height / 2f else containerSize.height / 2f - inset,
        )
    }

    /** How far along the line to [corner] the card is: 0 at rest, 1 at the corner, never more. */
    fun journeyOf(
        corner: GradeCorner,
        offset: Offset,
    ): Float {
        val anchor = anchorFor(corner)
        val lengthSquared = anchor.x * anchor.x + anchor.y * anchor.y
        if (lengthSquared <= 0f) return 0f
        // projected onto the line, so shoving the card past the corner stays at 1 instead of unwinding
        return ((offset.x * anchor.x + offset.y * anchor.y) / lengthSquared).coerceIn(0f, 1f)
    }

    fun cornerUnder(offset: Offset): GradeCorner? {
        if (containerSize == IntSize.Zero) return null
        val dead = min(containerSize.width, containerSize.height) * spec.deadZone
        if (offset.getDistance() < dead) return null
        val leftwards = offset.x < 0f
        val towardsStart = leftwards != isRtl
        val towardsTop = offset.y < 0f
        return GradeCorner.entries.first { it.towardsStart == towardsStart && it.towardsTop == towardsTop }
    }

    /** The corner takes the card off the finger as it closes in. */
    fun magnetised(
        raw: Offset,
        corner: GradeCorner?,
    ): Offset {
        if (corner == null || spec.cornerPull <= 0f) return raw
        val anchor = anchorFor(corner)
        val grip = journeyOf(corner, raw).pow(MAGNET_CURVE) * spec.cornerPull
        return Offset(raw.x + (anchor.x - raw.x) * grip, raw.y + (anchor.y - raw.y) * grip)
    }

    fun currentOffset(): Offset =
        if (phase == CardPhase.Flight) {
            val t = flight.value
            val mt = 1f - t
            Offset(
                x = mt * mt * flightFrom.x + 2f * mt * t * flightVia.x + t * t * flightTo.x,
                y = mt * mt * flightFrom.y + 2f * mt * t * flightVia.y + t * t * flightTo.y,
            )
        } else {
            cardOffset
        }

    fun currentJourney(): Float = activeCorner?.let { journeyOf(it, currentOffset()) } ?: 0f

    fun startFlight(
        to: Offset,
        millis: Int,
        onArrive: suspend () -> Unit,
    ) {
        val from = currentOffset()
        flightFrom = from
        flightTo = to
        // bow the path sideways, so the card pours in rather than sliding along a ruler
        val mid = Offset((from.x + to.x) / 2f, (from.y + to.y) / 2f)
        val run = Offset(to.x - from.x, to.y - from.y)
        flightVia = Offset(mid.x - run.y * spec.pathCurve, mid.y + run.x * spec.pathCurve)
        phase = CardPhase.Flight
        scope.launch {
            flight.snapTo(0f)
            flight.animateTo(1f, tween(millis, easing = FastOutSlowInEasing))
            onArrive()
        }
    }

    fun driftHome() {
        startFlight(Offset.Zero, spec.homeMillis) {
            phase = CardPhase.Idle
            cardOffset = Offset.Zero
            rawOffset = Offset.Zero
            activeCorner = null
            throwiness = 0f
        }
    }

    fun dropInto(
        corner: GradeCorner,
        speedPxPerMs: Float,
    ) {
        val anchor = anchorFor(corner)
        val remaining = hypot(anchor.x - cardOffset.x, anchor.y - cardOffset.y)
        // the flight carries on at the speed the hand was already moving
        val millis =
            (remaining / speedPxPerMs.coerceAtLeast(0.15f))
                .toInt()
                .coerceIn(spec.minDropMillis, spec.maxDropMillis)
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        startFlight(anchor, millis) {
            onRateCard(corner.rating)
            phase = CardPhase.Idle
            cardOffset = Offset.Zero
            rawOffset = Offset.Zero
            activeCorner = null
            throwiness = 0f
            entrance.snapTo(0f)
            entrance.animateTo(1f, tween(spec.enterMillis, easing = FastOutSlowInEasing))
        }
    }

    LaunchedEffect(questionHtml, isAnswerShown) {
        val target = if (isAnswerShown) FULL_TURN else 0f
        if (questionHtml != lastQuestionHtml) {
            // a different card starts face up rather than playing the last flip backwards
            lastQuestionHtml = questionHtml
            flip.snapTo(target)
        } else {
            flip.animateTo(target, tween(spec.flipMillis, easing = FastOutSlowInEasing))
        }
    }

    val labelAgain = stringResource(R.string.ease_button_again)
    val labelHard = stringResource(R.string.ease_button_hard)
    val labelGood = stringResource(R.string.ease_button_good)
    val labelEasy = stringResource(R.string.ease_button_easy)
    // a screen reader cannot drag a card, so every rating stays reachable as an action
    val ratingActions =
        remember(labelAgain, labelHard, labelGood, labelEasy, onRateCard) {
            listOf(
                CustomAccessibilityAction(labelAgain) {
                    onRateCard(CardAnswer.Rating.AGAIN)
                    true
                },
                CustomAccessibilityAction(labelHard) {
                    onRateCard(CardAnswer.Rating.HARD)
                    true
                },
                CustomAccessibilityAction(labelGood) {
                    onRateCard(CardAnswer.Rating.GOOD)
                    true
                },
                CustomAccessibilityAction(labelEasy) {
                    onRateCard(CardAnswer.Rating.EASY)
                    true
                },
            )
        }

    val isArmed = dragToGrade && isAnswerShown
    val dragModifier =
        if (isArmed) {
            Modifier.pointerInput(questionHtml, isAnswerShown, containerSize) {
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        rawOffset = cardOffset
                        phase = CardPhase.Drag
                        throwiness = 0f
                    },
                    onDragCancel = { driftHome() },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity()
                        val pxPerSecond = hypot(velocity.x, velocity.y)
                        val cardWidths = pxPerSecond / containerSize.width.coerceAtLeast(1)
                        throwiness = spec.throwiness(cardWidths)
                        val corner = activeCorner
                        val journey = corner?.let { journeyOf(it, cardOffset) } ?: 0f
                        // a real throw counts early; a slow drag has to reach the registering point
                        val thrown = throwiness > 0.5f && journey > spec.registerAt / 2f
                        if (corner != null && (journey >= spec.registerAt || thrown)) {
                            dropInto(corner, pxPerSecond / 1000f)
                        } else {
                            driftHome()
                        }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    rawOffset += dragAmount
                    val corner = cornerUnder(rawOffset)
                    cardOffset = magnetised(rawOffset, corner)
                    if (corner != activeCorner) {
                        if (corner != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        activeCorner = corner
                    }
                }
            }
        } else {
            Modifier
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it },
    ) {
        for (corner in GradeCorner.entries) {
            CornerWell(
                corner = corner,
                color = ratingColors.forRating(corner.rating).color,
                progress = { if (activeCorner == corner) (currentJourney() / spec.registerAt).coerceIn(0f, 1f) else 0f },
                visible = isArmed,
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(CardInset)
                    // 1: where the card is, how big it is, and how it leans
                    .graphicsLayer {
                        val offset = currentOffset()
                        translationX = offset.x
                        translationY = offset.y
                        val scale = spec.scaleAt(currentJourney()) * entrance.value
                        scaleX = scale
                        scaleY = scale
                        rotationZ = (offset.x / size.width.coerceAtLeast(1f)) * spec.tiltDegrees
                        alpha =
                            if (phase == CardPhase.Flight && activeCorner != null) {
                                // only dissolve once it is all the way in, at its smallest
                                1f - ((flight.value - FADE_STARTS_AT) / (1f - FADE_STARTS_AT)).coerceIn(0f, 1f)
                            } else {
                                1f
                            }
                        cameraDistance = CAMERA_DISTANCE_DP * density
                    }
                    // 2: line the frame up with the direction of travel
                    .graphicsLayer {
                        val offset = currentOffset()
                        rotationZ = atan2(offset.y, offset.x) * (180f / Math.PI.toFloat())
                    }
                    // 3: the two release behaviours, blended by how hard the card was thrown
                    .graphicsLayer {
                        val journey = currentJourney()
                        rotationX = journey * spec.tumbleDegrees * throwiness
                        val funnel = 1f - throwiness
                        scaleX = 1f + spec.funnelStretch * journey * funnel
                        scaleY = (1f - spec.funnelNarrow * journey * funnel).coerceAtLeast(0.05f)
                        cameraDistance = CAMERA_DISTANCE_DP * density
                    }
                    // 4: back into the card's own frame
                    .graphicsLayer {
                        val offset = currentOffset()
                        rotationZ = -atan2(offset.y, offset.x) * (180f / Math.PI.toFloat())
                    }.then(dragModifier)
                    .semantics { customActions = ratingActions },
        ) {
            Box(
                modifier =
                    Modifier.graphicsLayer {
                        rotationY = flip.value
                        cameraDistance = CAMERA_DISTANCE_DP * density
                    },
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = if (phase == CardPhase.Drag) DragElevation else RestElevation,
                ) {
                    Box(
                        // un-mirror the content once the card is past edge-on
                        modifier = Modifier.graphicsLayer { rotationY = if (flip.value > HALF_TURN) FULL_TURN else 0f },
                    ) {
                        Flashcard(
                            baseUrl = baseUrl,
                            questionHtml = questionHtml,
                            answerHtml = answerHtml,
                            bodyClass = bodyClass,
                            isMediaAutoplayEnabled = isMediaAutoplayEnabled,
                            javascriptCommand = javascriptCommand,
                            onJavascriptCommandConsumed = onJavascriptCommandConsumed,
                            onTap = { if (tapToFlip && !isAnswerShown) onShowAnswer() },
                            onLinkClick = onLinkClick,
                            // the sides swap while the card is edge-on, where a fade would never be seen
                            isAnswerShown = flip.value > HALF_TURN,
                            toolbarHeight = 0,
                            useStableLayout = true,
                            sideChangeDurationMs = 0,
                        )

                        val tint = activeCorner?.let { ratingColors.forRating(it.rating).color } ?: Color.Transparent
                        Box(
                            modifier =
                                Modifier
                                    .matchParentSize()
                                    .graphicsLayer {
                                        alpha = (currentJourney() / spec.registerAt).coerceIn(0f, 1f) * TINT_ALPHA
                                    }.background(tint),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The well that swallows the card: a bloom of the rating colour with rings that contract toward the
 * corner as the card approaches, and the rating's name above it.
 *
 * [progress] is read lazily so the drag repaints the well without recomposing it.
 */
@Composable
private fun BoxScope.CornerWell(
    corner: GradeCorner,
    color: Color,
    progress: () -> Float,
    visible: Boolean,
) {
    if (!visible) return

    Box(
        modifier =
            Modifier
                .align(corner.alignment)
                .fillMaxWidth(WELL_FRACTION)
                .fillMaxSize(WELL_FRACTION),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val p = progress()
            val focus =
                Offset(
                    x = if (corner.towardsStart) 0f else size.width,
                    y = if (corner.towardsTop) 0f else size.height,
                )
            drawRect(
                brush =
                    Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.1f + 0.62f * p), Color.Transparent),
                        center = focus,
                        radius = size.minDimension,
                    ),
            )
            if (p > 0f) {
                drawCircle(
                    color = color.copy(alpha = 0.42f * p),
                    radius = size.minDimension * (1.05f - 0.55f * p),
                    center = focus,
                    style = Stroke(width = 3f),
                )
                drawCircle(
                    color = color.copy(alpha = 0.28f * p),
                    radius = size.minDimension * (0.7f - 0.4f * p),
                    center = focus,
                    style = Stroke(width = 2f),
                )
            }
        }

        Text(
            text = stringResource(corner.labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier =
                Modifier
                    .align(corner.alignment)
                    .padding(WellLabelInset),
        )
    }
}
