package com.futtetennista.trading.product

import android.icu.text.NumberFormat
import android.icu.util.Currency
import com.futtetennista.trading.TradingApplication
import com.squareup.moshi.Json
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import okhttp3.HttpUrl
import okhttp3.Response
import okio.BufferedSource


// ALIASES
// i.e. sb26500
typealias ProductId = String
// i.e. GOLD
typealias ProductSymbol = String
typealias ISO4217CurrencyCode = String
typealias JsonString = String
typealias HttpStatusCode = Int
typealias NewProductId = ProductId?
typealias LastProductId = ProductId?
typealias RestfulEndpoint = HttpUrl
typealias RTFEndpoint = HttpUrl
typealias PercentageString = String
typealias ErrorMessage = String

// API MODELS
sealed class ApiModel

object Empty : ApiModel()
data class ProductDetails(val symbol: ProductSymbol,
                          @Json(name = "securityId") val productId: ProductId,
                          val displayName: String, val currentPrice: Price,
                          val closingPrice: Price) : ApiModel() {
    companion object {
        private val moshi by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }

        fun fromJson(source: BufferedSource) =
                moshi.adapter<ProductDetails>(ProductDetails::class.java).fromJson(source)
    }
}

data class ProductQuote(val productId: ProductId, val currentPrice: Double) : ApiModel()
// "artificial" class to account for the loading case
data class LoadingProduct(val productId: ProductId) : ApiModel()


// PRODUCTS CHANNEL UPDATES
// State
enum class WebSocketState {
    OPEN, CLOSE, FAIL, EMPTY
}

enum class ProductsChannelConnectState {
    CONNECTED, DISCONNECTED, FAILED
}

// Messages
sealed class ProductsChannelMessage

sealed class InternalProductsChannelMessage : ProductsChannelMessage()
sealed class PublicProductsChannelMessage : ProductsChannelMessage()

data class TradingQuote(val productId: ProductId,
                        val currentPrice: Double) : PublicProductsChannelMessage()
object ConnectConnected : InternalProductsChannelMessage()
data class ConnectFailed(val developerMessage: String,
                         val errorCode: String) : InternalProductsChannelMessage()

// Events
sealed class ProductsChannelEvent

sealed class UpstreamProductsChannelEvent : ProductsChannelEvent() // Coming from the server
sealed class DownstreamProductsChannelEvent : ProductsChannelEvent() // Sent by the client
object WebSocketOpen : UpstreamProductsChannelEvent()
data class PushMessage(val productsChannelMessage: ProductsChannelMessage) :
        UpstreamProductsChannelEvent()

data class ServerDisconnect(val code: Int, val reason: String) :
        UpstreamProductsChannelEvent()

data class Failure(val t: Throwable) :
        UpstreamProductsChannelEvent()

data class UpdateSubscriptions(val newProductId: NewProductId,
                               val lastProductId: LastProductId = null) :
        DownstreamProductsChannelEvent()
object ClientDisconnect : DownstreamProductsChannelEvent()

// EXCEPTIONS
sealed class RootException : Exception()

data class HttpException(val code: HttpStatusCode) : RootException()
sealed class ProductsChannelException : RootException()
data class ConnectFailedException(val developerMessage: String,
                                  val errorCode: String) : ProductsChannelException() {
    fun recoverable() =
            listOf<String>().any { it == errorCode }
}

data class WsTemporaryException(val statusCode: WsCloseEventStatusCode) : ProductsChannelException()

data class WsServerException(val statusCode: WsCloseEventStatusCode) : ProductsChannelException()
data class WsFatalException(val statusCode: Int? = null,
                            val t: Throwable? = null) : ProductsChannelException()

