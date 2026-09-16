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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ui.compose.theme.AnkiDroidTheme
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card as something other than a pair of gestures.
 *
 * With both card gestures on, the answer bar is never composed, so the card is the only thing a screen
 * reader can find on the whole step: it has to say which side is up, take a reader's tap as the turn a
 * finger's tap performs, and offer the four ratings. And while the card is hidden - graded away, or not yet
 * arrived - it keeps both faces' pages loaded behind an alpha of 0, which stops no touch by itself.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DraggableFlashcardTest : RobolectricTest() {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var answerShown by mutableStateOf(false)
    private var reveals = 0
    private var unanswers = 0
    private val ratings = mutableListOf<CardAnswer.Rating>()

    /** whether each touch that reached the container around the card had already been taken by the card */
    private val consumedTouches = mutableListOf<Boolean>()

    @Test
    fun `the card is a labelled button a screen reader can turn over and rate`() {
        showCard()
        settle()

        composeTestRule
            .onNodeWithContentDescription(getResourceString(R.string.card_view_question_side))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertThat("a screen reader's tap reveals the answer", reveals, equalTo(1))

        answerShown = true
        settle()

        val answerSide = getResourceString(R.string.card_view_answer_side)
        composeTestRule.onNodeWithContentDescription(answerSide).performSemanticsAction(SemanticsActions.OnClick)
        assertThat("and turns the card back over", unanswers, equalTo(1))

        composeTestRule
            .onNodeWithContentDescription(answerSide)
            .performCustomAccessibilityActionWithLabel(getResourceString(R.string.ease_button_good))
        assertThat("the ratings on that node are reachable", ratings, equalTo(listOf(CardAnswer.Rating.GOOD)))
    }

    @Test
    fun `a tap on a card that has not arrived never reaches its page`() {
        composeTestRule.mainClock.autoAdvance = false
        showCard()
        composeTestRule.mainClock.advanceTimeByFrame()

        tapTheCard()
        assertThat("the touch reached the container", consumedTouches.isNotEmpty(), equalTo(true))
        assertThat("a hidden card takes it, so its page cannot open a link or replay audio", consumedTouches.all { it }, equalTo(true))

        consumedTouches.clear()
        // robolectric's webview never reports a paint, so the card is let in by its paint timeout
        composeTestRule.mainClock.advanceTimeBy(CardMotionSpec.Default.paintWaitMillis * 2L)
        composeTestRule.waitForIdle()

        tapTheCard()
        assertThat("a card on screen leaves the touch to its page", consumedTouches.any { it }, equalTo(false))
    }

    private fun tapTheCard() =
        composeTestRule.onRoot().performTouchInput {
            down(center)
            up()
        }

    /** lets the card's entrance and any turn finish, past the paint timeout */
    private fun settle() {
        composeTestRule.mainClock.advanceTimeBy(CardMotionSpec.Default.paintWaitMillis * 2L)
        composeTestRule.waitForIdle()
    }

    private fun showCard() {
        composeTestRule.setContent {
            AnkiDroidTheme {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        // the main pass runs from the card outwards, so a touch the card took
                                        // on the initial pass arrives here already consumed
                                        awaitPointerEvent(PointerEventPass.Main).changes.forEach {
                                            consumedTouches += it.isConsumed
                                        }
                                    }
                                }
                            },
                ) {
                    DraggableFlashcard(
                        cardKey = 1L,
                        baseUrl = BASE_URL,
                        questionHtml = "question",
                        answerHtml = "answer",
                        bodyClass = "card",
                        isMediaAutoplayEnabled = false,
                        javascriptCommand = null,
                        onJavascriptCommandConsumed = {},
                        onLinkClick = {},
                        isAnswerShown = answerShown,
                        tapToFlip = true,
                        dragToGrade = true,
                        nextTimes = List(4) { "" },
                        replayFinished = 0,
                        onShowAnswer = { reveals += 1 },
                        onUnanswer = { unanswers += 1 },
                        onRateCard = { ratings += it },
                    )
                }
            }
        }
    }

    private companion object {
        const val BASE_URL = "http://127.0.0.1:1/"
    }
}
