package com.futtetennista.trading

import android.content.Context
import android.net.NetworkInfo
import android.util.Log
import com.futtetennista.trading.product.NetworkConnectivityEvent
import com.github.pwittchen.reactivenetwork.library.rx2.Connectivity
import com.github.pwittchen.reactivenetwork.library.rx2.ReactiveNetwork
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers


object Platform {

    fun observeNetworkConnectivity(applicationContext: Context): Observable<NetworkConnectivityEvent> =
            ReactiveNetwork
                    .observeNetworkConnectivity(applicationContext)
                    .subscribeOn(Schedulers.io())
                    .map { connectivity: Connectivity ->
                        val connected = connectivity.isAvailable
                                && (connectivity.state == NetworkInfo.State.CONNECTED
                                || connectivity.state == NetworkInfo.State.CONNECTING)
                        NetworkConnectivityEvent(connected)
                    }
//                .filter(ConnectivityPredicate.hasState(NetworkInfo.State.CONNECTED)
//                .filter(ConnectivityPredicate.hasType(ConnectivityManager.TYPE_WIFI))
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnEach { Log.d("[Platform#observeNetworkConnectivity]", "$it")}
}