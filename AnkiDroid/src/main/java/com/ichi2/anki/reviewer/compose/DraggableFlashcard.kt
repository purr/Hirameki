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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import anki.scheduler.CardAnswer
import com.ichi2.anki.R
import com.ichi2.anki.reviewer.ReviewerJavascriptCommand
import com.ichi2.anki.ui.compose.theme.LocalAnkiColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

private val WellLabelInset = 18.dp
private val RestElevation = 2.dp
private val DragElevation = 14.dp
private val FrontBorder = 1.dp
private val BackBorder = 1.5.dp
private const val BACK_TINT = 0.14f
private const val BACK_BORDER_ALPHA = 0.55f
private const val CAMERA_DISTANCE_DP = 14f
private const val HALF_TURN = 90f
private const val FULL_TURN = 180f
private const val DEGREES_PER_RADIAN = 180f / Math.PI.toFloat()
private const val MAGNET_CURVE = 1.6f
private const val WELL_FRACTION = 0.62f
private const val FADE_STARTS_AT = 0.86f
private const val TINT_ALPHA = 0.16f
private const val LABEL_IDLE_ALPHA = 0.55f

/** What the card is doing right now. */
private enum class CardPhase { Idle, Drag, Flight }

/**
 * A corner the card can be dropped into, and the rating that records.
 *
 * Left is negative and right is positive; the two most-used ratings sit along the bottom, where a
 * thumb already rests. Declaration order matches the interval labels the scheduler hands back.
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
 * It is shaped and sized like a bank card rather than filling the screen, so the four corner wells
 * stay visible and reachable around it. Both sides fill the same rectangle, so nothing reflows when
 * the answer appears, and grading is the drag itself, which is why this replaces [AnswerButtons]
 * rather than sitting beside it.
 *
 * Nothing is recorded until the finger lifts: while dragging, the card only shrinks as far as
 * [CardMotionSpec.dragFloorScale], and the rest of the journey into the corner happens on release.
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
    nextTimes: List<String>,
    onShowAnswer: () -> Unit,
    onUnanswer: () -> Unit,
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

    // a flight is either the trip into a corner, which grades, or the drift back to the middle
    val flight = remember { Animatable(0f) }
    var isGrading by remember { mutableStateOf(false) }
    var flightFrom by remember { mutableStateOf(Offset.Zero) }
    var flightVia by remember { mutableStateOf(Offset.Zero) }
    var flightTo by remember { mutableStateOf(Offset.Zero) }
    var flightFromScale by remember { mutableFloatStateOf(1f) }
    var flightCorner by remember { mutableStateOf<GradeCorner?>(null) }

    val flip = remember { Animatable(0f) }
    val entrance = remember { Animatable(1f) }
    // changes only as the card passes edge-on, so the faces recompose twice per flip, not every frame
    val showsBackFace by remember { derivedStateOf { flip.value > HALF_TURN } }

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

    /** The corner whose quarter of the screen [offset] points into, with no dead zone. */
    fun quadrantOf(offset: Offset): GradeCorner {
        val towardsStart = (offset.x < 0f) != isRtl
        val towardsTop = offset.y < 0f
        return GradeCorner.entries.first { it.towardsStart == towardsStart && it.towardsTop == towardsTop }
    }

    /** The corner a release would grade into: none until the card has left the middle. */
    fun cornerUnder(offset: Offset): GradeCorner? {
        if (containerSize == IntSize.Zero) return null
        val dead = min(containerSize.width, containerSize.height) * spec.deadZone
        if (offset.getDistance() < dead) return null
        return quadrantOf(offset)
    }

    /**
     * The corner takes the card off the finger as it closes in.
     *
     * The pull fades to nothing along the two axes that divide the corners. Without that, sliding
     * straight from one corner to its neighbour swaps the anchor mid-drag and the card jumps across;
     * with it, the pull hands over from one corner to the next through zero.
     */
    fun magnetised(raw: Offset): Offset {
        if (spec.cornerPull <= 0f || raw == Offset.Zero) return raw
        val corner = quadrantOf(raw)
        val anchor = anchorFor(corner)
        val offAxis = 2f * min(abs(raw.x), abs(raw.y)) / (abs(raw.x) + abs(raw.y))
        val grip = journeyOf(corner, raw).pow(MAGNET_CURVE) * spec.cornerPull * offAxis
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

    /**
     * How far toward its corner the card is, used for its size, tint and tilt. It reads the quadrant
     * directly rather than [activeCorner], so it is continuous from the first pixel of movement: the
     * dead zone only decides what a release grades, never how the card looks.
     */
    fun currentJourney(): Float {
        val offset = currentOffset()
        val corner = if (phase == CardPhase.Flight && isGrading) flightCorner ?: quadrantOf(offset) else quadrantOf(offset)
        return journeyOf(corner, offset)
    }

    /**
     * While a finger is down the card only shrinks to the drag floor, so it can always be seen and
     * dragged back out. The remaining shrink happens on the flight in, once grading is committed.
     */
    fun currentScale(): Float {
        val dragScale = spec.dragScaleAt(currentJourney())
        val base =
            if (phase == CardPhase.Flight && isGrading) {
                flightFromScale + (spec.minScale - flightFromScale) * flight.value
            } else {
                dragScale
            }
        return base * entrance.value
    }

    fun resetToRest() {
        phase = CardPhase.Idle
        isGrading = false
        flightCorner = null
        cardOffset = Offset.Zero
        rawOffset = Offset.Zero
        activeCorner = null
        throwiness = 0f
    }

    fun startFlight(
        to: Offset,
        millis: Int,
        onArrive: suspend () -> Unit,
    ) {
        val from = currentOffset()
        flightFrom = from
        flightTo = to
        flightFromScale = spec.dragScaleAt(currentJourney())
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
        isGrading = false
        startFlight(Offset.Zero, spec.homeMillis) { resetToRest() }
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
        isGrading = true
        flightCorner = corner
        startFlight(anchor, millis) {
            onRateCard(corner.rating)
            resetToRest()
            entrance.snapTo(0f)
            entrance.animateTo(1f, tween(spec.enterMillis, easing = FastOutSlowInEasing))
        }
    }

    LaunchedEffect(questionHtml, isAnswerShown) {
        if (questionHtml != lastQuestionHtml) {
            lastQuestionHtml = questionHtml
            // a different card starts face up, centred, and never inherits a half-finished drag
            if (phase != CardPhase.Flight) resetToRest()
            flip.snapTo(if (isAnswerShown) FULL_TURN else 0f)
        } else {
            flip.animateTo(
                targetValue = if (isAnswerShown) FULL_TURN else 0f,
                animationSpec = tween(spec.flipMillis, easing = FastOutSlowInEasing),
            )
            // turning the card back over drops any aim at a corner
            if (!isAnswerShown && phase == CardPhase.Idle) resetToRest()
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
            // keyed only on arming: a size change mid-drag must not cancel the gesture
            Modifier.pointerInput(isArmed) {
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        rawOffset = cardOffset
                        phase = CardPhase.Drag
                        isGrading = false
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
                    cardOffset = magnetised(rawOffset)
                    if (corner != activeCorner) {
                        if (corner != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        activeCorner = corner
                    }
                }
            }
        } else {
            Modifier
        }

    // each well chases the card rather than snapping on, so crossing between corners is a cross-fade
    val glows = remember { GradeCorner.entries.map { mutableFloatStateOf(0f) } }
    LaunchedEffect(isArmed) {
        if (!isArmed) {
            glows.forEach { it.floatValue = 0f }
            return@LaunchedEffect
        }
        while (true) {
            withFrameMillis { }
            val aim = (currentJourney() / spec.registerAt).coerceIn(0f, 1f)
            GradeCorner.entries.forEachIndexed { index, corner ->
                val target = if (corner == activeCorner) aim else 0f
                val current = glows[index].floatValue
                val next = current + (target - current) * spec.wellSmoothing
                // settling exactly stops the redraws once nothing is moving
                glows[index].floatValue = if (abs(target - next) < 0.002f) target else next
            }
        }
    }

    val armedAlpha by animateFloatAsState(
        targetValue = if (isArmed) 1f else 0f,
        animationSpec = tween(spec.wellFadeMillis, easing = FastOutSlowInEasing),
        label = "wellArmedAlpha",
    )

    // the shadow lifts with the card instead of jumping, so picking it up has no seam
    val elevation by animateDpAsState(
        targetValue = if (phase == CardPhase.Drag) DragElevation else RestElevation,
        animationSpec = tween(spec.liftMillis, easing = FastOutSlowInEasing),
        label = "cardLift",
    )

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it },
    ) {
        // measured explicitly: a bank card's proportions, as large as fits inside the size fraction on
        // both axes. fillMaxSize() + aspectRatio() cannot do this, because tight constraints win.
        val cardWidth = min(maxWidth.value * spec.cardSizeFraction, maxHeight.value * spec.cardSizeFraction * spec.cardAspectRatio).dp
        val cardHeight = (cardWidth.value / spec.cardAspectRatio).dp

        for (corner in GradeCorner.entries) {
            CornerBloom(
                corner = corner,
                color = ratingColors.forRating(corner.rating).color,
                idleAlpha = spec.wellIdleAlpha,
                glow = { glows[corner.ordinal].floatValue },
                fade = { armedAlpha },
            )
        }

        Box(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(cardWidth, cardHeight)
                    // 1: where the card is, how big it is, and how it leans
                    .graphicsLayer {
                        val offset = currentOffset()
                        translationX = offset.x
                        translationY = offset.y
                        val scale = currentScale()
                        scaleX = scale
                        scaleY = scale
                        rotationZ = (offset.x / size.width.coerceAtLeast(1f)) * spec.tiltDegrees
                        // only a graded flight dissolves, and only once it is deep in the corner
                        alpha =
                            if (phase == CardPhase.Flight && isGrading) {
                                1f - ((flight.value - FADE_STARTS_AT) / (1f - FADE_STARTS_AT)).coerceIn(0f, 1f)
                            } else {
                                1f
                            }
                        cameraDistance = CAMERA_DISTANCE_DP * density
                    }
                    // 2: line the frame up with the direction of travel
                    .graphicsLayer {
                        val offset = currentOffset()
                        rotationZ = atan2(offset.y, offset.x) * DEGREES_PER_RADIAN
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
                        rotationZ = -atan2(offset.y, offset.x) * DEGREES_PER_RADIAN
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
                // tapping turns the card over either way, so a reveal can be taken back
                val onFaceTap: () -> Unit = {
                    if (tapToFlip) {
                        if (isAnswerShown) onUnanswer() else onShowAnswer()
                    }
                }
                // both faces are full-size webviews stacked in one box, and alpha 0 hides a view without
                // stopping it from receiving touches. whichever face is showing must be on top, or the
                // invisible one swallows every tap, including the replay buttons on the visible side.
                val showsBack = showsBackFace
                val tint = activeCorner?.let { ratingColors.forRating(it.rating).color } ?: Color.Transparent
                val tintAlpha = { (currentJourney() / spec.registerAt).coerceIn(0f, 1f) * TINT_ALPHA }
                val colors = MaterialTheme.colorScheme
                // the answer side is a step lighter and tinted toward the theme, so it is never
                // mistaken for the question side at a glance
                val backColor = lerp(colors.surfaceContainerHighest, colors.primaryContainer, BACK_TINT)

                // two real faces, both painted as soon as the card loads: the flip only reveals a side
                // that is already rendered, instead of swapping the html while the card turns
                CardFace(
                    modifier =
                        Modifier
                            .zIndex(if (showsBack) 0f else 1f)
                            .graphicsLayer { alpha = if (flip.value <= HALF_TURN) 1f else 0f },
                    color = colors.surfaceContainerHigh,
                    border = BorderStroke(FrontBorder, colors.outlineVariant),
                    elevation = elevation,
                    tint = tint,
                    tintAlpha = tintAlpha,
                ) { pageColor ->
                    Flashcard(
                        baseUrl = baseUrl,
                        questionHtml = questionHtml,
                        answerHtml = answerHtml,
                        bodyClass = bodyClass,
                        isMediaAutoplayEnabled = isMediaAutoplayEnabled && !isAnswerShown,
                        javascriptCommand = if (isAnswerShown) null else javascriptCommand,
                        onJavascriptCommandConsumed = onJavascriptCommandConsumed,
                        onTap = { if (!showsBackFace) onFaceTap() },
                        onLinkClick = onLinkClick,
                        isAnswerShown = false,
                        toolbarHeight = 0,
                        useStableLayout = true,
                        sideChangeDurationMs = 0,
                        pageColor = pageColor,
                    )
                }

                CardFace(
                    // pre-turned half a revolution, so it reads the right way round once flipped to
                    modifier =
                        Modifier
                            .zIndex(if (showsBack) 1f else 0f)
                            .graphicsLayer {
                                rotationY = FULL_TURN
                                alpha = if (flip.value > HALF_TURN) 1f else 0f
                            },
                    color = backColor,
                    border = BorderStroke(BackBorder, colors.primary.copy(alpha = BACK_BORDER_ALPHA)),
                    elevation = elevation,
                    tint = tint,
                    tintAlpha = tintAlpha,
                ) { pageColor ->
                    Flashcard(
                        baseUrl = baseUrl,
                        questionHtml = questionHtml,
                        answerHtml = answerHtml,
                        bodyClass = bodyClass,
                        // audio is autoplayed by the view model on reveal; the hidden face must not
                        // start any page media of its own while the question is still up
                        isMediaAutoplayEnabled = isMediaAutoplayEnabled && isAnswerShown,
                        javascriptCommand = if (isAnswerShown) javascriptCommand else null,
                        onJavascriptCommandConsumed = onJavascriptCommandConsumed,
                        onTap = { if (showsBackFace) onFaceTap() },
                        onLinkClick = onLinkClick,
                        isAnswerShown = true,
                        toolbarHeight = 0,
                        useStableLayout = true,
                        sideChangeDurationMs = 0,
                        pageColor = pageColor,
                    )
                }
            }
        }

        // drawn after the card so a rating and its interval stay readable even as the card passes over
        for (corner in GradeCorner.entries) {
            CornerLabel(
                corner = corner,
                color = ratingColors.forRating(corner.rating).color,
                nextTime = nextTimes.getOrElse(corner.ordinal) { "" },
                glow = { glows[corner.ordinal].floatValue },
                fade = { armedAlpha },
            )
        }
    }
}

