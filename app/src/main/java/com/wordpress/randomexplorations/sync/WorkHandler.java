package com.wordpress.randomexplorations.sync;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Created by maniksin on 5/28/15.
 * <p/>
 * Common handler for foreground/background threads
 * Note that this is common code, not common context for the handlers.
 */
public class WorkHandler extends Handler {
    private Handler mClient;   // PeerManager's handler (worker-thread => PeerManager)

    private Socket mSocket;    // Our socket
    private InetSocketAddress localAddr;  // Our local addr to which we are bound

    // Last peer to which socket was (or is) connected.
    private InetSocketAddress remoteAddr;

    // Identifier whether its foreground, or background thread
    private int mCallerContext;

    // Context for the ongoing file transfer, or directories sync
    private FileTransferContext fileContext = null;
    private DirectorySyncContext directorySyncContext = null;

    // Prevent re-init of background thread if activity gets destroyed and comes back again
    private boolean mInitDone = false;

    // handle to the notification view started during background operations
    public NotificationManager mNotification = null;
    public NotificationCompat.Builder mNotifyBuilder = null;


    // Messages exchanged with the peer
    // Keep in sync with Peer's project
    private final int NETWORK_MSG_STRING_ID = 1;
    private final int NETWORK_MSG_ACK_ID = 2;
    private final int NETWORK_MSG_FILE_PUT_START = 3;
    private final int NETWORK_MSG_FILE_PUT_START_ACK = 4;
    private final int NETWORK_MSG_FILE_DATA = 5;
    private final int NETWORK_MSG_FILE_DATA_ACK = 6;
    private final int NETWORK_MSG_FILE_PUT_END = 7;
    private final int NETWORK_MSG_FILE_PUT_END_ACK = 8;
    private final int NETWORK_MSG_FILE_TRANSFER_CANCEL = 9;
    private final int NETWORK_MSG_DIRECTORY_TIME_GET = 10;
    private final int NETWORK_MSG_DIRECTORY_TIME_ACK = 11;
    private final int NETWORK_MSG_DIRECTORY_TIME_SET = 12;
    private final int NETWORK_MSG_FILE_PUT_START_NACK = 13;


    // Cleanup ongoing background transfer
    // Aborted, cancelled or complete
    private void cleanup_background_operation() {
        fileContext = null;
        if (mNotification != null) {
            mNotification.cancel(0);
            mNotification = null;
            mNotifyBuilder = null;
        }
        directorySyncContext = null;
    }

    /*
    * Class managing context for ongoing files transfer
     */
    private class FileTransferContext {
        public List<String> files;  // Remaining files
        public String currentFile;  // Replace with file descriptor
        public long totalBytes;
        public long bytesTransferred;
        public Operation op;
        public FileInputStream fileStream;  // Descriptor to current file

        public FileTransferContext(Operation oper) {
            files = (List<String>) oper.mObj;
            currentFile = null;
            totalBytes = 0;
            bytesTransferred = 0;
            op = oper;
            fileStream = null;

        }
    }

    /*
    * Class managing context for ongoing Directory sync
    */
    private class DirectorySyncContext {
        public List<String> directories;
        public String currentDirectory;
        public Operation op;
        public int total_bytes;
    }

    /*
    * Object returned by the low-level network operation API
    * containing result of the network operation.
     */
    private class NetworkResponse {
        public byte[] response;  // payload portion of the response from the Peer
        public int msg_id;   // msg-id of the response from the peer
        public int response_len;  // length of the payload of Peer's response (response.length)
        public int return_code;  // General status of the low-level operation
        public IOException e;  // Exception raised by low-level operation (if any)

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

    /*
    * Background (service) thread is inited with this constructor
    * and the call to work_handler_init on init messages received
    * from PeerManager.
     */
    public WorkHandler(Looper looper) {
        super(looper);
    }

    /*
    * Foreground's thread is inited with this constructor.
     */
    public WorkHandler(Looper looper, Handler client, InetAddress addr, int context) {
        super(looper);
        workhandler_init(client, addr, context);
    }

