package com.github.druk.dnssdsamples;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.druk.rx3dnssd.BonjourService;
import com.github.druk.rx3dnssd.Rx3Dnssd;
import com.github.druk.rx3dnssd.Rx3DnssdEmbedded;

import java.util.Objects;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class MainActivity extends AppCompatActivity {

    private Rx3Dnssd rxDnssd;

    @Nullable
    private Disposable browseDisposable;
    @Nullable
    private Disposable registerDisposable;

    private ServiceAdapter mServiceAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rxDnssd = new Rx3DnssdEmbedded(this);

        findViewById(R.id.check_threads).setOnClickListener(v -> {
            /*
             *   When make browse after all services were found and timeout exhausted (default 60 sec) should be only 5 threads:
             *   - main
             *   - NsdManager
             *   - Thread #<n> (it's DNSSD browse thread)
             *   - RxIoScheduler-1 (rx possibly can create more or less threads, in my case was 2)
             *   - RxIoScheduler-2
             */
            Log.i("Thread", "Thread count " + Thread.activeCount() + ":");
            Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
            for (Thread thread : threadSet) {
                // We only interested in main group
                if (Objects.requireNonNull(thread.getThreadGroup()).getName().equals("main")) {
                    Log.v("Thread", thread.getName());
                }
            }
        });

        findViewById(R.id.register).setOnClickListener(v -> {
            if (registerDisposable == null) {
                register((Button) v);
            }
            else {
                unregister((Button) v);
            }
        });

        findViewById(R.id.browse).setOnClickListener(v -> {
            if (browseDisposable == null) {
                ((TextView) v).setText(R.string.browse_stop);
                findViewById(R.id.progressBar).setVisibility(View.VISIBLE);
                startBrowse();
            } else {
                ((TextView) v).setText(R.string.browse_start);
                findViewById(R.id.progressBar).setVisibility(View.INVISIBLE);
                stopBrowse();
                mServiceAdapter.clear();
            }
        });

        mServiceAdapter = new ServiceAdapter(this);

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(mServiceAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (browseDisposable == null) {
            ((TextView) findViewById(R.id.browse)).setText(R.string.browse_stop);
            findViewById(R.id.progressBar).setVisibility(View.VISIBLE);
            startBrowse();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (browseDisposable != null) {
            ((TextView) findViewById(R.id.browse)).setText(R.string.browse_start);
            findViewById(R.id.progressBar).setVisibility(View.INVISIBLE);
            stopBrowse();
            mServiceAdapter.clear();
        }
    }

    private void startBrowse() {
        Log.i("TAG", "start browse");
        browseDisposable = NsdManager.INSTANCE.discoverDnsSdServices(getBaseContext())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(bonjourService ->
                {
                    if (bonjourService.isLost()) {
                        mServiceAdapter.remove(bonjourService);
                    } else {
                        mServiceAdapter.add(bonjourService);
                    }
                }, throwable -> Log.e("TAG", "error", throwable));
    }

    private void stopBrowse() {
        Log.d("TAG", "Stop browsing");
        if (browseDisposable != null) {
            browseDisposable.dispose();
            NsdManager.INSTANCE.clear();
        }
        browseDisposable = null;
    }

    private void register(final Button button) {
        Log.i("TAG", "register");
        button.setEnabled(false);
        BonjourService bs = new BonjourService.Builder(0, 0, Build.DEVICE, "_rxdnssd._tcp", null).port(123).build();
        registerDisposable = rxDnssd.register(bs).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(bonjourService -> {
            Log.i("TAG", "Register successfully " + bonjourService.toString());
            button.setEnabled(true);
            button.setText(R.string.unregister);
            Toast.makeText(MainActivity.this, "Rgstrd " + Build.DEVICE, Toast.LENGTH_SHORT).show();
        }, throwable -> {
            Log.e("TAG", "error", throwable);
            button.setEnabled(true);
        });
    }

    private void unregister(final Button button) {
        Log.d("TAG", "unregister");
        if (registerDisposable != null) {
            registerDisposable.dispose();
        }
        registerDisposable = null;
        button.setText(R.string.register);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NsdManager.INSTANCE.clear();
        if (browseDisposable != null) {
            browseDisposable.dispose();
        }
        if (registerDisposable != null) {
            registerDisposable.dispose();
        }
    }
}
