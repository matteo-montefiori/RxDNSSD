package com.github.druk.dnssdsamples

import android.content.Context
import android.util.Log
import com.github.druk.rx3dnssd.BonjourService
import com.github.druk.rx3dnssd.Rx3DnssdEmbedded
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.ReplaySubject
import java.util.concurrent.TimeUnit

private const val Tag = "NsdManager"

object NsdManager {

    private const val NsdDefaultTimeout = 3L
    private const val NsdDefaultDelay = 2000L
    lateinit var dnssd : Rx3DnssdEmbedded


    /*
     * andriydruk/RxDNSSD
     */

    private var scanInProgress: Boolean = false
    private val scanMutex = Any()
    private lateinit var scanBehaviourSubj: ReplaySubject<BonjourService>
    private var scanCount: UInt = 0u
    private var disp: Disposable? = null

    class DnsSdServiceInfo(
        val serviceName: String,
        val flags: Int,
        val ifIndex: Int,
        val regType: String,
        val domain: String,
        val hostName: String,
        val port: Int,
        val txtRecord: Map<String?, String?>? = null,
        val fullName: String? = null
    )

    private class RetryWithDelay(
        private val maxRetries: Int,
        private val retryDelayMillis: Long
    ) : (Observable<Throwable>) -> Observable<*> {
        private var retryCount = 0
        override fun invoke(attempts: Observable<Throwable>): Observable<*> {
            return attempts.flatMap { throwable ->
                if (++retryCount < maxRetries) {
                    Observable
                        .timer(retryDelayMillis, TimeUnit.MILLISECONDS)
                } else {
                    Observable
                        .timer(retryDelayMillis, TimeUnit.MILLISECONDS)
                        .concatMap { Observable.error(throwable) }
                }
            }
        }
    }

    fun clear(){
        disp?.let { if (!it.isDisposed) it.dispose() }
    }

    @JvmOverloads
    fun discoverDnsSdServices(
        context: Context,
        serviceType: String = "_services._dns-sd._udp",
        discoveryTimeout: Long = NsdDefaultTimeout
    ): Observable<BonjourService> {
        var isAssigned = false
        while (!isAssigned) {
            synchronized(scanMutex) {
                isAssigned = if (!scanInProgress && scanCount == 0u) {
                    scanInProgress = true
                    scanBehaviourSubj = ReplaySubject.create()
                    scanCount = scanCount.inc()
                    Log.d(Tag, "Scan not in progress and no observer, observer +1 -> $scanCount")
                    startDnsSdDiscovery(context, serviceType, discoveryTimeout)
                    true
                } else if (scanInProgress) {
                    scanCount = scanCount.inc()
                    Log.d(Tag, "Scan in progress, observer +1 -> $scanCount")
                    true
                } else {
                    false
                }
            }
        }

        return scanBehaviourSubj
            .doFinally {
                synchronized(scanMutex) {
                    scanInProgress = false
                    scanCount = scanCount.dec()
                    Log.d(Tag, "Scan observer -1 -> $scanCount")
                }
            }
    }

    private fun startDnsSdDiscovery(
        context: Context,
        serviceType: String = "_services._dns-sd._udp",
        discoveryTimeout: Long = NsdDefaultTimeout
    ) {
        disp?.let { if (!it.isDisposed) it.dispose() }
        disp = dnsSdDiscovery(context, serviceType, discoveryTimeout)
    }

    private fun dnsSdDiscovery(
        context: Context,
        serviceType: String = "_services._dns-sd._udp",
        discoveryTimeout: Long = NsdDefaultTimeout
    ): Disposable {
        Log.d(Tag, "discoverDnsSdServices starts")

        if (!this::dnssd.isInitialized)
            dnssd = Rx3DnssdEmbedded(context)

        Log.i(Tag, "start browse")
        return dnssd.browse(serviceType, "local.")
            //.compose(dnssd.resolve())
            //.compose(dnssd.queryIPRecords())
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { bonjourService: BonjourService ->
                    Log.d(Tag, bonjourService.toString())
                    scanBehaviourSubj.onNext(bonjourService)
                },
                { throwable: Throwable? ->
                    Log.e(
                        Tag,
                        "error",
                        throwable
                    )
                    throwable?.let {
                        scanBehaviourSubj.onError(it)
                    }
                }
            )
    }

}

fun <E : Any, T : Observable<E>> T.toSingle(): Single<E> {
    return this.take(1).singleOrError()
}