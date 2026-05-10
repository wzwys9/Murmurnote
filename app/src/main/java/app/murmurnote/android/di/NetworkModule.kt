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
            .addInterceptor(apiLogInterceptor)
            // 顺序很重要：apiLog 拦截器包住 debug 拦截器，debug 注入的延时/失败才会进入 api_logs。
            .addInterceptor(debugFlagsInterceptor)
            .addInterceptor(logging)
            .build()
    }
}
