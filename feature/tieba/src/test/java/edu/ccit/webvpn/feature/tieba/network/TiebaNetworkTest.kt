package edu.ccit.webvpn.feature.tieba.network

import android.content.Context
import android.provider.Settings
import com.google.gson.Gson
import com.huanchengfly.tieba.post.api.models.protos.Abstract as ThreadAbstract
import com.huanchengfly.tieba.post.api.models.protos.Anti
import com.huanchengfly.tieba.post.api.models.protos.Error
import com.huanchengfly.tieba.post.api.models.protos.Media
import com.huanchengfly.tieba.post.api.models.protos.Page
import com.huanchengfly.tieba.post.api.models.protos.PbContent
import com.huanchengfly.tieba.post.api.models.protos.Post
import com.huanchengfly.tieba.post.api.models.protos.SimpleForum
import com.huanchengfly.tieba.post.api.models.protos.SubPost
import com.huanchengfly.tieba.post.api.models.protos.SubPostList
import com.huanchengfly.tieba.post.api.models.protos.ThreadInfo
import com.huanchengfly.tieba.post.api.models.protos.User
import com.huanchengfly.tieba.post.api.models.protos.frsPage.ForumInfo
import com.huanchengfly.tieba.post.api.models.protos.frsPage.FrsPageResponse
import com.huanchengfly.tieba.post.api.models.protos.frsPage.FrsPageResponseData
import com.huanchengfly.tieba.post.api.models.protos.frsPage.SignInfo
import com.huanchengfly.tieba.post.api.models.protos.frsPage.SignUser
import com.huanchengfly.tieba.post.api.models.protos.forumRuleDetail.ForumRuleDetailResponse
import com.huanchengfly.tieba.post.api.models.protos.pbFloor.PbFloorResponse
import com.huanchengfly.tieba.post.api.models.protos.pbFloor.PbFloorResponseData
import com.huanchengfly.tieba.post.api.models.protos.pbPage.PbPageResponse
import com.huanchengfly.tieba.post.api.models.protos.pbPage.PbPageResponseData
import com.huanchengfly.tieba.post.api.models.protos.profile.ProfileResponse
import com.huanchengfly.tieba.post.api.models.protos.userPost.UserPostResponse
import com.huanchengfly.tieba.post.api.models.protos.addPost.AddPostResponse
import edu.ccit.webvpn.feature.tieba.FloorSort
import edu.ccit.webvpn.feature.tieba.ForumSummary
import edu.ccit.webvpn.feature.tieba.ForumSort
import edu.ccit.webvpn.feature.tieba.LoadPicPageData
import edu.ccit.webvpn.feature.tieba.SignOutcome
import edu.ccit.webvpn.feature.tieba.TARGET_FORUM_ID
import edu.ccit.webvpn.feature.tieba.TARGET_FORUM_NAME
import edu.ccit.webvpn.feature.tieba.TiebaContent
import edu.ccit.webvpn.feature.tieba.data.AccountEntity
import edu.ccit.webvpn.feature.tieba.data.TiebaClientConfig
import edu.ccit.webvpn.feature.tieba.data.TiebaSettingsRepository
import java.net.URLDecoder
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TiebaNetworkTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun tiebaLiteMd5UsesUppercaseHex() {
        assertEquals("098F6BCD4621D373CADE4E832627B4F6", md5ForTest("test"))
    }

    @Test
    fun sofireZidRequestMatchesTiebaLiteX6Handshake() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":"","request_id":1,"skey":""}"""))
        try {
            val uuid = "00000000-1111-2222-3333-444444444444"
            runCatching {
                TiebaSofireClient(
                    client = OkHttpClient(),
                    gson = Gson(),
                    endpoint = server.url("/").toString().removeSuffix("/"),
                    clock = { 1_700_000_000_000 },
                    randomKey = { "abcdefghijklmnop" },
                ).fetchZid(uuid)
            }
            val request = server.takeRequest()
            val cuid = "${md5ForTest(uuid)}|0"
            val cuidMd5 = md5ForTest(cuid).lowercase(Locale.ROOT)
            val pathMd5 = md5ForTest(
                "2000331700000000ea737e4f435b53786043369d2e5ace4f",
            ).lowercase(Locale.ROOT)

            assertEquals("/c/11/z/100/200033/1700000000/$pathMd5", request.requestUrl?.encodedPath)
            assertTrue(request.requestUrl?.queryParameter("skey").orEmpty().isNotBlank())
            assertEquals(cuidMd5, request.getHeader("x-device-id"))
            assertEquals("src", request.getHeader("x-client-src"))
            assertEquals("x6/200033/12.35.1.0/4.4.1.3", request.getHeader("User-Agent"))
            assertEquals("sofire/3.5.9.6", request.getHeader("x-sdk-ver"))
            assertEquals("x6/4.4.1.3", request.getHeader("x-plu-ver"))
            assertEquals("com.baidu.tieba/12.35.1.0", request.getHeader("x-app-ver"))
            assertEquals("33", request.getHeader("x-api-ver"))
            assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"))
            assertTrue(request.bodySize > 16)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun loginCookieParserIsCaseInsensitiveAndKeepsEqualsInValues() {
        val parsed = parseLoginCookies("foo=1; bduss=a=b=c; StOkEn=token; BAIDUID=device")
        assertEquals("a=b=c", parsed?.bduss)
        assertEquals("token", parsed?.sToken)
        assertEquals("device", parsed?.baiduId)
        assertNull(parseLoginCookies("BDUSS=only"))
    }

    @Test
    fun repositoryMapsTargetForumAndThreadFromWire() = runBlocking {
        val readApi = FakeTiebaReadApi(successForum(), successThread())
        val repository = repository(readApi)

        val forum = repository.loadForum(1, ForumSort.BY_REPLY, goodOnly = false)
        val thread = repository.loadThread("9", 1, FloorSort.ASCENDING, onlyOriginalPoster = false)

        assertEquals(TARGET_FORUM_NAME, forum.forum.name)
        assertEquals("9", forum.threads.single().id)
        assertEquals("#(泪)", forum.threads.single().excerpt)
        assertEquals("image_emoticon9", forum.threads.single().richExcerpt.filterIsInstance<TiebaContent.Emoticon>().single().id)
        assertTrue(forum.threads.single().authorIsManager)
        assertTrue(forum.forum.signed)
        assertEquals(6, forum.forum.signedDays)
        assertEquals("fresh-forum-tbs", forum.forum.tbs)
        assertEquals("10", thread.body?.postId)
        assertEquals(1, thread.body?.floor)
        assertEquals("12", thread.floors.single().postId)
        assertEquals("正文#(黑线)", thread.floors.single().content)
        assertEquals("https://img.example/original.jpg?tbpicau=fresh-token", thread.floors.single().imageUrls.single())
        val image = thread.floors.single().richContent.filterIsInstance<TiebaContent.Image>().single()
        assertEquals("https://img.example/preview.jpg?token=kept", image.previewUrl)
        assertEquals("https://img.example/original.jpg?tbpicau=fresh-token", image.originalUrl)
        assertEquals("original", image.picId)
        assertEquals("回复者", thread.floors.single().replies.single().authorNickname)
        assertEquals("#(泪)", thread.floors.single().replies.single().content)
        assertEquals(2, thread.replyCount)
        assertEquals(8, thread.floors.single().authorLevel)
        assertEquals("渐入佳境", thread.floors.single().authorTitle)
        assertEquals("吉林", thread.floors.single().authorIp)
        assertTrue(thread.floors.single().authorIsManager)
    }

    @Test
    fun repositoryLoadsTheConfiguredHomeForum() = runBlocking {
        val forumId = 12345L
        val forumName = "Kotlin"
        val readApi = FakeTiebaReadApi(successForum(forumId, forumName), successThread())

        val page = repository(readApi).loadForum(
            page = 1,
            sort = ForumSort.BY_REPLY,
            goodOnly = false,
            forumName = "  ${forumName}吧  ",
        )

        assertEquals(forumId.toString(), page.forum.id)
        assertEquals(forumName, page.forum.name)
        assertEquals(forumId, page.threads.single().forumId)
        assertEquals(forumName, page.threads.single().forumName)
        assertEquals(TiebaReadRequestFactory.encodedForumName(forumName), readApi.lastForumName)
    }

    @Test
    fun floorRepliesKeepAuthorAndRichContent() = runBlocking {
        val repository = repository(FakeTiebaReadApi(successForum(), successThread(), successFloor()))

        val page = repository.loadFloorReplies("9", "10", 1)

        assertEquals(6, page.floor.floor)
        assertEquals("原楼正文", page.floor.content)
        assertEquals(1, page.totalReplies)
        assertEquals("回复者", page.replies.single().authorNickname)
        assertEquals("#(泪)", page.replies.single().content)
        assertEquals("image_emoticon9", page.replies.single().richContent.filterIsInstance<TiebaContent.Emoticon>().single().id)
        assertEquals(5, page.replies.single().authorLevel)
        assertEquals("小有名气", page.replies.single().authorTitle)
        assertEquals("北京", page.replies.single().authorIp)
    }

    @Test
    fun repositoryRejectsAnyOtherForum() = runBlocking {
        val otherForum = successForum().copy(
            data_ = successForum().data_?.copy(forum = ForumInfo(id = 1, name = "其他")),
        )
        val failure = runCatching {
            repository(FakeTiebaReadApi(otherForum, successThread())).loadForum(1, ForumSort.BY_REPLY, false)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals("帖子不属于本吧", failure?.message)
    }

    @Test
    fun inForumSearchDropsCrossForumResults() = runBlocking {
        val support = FakeTiebaSupportApi(
            SearchEnvelope(
                errorCode = 0,
                data = SearchData(
                    posts = listOf(
                        SearchPost(tid = "1", title = "目标", forumName = TARGET_FORUM_NAME),
                        SearchPost(tid = "2", title = "其他", forumName = "其他吧"),
                    ),
                ),
            ),
        )
        val results = repository(FakeTiebaReadApi(successForum(), successThread()), support).search("测试", 1)

        assertEquals(listOf("1"), results.threads.map { it.id })
        assertTrue(support.lastReferer.contains("forumName=%E9%95%BF%E6%98%A5%E5%B7%A5%E7%A8%8B%E5%AD%A6%E9%99%A2"))
        assertTrue(!support.lastReferer.contains(TARGET_FORUM_NAME))
    }

    @Test
    fun searchAcceptsTiebaPicMediaTypeAndMapsItsImage() = runBlocking {
        val support = FakeTiebaSupportApi(
            SearchEnvelope(
                errorCode = 0,
                data = SearchData(
                    posts = listOf(
                        SearchPost(
                            tid = "1",
                            title = "图片帖",
                            forumName = TARGET_FORUM_NAME,
                            user = SearchUser(id = 7, name = "user"),
                            media = listOf(
                                SearchMedia(
                                    type = "pic",
                                    bigPic = "https://img.example/forum/pic/item/search.jpg",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = repository(FakeTiebaReadApi(successForum(), successThread()), support).search("图片", 1)

        assertEquals(7L, result.threads.single().authorId)
        assertEquals("https://img.example/forum/pic/item/search.jpg", result.threads.single().imageUrls.single())
    }

    @Test
    fun forumImagesWithTheSameTiebaItemAreShownOnlyOnce() = runBlocking {
        val original = successForum()
        val thread = original.data_!!.thread_list.single().copy(
            media = listOf(Media(originPic = "https://img-a.example/forum/pic/item/one.jpg?token=1")),
            richAbstract = listOf(PbContent(type = 3, originSrc = "https://img-b.example/forum/pic/item/one.jpg?token=2")),
        )
        val response = original.copy(data_ = original.data_!!.copy(thread_list = listOf(thread)))

        val page = repository(FakeTiebaReadApi(response, successThread())).loadForum(1, ForumSort.BY_REPLY, false)

        assertEquals(1, page.threads.single().imageUrls.size)
    }

    @Test
    fun accountFrsErrorIsNotRetriedAnonymously() = runBlocking {
        var forumCalls = 0
        val api = object : TiebaReadApi {
            override suspend fun forum(body: RequestBody, forumName: String): FrsPageResponse {
                forumCalls += 1
                return FrsPageResponse(error = Error(error_code = 4))
            }

            override suspend fun thread(body: RequestBody, userToken: String?): PbPageResponse = successThread()
            override suspend fun floor(body: RequestBody, userToken: String?): PbFloorResponse = PbFloorResponse(error = Error(error_code = 0))
            override suspend fun profile(body: RequestBody, userToken: String?): ProfileResponse = ProfileResponse(error = Error(error_code = 0))
            override suspend fun userPosts(body: RequestBody, userToken: String?): UserPostResponse = UserPostResponse(error = Error(error_code = 0))
            override suspend fun forumRule(body: RequestBody): ForumRuleDetailResponse = ForumRuleDetailResponse(error = Error(error_code = 0))
            override suspend fun addPost(body: RequestBody, userToken: String): AddPostResponse =
                AddPostResponse(error = Error(error_code = 0))
        }
        val account = AccountEntity(
            uid = 7,
            name = "user",
            nickname = "nickname",
            bduss = "bduss",
            tbs = "tbs",
            portrait = "",
            sToken = "stoken",
            cookie = "BDUSS=bduss; STOKEN=stoken",
        )

        val failure = runCatching { repository(api).loadForum(1, ForumSort.BY_REPLY, false, account) }

        assertTrue(failure.isFailure)
        assertEquals(1, forumCalls)
    }

    @Test
    fun bothRetrofitServiceFamiliesCanBeCreated() {
        val client = OkHttpClient()
        assertNotNull(TiebaNetworkRepository.createSupportRetrofit(client).create(TiebaSupportApi::class.java))
        assertNotNull(createTiebaReadRetrofit(client).create(TiebaReadApi::class.java))
        assertNotNull(TiebaNetworkRepository.createPicPageRetrofit(client).create(TiebaPicPageApi::class.java))
    }

    @Test
    fun displayedReplyCountNeverIncludesFirstFloor() {
        assertEquals(0, replyCountExcludingFirstFloor(0))
        assertEquals(0, replyCountExcludingFirstFloor(1))
        assertEquals(24, replyCountExcludingFirstFloor(25))
    }

    @Test
    fun officialSignRequestMatchesTiebaLiteV11InterceptorOutput() = runBlocking {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "fixed-android-id")
        val account = AccountEntity(
            uid = 7,
            name = "user",
            nickname = "nickname",
            bduss = "bduss",
            tbs = "old-tbs",
            portrait = "",
            sToken = "stoken",
            cookie = "BDUSS=bduss; STOKEN=stoken; BAIDUID=baidu=device",
        )
        val config = TiebaClientConfig(
            uuid = "00000000-1111-2222-3333-444444444444",
            clientId = "fixed-client-id",
            sampleId = "fixed-sample-id",
            baiduId = "baidu=device",
            activeTimestamp = 1_600_000_000_000,
            firstInstallTime = 1_500_000_000_000,
            lastUpdateTime = 1_650_000_000_000,
        )
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"error_code":"0","user_info":{"is_sign_in":1,"cont_sign_num":8,"user_sign_rank":42,"sign_bonus_point":4}}""",
            ),
        )
        try {
            val client = TiebaOfficialClient(
                context = context,
                gson = Gson(),
                account = account,
                config = config,
                baseUrl = server.url("/").toString(),
                clock = { 1_700_000_000_000 },
                stNumber = { 250 },
                stSizeFactor = { 2.0 },
            )
            val result = client.sign(TARGET_FORUM_ID.toString(), TARGET_FORUM_NAME, "fresh-tbs")
            val request = server.takeRequest()
            val rawBody = request.body.readUtf8()
            val fields = parseFormForTest(rawBody)

            assertEquals("/c/c/forum/sign", request.path)
            assertEquals("11.10.8.6", fields["_client_version"])
            assertEquals("bdtb for Android 11.10.8.6", request.getHeader("User-Agent"))
            assertEquals("ka=open", request.getHeader("Cookie"))
            assertEquals("7", request.getHeader("client_user_token"))
            assertEquals("1700000000000", request.getHeader("client_logid"))
            assertNull(request.getHeader("Charset"))
            assertNull(request.getHeader("client_type"))
            assertEquals("fresh-tbs", fields["tbs"])
            assertEquals("baidu=device", fields["baiduid"])
            assertEquals("fixed-client-id", fields["_client_id"])
            assertEquals("fixed-sample-id", fields["sample_id"])
            assertEquals("000000000000000", fields["_phone_imei"])
            assertTrue(fields["android_id"].orEmpty().endsWith("\n"))
            assertEquals("1700000000000", fields["timestamp"])
            assertNull(fields["_timestamp"])
            assertNull(fields["oaid"])
            assertEquals("1", fields["stErrorNums"])
            assertEquals("1", fields["stMethod"])
            assertEquals("250", fields["stTime"])
            assertEquals("500", fields["stSize"])
            assertEquals(
                fields.keys.filter { it != "sign" }.sorted() + "sign",
                rawBody.split('&').map { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) },
            )
            assertEquals(
                md5ForTest(
                    fields.filterKeys { it != "sign" }.entries
                        .map { "${it.key}=${it.value}" }.sorted().joinToString("") + "tiebaclient!!!",
                ),
                fields["sign"],
            )
            assertEquals(fields["sign"]?.uppercase(Locale.ROOT), fields["sign"])
            assertEquals(8, result.userInfo?.contSignNum)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun forumFollowRequestMatchesTiebaLiteMiniClient() = runBlocking {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "fixed-android-id")
        val account = AccountEntity(
            uid = 7,
            name = "user",
            nickname = "nickname",
            bduss = "bduss",
            tbs = "old-tbs",
            portrait = "",
            sToken = "stoken",
            cookie = "BDUSS=bduss; STOKEN=stoken; BAIDUID=baidu=device",
        )
        val config = TiebaClientConfig(
            uuid = "00000000-1111-2222-3333-444444444444",
            clientId = "fixed-client-id",
            sampleId = "fixed-sample-id",
            baiduId = "baidu=device",
            activeTimestamp = 1_600_000_000_000,
            firstInstallTime = 1_500_000_000_000,
            lastUpdateTime = 1_650_000_000_000,
        )
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"error_code":0,"error":{"errno":"0"}}"""))
        try {
            val client = TiebaOfficialClient(
                context = context,
                gson = Gson(),
                account = account,
                config = config,
                baseUrl = server.url("/").toString(),
                clock = { 1_700_000_000_000 },
                stNumber = { 250 },
                stSizeFactor = { 2.0 },
            )
            client.likeForum(TARGET_FORUM_ID.toString(), TARGET_FORUM_NAME, "fresh-follows-tbs", "follow-attempt")
            val request = server.takeRequest()
            val rawBody = request.body.readUtf8()
            val fields = parseFormForTest(rawBody)

            assertEquals("/c/c/forum/like", request.path)
            assertEquals("ka=open", request.getHeader("Cookie"))
            assertEquals("no-cache", request.getHeader("Pragma"))
            assertEquals("bdtb for Android 7.2.0.0", request.getHeader("User-Agent"))
            assertNull(request.getHeader("c3_aid"))
            assertNull(request.getHeader("cuid_gid"))
            assertNull(request.getHeader(SIGN_DIAGNOSTIC_ATTEMPT_HEADER))
            assertEquals(TIEBA_LITE_MINI_VERSION, fields["_client_version"])
            assertEquals(TIEBA_LITE_MINI_SOURCE, fields["from"])
            assertEquals("mini", fields["subapp_type"])
            assertEquals(TARGET_FORUM_ID.toString(), fields["fid"])
            assertEquals(TARGET_FORUM_NAME, fields["kw"])
            assertEquals("fresh-follows-tbs", fields["tbs"])
            assertNull(fields["active_timestamp"])
            assertNull(fields["android_id"])
            assertNull(fields["sample_id"])
            assertNull(fields["oaid"])
            assertEquals(
                md5ForTest(
                    fields.filterKeys { it != "sign" }.entries
                        .map { "${it.key}=${it.value}" }.sorted().joinToString("") + "tiebaclient!!!",
                ),
                fields["sign"],
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun officialLoginHydrationMatchesTiebaLiteV11InterceptorOutput() = runBlocking {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "fixed-android-id")
        val account = AccountEntity(
            uid = 7,
            name = "web-user",
            nickname = "web-nickname",
            bduss = "bduss",
            tbs = "web-tbs",
            portrait = "",
            sToken = "stoken",
            cookie = "BDUSS=bduss; STOKEN=stoken; BAIDUID=baidu=device",
        )
        val config = TiebaClientConfig(
            uuid = "00000000-1111-2222-3333-444444444444",
            clientId = "fixed-client-id",
            sampleId = "fixed-sample-id",
            baiduId = "baidu=device",
            activeTimestamp = 1_600_000_000_000,
            firstInstallTime = 1_500_000_000_000,
            lastUpdateTime = 1_650_000_000_000,
        )
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"error_code":"0","anti":{"tbs":"official-tbs"},"user":{"id":"9","name":"official-user","portrait":"portrait-token"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"error_code":"0","user_info":{"name_show":"官方昵称","tieba_uid":"tieba-9","user_name":"official-user","user_nickname":"官方昵称"}}""",
            ),
        )
        try {
            val client = TiebaOfficialClient(
                context = context,
                gson = Gson(),
                account = account,
                config = config,
                baseUrl = server.url("/").toString(),
                clock = { 1_700_000_000_000 },
                stNumber = { 100 },
                stSizeFactor = { 1.0 },
            )

            val session = client.login("bduss", "stoken")
            val loginRequest = server.takeRequest()
            val initRequest = server.takeRequest()
            val loginFields = parseFormForTest(loginRequest.body.readUtf8())
            val initFields = parseFormForTest(initRequest.body.readUtf8())

            assertEquals("/c/s/login", loginRequest.path)
            assertEquals("bduss|", loginFields["bdusstoken"])
            assertEquals("stoken", loginFields["stoken"])
            assertNull(loginFields["BDUSS"])
            assertNull(loginFields["user_id"])
            assertEquals("null", loginFields["authsid"])
            assertEquals("11.10.8.6", loginFields["_client_version"])
            assertEquals("bdtb for Android 11.10.8.6", loginRequest.getHeader("User-Agent"))
            assertEquals("ka=open", loginRequest.getHeader("Cookie"))
            assertNull(loginRequest.getHeader("Charset"))
            assertNull(loginRequest.getHeader("client_type"))

            assertEquals("/c/s/initNickname", initRequest.path)
            assertEquals("bduss", initFields["BDUSS"])
            assertEquals("stoken", initFields["stoken"])
            assertEquals("11.10.8.6", initFields["_client_version"])
            assertEquals("ka=open", initRequest.getHeader("Cookie"))
            assertEquals(9, session.uid)
            assertEquals("official-user", session.name)
            assertEquals("官方昵称", session.nickname)
            assertEquals("official-tbs", session.tbs)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun officialLoginUsesInitNicknameIdentityWhenLoginUserNameIsBlank() = runBlocking {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "fixed-android-id")
        val account = AccountEntity(
            uid = 7,
            name = "web-user",
            nickname = "web-nickname",
            bduss = "bduss",
            tbs = "web-tbs",
            portrait = "web-portrait",
            sToken = "stoken",
            cookie = "BDUSS=bduss; STOKEN=stoken",
        )
        val config = TiebaClientConfig(
            uuid = "00000000-1111-2222-3333-444444444444",
            clientId = null,
            sampleId = null,
            baiduId = null,
            activeTimestamp = 1_600_000_000_000,
            firstInstallTime = 1_500_000_000_000,
            lastUpdateTime = 1_650_000_000_000,
        )
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"error_code":"0","anti":{"tbs":"official-tbs"},"user":{"id":"9","name":"","portrait":"portrait-token"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"error_code":"0","user_info":{"name_show":"官方昵称","tieba_uid":"9","user_name":"official-user","user_nickname":"官方昵称"}}""",
            ),
        )
        try {
            val session = TiebaOfficialClient(
                context = context,
                gson = Gson(),
                account = account,
                config = config,
                baseUrl = server.url("/").toString(),
                clock = { 1_700_000_000_000 },
                stNumber = { 100 },
                stSizeFactor = { 1.0 },
            ).login("bduss", "stoken")

            assertEquals(9, session.uid)
            assertEquals("official-user", session.name)
            assertEquals("官方昵称", session.nickname)
            assertEquals("portrait-token", session.portrait)
            assertEquals("official-tbs", session.tbs)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun officialSignResponseRequiresCompleteTiebaLiteUserInfo() {
        val alreadySigned = mapOfficialSignFailure(1101, "已经签到过了")
        val notFollowed = mapOfficialSignFailure(1004, "未关注该吧")
        val dataFailure = mapOfficialSignFailure(300004, "数据加载失败")
        val success = mapOfficialSignResult(
            TiebaSignResultBean(
                errorCode = "0",
                userInfo = TiebaSignResultBean.UserInfo(
                    isSignIn = 1,
                    contSignNum = 8,
                    userSignRank = 42,
                    signBonusPoint = 4,
                ),
            ),
        )
        val invalid = mapOfficialSignResult(
            TiebaSignResultBean(
                errorCode = "0",
                userInfo = TiebaSignResultBean.UserInfo(isSignIn = 1, contSignNum = 8),
            ),
        )

        assertEquals(edu.ccit.webvpn.feature.tieba.SignOutcome.ALREADY_SIGNED, alreadySigned.outcome)
        assertEquals("今日已经签到", alreadySigned.message)
        assertEquals(edu.ccit.webvpn.feature.tieba.SignOutcome.FAILED, notFollowed.outcome)
        assertEquals("尚未关注长春工程学院吧", notFollowed.message)
        assertEquals("贴吧签到提交失败：数据加载失败（错误码 300004）", dataFailure.message)
        assertEquals(edu.ccit.webvpn.feature.tieba.SignOutcome.SUCCESS, success.outcome)
        assertEquals(8, success.signedDays)
        assertEquals(edu.ccit.webvpn.feature.tieba.SignOutcome.FAILED, invalid.outcome)
        assertEquals("贴吧签到提交失败：签到响应数据无效", invalid.message)
    }

    @Test
    fun officialFailureInterceptorAcceptsTiebaLiteErrorAliases() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"no":1101,"error":{"errmsg":"已经签到过了"}}"""))
        try {
            val failure = runCatching {
                TiebaOfficialClient(
                    context = context,
                    gson = Gson(),
                    account = AccountEntity(
                        uid = 7,
                        name = "user",
                        nickname = "nickname",
                        bduss = "bduss",
                        tbs = "old-tbs",
                        portrait = "",
                        sToken = "stoken",
                        cookie = "BDUSS=bduss; STOKEN=stoken",
                    ),
                    config = TiebaClientConfig(
                        uuid = "error-uuid",
                        clientId = null,
                        sampleId = null,
                        baiduId = null,
                        activeTimestamp = 10,
                        firstInstallTime = 20,
                        lastUpdateTime = 30,
                    ),
                    baseUrl = server.url("/").toString(),
                    clock = { 1_700_000_000_000 },
                    stNumber = { 100 },
                    stSizeFactor = { 1.0 },
                ).sign(TARGET_FORUM_ID.toString(), TARGET_FORUM_NAME, "fresh-tbs")
            }.exceptionOrNull() as TiebaApiException

            assertEquals(1101, failure.code)
            assertEquals("已经签到过了", failure.message)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun repositorySignUsesCurrentFrsTbsWithoutProfileOrStoredTbs() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"error_code":"0","user_info":{"cont_sign_num":9,"user_sign_rank":2,"sign_bonus_point":4}}""",
            ),
        )
        val supportApi = FakeTiebaSupportApi()
        val account = AccountEntity(
            uid = 7,
            name = "user",
            nickname = "nickname",
            bduss = "bduss",
            tbs = "stale-account-tbs",
            portrait = "",
            sToken = "stoken",
            cookie = "BDUSS=bduss; STOKEN=stoken; BAIDUID=device-id",
        )
        val fixedConfig = TiebaClientConfig(
            uuid = "fixed-uuid",
            clientId = null,
            sampleId = null,
            baiduId = "device-id",
            activeTimestamp = 10,
            firstInstallTime = 20,
            lastUpdateTime = 30,
        )
        try {
            val repository = repository(
                readApi = FakeTiebaReadApi(successForum(), successThread()),
                supportApi = supportApi,
                officialClientFactory = { current, _ ->
                    TiebaOfficialClient(
                        context,
                        Gson(),
                        current,
                        fixedConfig,
                        server.url("/").toString(),
                        clock = { 1_700_000_000_000 },
                        stNumber = { 100 },
                        stSizeFactor = { 1.0 },
                    )
                },
            )

            val response = repository.sign(account, "fresh-forum-tbs")
            val signRequest = server.takeRequest()
            val fields = parseFormForTest(signRequest.body.readUtf8())

            assertEquals("/c/c/forum/sign", signRequest.path)
            assertEquals("7", signRequest.getHeader("client_user_token"))
            assertEquals("fresh-forum-tbs", fields["tbs"])
            assertTrue(fields.values.none { it == "stale-account-tbs" })
            assertEquals(0, supportApi.profileCalls)
            assertEquals(edu.ccit.webvpn.feature.tieba.SignOutcome.SUCCESS, response.outcome)
            assertEquals(9, response.signedDays)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun mobile300004FallsBackToHttpsWebSign() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"error_code":300004,"error_msg":"数据加载失败"}"""))
        val account = AccountEntity(
            uid = 7,
            name = "user",
            nickname = "nickname",
            bduss = "bduss",
            tbs = "stored-tbs",
            portrait = "",
            sToken = "stoken",
            cookie = "BDUSS=bduss; STOKEN=stoken",
        )
        var fallbackCalls = 0
        try {
            val response = repository(
                readApi = FakeTiebaReadApi(successForum(), successThread()),
                officialClientFactory = { current, config ->
                    TiebaOfficialClient(
                        context,
                        Gson(),
                        current,
                        config,
                        server.url("/").toString(),
                        clock = { 1_700_000_000_000 },
                        stNumber = { 100 },
                        stSizeFactor = { 1.0 },
                    )
                },
                webSignFallback = { fallbackAccount, forumName, tbs, attemptId ->
                    fallbackCalls++
                    assertEquals(account, fallbackAccount)
                    assertEquals(TARGET_FORUM_NAME, forumName)
                    assertEquals("fresh-forum-tbs", tbs)
                    assertEquals("attempt-300004", attemptId)
                    SignResponse(SignOutcome.SUCCESS, "签到成功", 12)
                },
            ).sign(
                account = account,
                forumId = TARGET_FORUM_ID,
                forumName = TARGET_FORUM_NAME,
                tbs = "fresh-forum-tbs",
                diagnosticAttempt = "attempt-300004",
            )

            assertEquals(SignOutcome.SUCCESS, response.outcome)
            assertEquals(12, response.signedDays)
            assertEquals(1, fallbackCalls)
            assertEquals("/c/c/forum/sign", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun webSignFallbackUsesCookieAndDisplayedFrsTbs() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"no":0,"error":"success","data":{"uinfo":{"cont_sign_num":13,"user_sign_rank":2}}}""",
            ),
        )
        val account = AccountEntity(
            uid = 7,
            name = "user",
            nickname = "nickname",
            bduss = "secret-bduss",
            tbs = "stored-tbs",
            portrait = "",
            sToken = "secret-stoken",
            cookie = "BDUSS=secret-bduss; STOKEN=secret-stoken; BAIDUID=device-id",
        )
        try {
            val response = TiebaWebSignClient(
                context = context,
                gson = Gson(),
                baseUrl = server.url("/").toString(),
            ).sign(account, TARGET_FORUM_NAME, "displayed-frs-tbs", "web-attempt")
            val request = server.takeRequest()
            val fields = parseFormForTest(request.body.readUtf8())

            assertEquals("/sign/add", request.path)
            assertEquals(account.cookie, request.getHeader("Cookie"))
            assertEquals("displayed-frs-tbs", fields["tbs"])
            assertEquals(TARGET_FORUM_NAME, fields["kw"])
            assertNull(request.getHeader(SIGN_DIAGNOSTIC_ATTEMPT_HEADER))
            assertEquals(SignOutcome.SUCCESS, response.outcome)
            assertEquals(13, response.signedDays)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun webSignStringDataReturnsServer1011InsteadOfCrashingGson() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"no":1011,"error":"您还未加入此吧或等级不够","data":""}""",
            ),
        )
        val account = AccountEntity(
            uid = 7,
            name = "user",
            nickname = "nickname",
            bduss = "bduss",
            tbs = "stored-tbs",
            portrait = "",
            sToken = "stoken",
            cookie = "BDUSS=bduss; STOKEN=stoken",
        )
        try {
            val response = TiebaWebSignClient(
                context = context,
                gson = Gson(),
                baseUrl = server.url("/").toString(),
            ).sign(account, TARGET_FORUM_NAME, "displayed-frs-tbs", "web-1011")

            assertEquals(SignOutcome.FAILED, response.outcome)
            assertTrue(response.message.contains("未关注"))
            assertTrue(response.message.contains("等级不足"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun signDiagnosticsNeverPersistCredentialOrTbsPlaintext() = runBlocking {
        val diagnostics = TiebaSignDiagnostics.get(context)
        diagnostics.clear()
        val account = AccountEntity(
            uid = 987654,
            name = "user",
            nickname = "nickname",
            bduss = "plain-bduss-must-not-leak",
            tbs = "stored-tbs-must-not-leak",
            portrait = "",
            sToken = "plain-stoken-must-not-leak",
            cookie = "BDUSS=plain-bduss-must-not-leak; STOKEN=plain-stoken-must-not-leak",
        )
        val attempt = diagnostics.startAttempt(
            source = "test",
            account = account,
            forum = ForumSummary(
                id = TARGET_FORUM_ID.toString(),
                name = TARGET_FORUM_NAME,
                tbs = "displayed-tbs-must-not-leak",
            ),
        )
        val body = FormBody.Builder()
            .add("BDUSS", account.bduss)
            .add("tbs", "displayed-tbs-must-not-leak")
            .add("sign", "plain-sign-must-not-leak")
            .add("kw", TARGET_FORUM_NAME)
            .build()
        diagnostics.recordHttpRequest(
            attempt,
            "test",
            Request.Builder()
                .url("https://c.tieba.baidu.com/c/c/forum/sign")
                .header("Cookie", account.cookie)
                .post(body)
                .build(),
        )

        val exported = diagnostics.exportText()
        assertFalse(exported.contains("plain-bduss-must-not-leak"))
        assertFalse(exported.contains("plain-stoken-must-not-leak"))
        assertFalse(exported.contains("stored-tbs-must-not-leak"))
        assertFalse(exported.contains("displayed-tbs-must-not-leak"))
        assertFalse(exported.contains("plain-sign-must-not-leak"))
        assertTrue(exported.contains("sha256:"))
        diagnostics.clear()
    }

    @Test
    fun officialLoginRejectionIdentifiesTheFailedStageAndCode() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"error_code":300004,"error_msg":"数据加载失败"}"""))
        val account = AccountEntity(
            uid = 7,
            name = "user",
            nickname = "nickname",
            bduss = "bduss",
            tbs = "old-tbs",
            portrait = "",
            sToken = "stoken",
            cookie = "BDUSS=bduss; STOKEN=stoken",
        )
        try {
            val error = runCatching {
                repository(
                    readApi = FakeTiebaReadApi(successForum(), successThread()),
                    officialClientFactory = { current, config ->
                        TiebaOfficialClient(
                            context,
                            Gson(),
                            current,
                            config,
                            server.url("/").toString(),
                            clock = { 1_700_000_000_000 },
                            stNumber = { 100 },
                            stSizeFactor = { 1.0 },
                        )
                    },
                ).refreshOfficialAccount(account)
            }.exceptionOrNull() as TiebaApiException

            assertTrue(error.message.contains("贴吧官方登录校验失败"))
            assertTrue(error.message.contains("300004"))
            assertEquals("/c/s/login", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun signRejectsMissingFrsTbsBeforeNetworkCall() = runBlocking {
        val response = repository(FakeTiebaReadApi(successForum(), successThread())).sign(
            AccountEntity(
                uid = 7,
                name = "user",
                nickname = "nickname",
                bduss = "bduss",
                tbs = "stale-account-tbs",
                portrait = "",
                sToken = "stoken",
                cookie = "BDUSS=bduss; STOKEN=stoken",
            ),
            "",
        )
        assertEquals(edu.ccit.webvpn.feature.tieba.SignOutcome.FAILED, response.outcome)
        assertEquals("贴吧认证 FRS 失败：签到凭据无效", response.message)
    }

    @Test
    fun clientIdentityAndSyncStateArePersisted() = runBlocking {
        val settings = TiebaSettingsRepository(context)
        val first = settings.clientConfig("persisted-baidu-id")
        settings.updateClientSync("persisted-client-id", "persisted-sample-id")
        settings.refreshClientActiveTimestamp(1_700_000_000_123)
        val second = settings.clientConfig()

        assertEquals(first.uuid, second.uuid)
        assertEquals(first.firstInstallTime, second.firstInstallTime)
        assertEquals(first.lastUpdateTime, second.lastUpdateTime)
        assertEquals("persisted-client-id", second.clientId)
        assertEquals("persisted-sample-id", second.sampleId)
        assertEquals("persisted-baidu-id", second.baiduId)
        assertEquals(1_700_000_000_123, second.activeTimestamp)
    }

    @Test
    fun clientSyncUsesTiebaLiteEndpointAndFailureDoesNotBlockForumRead() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"client":{"client_id":"synced-client"},"wl_config":{"sample_id":"synced-sample"}}""",
            ),
        )
        server.enqueue(MockResponse().setBody("""{"error_code":321,"error_msg":"sync rejected"}"""))
        val settings = TiebaSettingsRepository(context)
        val fixedConfig = TiebaClientConfig(
            uuid = "sync-uuid",
            clientId = null,
            sampleId = null,
            baiduId = "sync-baidu-id",
            activeTimestamp = 10,
            firstInstallTime = 20,
            lastUpdateTime = 30,
        )
        try {
            val repository = repository(
                readApi = FakeTiebaReadApi(successForum(), successThread()),
                officialClientFactory = { account, _ ->
                    TiebaOfficialClient(
                        context,
                        Gson(),
                        account,
                        fixedConfig,
                        server.url("/").toString(),
                        clock = { 1_700_000_000_000 },
                        stNumber = { 100 },
                        stSizeFactor = { 1.0 },
                    )
                },
                settings = settings,
            )

            repository.syncClientConfig(null)
            val syncRequest = server.takeRequest()
            val syncFields = parseFormForTest(syncRequest.body.readUtf8())
            val snapshot = settings.clientConfig()

            assertEquals("/c/s/sync", syncRequest.path)
            assertEquals("synced-client", snapshot.clientId)
            assertEquals("synced-sample", snapshot.sampleId)
            assertNull(syncFields["oaid"])
            assertNull(syncFields["mac"])
            assertNull(syncFields["_phone_imei"])
            assertNull(syncFields["android_id"])
            assertNull(syncFields["swan_game_ver"])
            assertNull(syncFields["sdk_ver"])

            val syncFailure = runCatching { repository.syncClientConfig(null) }
            val forumAfterFailure = repository.loadForum(1, ForumSort.BY_REPLY, goodOnly = false)
            assertTrue(syncFailure.isFailure)
            assertEquals(TARGET_FORUM_NAME, forumAfterFailure.forum.name)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun picPageResolutionUsesTiebaLitesSignedWaterUrl() = runBlocking {
        val signedOriginal =
            "https://tiebapic.baidu.com/forum/pic/item/picture.jpg?tbpicau=2026-07-27_token"
        val picPageApi = FakeTiebaPicPageApi(
            PicPageResponse(
                errorCode = "0",
                picList = listOf(
                    PicPagePicture(
                        overallIndex = "1",
                        img = PicPageImage(
                            original = PicPageImageInfo(
                                id = "picture",
                                width = "160",
                                height = "142",
                                size = "6497",
                                format = "1",
                                waterUrl = signedOriginal,
                                bigCdnSrc = "https://tiebapic.baidu.com/forum/w=960/picture.jpg?tbpicau=preview",
                                url = signedOriginal,
                                originalSrc = signedOriginal,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val request = LoadPicPageData(
            forumId = TARGET_FORUM_ID,
            forumName = TARGET_FORUM_NAME,
            seeLz = false,
            objType = "pb",
            picId = "picture",
            picIndex = 1,
            threadId = 9,
            postId = 12,
            originUrl = "https://tiebapic.baidu.com/forum/pic/item/picture.jpg",
        )

        val resolved = repository(
            FakeTiebaReadApi(successForum(), successThread()),
            picPageApi = picPageApi,
        ).resolveOriginalImage(request)

        assertEquals(signedOriginal, resolved)
        assertTrue(resolved.contains("tbpicau="))
    }

    @Test
    fun miniPicPageRequestMatchesTiebaLiteFieldsAndSignature() {
        val identity = TiebaClientIdentity(context)
        val fields = TiebaPicPageRequestFactory(context, identity).picPage(
            LoadPicPageData(
                forumId = TARGET_FORUM_ID,
                forumName = TARGET_FORUM_NAME,
                seeLz = false,
                objType = "pb",
                picId = "picture",
                picIndex = 1,
                threadId = 9,
                postId = 12,
                originUrl = null,
            ),
            credentials = null,
        )

        assertEquals("7.2.0.0", fields["_client_version"])
        assertEquals("mini", fields["subapp_type"])
        assertEquals("10", fields["next"])
        assertEquals("0", fields["prev"])
        assertEquals("1", fields["not_see_lz"])
        assertEquals(miniTiebaSign(fields), fields["sign"])
    }

    private fun repository(
        readApi: TiebaReadApi,
        supportApi: TiebaSupportApi = FakeTiebaSupportApi(),
        picPageApi: TiebaPicPageApi = FakeTiebaPicPageApi(),
        officialClientFactory: ((AccountEntity?, TiebaClientConfig) -> TiebaOfficialClient)? = null,
        webSignFallback: (suspend (AccountEntity, String, String, String?) -> SignResponse)? = null,
        settings: TiebaSettingsRepository = TiebaSettingsRepository(context),
    ): TiebaNetworkRepository {
        val identity = TiebaClientIdentity(context)
        return TiebaNetworkRepository(
            context = context,
            client = OkHttpClient(),
            supportApi = supportApi,
            readApi = readApi,
            readRequests = TiebaReadRequestFactory(context, identity),
            picPageApi = picPageApi,
            picPageRequests = TiebaPicPageRequestFactory(context, identity),
            gson = Gson(),
            settings = settings,
            officialClientFactory = officialClientFactory ?: { account, config ->
                TiebaOfficialClient(context, Gson(), account, config)
            },
            webSignFallback = webSignFallback ?: { account, forumName, tbs, attemptId ->
                TiebaWebSignClient(context, Gson()).sign(account, forumName, tbs, attemptId)
            },
            zidProvider = { "test-zid" },
        )
    }

    private fun successForum(
        forumId: Long = TARGET_FORUM_ID,
        forumName: String = TARGET_FORUM_NAME,
    ) = FrsPageResponse(
        error = Error(error_code = 0),
        data_ = FrsPageResponseData(
            anti = Anti(tbs = "fresh-forum-tbs"),
            forum = ForumInfo(
                id = forumId,
                name = forumName,
                member_num = 10,
                sign_in_info = SignInfo(user_info = SignUser(is_sign_in = 1, cont_sign_num = 6)),
            ),
            page = Page(current_page = 1, total_page = 2, has_more = 1),
            thread_list = listOf(
                ThreadInfo(
                    threadId = 9,
                    title = "主题",
                    authorId = 4,
                    _abstract = listOf(ThreadAbstract(type = 0, text = "摘要")),
                    richAbstract = listOf(PbContent(type = 2, text = "image_emoticon9", c = "泪")),
                    forumInfo = SimpleForum(id = forumId, name = forumName),
                ),
            ),
            user_list = listOf(User(id = 4, name = "user", nameShow = "昵称", is_manager = 1)),
        ),
    )

    private fun successThread() = PbPageResponse(
        error = Error(error_code = 0),
        data_ = PbPageResponseData(
            forum = SimpleForum(id = TARGET_FORUM_ID, name = TARGET_FORUM_NAME),
            page = Page(current_page = 1, total_page = 1),
            anti = Anti(),
            thread = ThreadInfo(id = 9, title = "主题", replyNum = 3, author = User(id = 4, name = "user")),
            first_floor_post =
                Post(
                    id = 10,
                    floor = 1,
                    time = 1_700_000_000,
                    author_id = 4,
                    content = listOf(
                        PbContent(type = 0, text = "正文"),
                        PbContent(type = 2, text = "image_emoticon10", c = "黑线"),
                        PbContent(
                            type = 3,
                            bigCdnSrc = "https://img.example/preview.jpg?token=kept",
                            originSrc = "https://img.example/original.jpg?tbpicau=fresh-token",
                            bsize = "800,600",
                        ),
                    ),
                    sub_post_number = 1,
                    sub_post_list = SubPost(
                        sub_post_list = listOf(
                            SubPostList(
                                id = 11,
                                author_id = 5,
                                time = 1_700_000_100,
                                content = listOf(PbContent(type = 2, text = "image_emoticon9", c = "泪")),
                            ),
                        ),
                    ),
                ),
            post_list = listOf(
                Post(
                    id = 12,
                    floor = 2,
                    time = 1_700_000_000,
                    author_id = 4,
                    content = listOf(
                        PbContent(type = 0, text = "正文"),
                        PbContent(type = 2, text = "image_emoticon10", c = "黑线"),
                        PbContent(
                            type = 3,
                            bigCdnSrc = "https://img.example/preview.jpg?token=kept",
                            originSrc = "https://img.example/original.jpg?tbpicau=fresh-token",
                            bsize = "800,600",
                        ),
                    ),
                    sub_post_number = 1,
                    sub_post_list = SubPost(
                        sub_post_list = listOf(
                            SubPostList(
                                id = 11,
                                author_id = 5,
                                time = 1_700_000_100,
                                content = listOf(PbContent(type = 2, text = "image_emoticon9", c = "泪")),
                            ),
                        ),
                    ),
                ),
            ),
            user_list = listOf(
                User(id = 4, name = "user", nameShow = "昵称", level_id = 8, level_name = "渐入佳境", ip_address = "吉林", is_manager = 1),
                User(id = 5, name = "reply", nameShow = "回复者", level_id = 5, level_name = "小有名气", ip_address = "北京"),
            ),
        ),
    )

    private fun successFloor() = PbFloorResponse(
        error = Error(error_code = 0),
        data_ = PbFloorResponseData(
            forum = SimpleForum(id = TARGET_FORUM_ID, name = TARGET_FORUM_NAME),
            page = Page(current_page = 1, total_page = 1, total_count = 1),
            post = Post(
                id = 10,
                floor = 6,
                time = 1_700_000_000,
                author = User(id = 4, name = "user", nameShow = "原楼作者"),
                content = listOf(PbContent(type = 0, text = "原楼正文")),
                sub_post_number = 1,
            ),
            subpost_list = listOf(
                SubPostList(
                    id = 11,
                    time = 1_700_000_100,
                    author = User(id = 5, name = "reply", nameShow = "回复者", level_id = 5, level_name = "小有名气", ip_address = "北京"),
                    content = listOf(PbContent(type = 2, text = "image_emoticon9", c = "泪")),
                ),
            ),
        ),
    )
}

private fun md5ForTest(value: String): String = java.security.MessageDigest.getInstance("MD5")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }

private fun parseFormForTest(raw: String): LinkedHashMap<String, String> = linkedMapOf<String, String>().apply {
    raw.split('&').filter(String::isNotEmpty).forEach { entry ->
        put(
            URLDecoder.decode(entry.substringBefore('='), Charsets.UTF_8),
            URLDecoder.decode(entry.substringAfter('=', ""), Charsets.UTF_8),
        )
    }
}

private class FakeTiebaReadApi(
    private val forumResponse: FrsPageResponse,
    private val threadResponse: PbPageResponse,
    private val floorResponse: PbFloorResponse = PbFloorResponse(error = Error(error_code = 0)),
) : TiebaReadApi {
    var lastForumName: String = ""

    override suspend fun forum(body: RequestBody, forumName: String): FrsPageResponse {
        lastForumName = forumName
        return forumResponse
    }
    override suspend fun thread(body: RequestBody, userToken: String?): PbPageResponse = threadResponse
    override suspend fun floor(body: RequestBody, userToken: String?): PbFloorResponse = floorResponse
    override suspend fun profile(body: RequestBody, userToken: String?): ProfileResponse = ProfileResponse(error = Error(error_code = 0))
    override suspend fun userPosts(body: RequestBody, userToken: String?): UserPostResponse = UserPostResponse(error = Error(error_code = 0))
    override suspend fun forumRule(body: RequestBody): ForumRuleDetailResponse = ForumRuleDetailResponse(error = Error(error_code = 0))
    override suspend fun addPost(body: RequestBody, userToken: String): AddPostResponse =
        AddPostResponse(error = Error(error_code = 0))
}

private class FakeTiebaSupportApi(
    private val searchResponse: SearchEnvelope = SearchEnvelope(errorCode = 0, data = SearchData()),
) : TiebaSupportApi {
    var lastReferer: String = ""
    var profileCalls: Int = 0

    override suspend fun search(
        keyword: String,
        page: Int,
        order: Int,
        filter: Int,
        pageSize: Int,
        forumName: String,
        contentType: Int,
        useCombined: Int?,
        clientVersion: String,
        referer: String,
    ): SearchEnvelope {
        lastReferer = referer
        return searchResponse
    }

    override suspend fun profile(needUser: Int, cookie: String): ProfileEnvelope {
        profileCalls++
        return ProfileEnvelope()
    }
}

private class FakeTiebaPicPageApi(
    private val response: PicPageResponse = PicPageResponse(errorCode = "0"),
) : TiebaPicPageApi {
    override suspend fun picPage(fields: Map<String, String>): PicPageResponse = response
}
