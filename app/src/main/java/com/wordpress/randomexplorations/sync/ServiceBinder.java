package com.wordpress.randomexplorations.sync;

import android.os.Binder;

/**
 * Created by maniksin on 5/31/15.
 * Simple extension to the Binder class to communicate
 * background worker thread's handler to the PeerManager.
 *
 * The Binder class is used by the service to return a binder
 * object to the PeerManager when service is created and connected.
 *
 */
public class ServiceBinder extends Binder {
    public WorkHandler workhandler;

    public ServiceBinder(WorkHandler wh) {
        workhandler = wh;
    }
}
