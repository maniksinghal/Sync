package com.wordpress.randomexplorations.sync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static android.text.format.Formatter.*;

/*
* Main UI Activity of the application
 */
public class MainActivity extends ActionBarActivity {

    private PeerManager mPeerManager;
    private TextView mTv;
    private ClientChannel mChannel;

    // for test-only
    private Peer mPeer;

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

    }

    public void onButtonClick (View view) {
        Operation op = new Operation(mPeer);
        Log.d(PeerManager.LOGGER, "Sending Hello world message to peer");
        String str = new String("Hello world");

        op.setMessageSend(str.getBytes());
        int ret = mPeerManager.startBackgroundOperation(op);
        Log.d(PeerManager.LOGGER, "Start operation said: " + ret);

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
