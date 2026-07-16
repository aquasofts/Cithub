package edu.ccit.webvpn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.ccit.webvpn.core.ui.CcitAcademicTheme
import edu.ccit.webvpn.core.ui.CcitSelectField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CcitSelectFieldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun weekAndWeekdayRemainVisibleAndSelectableAt320Dp() {
        var selectedDay = 1

        compose.setContent {
            CcitAcademicTheme {
                Row(
                    modifier = Modifier.width(320.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CcitSelectField(
                        label = "周次",
                        value = 1,
                        options = (1..20).map { it to "第 $it 周" },
                        onValueChange = {},
                        modifier = Modifier.weight(1f),
                    )
                    CcitSelectField(
                        label = "星期",
                        value = selectedDay,
                        options = (1..7).map { it to "星期${"一二三四五六日"[it - 1]}" },
                        onValueChange = { selectedDay = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        compose.onNodeWithText("周次").assertIsDisplayed()
        compose.onNodeWithText("星期").assertIsDisplayed()
        compose.onNodeWithText("星期一").performClick()
        compose.onNodeWithText("星期二").performClick()
        compose.runOnIdle { assertEquals(2, selectedDay) }
    }
}
