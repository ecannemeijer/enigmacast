package com.enigmacast.data.radio

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioBrowserHostInterceptor @Inject constructor(
    private val serverSelector: RadioBrowserServerSelector
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val host = serverSelector.resolveHost()
        val requestBuilder = original.newBuilder()
        if (original.url.host != host) {
            requestBuilder.url(
                original.url.newBuilder()
                    .scheme("https")
                    .host(host)
                    .build()
            )
        }
        return chain.proceed(requestBuilder.build())
    }
}
