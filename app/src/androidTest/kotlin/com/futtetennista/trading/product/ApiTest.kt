package com.futtetennista.trading.product

import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import android.util.Log
import io.reactivex.Observable
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.Okio
import org.junit.After
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit


@RunWith(AndroidJUnit4::class)
class ApiTest {

    private var server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchProductDetailsSuccessfully() {
        val (restfulEndpoint, wsEndpoint) =
                setupMockWebServerRestful("product_details_success_gold.json", 200)
        val expected =
                ProductDetails("GOLD", "sb26500", "Gold",
                        Price("USD", 1, 1587.4),
                        Price("USD", 1, 1342.1))
        val httpClient = OkHttpClient.Builder().build()
        Api(httpClient, restfulEndpoint!!, wsEndpoint!!)
                .fetchProductDetails("sb26500")
                .test()
                .assertNoErrors()
                .assertValue(expected)
                .dispose()
    }

    @Test
    fun fetchProductDetailsUnsuccessfullyUnauthorised() {
        val (restfulEndpoint, wsEndpoint) =
                setupMockWebServerRestful("product_details_failure.json", 401)
        val expected = AuthException(401, ErrorDetails(null, "foobar", "AUTH_001"))
        val httpClient = OkHttpClient.Builder().build()
        Api(httpClient, restfulEndpoint!!, wsEndpoint!!)
                .fetchProductDetails("sb26496")
                .test()
                .assertNoValues()
                .assertError(expected)
                .dispose()
    }

    @Test
    fun fetchProductDetailsUnsuccessfullyProductIdNotFound() {
        val (restfulEndpoint, wsEndpoint) = setupMockWebServerRestful(code = 404)
        val expected = HttpException(404)
        val httpClient = OkHttpClient.Builder().build()
        Api(httpClient, restfulEndpoint!!, wsEndpoint!!)
                .fetchProductDetails("sb26496")
                .test()
                .assertNoValues()
                .assertError(expected)
                .dispose()
    }

    @Test
    fun getProductChannelUpdatesSuccessfully() {
        val (restfulEndpoint, wsEndpoint) =
                setupMockWebServerWs(true, listOf("ws_us500.json"))
        val httpClient = OkHttpClient.Builder().build()
        Api(httpClient, restfulEndpoint!!, wsEndpoint!!)
                .getProductsChannelUpdates(Observable.just(UpdateSubscriptions("sb26496")))
                .test()
                .awaitDone(250, TimeUnit.MILLISECONDS)
                .assertValue(TradingQuote("sb26496", 10116.1))
                .assertNoErrors()
//                .assertComplete() // TODO: Should it complete?
                .dispose()
    }

    @Test
    fun getProductChannelUpdatesFails_applicationProtocolError() {
        val (restfulEndpoint, wsEndpoint) =
                setupMockWebServerWs(false, listOf("ws_germany30.json"))
        val httpClient = OkHttpClient.Builder().build()
        val exception = ConnectFailedException("Missing JWT Access Token in request", "RTF_002")
        Api(httpClient, restfulEndpoint!!, wsEndpoint!!)
                .getProductsChannelUpdates(Observable.just(UpdateSubscriptions("sb26493")))
                .test()
                .awaitDone(100, TimeUnit.MILLISECONDS)
                .assertNoValues()
//                .assertNotComplete() // TODO: Should it complete?
                .assertError(exception)
                .dispose()
    }

    @Test
    fun getProductChannelUpdatesRenovatesSubscriptions() {
        val fixtures = listOf("ws_germany30.json", "ws_us500.json")
        val (restfulEndpoint, wsEndpoint) = setupMockWebServerWs(true, fixtures)
        val httpClient = OkHttpClient.Builder().build()
        val productsChannelEvents: Observable<ProductsChannelEvent> =
                Observable.just(UpdateSubscriptions("sb26493"),
                        UpdateSubscriptions("sb26496", "sb26493"))
        Api(httpClient, restfulEndpoint!!, wsEndpoint!!)
                .getProductsChannelUpdates(productsChannelEvents)
                .test()
                .awaitDone(250, TimeUnit.MILLISECONDS)
                .assertValueAt(0, TradingQuote("sb26493", 10692.3))
                .assertValueAt(1, TradingQuote("sb26496", 10116.1))
                .assertNoErrors()
//                .assertComplete() // TODO: Should it complete?
                .dispose()
    }

    @Ignore
    @Test
    fun getProductChannelUpdatesFails_wsServerException() {
        TODO("test ws server exception")
    }

    @Ignore
    @Test
    fun getProductChannelUpdatesFails_wsRetriableException() {
        TODO("test ws recoverable exception")
    }

    @Ignore
    @Test
    fun getProductChannelUpdatesFails_wsDevException() {
        TODO("test ws dev exception")
    }

    @Ignore
    @Test
    fun getProductChannelUpdatesFails_wsUnknownException() {
        TODO("test ws unknown exception")
    }

    @Ignore
    @Test
    fun getProductChannelUpdatesFails_wsCleanClose() {
        TODO("test ws clean close")
    }

    @Ignore
    @Test
    fun getProductChannelUpdatesFails_wsServerDown() {
        TODO("test ws server goes down")
    }

    private fun setupMockWebServerWs(connectOk: Boolean, fixtures: List<String>):
            Pair<RestfulEndpoint?, RTFEndpoint?> {
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response?) {
                if (connectOk) {
                    ws.send(buffer("ws_connect_success.json").readByteString().utf8())
                    fixtures.forEach { ws.send(buffer(it).readByteString().utf8()) }
                } else {
                    ws.send(buffer("ws_connect_failure.json").readByteString().utf8())
                }
                ws.close(WsCloseEventStatusCode.NORMAL_CLOSURE.code, "Normal closure")
                Log.d(" [SERVER]", "close requested")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                Log.d("", "onMessage(server): $text")
                if (!connectOk) {
                    Log.d("", "Unexpected message. Throwing…")
                    throw Exception("Unexpected message after 'connect.failed': $text")
                }
            }

            override fun onClosing(ws: WebSocket?, code: Int, reason: String?) {
                Log.d("", "onClosing(server): $code, $reason")
            }

            override fun onClosed(ws: WebSocket?, code: Int, reason: String?) {
                Log.d("", "onClosed(server): $code, $reason")
            }

            override fun onFailure(webSocket: WebSocket?, t: Throwable?, response: Response?) {
                Log.d("", "onFailure(server): " + t)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()

        val restfulEndpoint = server.url("/core/16/products/")
        val wsEndpoint = server.url("/subscriptions/me")
        return Pair(restfulEndpoint, wsEndpoint)
    }

    private fun setupMockWebServerRestful(fixtureName: String? = null, code: HttpStatusCode):
            Pair<RestfulEndpoint?, RTFEndpoint?> {
        val response = if (fixtureName != null) {
            MockResponse().setBody(buffer(fixtureName)).setResponseCode(code)
        } else {
            MockResponse().setResponseCode(code)
        }
        server.enqueue(response)
        server.start()

        val restfulEndpoint = server.url("/core/16/products/")
        val wsEndpoint = server.url("/subscriptions/me")
        return Pair(restfulEndpoint, wsEndpoint)
    }

    private fun buffer(fixtureName: String): Buffer {
        val fixture =
                InstrumentationRegistry.getContext().classLoader.getResourceAsStream(fixtureName)
        val buffer = Buffer()
        Okio.buffer(Okio.source(fixture)).readAll(buffer)
        return buffer
    }
}
