package com.futtetennista.trading.product

import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import com.futtetennista.trading.product.Price
import com.futtetennista.trading.product.ProductDetails
import okio.BufferedSource
import okio.Okio
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class ProductDetailsTest {

    @Test
    fun fromJson() {
        val source = fixture("product_details_success_gold.json")

        val expected =
                ProductDetails("GOLD", "sb26500", "Gold",
                        Price("USD", 1, 1587.4), Price("USD", 1, 1342.1))
        assertEquals(expected, ProductDetails.fromJson(source))
    }

    private fun fixture(fixtureName: String): BufferedSource {
        val fixture =
                InstrumentationRegistry.getContext().classLoader.getResourceAsStream(fixtureName)
        return Okio.buffer(Okio.source(fixture))
    }
}
