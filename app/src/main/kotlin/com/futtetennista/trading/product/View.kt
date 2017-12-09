package com.futtetennista.trading.product

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.SearchView
import android.support.v7.widget.Toolbar
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import android.widget.TextView
import com.futtetennista.trading.*
import com.jakewharton.rxbinding2.support.v7.widget.RxSearchView
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.subjects.PublishSubject


data class Product(val symbol: ProductSymbol, private val productId: ProductId,
                   val displayName: String, val currentPrice: Price, val closingPrice: Price) {

    private val priceDiff = (currentPrice.amount - closingPrice.amount) / closingPrice.amount

    val negativeTrend = priceDiff < 0
    val priceDiffFormatted: PercentageString = TradingApplication.percentFormatter.format(priceDiff)

    fun updateCurrentPrice(currentAmount: Double) =
            this.copy(currentPrice = this.currentPrice.copy(amount = currentAmount))

    override fun toString() =
            "Product(symbol=$symbol,productId=$productId,displayName=$displayName," +
                    "currentPrice=$currentPrice,closingPrice=$closingPrice," +
                    "priceDiffFormatted=$priceDiffFormatted,negativeTrend=$negativeTrend)"

    companion object {
        fun fromProductDetails(productDetails: ProductDetails) =
                Product(productDetails.symbol, productDetails.productId,
                        productDetails.displayName, productDetails.currentPrice,
                        productDetails.closingPrice)
    }
}

data class ViewModel(val eitherData: Either<ErrorMessage, Product>? = null,
                     val viewState: ViewState = ViewState.IDLE,
                     private val inputProductId: ProductId? = null,
                     private val lastProduct: Product? = null,
                     private val context: Context) {

    private fun toErrorMessage(ex: Throwable): ErrorMessage =
            when (ex) {
                is HttpException ->
                    if (ex.code == 404) context.getString(R.string.error_not_found, inputProductId)
                    else context.getString(R.string.error_try_again, ex.code)
                is ConnectFailedException -> context.getString(R.string.error_connect_failed)
                is WsTemporaryException -> context.getString(R.string.error_general_ws)
                is WsServerException -> context.getString(R.string.error_general_ws)
            // Fail fast and fix it before it gets released
                is WsFatalException -> throw ex
                is TradingException ->
                    ex.errorDetails?.errorCode ?: context.getString(R.string.error_general)
                is AuthException ->
                    ex.errorDetails?.errorCode ?: context.getString(R.string.error_general)
                is GeneralException -> context.getString(R.string.error_general)
                else -> context.getString(R.string.error_general)
            }

    companion object {
        fun empty(context: Context): ViewModel = ViewModel(context = context)
    }

    fun fromProductDetails(productDetails: ProductDetails): ViewModel {
        val product = Product.fromProductDetails(productDetails)
        return this.copy(eitherData = Right(product), lastProduct = product,
                inputProductId = productDetails.productId, viewState = ViewState.IDLE)
    }

    fun fromProductQuote(productQuote: ProductQuote) =
            when (lastProduct) {
                null -> this
                else -> {
                    val newProduct = lastProduct.updateCurrentPrice(productQuote.currentPrice)
                    this.copy(eitherData = Right(newProduct), viewState = ViewState.IDLE)
                }
            }

    fun fromException(productId: ProductId, ex: Throwable): ViewModel =
            this.copy(eitherData = Left(toErrorMessage(ex)), inputProductId = productId,
                    viewState = ViewState.IDLE)

    fun loading(loadingProductId: ProductId) =
            this.copy(inputProductId = loadingProductId, viewState = ViewState.LOADING)

    fun offline(): ViewModel =
            this.copy(viewState = ViewState.OFFLINE)

    fun online(): ViewModel =
            this.copy(viewState = ViewState.IDLE)
}

interface IView {
    val viewEvents: Observable<out RealWorldEvent>
    val appContext: Context
}

class View : AppCompatActivity(), IView {
    override val viewEvents: Observable<out RealWorldEvent>
        get() = Observable.merge(lifecycleEvents, submitProductIds)

    override val appContext: Context
        get() = applicationContext

    private val submitProductIds: Observable<SubmitProductId>
        get() = RxSearchView
                .queryTextChangeEvents(searchView!!)
                .distinctUntilChanged()
                .filter { it.isSubmitted }
                .doOnNext {
                    searchView?.let {
                        it.isSubmitButtonEnabled = false

                        val mgr =
                                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        mgr.hideSoftInputFromWindow(it.windowToken, 0)
                        it.onActionViewCollapsed()
                    }
                }
                .map { SubmitProductId(it.queryText().toString()) }

    private val lifecycleEvents: PublishSubject<LifecycleEvent> = PublishSubject.create()

    private var update: Update? = null
    private val compositeDisposable = CompositeDisposable()

    private var parentView: View? = null
    private var progressView: ProgressBar? = null
    private var topContainerView: View? = null
    private var bottomContainerView: View? = null
    private var emptyView: TextView? = null
    private var searchView: SearchView? = null
    private var priceDiffView: TextView? = null
    private var nameView: TextView? = null
    private var closingPriceView: TextView? = null
    private var currentPriceView: TextView? = null
    private var snackbar: Snackbar? = null

