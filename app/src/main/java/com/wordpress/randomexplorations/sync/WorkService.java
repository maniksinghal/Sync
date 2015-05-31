package com.wordpress.randomexplorations.sync;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Messenger;
import android.util.Log;

/**
 * Created by maniksin on 5/30/15.
 * Class for service that invokes the background thread.
 * General jargon, except that it uses an extended Binder
 * to communicate background thread's message handler to the peer Manager.
 */
public class WorkService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        WorkHandler workHandler;
        HandlerThread t = new HandlerThread("ServiceWorker");
        t.start();
        Looper looper = t.getLooper();
        workHandler = new WorkHandler(looper);
        ServiceBinder sb  = new ServiceBinder(workHandler);
        return sb;
    }
}