    /*
    * Common code for initialization of foreground/background threads
     */
    public void workhandler_init(Handler client, InetAddress addr, int context) {

        mClient = client;
        mCallerContext = context;

        if (mInitDone) {
            return;
        }


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
        } catch (SocketException e) {
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
        mInitDone = true;
    }

    /*
    * Function to send response back to PeerManager
     */
    private void sendResponseToClient(int what, int arg1, int arg2, Object obj) {
        Message msg = mClient.obtainMessage();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        msg.obj = obj;
        mClient.sendMessage(msg);
    }

    /*
    * Check reply from peer and send appropriate notification to PeerManager.
    *
    * Background thread may not require sending each network reply to the PeerManager.
    * In such case, background thread may call this function only in case of errors.
     */
    void handleNetworkResponse(NetworkResponse netResponse, Operation op) {
        Message msg = mClient.obtainMessage();

        msg.what = PeerManager.MSG_WORKER_OP_STATUS;
        msg.obj = op;
        msg.arg2 = mCallerContext;
        switch (netResponse.return_code) {
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

    private void continue_file_transfer() {

        NetworkResponse nResponse;
        InetSocketAddress sockaddr = new InetSocketAddress(fileContext.op.mPeer.ip_address,
                fileContext.op.mPeer.portNumber);

        if (fileContext.currentFile == null) {
            // Starting transfer or previous file complete
            while (!fileContext.files.isEmpty() && fileContext.currentFile == null) {
                fileContext.currentFile = fileContext.files.remove(0);

                File thisFile = new File(fileContext.currentFile);
                if (thisFile == null || !thisFile.exists() || !thisFile.isFile()) {
                    fileContext.op.mOperationStatusString = fileContext.currentFile;
                    sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                            PeerManager.PEER_MANAGER_ERR_FILE_ERROR, mCallerContext,
                            fileContext.op);
                    cleanup_background_operation();
                    return;
                }

                /*
                * @todo: check how to update notifications

                mNotifyBuilder.setContentText("Sending " + fileContext.currentFile);
                Notification nt = mNotifyBuilder.build();
                nt.flags = Notification.FLAG_ONGOING_EVENT;
                mNotification.notify(0, nt);
                */

                try {
                    fileContext.fileStream = new FileInputStream(thisFile);
                } catch (FileNotFoundException e) {
                    fileContext.op.mOperationStatusString = e.getMessage();
                    sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                            PeerManager.PEER_MANAGER_ERR_FILE_ERROR, mCallerContext, fileContext.op);
                    cleanup_background_operation();
                    return;
                }

                /*
                * NETWORK_MSG_FILE_PUT_START
                * 4 byte msg-id
                * 4 byte payload
                * 8 byte file last modified time
                * <file-name>
                 */
                int msg_len = fileContext.currentFile.length() + 8;
                byte[] data = new byte[8 + msg_len];
                ByteBuffer bb = ByteBuffer.wrap(data);
                bb.putInt(NETWORK_MSG_FILE_PUT_START);
                bb.putInt(msg_len);
                bb.putLong(thisFile.lastModified());
                bb.put(fileContext.currentFile.getBytes());

                nResponse = sendMessage(data, data.length, sockaddr, true);
                if (nResponse.return_code != NetworkResponse.NETWORK_RESPONSE_SUCCESS) {
                    handleNetworkResponse(nResponse, fileContext.op);
                    cleanup_background_operation();
                    return;
                }

                // Check if peer responded that file already exists and needs to be skipped.
                if (nResponse.msg_id == NETWORK_MSG_FILE_PUT_START_NACK) {
                    // Peer does not want to download this file, may be it already exists
                    // Check next file
                    fileContext.currentFile = null;
                }

            }

            if (fileContext.files.isEmpty() && fileContext.currentFile == null) {
                // Transfer complete for all files
                sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                        PeerManager.PEER_MANAGER_ERR_SUCCESS, 0, fileContext.op);
                cleanup_background_operation();   // Ready for next operation request
                return;
            }
        }

