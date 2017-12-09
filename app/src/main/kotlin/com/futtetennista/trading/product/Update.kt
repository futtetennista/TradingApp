package com.futtetennista.trading.product

import android.util.Log
import com.futtetennista.trading.Either
import com.futtetennista.trading.Left
import com.futtetennista.trading.Platform
import com.futtetennista.trading.Right
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.functions.Consumer
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject


private sealed class Action
private object ToBackground : Action()
private object BackFromBackground : Action() // Aka app restarted
private object NetworkConnectivityOn : Action()
private object NetworkConnectivityOff : Action()
private data class ApiAction(val productDetails: ProductDetails? = null,
                             val productQuote: ProductQuote? = null,
                             val error: Pair<ProductId?, Throwable>? = null,
                             val loading: ProductId? = null) : Action()

private data class UpdateState(val model: Either<Throwable, ApiModel> = Right(Empty),
                               val currentProductId: ProductId? = null,
                               val networkConnectivityOn: Boolean = true,
                               val loadingProductId: ProductId? = null) {

    fun fromProductDetails(productDetails: ProductDetails) =
            this.copy(model = Right(productDetails), currentProductId = productDetails.productId,
                    loadingProductId = null)

    // It can happen that a trading quote msg arrives before the subscription is updated: make
    // sure here (since the API layer is dumb in this sense, it just forwards messages) that the
    // trading quote relates to the currently chosen product id.
    fun fromProductQuote(productQuote: ProductQuote) =
            if (productQuote.productId == this.currentProductId) {
                this.copy(model = Right(productQuote))
            } else {
                this
            }

    fun fromException(productId: ProductId, ex: Throwable): UpdateState =
            this.copy(currentProductId = productId, model = Left(ex))

    fun loading(productId: ProductId): UpdateState =
            this.copy(model = Right(LoadingProduct(productId)), loadingProductId = productId)
}

class Update(private var view: IView?, private val api: Api) {
    private fun retryStrategy() =
            RetryBackoffDecorrelatedJitter(10, 50,
                    { ex: Throwable, retryCount: Int ->
                        when (ex) {
                            is WsTemporaryException -> true
                            is WsServerException -> ex.statusCode.recoverable()
                            is ConnectFailedException -> ex.recoverable()
                            is HttpException -> when (ex.code) {
                            // The server is having issues, but maybe it was just a glitch so retry but
                            // a limited amount of times
                                500, 502, 503, 504 -> retryCount < 3
                            // Either something is wrong with the app or something changed upstream:
                            // no need to retry
                                else -> false
                            }
                        // Again there are some unforeseen issues but maybe it was just a glitch so retry
                        // but a limited amount of times
                            is GeneralException -> retryCount < 3
//                is TradingException, is HttpException, is WsFatalException, is AuthException,
//                is WsDevException -> false
                            else -> retryCount < 3
                        }
                    })

    // TODO#Improve: It's unfortunate that `Subject`s must be invariant, this means that the
    // compiler doesn't prohibit a downstream client from sending upstream events. What's a
    // better way of achieving what `productsChannelEvents` does?
    // NB: It is vital for recovering gracefully from errors that the subject emits the last
    // event when a new subscriber arrives - this way it'll see the last event and have a
    // consistent state
    private val productsChannelEvents: Subject<ProductsChannelEvent> = BehaviorSubject.create()

    private val viewEventsTrans: ObservableTransformer<RealWorldEvent, out Action> =
            ObservableTransformer { events ->
                events.publish { event ->
                    Observable.merge(
                            event.ofType(SubmitProductId::class.java).compose(productDetailsTrans),
                            event.ofType(LifecycleEvent::class.java).compose(lifecycleEventsTrans))
                }
            }

