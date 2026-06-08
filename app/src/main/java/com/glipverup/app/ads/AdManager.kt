package com.glipverup.app.ads

import android.app.Activity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.glipverup.app.BuildConfig

object AdManager {
    private var mInterstitialAd: InterstitialAd? = null

    fun initialize(activity: Activity) {
        if (BuildConfig.DEBUG) return
        MobileAds.initialize(activity) {}
        loadAd(activity)
    }

    fun loadAd(activity: Activity) {
        if (BuildConfig.DEBUG) return
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            activity,
            "ca-app-pub-3940256099942544/1033173712",
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    mInterstitialAd = interstitialAd
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    mInterstitialAd = null
                }
            }
        )
    }

    fun showAdIfAvailable(activity: Activity) {
        if (BuildConfig.DEBUG) return
        mInterstitialAd?.let {
            it.show(activity)
            mInterstitialAd = null
            loadAd(activity)
        } ?: run {
            loadAd(activity)
        }
    }
}
