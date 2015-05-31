package com.wordpress.randomexplorations.sync;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.Runnable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Created by maniksin on 5/28/15.
 */
public class WorkHandler extends Handler {
    private Handler mClient;
    private Socket mSocket;
    private InetSocketAddress remoteAddr;
    private InetSocketAddress localAddr;
    private int mCallerContext;


    // Messages exchanged with the peer
    // Keep in sync with Peer's project
    private final int NETWORK_MSG_STRING_ID = 1;
    private final int NETWORK_MSG_ACK_ID = 2;

    private class NetworkResponse {
        public byte[] response;  // payload
        public int msg_id;
        public int response_len;
        public int return_code;
        public IOException e;

        private final static int NETWORK_RESPONSE_SUCCESS = 0;
        private final static int NETWORK_RESPONSE_ERR_IO_EXCEPTION = 1;
        private final static int NETWORK_RESPONSE_ERR_SHORT_LEN = 2;
    }

    public WorkHandler() {
        mClient = null;
        localAddr = null;
        remoteAddr = null;
        mSocket = null;
    }

    public WorkHandler(Looper looper) {
        super(looper);
    }

    public WorkHandler(Looper looper, Handler client, InetAddress addr, int context) {
        super(looper);
        workhandler_init(client, addr, context);
    }

    public void workhandler_init(Handler client, InetAddress addr, int context) {

        mClient = client;
        mCallerContext = context;

        // Create a DatagramSocket for Network actions.
        Message msg = mClient.obtainMessage();
        msg.what = PeerManager.MSG_WORKER_INIT_STATUS;
        msg.arg2 = context;

        try {
            localAddr = new InetSocketAddress(addr, 0);

            mSocket = new Socket();
            mSocket.bind(localAddr);

            msg.arg1 = PeerManager.PEER_MANAGER_ERR_SUCCESS;
            msg.obj = mSocket.getLocalSocketAddress();
        } catch ( SocketException e) {
            // Inform failure to PeerManager
            mSocket = null;
            msg.arg1 = PeerManager.PEER_MANAGER_ERR_INIT_FAILED;
            msg.obj = e.getMessage();
        } catch (IOException e) {
            mSocket = null;
            msg.arg1 = PeerManager.PEER_MANAGER_ERR_INIT_FAILED;
            msg.obj = e.getMessage();

        }

        mClient.sendMessage(msg);
    }

    private void runOp_sendMessage(Operation op) {

        Message msg = mClient.obtainMessage();
        NetworkResponse netResponse;

        byte[] payload = (byte[])op.mObj;
        byte[] full_message = new byte[8 + payload.length];
        ByteBuffer bb = ByteBuffer.wrap(full_message);
        bb.putInt(NETWORK_MSG_STRING_ID);
        bb.putInt(payload.length);
        bb.put(payload);

        InetSocketAddress sockaddr = new InetSocketAddress(op.mPeer.ip_address, op.mPeer.portNumber);
        netResponse = sendMessage(full_message, sockaddr, true);

        msg.what = PeerManager.MSG_WORKER_OP_STATUS;
        msg.obj = op;
        msg.arg2 = mCallerContext;
        switch(netResponse.return_code) {
            case NetworkResponse.NETWORK_RESPONSE_SUCCESS:
                msg.arg1 = PeerManager.PEER_MANAGER_ERR_SUCCESS;
                op.mOperationStatusString = new String("Success");
                op.mResponse = netResponse.response;
                break;
            case NetworkResponse.NETWORK_RESPONSE_ERR_IO_EXCEPTION:
                msg.arg1 = PeerManager.PEER_MANAGER_ERR_IO_EXCEPTION;
                op.mOperationStatusString = netResponse.e.getMessage();
                break;
            case NetworkResponse.NETWORK_RESPONSE_ERR_SHORT_LEN:
                msg.arg1 = PeerManager.PEER_MANAGER_ERR_INVALID_RESPONSE;
                op.mOperationStatusString = new String("Invalid Response");
                break;
            default:
                // Should not happen
                assert (false);
                break;
        }
        mClient.sendMessage(msg);
    }

