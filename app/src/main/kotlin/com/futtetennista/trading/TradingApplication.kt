package com.futtetennista.trading

import android.app.Application
import android.content.res.Configuration
import android.icu.text.NumberFormat
import android.icu.util.Currency
import android.icu.util.ULocale
import com.futtetennista.trading.product.Api
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TradingApplication : Application() {

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val currentLocale = newConfig.locales[0]
        currencyFormatter = NumberFormat.getCurrencyInstance(currentLocale)
        percentFormatter = NumberFormat.getPercentInstance(currentLocale)
        formatterCurrency = null
    }

    companion object {
        // There are possibly better ways to get these instances but it's out of scope for the
        // assignment
        private val httpClient = OkHttpClient
                .Builder()
                .readTimeout(5L, TimeUnit.SECONDS)
                .writeTimeout(5L, TimeUnit.SECONDS)
                .build()
        private val restfulEndpoint = HttpUrl.parse(BuildConfig.REST_ENDPOINT)
        private val productsChannelEndpoint = HttpUrl.parse(BuildConfig.WEBSOCKET_ENDPOINT)
        val api = Api(httpClient, restfulEndpoint!!, productsChannelEndpoint!!)

        var currencyFormatter: NumberFormat =
                NumberFormat.getCurrencyInstance(ULocale.getDefault(ULocale.Category.FORMAT))
        var percentFormatter: NumberFormat =
                NumberFormat.getPercentInstance(ULocale.getDefault(ULocale.Category.FORMAT))

        var formatterCurrency: Currency? = null

        init {
            percentFormatter.minimumFractionDigits = 2
            percentFormatter.maximumFractionDigits = 2
        }
    }
}
