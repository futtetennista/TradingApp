package com.futtetennista.trading.product

import android.util.Log
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import io.reactivex.*
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.Okio
import java.nio.charset.Charset


// Models the push / send nature of web sockets
private sealed class WebSocketEvent

// downstream to upstream messages
private object RequestOpen : WebSocketEvent()

private object RequestClose : WebSocketEvent()
private data class SendString(val value: String) : WebSocketEvent()
// upstream to downstream messages
private data class DidOpen(val response: Response) : WebSocketEvent()

private data class IncomingMessageString(val text: String) : WebSocketEvent()
private data class IncomingMessageBytes(val bytes: ByteString) : WebSocketEvent()
private data class WillClose(val code: Int, val reason: String) : WebSocketEvent()
private data class DidClose(val code: Int, val reason: String) : WebSocketEvent()
private data class DidFail(val t: Throwable, val response: Response?) : WebSocketEvent()

private data class ProductsChannelState(
        val wsState: WebSocketState = WebSocketState.EMPTY,
        val connectState: ProductsChannelConnectState = ProductsChannelConnectState.DISCONNECTED,
        val pendingSubscription: NewProductId = null)

class ProductsChannel(webSocketBuilder: (WebSocketListener) -> WebSocket,
                      private val productsChannelMessages: FlowableEmitter<PublicProductsChannelMessage>,
                      productChannelEvents: Observable<ProductsChannelEvent>) {

    private val rxWebSocket = RxWebSocketFactory.from(webSocketBuilder)

    private val webSocketEvents: ObservableTransformer<WebSocketEvent, out ProductsChannelEvent> =
            ObservableTransformer { events ->
                events.distinctUntilChanged()
                        .doOnEach { Log.d("[$this#webSocketEvents]", "$it") }
                        .flatMap { event: WebSocketEvent ->
                            when (event) {
                                is IncomingMessageString -> {
                                    val wsMessage = parseMessage(event.text)
                                    if (wsMessage != null) Observable.just(PushMessage(wsMessage))
                                    else Observable.never()
                                }
                                is WillClose ->
                                    Observable.just(ServerDisconnect(event.code, event.reason))
                                is DidFail ->
                                    Observable.just(Failure(event.t))
                                is DidOpen -> Observable.just(WebSocketOpen)
                                is RequestClose, is SendString, is IncomingMessageBytes,
                                is DidClose, RequestOpen -> Observable.never()
                            }
                        }
            }

    private val states: Flowable<ProductsChannelState> = rxWebSocket
            .compose(webSocketEvents)
            .mergeWith(productChannelEvents)
            .toFlowable(BackpressureStrategy.LATEST)
            .doOnEach { Log.d("[$this#states::input]", "$it") }
            .scan(ProductsChannelState(), { state: ProductsChannelState, event: ProductsChannelEvent ->
                when (event) {
                    WebSocketOpen -> state.copy(wsState = WebSocketState.OPEN)
                    is PushMessage -> handleMessage(state, event.productsChannelMessage)
                    is UpdateSubscriptions ->
                        handleUpdateSubscription(state, event.newProductId, event.lastProductId)
                    is ServerDisconnect -> when (state.wsState) {
                        WebSocketState.OPEN -> {
                            emitError(event.code)
                            state.copy(wsState = WebSocketState.CLOSE,
                                    connectState = ProductsChannelConnectState.DISCONNECTED)
                        }
                        WebSocketState.CLOSE, WebSocketState.FAIL, WebSocketState.EMPTY -> state
                    }
                    is Failure -> when (state.connectState) {
                    // NB: a CONNECTED state implies that the app is requesting updates
                    // for a given product id
                        ProductsChannelConnectState.CONNECTED -> {
                            onErrorDispose(event.t)
                            state.copy(wsState = WebSocketState.FAIL,
                                    connectState = ProductsChannelConnectState.DISCONNECTED)
                        }
                        ProductsChannelConnectState.DISCONNECTED,
                        ProductsChannelConnectState.FAILED -> state
                    }
                    is ClientDisconnect -> {
                        closeGracefully()
                        state.copy(connectState = ProductsChannelConnectState.DISCONNECTED,
                                wsState = WebSocketState.CLOSE)
                    }
                }
            })
            .doOnEach { Log.d("[$this#states::output]", "$it") }

    private val compositeDisposable: CompositeDisposable = CompositeDisposable()

    init {
        states.onTerminateDetach()
                .subscribe()
                .addTo(compositeDisposable)
    }

    private fun parseMessage(text: String): ProductsChannelMessage? {
        val source = Okio.source(text.byteInputStream(Charset.forName("UTF-8")))
        val wsMessage = ProductsChannelJson.toProductsChannelMessage(Okio.buffer(source))
        if (wsMessage == null) Log.d("[$this#parseMessage]", "Ignored:$text")
        return wsMessage
    }

    private fun handleMessage(state: ProductsChannelState,
                              productsChannelMessage: ProductsChannelMessage): ProductsChannelState {
        return when (productsChannelMessage) {
            is ConnectConnected -> {
                sendSubscriptions(state.pendingSubscription, lastProductId = null)
                state.copy(connectState = ProductsChannelConnectState.CONNECTED,
                        pendingSubscription = null)
            }
            is TradingQuote -> {
                productsChannelMessages.onNext(productsChannelMessage)
                state
            }
        // TODO#NeedMoreInfo: some error codes might be recoverable but the assignment doesn't
        // really go in depth explaining which are and which are not that is I'll assume this
        // kind of messages are not recoverable
            is ConnectFailed -> {
                val exception = ConnectFailedException(productsChannelMessage.developerMessage,
                        productsChannelMessage.errorCode)
                onErrorDispose(exception)
                state.copy(connectState = ProductsChannelConnectState.FAILED)
            }
        }
    }

    private fun sendSubscriptions(newProductId: NewProductId, lastProductId: LastProductId) {
        if (newProductId == null && lastProductId == null) return

        val message = ProductsChannelJson.toSubscriptionMessage(
                subscribeTo = newProductId?.let { listOf(it) },
                unsubscribeFrom = lastProductId?.let { listOf(it) })
        rxWebSocket.onNext(SendString(message))
    }

    private fun handleUpdateSubscription(state: ProductsChannelState, newProductId: NewProductId,
                                         lastProductId: LastProductId): ProductsChannelState =
            when (state.connectState) {
                ProductsChannelConnectState.CONNECTED -> {
                    sendSubscriptions(newProductId, lastProductId)
                    state
                }
            // Try to connect the ws: now I'm not entirely sure this is the best strategy if
            // the connection failed at the application protocol level but I lack a bit of
            // information here. If the app doesn't retry then it's basically "dead" with respect
            // to products channel updates because it will never switch from its FAILED state.
            // Since this will happen only when a new subscription is requested - that is a new
            // product id is requested - I think it's safe to assume that this won't overwhelm the
            // servers or used as a DOS attack to name a few possibilities.
                ProductsChannelConnectState.DISCONNECTED, ProductsChannelConnectState.FAILED -> {
                    if (newProductId != null) {
                        rxWebSocket.onNext(RequestOpen)
                        state.copy(pendingSubscription = newProductId)
                    } else {
                        // Disconnected, no request for a new subscription: nothing to do
                        state
                    }
                }
            }

    private fun emitError(code: Int) {
        when {
            WsCloseEventStatusCode.recoverable(code) ->
                onErrorDispose(WsTemporaryException(WsCloseEventStatusCode.from(code)))
            WsCloseEventStatusCode.server(code) ->
                onErrorDispose(WsServerException(WsCloseEventStatusCode.from(code)))
            WsCloseEventStatusCode.dev(code) ->
                onErrorDispose(WsFatalException(statusCode = code))
            WsCloseEventStatusCode.unknown(code) ->
                onErrorDispose(WsFatalException(statusCode = code))
        }
    }

    private fun onErrorDispose(t: Throwable) {
        productsChannelMessages.onError(t)
        compositeDisposable.dispose()
    }

    fun closeGracefully() {
        rxWebSocket.onNext(RequestClose)
    }
}