        if (fileContext.currentFile != null) {
            // We have some file to send, with the fileStream open
        /*
        * Transfer files by sending a batch of bytes in every iteration and then poll
        * on the worker thread's queue to check for any Status/Cancel messages from PeerManager
        */
            int bytes_this_operation = 0;
            while (bytes_this_operation < 10000) {  // 10k bytes per operation

                int actual_read = 0;
                int msg_len = 0;

                byte[] data = new byte[1000 + 8];  // msg_id + len + 1000byte file-data
                ByteBuffer bb = ByteBuffer.wrap(data);

                //bb.putInt(NETWORK_MSG_FILE_DATA);

                try {
                    actual_read = fileContext.fileStream.read(data, 8, 1000);

                    if (actual_read == -1) {
                        // Reached end of file
                        actual_read = 0;
                        Log.d(PeerManager.LOGGER, "Reached End of file");
                        bb.putInt(NETWORK_MSG_FILE_PUT_END);
                        bb.putInt(0);
                        msg_len = 8;
                    } else {
                        bb.putInt(NETWORK_MSG_FILE_DATA);
                        bb.putInt(actual_read);
                        msg_len = actual_read + 8;
                        //Log.d(PeerManager.LOGGER, "Sending " + actual_read + " file bytes");
                    }

                    NetworkResponse netResponse = sendMessage(data, msg_len, sockaddr, true);
                    if (netResponse.return_code != NetworkResponse.NETWORK_RESPONSE_SUCCESS) {
                        handleNetworkResponse(netResponse, fileContext.op);
                        cleanup_background_operation();
                        return;
                    }

                    bytes_this_operation += actual_read;
                    fileContext.bytesTransferred += actual_read;

                    if (actual_read == 0) {
                        // EOF reached last time
                        fileContext.currentFile = null;  // mark null so next time we pick up next file in List
                        break;
                    }

                } catch (IOException e) {
                    fileContext.op.mOperationStatusString = e.getMessage();
                    sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                            PeerManager.PEER_MANAGER_ERR_FILE_ERROR, mCallerContext, fileContext.op);
                    cleanup_background_operation();
                    return;
                }
            }
        }

