package com.wordpress.randomexplorations.sync;
import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Created by maniksin on 5/28/15.
 * Peer information
 */
public class Peer {
    public InetAddress ip_address;
    public int portNumber;
    public String hostname;  // not in use right now
    public int state;

    public Peer(InetAddress addr, int port, String host) {
        ip_address = addr;
        portNumber = port;
        hostname = host;
        state = PeerManager.PEER_STATE_DISCOVERED;
    }

    public void setState(int newstate) {
        state = newstate;
    }

}