/**
 * One side of the card: a Material surface in its own tone and border, with the page drawn in that
 * same tone, and the corner colour washed over it as the card is aimed.
 */
@Composable
private fun CardFace(
    color: Color,
    border: BorderStroke,
    elevation: Dp,
    tint: Color,
    tintAlpha: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable (pageColor: Color) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = MaterialTheme.shapes.extraLarge,
        color = color,
        border = border,
        shadowElevation = elevation,
    ) {
        Box {
            content(color)
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = tintAlpha() }
                        .background(tint),
            )
        }
    }
}

/**
 * The well that swallows the card: a soft bloom of the rating colour with rings that contract toward
 * the corner as the card approaches.
 *
 * [glow] and [fade] are read lazily inside draw and layer scopes, so the drag repaints the well
 * without recomposing it.
 */
@Composable
private fun BoxScope.CornerBloom(
    corner: GradeCorner,
    color: Color,
    idleAlpha: Float,
    glow: () -> Float,
    fade: () -> Float,
) {
    Box(
        modifier =
            Modifier
                .align(corner.alignment)
                .fillMaxSize(WELL_FRACTION),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val visibility = fade()
            if (visibility <= 0f) return@Canvas
            val p = glow() * visibility
            val focus =
                Offset(
                    x = if (corner.towardsStart) 0f else size.width,
                    y = if (corner.towardsTop) 0f else size.height,
                )
            drawRect(
                brush =
                    Brush.radialGradient(
                        colors = listOf(color.copy(alpha = (idleAlpha + 0.3f * p) * visibility), Color.Transparent),
                        center = focus,
                        radius = size.minDimension,
                    ),
            )
            if (p > 0f) {
                drawCircle(
                    color = color.copy(alpha = 0.3f * p),
                    radius = size.minDimension * (1.05f - 0.55f * p),
                    center = focus,
                    style = Stroke(width = 3f),
                )
                drawCircle(
                    color = color.copy(alpha = 0.2f * p),
                    radius = size.minDimension * (0.7f - 0.4f * p),
                    center = focus,
                    style = Stroke(width = 2f),
                )
            }
        }
    }
}