private class RxWebSocketFactory
private constructor(private val webSocketFactory: (WebSocketListener) -> WebSocket) :
        Subject<WebSocketEvent>() {

    private val webSocketListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            subject.onNext(DidOpen(response))
        }

        override fun onMessage(ws: WebSocket, text: String) {
            subject.onNext(IncomingMessageString(text))
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            subject.onNext(IncomingMessageBytes(bytes))
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            subject.onNext(WillClose(code, reason))
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            subject.onNext(DidClose(code, reason))
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            subject.onNext(DidFail(t, response))
        }
    }
    private var ws: WebSocket? = null
    private val subject: PublishSubject<WebSocketEvent> = PublishSubject.create()

    override fun onNext(event: WebSocketEvent) {
        when (event) {
            is SendString -> {
                ws!!.send(event.value)
                Log.d("[$this#onNext]", "wrote ${event.value} on $ws")
            }
            is RequestOpen -> {
                ws = webSocketFactory(webSocketListener)
                Log.d("[$this#onNext]", "opened $ws")
            }
            is RequestClose -> {
                Log.d("[$this#onNext]", "closed $ws")
                ws?.close(WsCloseEventStatusCode.GOING_AWAY.code, "Going Away")
                ws = null
            }
            else -> throw IllegalArgumentException("Unexpected event: $event")
        }
    }

    override fun getThrowable(): Throwable? = subject.throwable

    override fun onError(e: Throwable) = subject.onError(e)

    override fun onComplete() = subject.onComplete()

    override fun onSubscribe(d: Disposable) = subject.onSubscribe(d)

    override fun hasThrowable(): Boolean = subject.hasThrowable()

    override fun hasObservers(): Boolean = subject.hasObservers()

    override fun subscribeActual(observer: Observer<in WebSocketEvent>) =
            subject.subscribeActual(observer)

    override fun hasComplete(): Boolean = subject.hasComplete()

    companion object {
        fun from(createWebSocket: (WebSocketListener) -> WebSocket) =
                RxWebSocketFactory(createWebSocket)
    }
}

