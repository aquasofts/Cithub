package edu.ccit.webvpn.feature.tieba.network

import android.content.Context
import com.huanchengfly.tieba.post.api.models.protos.Error
import com.huanchengfly.tieba.post.api.models.protos.frsPage.ForumInfo
import com.huanchengfly.tieba.post.api.models.protos.frsPage.FrsPageRequest
import com.huanchengfly.tieba.post.api.models.protos.frsPage.FrsPageResponse
import com.huanchengfly.tieba.post.api.models.protos.frsPage.FrsPageResponseData
import com.huanchengfly.tieba.post.api.models.protos.pbFloor.PbFloorRequest
import com.huanchengfly.tieba.post.api.models.protos.pbPage.PbPageRequest
import com.huanchengfly.tieba.post.api.models.protos.profile.ProfileRequest
import com.huanchengfly.tieba.post.api.models.protos.userPost.UserPostRequest
import com.huanchengfly.tieba.post.api.models.protos.addPost.AddPostRequest
import edu.ccit.webvpn.feature.tieba.TARGET_FORUM_ID
import edu.ccit.webvpn.feature.tieba.data.TiebaClientConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import android.util.Base64

@RunWith(RobolectricTestRunner::class)
class TiebaWireTest {
    private lateinit var context: Context
    private lateinit var identity: TiebaClientIdentity
    private lateinit var requests: TiebaReadRequestFactory
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        identity = TiebaClientIdentity(context)
        requests = TiebaReadRequestFactory(context, identity)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `frs multipart carries encoded forum and structured account`() {
        val credentials = TiebaReadCredentials(7, "bduss", "stoken", null)
        val multipart = requests.forum(2, sortType = 1, goodOnly = true, loadType = 2, credentials) as MultipartBody
        val message = FrsPageRequest.ADAPTER.decode(multipart.dataPart())

        assertEquals("%E9%95%BF%E6%98%A5%E5%B7%A5%E7%A8%8B%E5%AD%A6%E9%99%A2", message.data_?.kw)
        assertEquals(2, message.data_?.pn)
        assertEquals(2, message.data_?.load_type)
        assertEquals(-1, message.data_?.sort_type)
        assertEquals(1, message.data_?.is_good)
        assertEquals("bduss", message.data_?.common?.BDUSS)
        assertEquals("stoken", message.data_?.common?.stoken)
        assertNotNull(multipart.parts.firstOrNull { it.headers?.get("Content-Disposition")?.contains("stoken") == true })
    }

