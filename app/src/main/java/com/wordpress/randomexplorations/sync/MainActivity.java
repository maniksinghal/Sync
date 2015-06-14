package com.wordpress.randomexplorations.sync;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static android.text.format.Formatter.*;

/*
* Main UI Activity of the application
 */
public class MainActivity extends Activity {

    private PeerManager mPeerManager;
    private TextView mTv;
    private ClientChannel mChannel;
    private NetworkServiceFinder mServiceFinder = null;
    private NetworkServiceHandler mNetworkServiceHandler;
    private InetAddress mMyAddr;

    // for test-only
    private Peer mPeer;

    private final String PEER_SERVICE_TYPE = "_share._tcp.";
    private final String PEER_SERVICE_NAME = "myShare";

    // Message from NetworkServiceFinder thread when Peer is discovered
    public static final int NETWORK_SERVICE_MSG_DISCOVERED = 1;

    private class NetworkServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == NETWORK_SERVICE_MSG_DISCOVERED) {
                InetAddress ip = (InetAddress)msg.obj;
                int port = msg.arg1;
                mPeer = new Peer(ip, port, PEER_SERVICE_NAME);
                mPeerManager.addPeer(mPeer);
                mTv.setText("Got Peer at port: " + port);
                Log.d(PeerManager.LOGGER, "Got peer at port: " + port);
            }
        }

        public NetworkServiceHandler(Looper looper) {
            super(looper);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mNetworkServiceHandler = new NetworkServiceHandler(Looper.getMainLooper());
    }

    @Override
    protected void onStart() {

        super.onStart();

        mTv = (TextView) findViewById(R.id.myID);
        mChannel = new ClientChannel(this);

        // Check Wifi state
        ConnectivityManager cManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!cManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI).isConnected()) {
            mTv.setText("Wifi not connected: " + cManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI).getState().toString());
            return;
        } else {
            WifiManager wifiMgr = (WifiManager) getSystemService(WIFI_SERVICE);
            String ip = formatIpAddress(wifiMgr.getConnectionInfo().getIpAddress());
            mTv.setText("Trying with " + ip);
            try {
                mMyAddr = InetAddress.getByName(ip);
            } catch (UnknownHostException e) {
                mTv.setText(e.getMessage());
                return;
            }
        }

        String android_id = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ANDROID_ID);
        mPeerManager = new PeerManager(mChannel, mMyAddr, android_id);

    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mPeerManager != null) {
            mPeerManager.cleanup();
            mPeerManager = null;
        }

    }

    public void startNetworking(int myPort) {


        // Test code is what all it is
        try {
            mPeer = new Peer(InetAddress.getByName("192.168.1.165"), 32002, "some-peer");
        } catch (UnknownHostException e) {
            mTv.setText("Unable to create peer: " + e.getMessage());
            return;
        }

        mPeerManager.addPeer(mPeer);


        /*
        if (mServiceFinder == null) {
            mServiceFinder = new NetworkServiceFinder(PEER_SERVICE_TYPE, PEER_SERVICE_NAME, this, mNetworkServiceHandler);
            mServiceFinder.startDiscovery(myPort, mMyAddr);
        }
        */


    }

    private void add_directory_to_list(List<String> dirs, String dir) {

        File target = new File(dir);
        if (target != null && target.isDirectory()) {
            if (!dirs.contains(target.toString())) {
                dirs.add(target.toString());
                Log.d(PeerManager.LOGGER, "Added " + target.toString() + " to directories");
            } else {
                Log.d(PeerManager.LOGGER, "Skipping " + target.toString() + ". Already exists!!");
            }
        }
    }

    private List<String> gen_dcim_directories_list() {
        List<String> dirs = new ArrayList<>();
        add_directory_to_list(dirs,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString());
        add_directory_to_list(dirs, System.getenv("SECONDARY_STORAGE") + "/DCIM");
        add_directory_to_list(dirs, System.getenv("SECONDARY_STORAGE") + "/DCIM");
        add_directory_to_list(dirs, System.getenv("EXTERNAL_SDCARD_STORAGE") + "/DCIM");

        return dirs;

    }

    public void onButtonClick(View view) {

        List<String> dirs = gen_dcim_directories_list();

        Operation op = new Operation(mPeer, this);
        op.mOperationType = Operation.OPERATION_TYPE_SYNC_DIRECTORIES;
        op.mObj = dirs;
        mPeerManager.startBackgroundOperation(op);
    }

    /*
    * File transfer button
     */
    public void onButtonClick2(View view) {
        Operation op = new Operation(mPeer, this);
        Log.d(PeerManager.LOGGER, "Sending File to peer");


        List<String> mylist = new ArrayList<>();
        mylist.add(new String("/sdcard/DCIM/Camera/IMG_20150506_095011836.jpg"));
        mylist.add(new String("/sdcard/DCIM/Camera/IMG_20150506_102007388_HDR.jpg"));
        op.mOperationStatus = Operation.OPERATION_STATUS_PENDING;
        op.mOperationType = Operation.OPERATION_TYPE_SEND_FILE;
        op.mObj = mylist;
        int ret = mPeerManager.startBackgroundOperation(op);
        Log.d(PeerManager.LOGGER, "Start file send said: " + ret);

    }



    /*
    * Get total pending files
     */
    public void onButtonClick4(View view) {
        List<String> dirs = gen_dcim_directories_list();

        Operation op = new Operation(mPeer, this);
        op.mOperationType = Operation.OPERATION_TYPE_FETCH_SYNC_STATUS;
        op.mObj = dirs;
        mPeerManager.startBackgroundOperation(op);

    }

    /*
    * Cancel file transfer button
    * Applies only to the running background operation
     */
    public void onButtonClick3(View view) {

        if (mPeerManager != null) {
            mPeerManager.cancelOperation();
        }
    }

    public TextView getTextView() {
        return mTv;
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
