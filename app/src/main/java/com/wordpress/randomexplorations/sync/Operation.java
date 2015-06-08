package com.wordpress.randomexplorations.sync;

import android.app.Activity;

/**
 * Created by maniksin on 5/28/15.
 */
public class Operation {
    public Peer mPeer;
    public int mOperationType;  // see below
    public int mOperationStatus;  // see below
    public String mOperationStatusString;  // Stringified result from worker thread
    public Object mObj;
    public Object mResponse;    // Reply message from peer
    public Activity mThisActivity; // Activity launching the operation

    public final static int OPERATION_TYPE_UNKNOWN = 0;
    public final static int OPERATION_TYPE_SEND_MESSAGE = 1;
    public final static int OPERATION_TYPE_SEND_FILE = 2;
    public final static int OPERATION_TYPE_RECEIVE_FILE = 3;
    public final static int OPERATION_TYPE_SYNC_DIRECTORIES = 4;
    public final static int OPERATION_TYPE_FETCH_SYNC_STATUS = 5;

    public final static int OPERATION_STATUS_UNKNOWN = 0;
    public final static int OPERATION_STATUS_PENDING = 1;
    public final static int OPERATION_STATUS_ABORTED = 2;
    public final static int OPERATION_STATUS_COMPLETED = 3;

    public Operation(Peer peer, Activity thisActivity) {
        mPeer = peer;
        mOperationStatus = OPERATION_STATUS_UNKNOWN;
        mOperationType = OPERATION_TYPE_UNKNOWN;
        mThisActivity = thisActivity;
    }

    public void setMessageSend(byte[] message) {
        mOperationType = OPERATION_TYPE_SEND_MESSAGE;
        mOperationStatus = OPERATION_STATUS_PENDING;
        mObj = message;
    }

}
