/*
 * Copyright (C) 2016 Andriy Druk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.druk.dnssd;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.Semaphore;

/**
 * RxDnssd is implementation of RxDnssd with embedded DNS-SD  {@link InternalDNSSD}
 */
public class DNSSDEmbedded extends DNSSD {

    private static final String TAG = "DNSSDEmbedded";
    private Thread mThread;
    private volatile boolean isStarted = false;
    private int serviceCount = 0;

    private final Semaphore semaphore = new Semaphore(1);

    public DNSSDEmbedded(Context context) {
        super(context, "jdns_sd_embedded");
    }

    static native int nativeInit();

    static native int nativeLoop();

    static native void nativeExit();

    /**
     * Init DNS-SD thread and start event loop. Should be called before using any of DNSSD operations.
     * If DNS-SD thread has already initialised will try to reuse it.
     *
     * Note: This method will block thread until DNS-SD initialization finish.
     */
    public void init() {
        try {
            semaphore.acquire();
            Log.i(TAG, "semaphore init acquired");

            if (isStarted && mThread != null && mThread.isAlive()) {
                serviceCount++;
                Log.i(TAG, "thread already started, releasing it");
                semaphore.release();
                return;
            }
        } catch (Exception e) {
            Log.i(TAG, "semaphore init exception: " + e);
            semaphore.release();
            return;
        }

        isStarted = false;

        InternalDNSSD.getInstance();
        mThread = new Thread() {
            public void run() {
                Log.i(TAG, "init");
                int err = nativeInit();
                if (err != 0) {
                    Log.e(TAG, "error: " + err);
                    semaphore.release();
                    Log.i(TAG, "semaphore run error released");
                    return;
                }
                isStarted = true;
                serviceCount++;
                semaphore.release();
                Log.i(TAG, "start - semaphore released");
                int ret = nativeLoop();
                isStarted = false;
                serviceCount = 0;
                Log.i(TAG, "finish with code: " + ret);
                semaphore.release();
            }
        };
        mThread.setPriority(Thread.MAX_PRIORITY);
        mThread.setName("DNS-SDEmbedded");
        mThread.start();
    }

    /**
     * Exit from embedded DNS-SD loop. This method will stop DNS-SD after the delay (it makes possible to reuse already initialised DNS-SD thread).
     *
     * Note: method isn't blocking, can be used from any thread.
     */
    public void exit() {
        try {
            semaphore.acquire();
            Log.i(TAG, "serviceCount exit, semaphore acquired " + serviceCount);
            if (!isStarted) {
                Log.i(TAG, "thread not started, releasing semaphore");
                semaphore.release();
            } else {
                serviceCount--;
                Log.i(TAG, "serviceCount exit " + serviceCount);
                if (serviceCount > 0){
                    semaphore.release();
                } else {
                    nativeExit();
                }
            }
        } catch (Exception e) {
            // Log exception
            Log.i(TAG, "exception in exit semaphore acquire " + e);
        }
    }

    @Override
    public void onServiceStarting() {
        super.onServiceStarting();
        this.init();
    }

    @Override
    public void onServiceStopped() {
        super.onServiceStopped();
        this.exit();
    }
}