        // We either completed a batch of bytes or completed sending a file
        if (fileContext.currentFile == null && fileContext.files.isEmpty()) {

            // We finished sending all the files!!
            if (directorySyncContext != null) {
                // We are actually running a directory sync, so now we need to sync
                // the next directory with the peer.

                // First set current directory as sync'd with the Peer.
                nResponse = setDirectorySyncStatus();
                if (nResponse.return_code != NetworkResponse.NETWORK_RESPONSE_SUCCESS) {
                    handleNetworkResponse(nResponse, fileContext.op);
                    cleanup_background_operation();
                    return;
                }

                continue_directory_sync();

            } else {

                fileContext.op.mOperationStatusString = new String("Files send success!");
                sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                        PeerManager.PEER_MANAGER_ERR_SUCCESS, mCallerContext, fileContext.op);
                cleanup_background_operation();
                return;
            }
        }

        /*
        *  If activity is running, then we need to keep updating it with the progress
        *  which it may show in a progress bar to the user.
         */
        // @todo: Send iterim operation progress status
        //sendResponseToClient(PeerManager.MSG_WORKER_OP_PROGRESS,
        //      (int)((fileContext.bytesTransferred * 100 )/fileContext.totalBytes), mCallerContext,
        //    fileContext.op);

        /*
        * Post a message to self, so that we can also cleanup our message-queue and continue
        * sending the files
        */
        Message msg = this.obtainMessage();
        msg.what = PeerManager.MSG_WORKER_CONTINUE_BACKGROUND_OP;
        this.sendMessage(msg);
        return;
    }


    /*
    * Start an android notification view to denote a running
    * background operation
    */
    private void startNotification(Operation op) {
        mNotifyBuilder = new
                NotificationCompat.Builder(op.mThisActivity);
        mNotifyBuilder.setSmallIcon(R.mipmap.sync);
        mNotifyBuilder.setContentTitle("Copying files");
        mNotifyBuilder.setContentText("Yes, its true, copying files");

        Intent intent = new Intent(op.mThisActivity, MainActivity.class);

        // Do not start another instance of the activity when the notification is clicked.
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent p = PendingIntent.getActivity(op.mThisActivity, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        mNotifyBuilder.setContentIntent(p);

        mNotification =
                (NotificationManager) op.mThisActivity.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification nt = mNotifyBuilder.build();
        nt.flags = Notification.FLAG_ONGOING_EVENT;
        mNotification.notify(0, nt);
    }


    NetworkResponse getDirectoryPeerStatus(String directory) {
        InetSocketAddress sockaddr = new InetSocketAddress(fileContext.op.mPeer.ip_address,
                fileContext.op.mPeer.portNumber);
        /*
        * Directory sync status get message
        * 4 byte msg-id
        * 4 byte payload-length
        * 4 byte directory-len
        * <directory-len>
        */
        int total_len = 12 + directory.length();
        byte[] data = new byte[total_len];
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.putInt(NETWORK_MSG_DIRECTORY_TIME_GET);
        bb.putInt(total_len - 8);
        bb.putInt(directory.length());
        bb.put(directory.getBytes());
        return sendMessage(data, total_len, sockaddr, true);

    }

    /*
    * Scan directory and update directorySyncContext
    */
    void scan_directory(File directory) {

        directorySyncContext.directories.add(directory.getAbsolutePath());
        Log.d(PeerManager.LOGGER, "Adding directory: " + directory.getAbsolutePath());

        File[] files = directory.listFiles();
        int total_files = files.length;
        for (int i = 0; i < total_files; i++) {
            if (files[i].isDirectory()) {
                scan_directory(files[i]);
            }
        }

        return;
    }

    /*
    * Scan Directories recursively to accumulate all children directories
    */
    private void scanDirectories(Operation op) {

        int i = 0;
        int err_code = 0;
        List<String> directories = (List<String>) op.mObj;
        for (i = 0; i < directories.size(); i++) {
            File dir = new File(directories.get(i));
            scan_directory(dir);
        }
    }

    /*
    * Query sync status from server and calculate the files/bytes
    * pending for sync
    * This function can be called in two contexts:
    * 1. Stanalone from activity to get sync-status for the user
    * 2. On start of actual file-sync to get the total pendingBytes for sync.
    */
    private int calcPendingSync(Operation op, boolean sendResponse) {

        if (fileContext != null) {
            // Some background operation is running
            // Not allowed at this time
            sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                    PeerManager.PEER_MANAGER_ERR_BUSY, mCallerContext, op);
            return -1;
        }

        if (mCallerContext != PeerManager.WORKER_TYPE_BACKGROUND_SERVICE) {
            // Operation allowed only in background service
            if (sendResponse) {
                sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                        PeerManager.PEER_MANAGER_ERR_INVALID_REQUEST, mCallerContext, op);
            }
            return -1;
        }

        // Ok to proceed
        fileContext = new FileTransferContext(op);
        directorySyncContext = new DirectorySyncContext();
        directorySyncContext.directories = new ArrayList<>();
        directorySyncContext.op = op;
        scanDirectories(op);  // Build list of all directories

        int pendingFiles = 0;
        int pendingBytes = 0;
        int total_dirs = directorySyncContext.directories.size();
        for (int j = 0; j < total_dirs; j++) {

            String cur_dir_str = directorySyncContext.directories.get(j);
            NetworkResponse nResponse = getDirectoryPeerStatus(cur_dir_str);
            if (nResponse.return_code != PeerManager.PEER_MANAGER_ERR_SUCCESS) {
                if (sendResponse) {
                    op.mOperationStatusString = nResponse.e.getLocalizedMessage();
                    sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                            nResponse.return_code, mCallerContext, op);
                }
                cleanup_background_operation();
                return -1;
            }

            // Get the timestamp from peer
            /*
            * Directory sync status response message format
            * 4-byte message-id  <stripped>
            * 4-byte payload-length  <stripped>
            * 8-byte mod_time
             */
            ByteBuffer bb = ByteBuffer.wrap(nResponse.response);
            long mod_time = bb.getLong(0);

            File cur_dir = new File(cur_dir_str);
            if (cur_dir.lastModified() <= mod_time) {
                // No files to sync in this directory
                continue;
            }

            // Directory has files to sync
            File[] files = cur_dir.listFiles();
            int total_files = files.length;
            for (int i = 0; i < total_files; i++) {
                if (files[i].isFile() && files[i].lastModified() >= mod_time) {
                    pendingFiles++;
                    Log.d(PeerManager.LOGGER, "File " + files[i].getAbsolutePath() + " pending for sync.");
                    pendingBytes += files[i].length();
                }
            }
        }

        // Done
        if (sendResponse) {
            String rsp = new String("Total " + pendingFiles + " to sync.");
            op.mResponse = rsp.getBytes();
            sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS, PeerManager.PEER_MANAGER_ERR_SUCCESS,
                    mCallerContext, op);
            cleanup_background_operation();
        }

        return pendingBytes;
    }

    private NetworkResponse setDirectorySyncStatus() {
        ByteBuffer bb;
        InetSocketAddress sockaddr = new InetSocketAddress(fileContext.op.mPeer.ip_address,
                fileContext.op.mPeer.portNumber);
        File cur_dir = new File(directorySyncContext.currentDirectory);
           /*
                * Directory sync message:
                * 4 byte msg-id
                * 4 byte payload-len
                * 4 byte directory-string length
                * <directory-string>
                * 8 byte modified time
                 */
        int total_length = 20 + directorySyncContext.currentDirectory.length();
        byte[] data = new byte[total_length];
        bb = ByteBuffer.wrap(data);
        bb.putInt(NETWORK_MSG_DIRECTORY_TIME_SET);
        bb.putInt(total_length - 8);
        bb.putInt(directorySyncContext.currentDirectory.length());
        bb.put(directorySyncContext.currentDirectory.getBytes());
        bb.putLong(cur_dir.lastModified());
        return sendMessage(data, total_length, sockaddr, true);
    }

    private void continue_directory_sync() {

        NetworkResponse nResponse;
        List<String> files_list = new ArrayList<>();
        ;

        directorySyncContext.currentDirectory = null;
        while (!directorySyncContext.directories.isEmpty()) {

            // Pick and start with next directory
            directorySyncContext.currentDirectory = directorySyncContext.directories.remove(0);
            nResponse = getDirectoryPeerStatus(directorySyncContext.currentDirectory);
            if (nResponse.return_code != PeerManager.PEER_MANAGER_ERR_SUCCESS) {
                handleNetworkResponse(nResponse, directorySyncContext.op);
                cleanup_background_operation();
                return;
            }

            /*
            * Directory sync status response message format
            * 4-byte message-id  <stripped>
            * 4-byte payload-length  <stripped>
            * 8-byte mod_time
             */
            ByteBuffer bb = ByteBuffer.wrap(nResponse.response);
            long mod_time = bb.getLong(0);
            File cur_dir = new File(directorySyncContext.currentDirectory);

            if (cur_dir.lastModified() <= mod_time) {
                // No change in this directory since last sync.. Nothing to do
                Log.d(PeerManager.LOGGER, "No change in " + directorySyncContext.currentDirectory);
                continue;
            }

            // Sync needed in this directory
            // Select only those files which have bigger modification time
            File cur_files[] = cur_dir.listFiles();
            int total_files = cur_files.length;
            for (int i = 0; i < total_files; i++) {
                if (cur_files[i].isFile() && cur_files[i].lastModified() > mod_time) {
                    // This file requires sync to peer
                    files_list.add(cur_files[i].getAbsolutePath());
                    Log.d(PeerManager.LOGGER, "Need to send: "
                            + cur_files[i].getAbsolutePath());
                }
            }

            if (files_list.isEmpty()) {
                // Could be a case that a file got added and then deleted
                // This caused directory mod-time to increase, but we didn't end
                // up in having any actual file to sync
                nResponse = setDirectorySyncStatus();
                if (nResponse.return_code != PeerManager.PEER_MANAGER_ERR_SUCCESS) {
                    handleNetworkResponse(nResponse, directorySyncContext.op);
                    cleanup_background_operation();
                    return;
                }
                continue;
            } else {
                break;
            }


        }

        if (directorySyncContext.currentDirectory == null) {
            // Directories Sync complete
            sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                    PeerManager.PEER_MANAGER_ERR_SUCCESS, mCallerContext, directorySyncContext.op);
            cleanup_background_operation();
        } else {
            fileContext.files = files_list;
            fileContext.op = directorySyncContext.op;
            fileContext.currentFile = null;

            continue_file_transfer();
        }
    }

    /*
    * Request to sync directories
     */
    private void runOp_syncDirectories(Operation op) {
        int err_code = PeerManager.PEER_MANAGER_ERR_SUCCESS;
        String str = new String("Success");

        if (mCallerContext == PeerManager.WORKER_TYPE_FOREGROUND) {
            // Not allowed in foreground thread
            err_code = PeerManager.PEER_MANAGER_ERR_INVALID_REQUEST;
            str = new String("Not allowed in foreground thread");
        }

        if (fileContext != null) {
            // A file transfer operation is already ongoing.
            err_code = PeerManager.PEER_MANAGER_ERR_BUSY;
            str = new String("Busy!!");
        }

        if (err_code != PeerManager.PEER_MANAGER_ERR_SUCCESS) {
            op.mOperationStatusString = str;
            sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                    err_code, mCallerContext, op);
            return;
        }

        int pendingBytes = calcPendingSync(op, false);

        /*
        * All OK, now we can start
        */
        startNotification(op);
        fileContext = new FileTransferContext(op);
        fileContext.totalBytes = pendingBytes;
        Log.d(PeerManager.LOGGER, "Total pending bytes: " + pendingBytes);
        continue_directory_sync();
    }


    /*
    * Request to send file
     */
    private void runOp_sendFiles(Operation op) {

        int err_code = PeerManager.PEER_MANAGER_ERR_SUCCESS;
        String str = new String("Success");
        long total_bytes = 0;

        if (mCallerContext == PeerManager.WORKER_TYPE_FOREGROUND) {
            // Not allowed in foreground thread
            err_code = PeerManager.PEER_MANAGER_ERR_INVALID_REQUEST;
            str = new String("Not allowed in foreground thread");
        }

        if (fileContext != null) {
            // A file transfer operation is already ongoing.
            err_code = PeerManager.PEER_MANAGER_ERR_BUSY;
            str = new String("Busy!!");
        }

        for (String cur_file : (List<String>) op.mObj) {
            File fd = new File(cur_file);
            if (fd == null || !fd.isFile() || !fd.exists()) {
                err_code = PeerManager.PEER_MANAGER_ERR_FILE_ERROR;
                str = new String("Invalid file: " + cur_file);
                break;
            } else {
                total_bytes += fd.length();
            }
        }

        if (err_code != PeerManager.PEER_MANAGER_ERR_SUCCESS) {
            op.mOperationStatusString = str;
            sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                    err_code, mCallerContext, op);
            return;
        }

        // All ok, begin the transfer
        startNotification(op);
        fileContext = new FileTransferContext(op);
        fileContext.totalBytes = total_bytes;
        continue_file_transfer();
    }

    /*
    * Send requested message to peer and (optionally) receive
    * Peer's response.
     */
    private void runOp_sendMessage(Operation op) {

        Message msg = mClient.obtainMessage();
        NetworkResponse netResponse;

        /* Message format:
        * int Msg-id
        * int Payload-len
        * byte[] Payload
         */

        byte[] payload = (byte[]) op.mObj;
        byte[] full_message = new byte[8 + payload.length];
        ByteBuffer bb = ByteBuffer.wrap(full_message);
        bb.putInt(NETWORK_MSG_STRING_ID);
        bb.putInt(payload.length);
        bb.put(payload);

        InetSocketAddress sockaddr = new InetSocketAddress(op.mPeer.ip_address, op.mPeer.portNumber);

        // actual low-level call to send message and receive reply
        netResponse = sendMessage(full_message, full_message.length, sockaddr, true);
        handleNetworkResponse(netResponse, op);
    }

    /*
    * Request from activity to cancel ongoing background
    * operation
     */
    void cancelBackgroundOperation() {
        if (fileContext != null) {
            // Some background operation is running
            InetSocketAddress sockaddr = new InetSocketAddress(fileContext.op.mPeer.ip_address,
                    fileContext.op.mPeer.portNumber);
            byte[] data = new byte[8];
            ByteBuffer bb = ByteBuffer.wrap(data);
            bb.putInt(NETWORK_MSG_FILE_TRANSFER_CANCEL);
            bb.putInt(0);
            sendMessage(data, data.length, sockaddr, false); // no-reply expected from peer
            cleanup_background_operation();

            String str = new String("Operation cancelled");
            sendResponseToClient(PeerManager.MSG_WORKER_CANCEL_STATUS,
                    PeerManager.PEER_MANAGER_ERR_SUCCESS, mCallerContext, str);
        } else {
            String str = new String("No operation running!!");
            sendResponseToClient(PeerManager.MSG_WORKER_CANCEL_STATUS,
                    PeerManager.PEER_MANAGER_ERR_INVALID_REQUEST, mCallerContext, str);
        }
    }

    /*
    Low level API for actual send message to the peer and
    receiving a reply.
     */
    private NetworkResponse sendMessage(byte[] message, int length, InetSocketAddress peer_addr, boolean reply_expected) {

        boolean reconnecting = true;
        NetworkResponse netResponse = new NetworkResponse();

        // Check if our socket is already connected to the same
        // Peer. In that case, we make an attempt to reuse the socket.
        if (mSocket.isConnected() && remoteAddr != null) {
            if (remoteAddr.getAddress().equals(peer_addr.getAddress()) &&
                    remoteAddr.getPort() == peer_addr.getPort()) {
                // Connected to same socket
                //Log.d(PeerManager.LOGGER, "Re-using connected socket");
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
                out.write(message, 0, length);

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

    /*
    * Handle the operation request form peer manager.
     */
    private void runOp(Operation op) {

        Message msg = mClient.obtainMessage();
        if (mSocket == null) {
            op.mOperationStatusString = new String("Socket not connected");
            sendResponseToClient(PeerManager.MSG_WORKER_OP_STATUS,
                    PeerManager.PEER_MANAGER_ERR_NOT_INITED, mCallerContext, op);
            return;
        }

        switch (op.mOperationType) {
            case Operation.OPERATION_TYPE_SEND_MESSAGE:
                runOp_sendMessage(op);
                break;
            case Operation.OPERATION_TYPE_SEND_FILE:
                runOp_sendFiles(op);
                break;
            case Operation.OPERATION_TYPE_SYNC_DIRECTORIES:
                runOp_syncDirectories(op);
                break;
            case Operation.OPERATION_TYPE_FETCH_SYNC_STATUS:
                calcPendingSync(op, true);
                break;
            default:
                break;
        }

    }

    /*
    * Cleanup call from peer manager.
    * Currently it is received only for the foreground thread when
    * the activity is going to stop.
     */
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

    /*
    * Worker threads (foreground or background) wait in a loop on their
    * message-queues, and this handler gets invoked when a message is
    * received from the Peer Manager.
     */
    public void handleMessage(Message msg) {
        // Main logic here
        switch (msg.what) {
            case PeerManager.MSG_WORKER_RUN_OP:
                runOp((Operation) msg.obj);
                break;
            case PeerManager.MSG_WORKER_CLEANUP:
                cleanup();
                break;
            case PeerManager.MSG_WORK_SERVICE_INIT_1:   // service handler initialization-start
                mClient = (Handler) msg.obj;
                mCallerContext = msg.arg2;
                break;
            case PeerManager.MSG_WORK_SERVICE_INIT_2:  // service handler initialization-end
                workhandler_init(mClient, (InetAddress) msg.obj, mCallerContext);
                break;
            // Interim message sent by background thread to self, for polling/cleaning-up
            // messages in the queue.
            case PeerManager.MSG_WORKER_CONTINUE_BACKGROUND_OP:
                if (fileContext != null) {
                    continue_file_transfer();
                }
                break;
            case PeerManager.MSG_WORKER_CANCEL_OPERATION:
                cancelBackgroundOperation();
                break;
            default:
                break;
        }
    }
}
