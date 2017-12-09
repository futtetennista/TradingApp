package com.futtetennista.trading.product

import android.util.Log
import io.reactivex.*
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Function
import okhttp3.*
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


class Api(private val httpClient: OkHttpClient, private val restfulEndpoint: RestfulEndpoint,
          private val productsChannelEndpoint: RTFEndpoint) {

    private val productsChannelConnectionFactory: (WebSocketListener) -> WebSocket = { listener ->
        val endpoint = productsChannelEndpoint
                .newBuilder()
                .addEncodedPathSegments("subscriptions/me")
                .build()
        val request = Request.Builder()
                .header("Authorization", authToken)
                .header("Accept-Language", "NL_nl,en;q=0.8")
                .url(endpoint)
                .build()
        httpClient.newWebSocket(request, listener)
    }

    fun fetchProductDetails(productId: ProductId): Single<ProductDetails?> {
        fun fromUnsuccessfulResponse(response: Response): RootException {
            val (code, errorDetails) = RestfulException.fromResponse(response)
            return when {
                errorDetails == null -> HttpException(code)
                errorDetails.errorCode.startsWith("AUTH_") ->
                    AuthException(code, errorDetails)
                errorDetails.errorCode.startsWith("TRADING_") ->
                    TradingException(code, errorDetails)
            // TODO#Enhancement: Add more exception (not added here because they'are not in the
            // specs)
                else -> GeneralException(code, errorDetails)
            }
        }

        return Single.create { emitter: SingleEmitter<ProductDetails?> ->
            fun newCall(productId: ProductId): Call {
                val url = restfulEndpoint
                        .newBuilder()
                        .addEncodedPathSegments("core/16/products")
                        .addPathSegment(productId)
                        .build()
                val request = Request.Builder()
                        .header("Authorization", authToken)
                        .header("Accept-Language", "NL_nl,en;q=0.8")
                        .header("Accept", "application/json")
                        .url(url)
                        .build()
                return httpClient.newCall(request)
            }

            val call = newCall(productId)

            emitter.setDisposable(object : Disposable {
                var disposed = AtomicBoolean(false)
                override fun isDisposed(): Boolean =
                        disposed.get()

                override fun dispose() {
                    disposed.set(true)
                    Log.d("[Api#fetchProductDetails]", "disposed")
                    call.cancel()
                }
            })

            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) emitter.onError(fromUnsuccessfulResponse(response))

                    response.body()?.let {
                        ProductDetails.fromJson(it.source())?.let {
                            emitter.onSuccess(it)
                        } ?: emitter.onError(Exception("Failed to parse ProductDetails response"))
                    } ?: emitter.onError(Exception("Failed to read ProductDetails response"))
                }
            } catch (e: IOException) {
                if (!call.isCanceled) emitter.onError(e)
            }
        }
    }

    // Backpressure: if the app is overwhelmed by server messages just drop all old messages, the
    // reason being it needs to show only the latest data so it can safely "forget" about old quotes
    fun getProductsChannelUpdates(events: Observable<ProductsChannelEvent>):
            Flowable<PublicProductsChannelMessage> =
            Flowable.create({ emitter: FlowableEmitter<PublicProductsChannelMessage> ->
                val productsChannel =
                        ProductsChannel(productsChannelConnectionFactory, emitter, events)

                emitter.setDisposable(object : Disposable {
                    var disposed = AtomicBoolean(false)
                    override fun isDisposed(): Boolean =
                            disposed.get()

                    override fun dispose() {
                        disposed.set(true)
                        Log.d("[Api#getProductsChannelUpdates]", "Disposed(client)")
                        // Try to close the connection gracefully even if we're not sure at this
                        // point if we were disposed because of a unexpected or expected event.
                        productsChannel.closeGracefully()
                    }
                })
            }, BackpressureStrategy.LATEST)

    companion object {
        private val authToken = "Bearer eyJhbGciOiJIUzI1NiJ9.eyJyZWZyZXNoYWJsZSI6ZmFsc2UsInN1Yi" +
                "I6ImJiMGNkYTJiLWExMGUtNGVkMy1hZDVhLTBmODJiNGMxNTJjNCIsImF1ZCI6ImJldGEuZ2V0YnV4" +
                "LmNvbSIsInNjcCI6WyJhcHA6bG9naW4iLCJydGY6bG9naW4iXSwiZXhwIjoxODIwODQ5Mjc5LCJpYX" +
                "QiOjE1MDU0ODkyNzksImp0aSI6ImI3MzlmYjgwLTM1NzUtNGIwMS04NzUxLTMzZDFhNGRjOGY5MiIs" +
                "ImNpZCI6Ijg0NzM2MjI5MzkifQ.M5oANIi2nBtSfIfhyUMqJnex-JYg6Sm92KPYaUL9GKg"
    }
}

// See: https://www.awsarchitectureblog.com/2015/03/backoff.html
// See: https://stackoverflow.com/a/25292833/389262
class RetryBackoffDecorrelatedJitter(private val maxRetries: Int,
                                     private val baseDelayMs: Long,
                                     private val retryPredicate: (Throwable, Int) -> Boolean) :
        Function<Flowable<out Throwable>, Flowable<*>> {
    private var retryCount = 0
    private val capMs = TimeUnit.MILLISECONDS.convert(5L, TimeUnit.SECONDS)

    override fun apply(retries: Flowable<out Throwable>) =
            retries.flatMap { error: Throwable ->
                if (retryCount > maxRetries || !retryPredicate(error, retryCount)) {
                    Log.d("[RetryBackoffDecorrelatedJitter]",
                            "Not retrying (retryCount=$retryCount," +
                                    "predicate=${!retryPredicate(error, retryCount)}): $error")
                    Flowable.error(error)
                } else {
                    retryCount++
                    val sleepMs =
                            minOf(capMs, baseDelayMs * Math.pow(2.0, retryCount.toDouble()).toLong())
                    val min = minOf(capMs, sleepMs)
                    val max = maxOf(capMs, sleepMs)
                    val delay =
                            if (min < max) random.longs(1, min, max).findFirst().asLong
                            else capMs
                    Log.d("[RetryBackoffDecorrelatedJitter]",
                            "retry in $delay ms (retryCount=$retryCount,$=$error)")
                    Flowable.timer(delay, TimeUnit.MILLISECONDS)
                }
            }!!

    companion object {
        val random = Random()
    }
}