    private val lifecycleEventsTrans: ObservableTransformer<LifecycleEvent, out Action>
        get() {
            val compositeDisposable = CompositeDisposable()

            fun observeNetworkConnectivity() = view?.let {
                Platform.observeNetworkConnectivity(it.appContext)
                        .subscribe { networkConnectivityEventsSubject.onNext(it) }
                        .addTo(compositeDisposable)
            }

            val networkConnectivity: Consumer<in LifecycleEvent> =
                    Consumer {
                        when (it.eventType) {
                            LifecycleEvent.EventType.START -> observeNetworkConnectivity()
                            LifecycleEvent.EventType.STOP -> compositeDisposable.clear()
                            LifecycleEvent.EventType.RESTART,
                            LifecycleEvent.EventType.DESTROY -> Unit
                        }
                    }
            val cleanup: Consumer<in LifecycleEvent> =
                    Consumer { if (it.eventType == LifecycleEvent.EventType.DESTROY) view = null }

            return ObservableTransformer { events ->
                events.distinctUntilChanged()
                        .doOnNext(networkConnectivity)
                        .doAfterNext(cleanup)
                        .flatMap { event ->
                            when (event.eventType) {
                                LifecycleEvent.EventType.RESTART ->
                                    Observable.just(BackFromBackground)
                                LifecycleEvent.EventType.STOP ->
                                    Observable.just(ToBackground)
                                else -> Observable.never()
                            }
                        }
            }
        }

    private val productDetailsTrans: ObservableTransformer<SubmitProductId, out Action>
        get() {
            val notEmptyProductIdPredicate: (Any?) -> Boolean = { event ->
                when (event) {
                    is SubmitProductId -> !event.productId.isEmpty()
                    else -> false
                }
            }

            return ObservableTransformer { viewEvents ->
                viewEvents.distinctUntilChanged()
                        .filter(notEmptyProductIdPredicate)
                        .flatMap { viewEvent: SubmitProductId ->
                            api.fetchProductDetails(viewEvent.productId)
                                    .subscribeOn(Schedulers.io())
                                    .retryWhen(retryStrategy())
                                    .map { ApiAction(productDetails = it) }
                                    .onErrorReturn {
                                        ApiAction(error = Pair(viewEvent.productId, it))
                                    }
                                    .toObservable()
                                    .doOnEach { Log.d("[Update#productDetailsTrans]", "$it") }
                                    .startWith(ApiAction(loading = viewEvent.productId))
                        }
            }
        }

    // TODO#Improve: find a simpler solution. The app needs to subscribe/dispose network
    // connectivity events on start and dispose on stop
    // (see https://developer.android.com/reference/android/content/BroadcastReceiver.html)
    // but the `updates` pipeline lives possibly longer than that. So in order to not fall to a
    // "tragedy of the commons" scenario and behave as the OS expects the only workaround I found is
    // this.
    private val networkConnectivityEventsSubject: PublishSubject<NetworkConnectivityEvent> =
            PublishSubject.create()

    private val networkConnectivityEventsTrans:
            ObservableTransformer<NetworkConnectivityEvent, out Action> =
            ObservableTransformer { events ->
                events.distinctUntilChanged()
                        .flatMap { event: NetworkConnectivityEvent ->
                            when (event.connected) {
                                true -> Observable.just(NetworkConnectivityOn)
                                false -> Observable.just(NetworkConnectivityOff)
                            }
                        }
            }

    private val networkConnectivityEvents: Observable<Action> =
            networkConnectivityEventsSubject.compose(networkConnectivityEventsTrans)

    private val productsChannelUpdates: Flowable<out Action> =
            api.getProductsChannelUpdates(productsChannelEvents)
                    .subscribeOn(Schedulers.io())
                    // this flowable should always recover but that doesn't mean it'll hammer the
                    // server opening ws connection, but just that's it'll open one when it's
                    // needed (in this respect its semantics are very different from the
                    // `retryWhen` for `productDetails`)
                    .retry()
                    .map {
                        when (it) {
                            is TradingQuote ->
                                ApiAction(productQuote = ProductQuote(it.productId,
                                        it.currentPrice))
                        }
                    }

