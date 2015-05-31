package com.wordpress.randomexplorations.sync;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Created by maniksin on 5/28/15.
 * This class defines the channel used by PeerManager
 * to send notifications to the client.
 *
 * In this case, the PeerManager is running inside
 * the MainUI thread, so this channel is just a wrapper
 * to access the main Activity for updating the UI
 */
public class ClientChannel {

    public MainActivity mActivity;

    public ClientChannel(MainActivity thisActivity) {
        mActivity = thisActivity;
    }

    public void updateStatus(String status) {
        mActivity.getTextView().setText(status);
    }

    public void reportInitFailure(String status) {
        // Report PeerManager init failure
        // @todo: Display different view
        mActivity.getTextView().setText(status);
    }

    public void reportOperationResult(int err, Operation op) {
        if (err == PeerManager.PEER_MANAGER_ERR_SUCCESS) {
            String str = new String((byte [])op.mResponse);
            mActivity.getTextView().setText("Send Message success: " + str);
        } else {
            mActivity.getTextView().setText("Send message failure: " + op.mOperationStatusString);
        }
    }

    public void setLocalNetworkParams(SocketAddress sockaddr) {
        InetSocketAddress addr = (InetSocketAddress)sockaddr;
        String str = new String("Listening on " + addr.getAddress().toString() + "-" +
                Integer.toString(addr.getPort()));
        mActivity.getTextView().setText(str);
        mActivity.startNetworking();
    }
}