sealed class RestfulException(val statusCode: HttpStatusCode,
                              val errorDetails: ErrorDetails?) : RootException() {
    companion object {
        fun fromResponse(response: Response): Pair<HttpStatusCode, ErrorDetails?> {
            val statusCode = response.code()
            return if (response.body()?.contentLength()!! > 0L) {
                val errorDetails = moshi
                        .adapter<ErrorDetails>(ErrorDetails::class.java)
                        .fromJson(response.body()!!.source())
                Pair(statusCode, errorDetails)
            } else {
                Pair(statusCode, null)
            }
        }

        private val moshi by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }
    }
}

data class ErrorDetails(val message: String?, val developerMessage: String,
                        val errorCode: String)

data class TradingException(val c: HttpStatusCode, val e: ErrorDetails?) : RestfulException(c, e)
// This should crash the app and be fixed by devs
data class AuthException(val c: HttpStatusCode, val e: ErrorDetails?) : RestfulException(c, e)

data class GeneralException(val c: HttpStatusCode, val e: ErrorDetails? = null) :
        RestfulException(c, e)

// https://developer.mozilla.org/en-US/docs/Web/API/CloseEvent
enum class WsCloseEventStatusCode(val code: Int) {
    NORMAL_CLOSURE(1000), GOING_AWAY(1001), PROTOCOL_ERROR(1002), UNSUPPORTED_DATA(1003),
    INVALID_FRAME_PAYLOAD_DATA(1007), POLICY_VIOLATION(1008), MESSAGE_TOO_BIG(1009),
    INTERNAL_ERROR(1011), SERVICE_RESTART(1012), TRY_AGAIN_LATER(1013), BAD_GATEWAY(1014),
    UNKNOWN(4999);

    companion object {
        private val recoverableWsCloseEvents =
                listOf(SERVICE_RESTART, TRY_AGAIN_LATER, INTERNAL_ERROR, GOING_AWAY)

        // The app can retry to connect to the server
        fun server(code: Int) =
                listOf(INTERNAL_ERROR, BAD_GATEWAY).any { it.code == code }

        // The app can retry to connect to the server, with some backoff
        fun recoverable(code: Int) =
                recoverableWsCloseEvents.any { it.code == code }

        // Crash the app and fix it!
        fun dev(code: Int) =
                listOf(UNSUPPORTED_DATA, INVALID_FRAME_PAYLOAD_DATA, POLICY_VIOLATION,
                        MESSAGE_TOO_BIG).any { it.code == code }

        fun unknown(code: Int) =
                WsCloseEventStatusCode.values().none { it.code == code }

        fun from(code: Int): WsCloseEventStatusCode {
            return WsCloseEventStatusCode.values().firstOrNull { it.code == code }
                    ?: UNKNOWN
        }
    }

    fun recoverable(): Boolean =
            recoverable(code)
}

// REAL WORLD EVENTS
sealed class RealWorldEvent

sealed class ViewEvent : RealWorldEvent()
data class SubmitProductId(val productId: ProductId, val retry: Boolean = false) : ViewEvent()

sealed class PlatformEvents : RealWorldEvent()
data class LifecycleEvent(val eventType: EventType) : PlatformEvents() {
    enum class EventType {
        START, RESTART, STOP, DESTROY
    }
}

data class NetworkConnectivityEvent(val connected: Boolean) : PlatformEvents()

data class Price(private val currency: ISO4217CurrencyCode,
                 private val decimals: Int,
                 val amount: Double,
                 @Transient private val testOnlyFormatter: NumberFormat? = null) {

    @Transient
    private val formattedPrice: String

    init {
        TradingApplication.formatterCurrency = TradingApplication.formatterCurrency?.let {
            if (it.currencyCode == currency) it
            else Currency.getInstance(currency)
        } ?: Currency.getInstance(currency)

        val actualFormatter = testOnlyFormatter ?: TradingApplication.currencyFormatter
        actualFormatter.currency = TradingApplication.formatterCurrency
        actualFormatter.maximumFractionDigits = decimals
        actualFormatter.minimumFractionDigits = decimals

        formattedPrice = actualFormatter.format(amount)
    }

    override fun toString(): String = formattedPrice
}

enum class ViewState {
    IDLE, LOADING, OFFLINE
}