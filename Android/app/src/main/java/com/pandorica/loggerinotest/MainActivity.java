package com.pandorica.loggerinotest;

import android.os.Handler;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import com.pandorica.loggerino.Logger;


// A simple test activity

public class MainActivity extends ActionBarActivity {

    private Logger mLogger;
    private Handler mHandler;
    private int count = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mLogger = Logger.getLogger(this);
        mHandler = new Handler();
        mHandler.post(logRun);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(logRun);
        mLogger.destroy();

    }

    private Runnable logRun = new Runnable() {
        public void run() {
            mLogger.e("Test","count: " + count);
            count++;
            mHandler.postDelayed(this, 1000);
        }
    };

}