    @Test
    fun `frs and official sign share the persisted TiebaLite client identity`() {
        val config = TiebaClientConfig(
            uuid = "shared-client-uuid",
            clientId = "shared-client-id",
            sampleId = "shared-sample-id",
            baiduId = "shared-baidu-id",
            activeTimestamp = 1_700_000_000_123,
            firstInstallTime = 1_600_000_000_000,
            lastUpdateTime = 1_650_000_000_000,
        )
        val expectedIdentity = TiebaOfficialIdentity.create(context, config.uuid)
        val multipart = requests.forum(
            page = 1,
            sortType = 0,
            goodOnly = false,
            loadType = 1,
            credentials = TiebaReadCredentials(7, "bduss", "stoken", "shared-zid"),
            clientConfig = config,
        ) as MultipartBody
        val common = FrsPageRequest.ADAPTER.decode(multipart.dataPart()).data_?.common

        assertEquals(expectedIdentity.cuid, common?.cuid)
        assertEquals(expectedIdentity.cuid, common?.cuid_galaxy2)
        assertEquals(expectedIdentity.aid, common?.c3_aid)
        assertEquals("shared-client-id", common?._client_id)
        assertEquals("shared-sample-id", common?.sample_id)
        assertEquals("000000000000000", common?._phone_imei)
        assertEquals("shared-zid", common?.z_id)
        assertEquals(
            Base64.encodeToString(
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID,
                ).orEmpty().ifBlank { "000" }.toByteArray(),
                Base64.DEFAULT,
            ),
            common?.android_id,
        )
        assertEquals(1_700_000_000_123, common?.active_timestamp)
        assertEquals(1_600_000_000_000, common?.first_install_time)
        assertEquals(1_650_000_000_000, common?.last_update_time)
        assertEquals(expectedIdentity.cuid, identity.cuid)
        assertEquals(expectedIdentity.aid, identity.aid)
        assertEquals("shared-client-id", identity.clientId)
    }

    @Test
    fun `pb multipart always carries target forum and anonymous credentials stay empty`() {
        val multipart = requests.thread(123, 3, sortType = 2, onlyOriginalPoster = true, credentials = null) as MultipartBody
        val message = PbPageRequest.ADAPTER.decode(multipart.dataPart())

        assertEquals(123L, message.data_?.kz)
        assertEquals(3, message.data_?.pn)
        assertEquals(2, message.data_?.r)
        assertEquals(1, message.data_?.lz)
        assertEquals(TARGET_FORUM_ID, message.data_?.forum_id)
        assertNull(message.data_?.common?.BDUSS)
        assertNull(message.data_?.common?.stoken)
    }

    @Test
    fun `pb floor multipart always carries target forum and identifiers`() {
        val multipart = requests.floor(123, 456, 2, 789, credentials = null) as MultipartBody
        val message = PbFloorRequest.ADAPTER.decode(multipart.dataPart())

        assertEquals(TARGET_FORUM_ID, message.data_?.forum_id)
        assertEquals(123L, message.data_?.kz)
        assertEquals(456L, message.data_?.pid)
        assertEquals(2, message.data_?.pn)
        assertEquals(789L, message.data_?.spid)
    }

    @Test
    fun `profile multipart follows TiebaLite guest profile fields`() {
        val credentials = TiebaReadCredentials(7, "bduss", "stoken", null)
        val multipart = requests.profile(uid = 9, credentials) as MultipartBody
        val message = ProfileRequest.ADAPTER.decode(multipart.dataPart())

        assertEquals(7L, message.data_?.uid)
        assertEquals(9L, message.data_?.friend_uid)
        assertEquals(1, message.data_?.is_guest)
        assertEquals(1, message.data_?.need_post_count)
        assertEquals(1, message.data_?.has_plist)
    }

    @Test
    fun `user post multipart selects reply mode for the current user`() {
        val credentials = TiebaReadCredentials(7, "bduss", "stoken", null)
        val multipart = requests.userPosts(uid = 7, page = 2, isThread = false, credentials) as MultipartBody
        val message = UserPostRequest.ADAPTER.decode(multipart.dataPart())

        assertEquals(7L, message.data_?.uid)
        assertEquals(2, message.data_?.pn)
        assertEquals(0, message.data_?.is_thread)
        assertEquals(0, message.data_?.subtype)
        assertEquals(1, message.data_?.need_content)
    }

    @Test
    fun `add post multipart matches TiebaLite nested reply fields`() {
        val credentials = TiebaReadCredentials(7, "bduss", "stoken", "zid")
        val multipart = requests.addPost(
            content = "回复 #(reply, portrait, 昵称) :正文",
            forumId = TARGET_FORUM_ID,
            forumName = "长春工程学院",
            threadId = 123,
            postId = 456,
            subPostId = 789,
            replyUserId = 9,
            nickname = "当前用户",
            tbs = "tbs",
            credentials = credentials,
        ) as MultipartBody
        val message = AddPostRequest.ADAPTER.decode(multipart.dataPart())

        assertEquals(TIEBA_V12_POST_VERSION, message.data_?.common?._client_version)
        assertEquals("tbs", message.data_?.common?.tbs)
        assertEquals("456", message.data_?.quote_id)
        assertEquals("456", message.data_?.repostid)
        assertEquals("789", message.data_?.sub_post_id)
        assertEquals("9", message.data_?.reply_uid)
        assertNull(message.data_?.post_from)
        assertEquals("当前用户", message.data_?.name_show)
        assertNotNull(multipart.parts.firstOrNull { it.headers?.get("Content-Disposition")?.contains("stoken") == true })
    }

    @Test
    fun `real protobuf retrofit service decodes frs and sends TiebaLite headers`() = runBlocking {
        val expected = FrsPageResponse(
            error = Error(error_code = 0),
            data_ = FrsPageResponseData(forum = ForumInfo(id = TARGET_FORUM_ID, name = "长春工程学院")),
        )
        server.enqueue(
            okhttp3.mockwebserver.MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(expected.encode())),
        )
        val client = OkHttpClient.Builder().addInterceptor(tiebaReadHeaderInterceptor(context, identity)).build()
        val api = createTiebaReadRetrofit(client, server.url("/").toString()).create(TiebaReadApi::class.java)

        val actual = api.forum(requests.forum(1, 0, false, 1, null), TiebaReadRequestFactory.encodedForumName())
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)

        assertEquals(expected, actual)
        assertEquals("/c/f/frs/page?cmd=301001", recorded?.path)
        assertEquals("protobuf", recorded?.getHeader("x_bd_data_type"))
        assertEquals("2", recorded?.getHeader("client_type"))
        assertEquals("ka:open; CUID:${identity.cuid}; TBBRAND:${android.os.Build.MODEL}", recorded?.getHeader("cookie"))
        assertEquals(TiebaReadRequestFactory.encodedForumName(), recorded?.getHeader("forum_name"))
        assertNull(recorded?.getHeader("X-CCIT-Tieba-Request"))
    }

    private fun MultipartBody.dataPart(): Buffer = Buffer().also { buffer -> parts.last().body.writeTo(buffer) }
}
