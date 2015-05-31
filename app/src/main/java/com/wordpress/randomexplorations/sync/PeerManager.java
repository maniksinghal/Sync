package com.wordpress.randomexplorations.sync;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by maniksin on 5/28/15.
 * Class to handle network operations with peers.
 */
public class PeerManager {
    private List<Peer> mPeers;
    private boolean mInitDone;

    private ClientChannel mChannel;  // Client provided Api callback for updating result from network operations

    // Handle to foreground thread (Needed to kill it on activity stop)
    private HandlerThread mWorkerThread = null;

    // Counters to track whether worker thread(s) are busy
    private int mPendingOperations = 0;
    private int mPendingServiceOperations = 0;

    // Background (service) thread's handler (peer => service communication)
    private WorkHandler mServiceHandler = null;
    private WorkHandler mWorker;    // Foreground thread's handler (peer => worker communication)

    private InetAddress localAddr;  // IP address to which we are bound

    // Handler provided to worker threads (worker => peer communication)
    private Handler mClientHandler;

    // Messages between worker thread and peer manager
    // worker-thread => peer-manager
    public final static int MSG_WORKER_INIT_STATUS = 1;
    public final static int MSG_WORKER_OP_STATUS = 2;
    public final static int MSG_WORKER_CLEANUP_DONE = 3;

    // peer-manager => worker-thread
    public final static int MSG_WORKER_RUN_OP = 1;
    public final static int MSG_WORKER_CLEANUP = 2;
    public final static int MSG_WORK_SERVICE_INIT_1 = 3;
    public final static int MSG_WORK_SERVICE_INIT_2 = 4;

    // Peer state
    public final static int PEER_STATE_DISCOVERED = 1;
    public final static int PEER_STATE_LOST = 2;

    // Worker context
    public final static int WORKER_TYPE_FOREGROUND = 1;
    public final static int WORKER_TYPE_BACKGROUND_SERVICE = 2;

    // Peer-Client messages return codes
    public final static int PEER_MANAGER_ERR_SUCCESS = 0;
    public final static int PEER_MANAGER_ERR_NOT_INITED = 1;
    public final static int PEER_MANAGER_ERR_INVALID = 2;
    public final static int PEER_MANAGER_ERR_PEER_LOST = 3;
    public final static int PEER_MANAGER_ERR_INIT_FAILED = 4;
    public final static int PEER_MANAGER_ERR_IO_EXCEPTION = 5;
    public final static int PEER_MANAGER_ERR_PEER_EXISTS = 6;
    public final static int PEER_MANAGER_ERR_INVALID_RESPONSE = 7;
    public final static int PEER_MANAGER_ERR_INVALID_REQUEST = 8;
    public final static int PEER_MANAGER_ERR_BUSY = 9;

    public final static String LOGGER = "PeerManagerLogger";


    /*
    API for starting a background operation.
    The request shall be sent to the thread created by the service
     */
    public int startBackgroundOperation(Operation op) {

        if (mServiceHandler == null) {
            return PEER_MANAGER_ERR_NOT_INITED;
        }

        if (op.mPeer == null) {
            return PEER_MANAGER_ERR_INVALID;
        }

        if (op.mPeer.state == PEER_STATE_LOST) {
            return PEER_MANAGER_ERR_PEER_LOST;
        }

        // Validations complete.. Enqueue operation to Worker thread
        Message msg = mServiceHandler.obtainMessage();
        msg.obj = op;
        msg.what = MSG_WORKER_RUN_OP;
        mServiceHandler.sendMessage(msg);

        mPendingServiceOperations++;
        return PEER_MANAGER_ERR_SUCCESS;

    }

    /*
    * API to invoke operations for the foreground thread
    * for short operations like send-message and receive reply.
    *
    * Assumes, the operations are triggered by the user so that the phone
    * does not go to sleep while they run.
     */
    public int startOperation(Operation Op) {

        if (!mInitDone) {
            return PEER_MANAGER_ERR_NOT_INITED;
        }

        if (Op.mPeer == null) {
            return PEER_MANAGER_ERR_INVALID;
        }

        if (Op.mPeer.state == PEER_STATE_LOST) {
            return PEER_MANAGER_ERR_PEER_LOST;
        }

        // Validations complete.. Enqueue operation to Worker thread
        Message msg = mWorker.obtainMessage();
        msg.obj = Op;
        msg.what = MSG_WORKER_RUN_OP;
        mWorker.sendMessage(msg);
        mPendingOperations++;

        return PEER_MANAGER_ERR_SUCCESS;
    }

