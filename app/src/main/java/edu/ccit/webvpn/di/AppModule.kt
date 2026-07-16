package edu.ccit.webvpn.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import edu.ccit.webvpn.CaptchaAutomationProvider
import edu.ccit.webvpn.core.academic.AcademicRepository
import edu.ccit.webvpn.core.captcha.CaptchaAutomation
import edu.ccit.webvpn.core.webvpn.AcademicCredentialStore
import edu.ccit.webvpn.core.webvpn.WebVpnApi
import edu.ccit.webvpn.core.webvpn.WebVpnAuthRepository
import edu.ccit.webvpn.core.webvpn.WebVpnCookieJar
import edu.ccit.webvpn.core.webvpn.WebVpnCredentialStore
import edu.ccit.webvpn.core.webvpn.WebVpnNetwork
import edu.ccit.webvpn.core.webvpn.WebVpnSessionManager
import edu.ccit.webvpn.core.webvpn.WebVpnSessionStore
import edu.ccit.webvpn.settings.DataStoreSettingsRepository
import edu.ccit.webvpn.settings.SettingsRepository
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun sessionManager(@ApplicationContext context: Context) = WebVpnSessionManager(context)

    @Provides
    fun sessionStore(manager: WebVpnSessionManager): WebVpnSessionStore = manager

    @Provides
    fun webVpnCredentialStore(manager: WebVpnSessionManager): WebVpnCredentialStore = manager

    @Provides
    fun academicCredentialStore(manager: WebVpnSessionManager): AcademicCredentialStore = manager

    @Provides
    @Singleton
    fun cookieJar() = WebVpnCookieJar()

    @Provides
    @Singleton
    fun httpClient(cookieJar: WebVpnCookieJar): OkHttpClient = WebVpnNetwork.createClient(cookieJar)

    @Provides
    @Singleton
    fun webVpnApi(client: OkHttpClient): WebVpnApi = WebVpnNetwork.createApi(client)

    @Provides
    @Singleton
    fun webVpnRepository(
        api: WebVpnApi,
        sessionStore: WebVpnSessionStore,
        cookieJar: WebVpnCookieJar,
    ) = WebVpnAuthRepository(api, sessionStore, cookieJar)

    @Provides
    @Singleton
    fun academicRepository(
        client: OkHttpClient,
        sessionStore: WebVpnSessionStore,
        cookieJar: WebVpnCookieJar,
    ) = AcademicRepository(client, sessionStore, cookieJar)

    @Provides
    fun captchaAutomation(@ApplicationContext context: Context): CaptchaAutomation =
        CaptchaAutomationProvider.get(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds
    @Singleton
    abstract fun settingsRepository(
        implementation: DataStoreSettingsRepository,
    ): SettingsRepository
}