    private NetworkResponse sendMessage(byte[] message, InetSocketAddress peer_addr, boolean reply_expected) {

        boolean reconnecting = true;
        NetworkResponse netResponse = new NetworkResponse();

        // Prepare the socket
        if (mSocket.isConnected() && remoteAddr != null) {
            if (remoteAddr.getAddress().equals(peer_addr.getAddress()) &&
                    remoteAddr.getPort() == peer_addr.getPort()) {
                // Connected to same socket
                Log.d(PeerManager.LOGGER, "Re-using connected socket");
                reconnecting = false;
            }
        }

        netResponse.return_code = NetworkResponse.NETWORK_RESPONSE_SUCCESS;
        boolean retry = true;
        while (retry) {
            retry = false;

            try {

                if (reconnecting) {
                    if (!mSocket.isClosed()) {
                        mSocket.close();  // Close any existing connections
                    }
                    mSocket = new Socket();
                    mSocket.bind(localAddr);
                    mSocket.connect(peer_addr);
                    mSocket.setSoTimeout(1000);  // 1 second
                    remoteAddr = peer_addr;
                }

                // Now send the message
                OutputStream out = mSocket.getOutputStream();
                out.write(message);

                if (reply_expected) {
                    InputStream in = mSocket.getInputStream();
                    DataInputStream din = new DataInputStream(in);

                    int msg_id = din.readInt();
                    int len = din.readInt();

                    int response_len = len;
                    byte[] response = null;
                    if (len > 0) {
                        response = new byte[len];
                        response_len = din.read(response);
                    }

                    if (response_len != len) {
                        netResponse.return_code = NetworkResponse.NETWORK_RESPONSE_ERR_SHORT_LEN;
                    } else {
                        netResponse.response = response;
                        netResponse.msg_id = msg_id;
                        netResponse.response_len = len;
                    }

                }
            } catch (IOException e) {

                if (!reconnecting) {
                    // We might have been using a stale socket. Retry once with fresh connection
                    reconnecting = true;
                    retry = true;
                    Log.d(PeerManager.LOGGER, "Exception with existing connection, retrying with fresh socket");
                } else {
                    netResponse.return_code = NetworkResponse.NETWORK_RESPONSE_ERR_IO_EXCEPTION;
                    netResponse.e = e;
                    remoteAddr = null;  // This would ensure fresh re-connect next time
                    Log.d(PeerManager.LOGGER, "Exception during IO!!");
                }

            }
        }

        return netResponse;
    }

    private void runOp(Operation op) {

        Message msg = mClient.obtainMessage();
        if (mSocket == null) {
            msg.what = PeerManager.MSG_WORKER_OP_STATUS;
            msg.arg1 = PeerManager.PEER_MANAGER_ERR_NOT_INITED;
            msg.obj = new String("Socket not connected!!");
            msg.arg2 = mCallerContext;
            mClient.sendMessage(msg);
            return;
        }

        switch (op.mOperationType) {
            case Operation.OPERATION_TYPE_SEND_MESSAGE:
                runOp_sendMessage(op);
                break;
            default:
                break;
        }

    }

    private void cleanup() {

        Message msg = mClient.obtainMessage();
        msg.what = PeerManager.MSG_WORKER_CLEANUP_DONE;
        msg.arg1 = PeerManager.PEER_MANAGER_ERR_SUCCESS;
        msg.arg2 = mCallerContext;

        if (mSocket != null && mSocket.isClosed() == false) {
            try {
                mSocket.close();
                mSocket = null;
            } catch (IOException e) {
                msg.arg1 = PeerManager.PEER_MANAGER_ERR_IO_EXCEPTION;
                msg.obj = e.getMessage();
            }
        }

        Log.d(PeerManager.LOGGER, "Cleanup complete within Worker thread");
        mClient.sendMessage(msg);
    }

    public void handleMessage(Message msg) {
        // Main logic here
        switch (msg.what) {
            case PeerManager.MSG_WORKER_RUN_OP:
                runOp((Operation)msg.obj);
                break;
            case PeerManager.MSG_WORKER_CLEANUP:
                cleanup();
                break;
            case PeerManager.MSG_WORK_SERVICE_INIT_1:   // service handler initialization-start
                mClient = (Handler)msg.obj;
                mCallerContext = msg.arg2;
                break;
            case PeerManager.MSG_WORK_SERVICE_INIT_2:  // service handler initialization-end
                workhandler_init(mClient, (InetAddress)msg.obj, mCallerContext);
                break;
            default:
                break;
        }
    }
}
