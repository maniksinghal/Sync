package com.wordpress.randomexplorations.sync;

import android.os.Binder;

/**
 * Created by maniksin on 5/31/15.
 */
public class ServiceBinder extends Binder {
    public WorkHandler workhandler;

    public ServiceBinder(WorkHandler wh) {
        workhandler = wh;
    }
}