/**
 * A corner's rating and the interval it would schedule, sitting above the card so it stays readable
 * while the card travels over it. Brightens and grows as the card comes for it.
 */
@Composable
private fun BoxScope.CornerLabel(
    corner: GradeCorner,
    color: Color,
    nextTime: String,
    glow: () -> Float,
    fade: () -> Float,
) {
    Column(
        modifier =
            Modifier
                .align(corner.alignment)
                .padding(WellLabelInset)
                .graphicsLayer {
                    val p = glow()
                    alpha = (LABEL_IDLE_ALPHA + (1f - LABEL_IDLE_ALPHA) * p) * fade()
                    val grow = 1f + 0.16f * p
                    scaleX = grow
                    scaleY = grow
                    transformOrigin = corner.labelOrigin()
                },
        horizontalAlignment = if (corner.towardsStart) Alignment.Start else Alignment.End,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = stringResource(corner.labelRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        if (nextTime.isNotEmpty()) {
            Text(
                text = nextTime,
                style = MaterialTheme.typography.labelMedium,
                color = color.copy(alpha = 0.8f),
            )
        }
    }
}

/** Grow the label out of its own corner rather than its middle. */
private fun GradeCorner.labelOrigin(): androidx.compose.ui.graphics.TransformOrigin =
    androidx.compose.ui.graphics.TransformOrigin(
        pivotFractionX = if (towardsStart) 0f else 1f,
        pivotFractionY = if (towardsTop) 0f else 1f,
    )