    private boolean is_same_peer(Peer A, Peer B) {
        return A.ip_address.equals(B.ip_address) && A.portNumber == B.portNumber;
    }

    /*
    * Activity is stopping
    * Cleanup peer-manager context.
     */
    public void cleanup() {
        Message msg = mWorker.obtainMessage();
        msg.what = MSG_WORKER_CLEANUP;
        mWorker.sendMessage(msg);

        /*
        * We should not be cleaning up the service thread
        * as its job is to continue performing background operations.

        if (mServiceHandler != null) {
            msg = mServiceHandler.obtainMessage();
            msg.what = MSG_WORKER_CLEANUP;
            mServiceHandler.sendMessage(msg);
        }
        */


    }

    /*
    * Add a peer to the PeerManager database.
     */
    public int addPeer(Peer peer) {
        for (Peer p : mPeers) {
            if (is_same_peer(p, peer)) {
                return PEER_MANAGER_ERR_PEER_EXISTS;
            }
        }

        // New Peer
        mPeers.add(peer);
        return PEER_MANAGER_ERR_SUCCESS;
    }


    /*
    * Callback when the service invoked by PeerManager finally gets
    * connected/disconnected.
     */
    private ServiceConnection mServiceConn = new ServiceConnection() {

        public void onServiceConnected(ComponentName name, IBinder service) {
            ServiceBinder sb = (ServiceBinder)service;
            mServiceHandler = sb.workhandler;


            Message msg = mServiceHandler.obtainMessage();
            msg.what = MSG_WORK_SERVICE_INIT_1;
            msg.arg2 = WORKER_TYPE_BACKGROUND_SERVICE;
            msg.obj = mClientHandler;
            mServiceHandler.sendMessage(msg);

            msg = mServiceHandler.obtainMessage();
            msg.what = MSG_WORK_SERVICE_INIT_2;
            msg.obj = localAddr;
            mServiceHandler.sendMessage(msg);
        }

        public void onServiceDisconnected(ComponentName name) {
            mServiceHandler = null;
        }

    };

    public PeerManager(ClientChannel channel, InetAddress addr) {
        Looper looper;
        mPeers = new ArrayList();
        mChannel = channel;
        mInitDone = false;
        localAddr = addr;

        /* Create the foreground worker thread. It will start and block
         * on its Message Queue. We later retrieve the queue-handle
         * to post messages to it.
         */
        mWorkerThread  = new HandlerThread("Worker");
        mWorkerThread.start();
        looper = mWorkerThread.getLooper();

        // Handler to receive messages from the foreground/background
        // worker threads.
        Handler client = new Handler(Looper.getMainLooper()) {

            @Override
            public void handleMessage(Message msg) {

                switch (msg.what) {
                    case MSG_WORKER_INIT_STATUS:
                        if (msg.arg1 == PEER_MANAGER_ERR_SUCCESS) {
                            mInitDone = true;
                            mChannel.setLocalNetworkParams((SocketAddress)msg.obj);
                            Log.d(LOGGER, "Workhandler init success for context: " + msg.arg2);
                        } else {
                            mChannel.reportInitFailure((String)msg.obj);
                        }
                        break;

                    // Result of the operation enqueued to the worker thread.
                    case MSG_WORKER_OP_STATUS:
                        mChannel.reportOperationResult(msg.arg1, (Operation)msg.obj);
                        if (msg.arg2 == WORKER_TYPE_FOREGROUND) {
                            mPendingOperations--;
                        } else {
                            mPendingServiceOperations--;
                        }
                        break;

                    // Result of the cleanup request sent to the foreground worker thread.
                    case MSG_WORKER_CLEANUP_DONE:
                        if (msg.arg2 == WORKER_TYPE_FOREGROUND) {
                            mWorkerThread.interrupt();  // Kill the thread now
                            mWorkerThread = null;
                        }
                        // Don't unbind service as background operations might be ongoing
                        Log.d(LOGGER, "Worker thread cleanup done received");
                        break;

                    default:
                        break;
                }

            }

        };

        mWorker = new WorkHandler(looper, client, addr, WORKER_TYPE_FOREGROUND);
        mClientHandler = client;

        // Create a bound service for background task requests
        Intent i = new Intent(mChannel.mActivity, WorkService.class);
        mChannel.mActivity.getApplicationContext().bindService(i, mServiceConn, Context.BIND_AUTO_CREATE);

    }


}
