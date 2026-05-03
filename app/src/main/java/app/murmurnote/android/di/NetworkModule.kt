package app.murmurnote.android.di

import app.murmurnote.android.data.remote.interceptor.ApiLogInterceptor
import app.murmurnote.android.data.remote.interceptor.DebugFlagsInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        apiLogInterceptor: ApiLogInterceptor,
        debugFlagsInterceptor: DebugFlagsInterceptor
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)      // GLM 长流式
            .writeTimeout(2, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS) // 不限制总时长（SSE 长跑）
            // 顺序很重要：debug 拦截器先跑，它注入的延时/失败会经过 apiLog 拦截器记录到 api_logs，
            // 这样调试开关产生的"假失败"也能在导出包里看到。
            .addInterceptor(debugFlagsInterceptor)
            .addInterceptor(apiLogInterceptor)
            .addInterceptor(logging)
            .build()
    }
}
