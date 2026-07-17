package edu.ccit.webvpn.feature.tieba

import edu.ccit.webvpn.feature.tieba.data.AccountEntity
import edu.ccit.webvpn.feature.tieba.network.SignResponse
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TiebaContractsTest {
    @Test
    fun onlyTargetForumUsesNativeRoute() {
        assertEquals(ForumRouteDecision.Native, forumRouteDecision("长春工程学院吧"))
        assertTrue(forumRouteDecision("其他吧") is ForumRouteDecision.External)
    }

    @Test
    fun imageSelectionAlwaysReturnsOriginalHttpsUrl() {
        assertEquals(
            "https://imgsa.baidu.com/forum/pic/item/a.jpg",
            originalImageUrl("http://imgsa.baidu.com/forum/pic/item/a.jpg?tbpicau=1"),
        )
    }

    @Test
    fun automaticSignRunsOnlyOnTheFirstForegroundOfEachLocalDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-07-16T08:00:00Z")
        val sameLocalDay = Instant.parse("2026-07-15T18:00:00Z").toEpochMilli()
        val previousLocalDay = Instant.parse("2026-07-15T12:00:00Z").toEpochMilli()

        assertTrue(shouldAutoSign(null, now, zone))
        assertFalse(shouldAutoSign(sameLocalDay, now, zone))
        assertTrue(shouldAutoSign(previousLocalDay, now, zone))
    }

    @Test
    fun manualSignUsesDisplayedFrsTbsWithoutRefreshingTheAccount() = runBlocking {
        val calls = mutableListOf<String>()
        val current = account(tbs = "account-tbs", zid = "existing-zid")

        val response = executeTiebaLiteForumSign(
            current = current,
            forumTbs = "displayed-frs-tbs",
            refreshOfficialAccount = {
                calls += "official-login"
                it.copy(tbs = "refreshed-login-tbs")
            },
            persistAccount = { calls += "persist" },
            submitSign = { account, tbs ->
                calls += "sign"
                assertEquals(current, account)
                assertEquals("displayed-frs-tbs", tbs)
                SignResponse(SignOutcome.SUCCESS, "签到成功")
            },
        )

        assertEquals(listOf("sign"), calls)
        assertEquals(SignOutcome.SUCCESS, response.outcome)
    }

    @Test
    fun automaticSignUsesTheTbsReturnedByTheRefreshedOfficialAccount() = runBlocking {
        val calls = mutableListOf<String>()
        val current = account(tbs = "stale-account-tbs", zid = "existing-zid")
        val refreshed = current.copy(uid = 9, tbs = "official-login-tbs")

        val response = executeTiebaLiteForumSign(
            current = current,
            forumTbs = null,
            refreshOfficialAccount = {
                calls += "official-login"
                refreshed
            },
            persistAccount = {
                calls += "persist"
                assertEquals(refreshed, it)
            },
            submitSign = { account, tbs ->
                calls += "sign"
                assertEquals(refreshed, account)
                assertEquals("official-login-tbs", tbs)
                SignResponse(SignOutcome.SUCCESS, "签到成功")
            },
        )

        assertEquals(listOf("official-login", "persist", "sign"), calls)
        assertEquals(SignOutcome.SUCCESS, response.outcome)
    }

    private fun account(tbs: String, zid: String) = AccountEntity(
        uid = 7,
        name = "user",
        nickname = "nickname",
        bduss = "bduss",
        tbs = tbs,
        portrait = "",
        sToken = "stoken",
        cookie = "BDUSS=bduss; STOKEN=stoken",
        zid = zid,
    )
}
