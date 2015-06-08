package com.wordpress.randomexplorations.sync;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;


/**
 * Created by maniksin on 6/8/15.
 */
public class NetworkServiceFinder {
    private String mServiceType = null;
    private MainActivity caller = null;
    private Listener mServiceListener;
    private Resolver mServiceResolver;
    private NsdManager mNsdManager;
    private WifiManager.MulticastLock lock;
    private Registrar mRegistrar;
    private String mServiceName = null;
    private Handler mCallerHandler;


    public NetworkServiceFinder(String service_type, String service_name, MainActivity act, Handler handler) {
        mServiceType = service_type;
        caller = act;
        mServiceListener = new Listener();
        mServiceResolver = new Resolver();
        mNsdManager = (NsdManager) caller.getSystemService(Context.NSD_SERVICE);
        mRegistrar = new Registrar();
        mServiceName = service_name;
        mCallerHandler = handler;
    }

    private class Registrar implements NsdManager.RegistrationListener {
        public boolean registered = false;
        @Override
        public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {
            // Nothing to do
            Log.d(PeerManager.LOGGER, "Service registered");
            registered = true;
            if (mServiceResolver.service_resolved == true) {
                mNsdManager.unregisterService(mRegistrar);
                registered = false;
            }
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            // Registration failed!  Put debugging code here to determine why.
            Log.d(PeerManager.LOGGER, "Service registration failed. err: " + errorCode);
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo arg0) {
            // Service has been unregistered.  This only happens when you call
            Log.d(PeerManager.LOGGER, "Service unregistered");
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            // Unregistration failed.  Put debugging code here to determine why.
            Log.d(PeerManager.LOGGER, "Service unregistration failed. err: " + errorCode);
        }
    }


    private class Resolver implements NsdManager.ResolveListener {
        public boolean service_resolved = false;

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            // Called when the resolve fails.  Use the error code to debug.
            Log.d(PeerManager.LOGGER, "Service resolve failed. err_code: " + errorCode);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            int port = serviceInfo.getPort();
            InetAddress ip = serviceInfo.getHost();
            String service_name = serviceInfo.getServiceName();
            Log.d(PeerManager.LOGGER, "Found service " + service_name + " on port " + port);
            service_resolved = true;

            Message msg = mCallerHandler.obtainMessage();
            msg.what = MainActivity.NETWORK_SERVICE_MSG_DISCOVERED;
            msg.arg1 = port;
            msg.obj = ip;
            mCallerHandler.sendMessage(msg);
            stopDiscovery();
        }
    }

    private class Listener implements NsdManager.DiscoveryListener {

        @Override
        public void onDiscoveryStarted(String regType) {
            // Nothing to do as of now
            Log.d(PeerManager.LOGGER, "onDiscoveryStarted: " + regType);
        }

        @Override
        public void onServiceFound(NsdServiceInfo info) {
            // Core work here
            Log.d(PeerManager.LOGGER, "Some service found.. " + info.getServiceName());
            if (!info.getServiceType().equals(mServiceType)) {
                Log.d(PeerManager.LOGGER, "Found unrelated service: " + info.getServiceType());
            } else if (info.getServiceName().equals(mServiceName)) {
                // Found required service
                Log.d(PeerManager.LOGGER, "Found service: " + mServiceName);
                mNsdManager.resolveService(info, mServiceResolver);
            }
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
            Log.d(PeerManager.LOGGER, "Some service lost..");
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            Log.d(PeerManager.LOGGER, "Discovery stopped...");
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errCode) {
            Log.d(PeerManager.LOGGER, "Start discovery failed for " + serviceType
               + " err:" + errCode);
            stopDiscovery();
            // @todo: Inform application of discovery error.

        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errCode) {
            Log.d(PeerManager.LOGGER, "Stop discovery failed for " + serviceType
                    + " err:" + errCode);
            stopDiscovery();
        }
    }

    public void stopDiscovery() {

        Log.d(PeerManager.LOGGER, "Stopping service discovery..");

        mNsdManager.stopServiceDiscovery(mServiceListener);

        /*
        if (lock != null) {
            lock.release();
            lock = null;
        } */

        if (mRegistrar.registered) {
            mNsdManager.unregisterService(mRegistrar);
            mRegistrar.registered = false;
            mServiceResolver.service_resolved = false;
        }
    }
    public int startDiscovery(int myPort, InetAddress myAddr) {

        mServiceResolver.service_resolved = false;
        mRegistrar.registered = false;

        /* Acquire WIFI multicast
        WifiManager wifi = (WifiManager)caller.getSystemService(Context.WIFI_SERVICE);
        lock = wifi.createMulticastLock(caller.getClass().getName());
        lock.acquire();
        */

        // Register for service as well. Only this would make us query for available service with
        // same type, resulting in peer responding and the listener above catching the MDNS.
        NsdServiceInfo nsdInfo = new NsdServiceInfo();
        nsdInfo.setServiceName("client_local");  // not used by peer
        nsdInfo.setPort(myPort);
        nsdInfo.setServiceType(mServiceType);
        nsdInfo.setHost(myAddr);
        mNsdManager.registerService(nsdInfo, NsdManager.PROTOCOL_DNS_SD, mRegistrar);

        mNsdManager.discoverServices(mServiceType, NsdManager.PROTOCOL_DNS_SD, mServiceListener);

        return PeerManager.PEER_MANAGER_ERR_SUCCESS;
    }


}