    private val updates: Flowable<UpdateState>?
            // The view shouldn't actually be null at this point but be extra-careful
        get() = view?.let {
            fun resubscribeLast(productId: ProductId?) {
                productId?.let {
                    productsChannelEvents.onNext(UpdateSubscriptions(it))
                }
            }

            fun unsubscribeCurrent(productId: ProductId?) {
                productId?.let {
                    // Don't keep old subscriptions active
                    productsChannelEvents.onNext(UpdateSubscriptions(null, it))
                }
            }

            it.viewEvents
                    .compose(viewEventsTrans)
                    .mergeWith(networkConnectivityEvents)
                    // Backpressure isn't an issue for this observable
                    .toFlowable(BackpressureStrategy.ERROR)
                    .mergeWith(productsChannelUpdates)
                    .doOnEach { Log.d("[Update#updates::input]", "$it") }
                    .scan(UpdateState(), { state: UpdateState, action: Action ->
                        // TODO: side efx and state handling are not cleanly separated here. Fact
                        // is: sometimes the current state is needed and sometimes the action so
                        // how can they be separated?
                        when (action) {
                            is ApiAction -> {
                                action.productDetails?.let {
                                    productsChannelEvents.onNext(UpdateSubscriptions(
                                            it.productId, state.currentProductId))

                                    return@scan state.fromProductDetails(it)
                                }
                                action.productQuote?.let { return@scan state.fromProductQuote(it) }
                                action.error?.let {
                                    val productId = when {
                                        it.first != null -> it.first!!
                                        state.currentProductId != null ->
                                            state.currentProductId
                                        else -> when (state.model) {
                                            is Right<ApiModel> -> when (state.model.value) {
                                                Empty -> throw IllegalStateException("$it thrown " +
                                                        "but ApiModel is `Empty`: $state")
                                                is ProductDetails -> state.model.value.productId
                                                is ProductQuote -> state.model.value.productId
                                                is LoadingProduct -> state.model.value.productId
                                            }
                                            is Left -> throw IllegalStateException("$it thrown " +
                                                    "without a current product id: $state")
                                        }
                                    }

                                    unsubscribeCurrent(state.currentProductId)
                                    return@scan state.fromException(productId, it.second)
                                }
                                action.loading?.let {
                                    unsubscribeCurrent(state.currentProductId)
                                    return@scan state.loading(it)
                                }

                                throw IllegalStateException("Unexpected ApiAction:$action")
                            }
                            is ToBackground -> {
                                // Unsubscribe…is it really needed though or is it sufficient
                                // to simply disconnect?!
                                unsubscribeCurrent(state.currentProductId)
                                productsChannelEvents.onNext(ClientDisconnect)
                                state
                            }
                            is BackFromBackground -> {
                                resubscribeLast(state.currentProductId)
                                state
                            }
                            NetworkConnectivityOn -> {
                                resubscribeLast(state.currentProductId)
                                state.copy(networkConnectivityOn = true)
                            }
                            NetworkConnectivityOff -> {
                                productsChannelEvents.onNext(ClientDisconnect)
                                state.copy(networkConnectivityOn = false)
                            }
                        }
                    })
                    .doOnEach { Log.d("[Update#updates::output]", "$it") }
        }

    fun viewModels(empty: ViewModel): Flowable<ViewModel>? = updates?.scan(empty,
            { viewModel: ViewModel, state: UpdateState ->
                when (state.networkConnectivityOn) {
                    false -> viewModel.offline()
                    true -> {
                        val result = state.model
                        when (result) {
                            is Right<ApiModel> -> when (result.value) {
                                is ProductDetails -> viewModel.fromProductDetails(result.value)
                                is ProductQuote ->
                                    if (state.loadingProductId == null) {
                                        viewModel.fromProductQuote(result.value)
                                    } else {
                                        viewModel.loading(result.value.productId)
                                    }
                                is LoadingProduct -> viewModel.loading(result.value.productId)
                                Empty -> viewModel.online()
                            }
                            is Left<Throwable> ->
                                viewModel.fromException(state.currentProductId!!, result.value)
                        }
                    }
                }
            })?.onTerminateDetach()
}