    private val onNextViewModel: ((ViewModel) -> Unit) = { viewModel: ViewModel ->
        fun updateIfChanged(value: String?, view: TextView?) = view?.let {
            when (it.text != value) {
                true -> {
                    it.text = value
                }
                else -> Unit
            }
            val textColor = when (viewModel.viewState) {
                ViewState.IDLE -> R.color.uptodate_product
                ViewState.LOADING, ViewState.OFFLINE -> R.color.outdated_product
            }
            it.setTextColor(resources.getColor(textColor, theme))
        }

        when (viewModel.viewState) {
            ViewState.LOADING -> {
                snackbar?.dismiss()
                snackbar = null
                progressView!!.visibility = View.VISIBLE
                emptyView!!.visibility = View.GONE
                topContainerView!!.visibility = View.GONE
                bottomContainerView!!.visibility = View.GONE
            }
            ViewState.OFFLINE -> {
                searchView?.visibility = View.GONE
                searchView?.isSubmitButtonEnabled = false

                if (snackbar == null) {
                    snackbar = Snackbar.make(parentView!!, R.string.offline,
                            Snackbar.LENGTH_INDEFINITE)
                    snackbar!!.show()
                } else {
                    snackbar!!.setText(R.string.offline).show()
                }
                priceDiffView!!.setTextColor(resources.getColor(R.color.outdated_product, theme))
                closingPriceView!!.setTextColor(resources.getColor(R.color.outdated_product, theme))
                currentPriceView!!.setTextColor(resources.getColor(R.color.outdated_product, theme))
            }
            ViewState.IDLE -> {
                searchView?.visibility = View.VISIBLE
                searchView?.isSubmitButtonEnabled = true

                val eModel = viewModel.eitherData
                when (eModel) {
                    null -> {
                        snackbar?.dismiss()
                        snackbar = null
                        emptyView!!.visibility = View.VISIBLE
                        progressView!!.visibility = View.GONE
                        topContainerView!!.visibility = View.GONE
                        bottomContainerView!!.visibility = View.GONE
                    }
                    is Right<Product> -> {
                        snackbar?.dismiss()
                        snackbar = null
                        emptyView!!.visibility = View.GONE
                        progressView!!.visibility = View.GONE
                        topContainerView!!.visibility = View.VISIBLE
                        bottomContainerView!!.visibility = View.VISIBLE

                        val product = eModel.value
                        updateIfChanged("${product.displayName}\n(${product.symbol})", nameView)
                        updateIfChanged(product.priceDiffFormatted, priceDiffView)
                        priceDiffView!!.setTextColor(
                                if (product.negativeTrend) Color.RED else Color.GREEN)
                        updateIfChanged(product.closingPrice.toString(), closingPriceView)
                        updateIfChanged(product.currentPrice.toString(), currentPriceView)
                    }
                    is Left<ErrorMessage> -> if (snackbar == null) {
                        emptyView!!.visibility = View.GONE
                        progressView!!.visibility = View.GONE

                        val errorMessage = eModel.value
                        snackbar = Snackbar.make(parentView!!, errorMessage,
                                Snackbar.LENGTH_INDEFINITE)
                        // TODO: add button to re-submit last query
                        snackbar!!.show()
                    } else {
                        emptyView!!.visibility = View.GONE
                        progressView!!.visibility = View.GONE
                        snackbar!!.setText(eModel.value).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupViews()

        // Since it's a very simple app, use the application instance instead of a dependency
        // injection framework
        update = Update(this, TradingApplication.api)
        update!!.viewModels(ViewModel.empty(application.applicationContext))
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.doOnEach { Log.d("[View#viewModels]", "$it") }
                ?.subscribe(onNextViewModel)
                ?.addTo(compositeDisposable)
    }

    private fun setupViews() {
        setContentView(R.layout.activity_product)
        setSupportActionBar(findViewById(R.id.toolbar))

        parentView = findViewById(R.id.view_product)
        emptyView = findViewById(R.id.view_empty)
        progressView = findViewById(android.R.id.progress)
        progressView!!.layoutParams = Toolbar.LayoutParams(Gravity.END)
        topContainerView = findViewById(R.id.view_container_top)
        bottomContainerView = findViewById(R.id.view_container_bottom)
        searchView = findViewById(R.id.view_search)
        searchView!!.layoutParams = Toolbar.LayoutParams(Gravity.END)
        nameView = findViewById(R.id.view_name)
        priceDiffView = findViewById(R.id.view_price_diff)
        currentPriceView = findViewById(R.id.view_current_price)
        closingPriceView = findViewById(R.id.view_closing_price)
    }

    override fun onStart() {
        super.onStart()
        lifecycleEvents.onNext(LifecycleEvent(LifecycleEvent.EventType.START))
    }

    override fun onRestart() {
        super.onRestart()
        lifecycleEvents.onNext(LifecycleEvent(LifecycleEvent.EventType.RESTART))
    }

    override fun onStop() {
        lifecycleEvents.onNext(LifecycleEvent(LifecycleEvent.EventType.STOP))
        super.onStop()
    }

    override fun onDestroy() {
        lifecycleEvents.onNext(LifecycleEvent(LifecycleEvent.EventType.DESTROY))
        compositeDisposable.clear()
        super.onDestroy()
    }
}
