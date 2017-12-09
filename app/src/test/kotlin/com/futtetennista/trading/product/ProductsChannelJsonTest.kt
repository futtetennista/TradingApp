package com.futtetennista.trading.product

import okio.BufferedSource
import okio.Okio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset


class ProductsChannelJsonTest {

    @Test
    fun toSubscribeMessage() {
        val subscribeTo = ProductsChannelJson.toSubscriptionMessage(subscribeTo = listOf("test_id"))
        val unsubscribeFrom =
                ProductsChannelJson.toSubscriptionMessage(unsubscribeFrom = listOf("test_id"))
        val mixed = ProductsChannelJson.toSubscriptionMessage(listOf("test_id1"), listOf("test_id2"))

        assertEquals("{\"subscribeTo\":[\"trading.product.test_id\"]}", subscribeTo)
        assertEquals("{\"unsubscribeFrom\":[\"trading.product.test_id\"]}", unsubscribeFrom)
        assertEquals("{\"subscribeTo\":[\"trading.product.test_id1\"]," +
                "\"unsubscribeFrom\":[\"trading.product.test_id2\"]}", mixed)
    }

    @Test
    fun toTradingQuoteMessage() {
        val tradingQuote = "{\"t\":\"trading.quote\",\"body\":{\"securityId\":\"test_id\"," +
                "\"currentPrice\":\"10692.3\"}}"

        val actual = ProductsChannelJson.toProductsChannelMessage(toBufferedSource(tradingQuote))
        assertEquals(TradingQuote("test_id", 10692.3), actual)
    }

    @Test
    fun toConnectConnectedMessage() {
        val connectConnected =
                "{\"body\":{\"foo\":\"ignored\",\"bar\":1},\"t\":\"connect.connected\"}"

        val actual = ProductsChannelJson.toProductsChannelMessage(toBufferedSource(connectConnected))
        assertEquals(ConnectConnected, actual)
    }

    @Test
    fun toConnectFailedMessage() {
        val connectFailed = "{\"t\":\"connect.failed\",\"body\":{\"developerMessage\":\"foo\"," +
                "\"errorCode\":\"RTF_002\"}}"

        val actual = ProductsChannelJson.toProductsChannelMessage(toBufferedSource(connectFailed))
        assertEquals(ConnectFailed("foo", "RTF_002"), actual)

    }

    @Test
    fun toNullMessage() {
        val unknown = "{\"SUBSCRIBETO\":[\"TRADING.PRODUCT.SB26493\"]}"

        assertNull(ProductsChannelJson.toProductsChannelMessage(toBufferedSource(unknown)))
    }

    companion object {
        fun toBufferedSource(json: JsonString): BufferedSource {
            val sourceOk = Okio.source(json.byteInputStream(Charset.forName("UTF-8")))
            return Okio.buffer(sourceOk)
        }
    }
}
