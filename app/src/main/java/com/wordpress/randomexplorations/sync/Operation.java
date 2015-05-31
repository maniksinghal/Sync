package com.wordpress.randomexplorations.sync;

/**
 * Created by maniksin on 5/28/15.
 */
public class Operation {
    public Peer mPeer;
    public int mOperationType;
    public int mOperationStatus;
    public String mOperationStatusString;
    public Object mObj;
    public Object mResponse;

    public final static int OPERATION_TYPE_UNKNOWN = 0;
    public final static int OPERATION_TYPE_SEND_MESSAGE = 1;
    public final static int OPERATION_TYPE_SEND_FILE = 2;
    public final static int OPERATION_TYPE_RECEIVE_FILE = 3;

    public final static int OPERATION_STATUS_UNKNOWN = 0;
    public final static int OPERATION_STATUS_PENDING = 1;
    public final static int OPERATION_STATUS_ABORTED = 2;
    public final static int OPERATION_STATUS_COMPLETED = 3;

    public Operation(Peer peer) {
        mPeer = peer;
        mOperationStatus = OPERATION_STATUS_UNKNOWN;
        mOperationType = OPERATION_TYPE_UNKNOWN;
    }

    public void setMessageSend(byte[] message) {
        mOperationType = OPERATION_TYPE_SEND_MESSAGE;
        mOperationStatus = OPERATION_STATUS_PENDING;
        mObj = message;
    }

}