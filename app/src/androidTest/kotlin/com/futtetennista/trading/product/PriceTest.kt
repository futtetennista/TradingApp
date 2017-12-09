package com.futtetennista.trading.product

import android.icu.text.NumberFormat
import android.icu.util.ULocale
import android.support.test.runner.AndroidJUnit4
import com.futtetennista.trading.product.Price
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith


@RunWith(AndroidJUnit4::class)
class PriceTest {

    @Test
    fun toStringShowsCorrectCurrency() {
        val formatter = NumberFormat.getCurrencyInstance(ULocale.GERMANY)
        val priceUS = Price("USD", 2, 4371.89, formatter)
        val priceEU = Price("EUR", 1, 4371.8, formatter)
        val priceGBP = Price("GBP", 5, 0.87654, formatter)

        assertEquals("4.371,89\u00A0$", priceUS.toString())
        assertEquals("4.371,8\u00A0€", priceEU.toString())
        assertEquals("0,87654\u00A0£", priceGBP.toString())
    }
}