object ProductsChannelJson {
    fun toSubscriptionMessage(subscribeTo: List<ProductId>? = null,
                              unsubscribeFrom: List<ProductId>? = null): String {
        val sink = Buffer()
        return JsonWriter.of(sink).use { writer ->
            writer.beginObject()
            subscribeTo?.let { writer.writeSubscriptionArray("subscribeTo", it) }
            unsubscribeFrom?.let { writer.writeSubscriptionArray("unsubscribeFrom", it) }
            writer.endObject()
            writer.close()

            return@use sink.readByteString().utf8()
        }
    }

    private fun JsonWriter.writeSubscriptionArray(name: String, productIds: List<ProductId>) {
        name(name)
        beginArray()
        productIds.forEach { value("trading.product.$it") }
        endArray()
    }

    fun toProductsChannelMessage(source: BufferedSource): ProductsChannelMessage? {
        JsonReader.of(source).use { reader ->
            var t: String? = null
            var body: Any? = null

            reader.beginObject()
            loop@ while (reader.hasNext()) {
                when (reader.nextName()) {
                    "t" -> t = reader.nextString()
                    "body" -> body = reader.readJsonValue()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return toProductsChannelMessage(t, body)
        }
    }

    // Is there a nicer way - aka no casts - to parse these messages ?!
    private fun toProductsChannelMessage(t: String?, body: Any?): ProductsChannelMessage? {
        return when (t) {
            "connect.connected" -> ConnectConnected
            "connect.failed" -> body?.let {
                @Suppress("UNCHECKED_CAST")
                val map = it as Map<String, String>
                ConnectFailed(map["developerMessage"]!!, map["errorCode"]!!)
            }
            "trading.quote" -> body?.let {
                @Suppress("UNCHECKED_CAST")
                val map = it as Map<String, String>
                map["currentPrice"]!!.toDoubleOrNull()?.let {
                    TradingQuote(map["securityId"]!!, it)
                }
            }
            else -> null // Message ignored
        }
    }
}
