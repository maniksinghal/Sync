package com.wordpress.randomexplorations.sync;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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

    // for test-only
    private Peer mPeer;

    private final static String ONGOING_SYNC_STATUS = "ongoing_sync_status";
    private final static String LAST_SYNC_STATUS = "last_sync_status";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onStart() {
        InetAddress addr;

        super.onStart();

        mTv = (TextView)findViewById(R.id.myID);
        mChannel = new ClientChannel(this);

        // Check Wifi state
        ConnectivityManager cManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!cManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI).isConnected()) {
            mTv.setText("Wifi not connected");
            return;
        } else {
            WifiManager wifiMgr = (WifiManager)getSystemService(WIFI_SERVICE);
            String ip = formatIpAddress(wifiMgr.getConnectionInfo().getIpAddress());
            mTv.setText("Trying with " + ip);
            try {
                addr = InetAddress.getByName(ip);
            } catch (UnknownHostException e) {
                mTv.setText(e.getMessage());
                return;
            }
        }

        mPeerManager = new PeerManager(mChannel, addr);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mPeerManager.cleanup();
    }

    public void startNetworking() {
        // Test code is what all it is
        try {
            mPeer = new Peer(InetAddress.getByName("192.168.1.103"), 32001, "some-peer");
        } catch (UnknownHostException e) {
            mTv.setText("Unable to create peer: " + e.getMessage());
            return;
        }

        mPeerManager.addPeer(mPeer);

        mTv.setText(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString());

    }

    private void scanDirectory(List<String> file_list, File directory, SharedPreferences lastSync,
                               SharedPreferences curSync) {

        if (lastSync.getLong(directory.getAbsolutePath(), 0) == directory.lastModified()) {
            // Directory not modified since last sync, nothing to do further
            return;
        }

        File[] files = directory.listFiles();
        int total = files.length;
        for (int i = 0; i < total; i++) {
            if (files[i].isFile()) {
                file_list.add(files[i].getAbsolutePath());
                Log.d(PeerManager.LOGGER, "Added file " + files[i].getAbsolutePath());
            } else if (files[i].isDirectory()) {
                scanDirectory(file_list, files[i], lastSync, curSync);
            }
        }

    }

    public void onButtonClick (View view) {

        List<String> dirs = new ArrayList<>();
        dirs.add(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM).toString());

        Operation op = new Operation(mPeer, this);
        op.mOperationType = Operation.OPERATION_TYPE_SYNC_DIRECTORIES;
        op.mObj = dirs;
        mPeerManager.startBackgroundOperation(op);
    }

    /*
    * File transfer button
     */
    public void onButtonClick2 (View view) {
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
    * Cancel file transfer button
    * Applies only to the running background operation
     */
    public void onButtonClick3 (View view) {
        mPeerManager.cancelOperation();
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
