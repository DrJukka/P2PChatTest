package test.microsoft.com.mytestapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import static android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_P2P_DEVICE;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;



/*
    Do the connection: http://developer.android.com/training/connect-devices-wirelessly/wifi-direct.html
http://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.html
An application that is looking for peer devices that support certain services can do so with a call to

 , WifiP2pManager.ServiceResponseListener

  */
public class MainActivity extends ActionBarActivity
        implements WifiP2pManager.ConnectionInfoListener,WifiP2pManager.ChannelListener
{

    final public MainActivity that = this;
    public static final int MESSAGE_READ = 0x400 + 1;
    public static final int MY_HANDLE = 0x400 + 2;

    static final public String DSS_WIFIDIRECT_VALUES = "test.microsoft.com.mytestapp.DSS_WIFIDIRECT_VALUES";
    static final public String DSS_WIFIDIRECT_MESSAGE = "test.microsoft.com.mytestapp.DSS_WIFIDIRECT_MESSAGE";

    //change me  to be dynamic!!
    public String CLIENT_PORT_INSTANCE = "38765";
    public String SERVICE_PORT_INSTANCE = "38765";

    public static final String TXTRECORD_PROP_AVAILABLE = "available";
    private static final String SERVICE_TYPE = "_p2p._tcp";

    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;

    private BroadcastReceiver receiver;
    private IntentFilter filter;

    private boolean restartServiceDiscovery = false;
    private WifiP2pManager.DnsSdServiceResponseListener serviceListener;

    private volatile ServerSocket wifiSocket = null;


    GroupOwnerSocketHandler  groupSocket = null;
    ClientSocketHandler clientSocket = null;

    ChatManager chat = null;
    Handler myHandler  = new Handler() {
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MESSAGE_READ:
                byte[] readBuf = (byte[]) msg.obj;
                // construct a string from the valid bytes in the buffer
                String readMessage = new String(readBuf, 0, msg.arg1);
                addText("Buddy: " + readMessage);
                break;

            case MY_HANDLE:
                Object obj = msg.obj;
                chat = (ChatManager) obj;

                String helloBuffer = "Hello ";
                if(clientSocket != null){
                    helloBuffer = helloBuffer + "From Client!";
                }else{
                    helloBuffer = helloBuffer + "From Groupowner!";
                }

                chat.write(helloBuffer.getBytes());
        }
    }
};


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        Button sendButton = (Button) findViewById(R.id.SendButton);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(chat != null) {
                    chat.write(((EditText) findViewById(R.id.SendText)).getText().toString().getBytes());
                    //addText("data sent");
                }else{
                    addText("chat is null, can not send data");
                }
            }
        });

        Button clearButton = (Button) findViewById(R.id.button2);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((TextView)findViewById(R.id.debugdataBox)).setText("");
            }
        });

        Button showIPButton = (Button) findViewById(R.id.button3);
        showIPButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MyP2PHelper.printLocalIpAddresses(that);
            }
        });

        p2p = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);

        if (p2p == null) {
            addText("This device does not support Wi-Fi Direct");
        }else {
            channel = p2p.initialize(this, getMainLooper(), this);

            //just making sure we start with clean table
            stopDiscovery();
            stopLocalServices();
            closeWifiSocket();

            receiver = new PeerReceiver();
            filter = new IntentFilter();
            filter.addAction(WIFI_P2P_STATE_CHANGED_ACTION);
            filter.addAction(WIFI_P2P_PEERS_CHANGED_ACTION);
            filter.addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
            registerReceiver(receiver, filter);

            serviceListener = new WifiP2pManager.DnsSdServiceResponseListener() {

                public void onDnsSdServiceAvailable(String instanceName, String serviceType, WifiP2pDevice device) {

                    addText("Service discovered, " + instanceName + " " + serviceType + " : " + MyP2PHelper.deviceToString(device));
                    if (serviceType.startsWith(SERVICE_TYPE)) {

                        CLIENT_PORT_INSTANCE = instanceName;

                        WifiP2pConfig config = new WifiP2pConfig();
                        config.deviceAddress = device.deviceAddress;
                        config.wps.setup = WpsInfo.PBC;

                        p2p.connect(channel, config, new WifiP2pManager.ActionListener() {

                            @Override
                            public void onSuccess() {
                                addText("Connecting to service");
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                addText("Failed connecting to service");
                                startServiceDiscovery();
                            }
                        });
                    } else {
                        addText("Not our Service, :" + SERVICE_TYPE + "!=" + serviceType + ":");
                        startServiceDiscovery();
                    }
                }
            };

            p2p.setDnsSdResponseListeners(channel, serviceListener, null);


            startLocalService();
            startServiceDiscovery();
        }

    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver((receiver), new IntentFilter(DSS_WIFIDIRECT_VALUES));
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        super.onStop();
    }
    @Override
    public void onDestroy() {
        restartServiceDiscovery = false;

        stopDiscovery();
        stopLocalServices();
        closeWifiSocket();
        unregisterReceiver(receiver);
    }


    private void startLocalService() {

        Map<String, String> record = new HashMap<String, String>();
        record.put(TXTRECORD_PROP_AVAILABLE, "visible");

        WifiP2pDnsSdServiceInfo service = WifiP2pDnsSdServiceInfo.newInstance( SERVICE_PORT_INSTANCE, SERVICE_TYPE, record);

        addText("Add local service");
        p2p.addLocalService(channel, service, new WifiP2pManager.ActionListener() {
            public void onSuccess() {
                addText("Added local service");
            }

            public void onFailure(int reason) {
                addText("Adding local service failed, error code " + reason);
            }
        });
    }

    private void startServiceDiscovery() {


        WifiP2pDnsSdServiceRequest request = WifiP2pDnsSdServiceRequest.newInstance();
        final Handler handler = new Handler();
        p2p.addServiceRequest(channel, request, new WifiP2pManager.ActionListener() {

            public void onSuccess() {
                addText("Added service request");
                // Calling discoverServices() too soon can result in a
                // NO_SERVICE_REQUESTS failure - looks like a race condition
                // http://p2feed.com/wifi-direct.html#bugs
                handler.postDelayed(new Runnable() {
                    public void run() {

                        p2p.discoverServices(channel, new WifiP2pManager.ActionListener() {

                            public void onSuccess() {
                                restartServiceDiscovery = true;
                                addText("Started service discovery");
                            }

                            public void onFailure(int reason) {
                                restartServiceDiscovery = true;
                                addText("Starting service discovery failed, error code " + reason);
                            }
                        });
                    }
                }, 1000);
            }

            public void onFailure(int reason) {
                addText("Adding service request failed, error code " + reason);
                // No point starting service discovery
            }
        });
    }


    private void stopDiscovery() {

        addText("Stopping discovery");
        p2p.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            public void onSuccess() {
                        addText("Cleared service requests");
                    }
            public void onFailure(int reason) {
                addText("Clearing service requests failed, error code " + reason);
            }
        });
    }


    private void stopLocalServices() {
        p2p.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
            public void onSuccess() {
                addText("Cleared local services");
            }

            public void onFailure(int reason) {
                addText("Clearing local services failed, error code " + reason);
            }
        });
    }


    private InetAddress getLocalIpAddress() {
        WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
        if(wifi == null) return null;
        WifiInfo info = wifi.getConnectionInfo();
        if(info == null || info.getNetworkId() == -1) return null;
        int ipInt = info.getIpAddress(); // What if it's an IPv6 address?
        byte[] ip = MyP2PHelper.ipIntToBytes(ipInt);
        try {
            return InetAddress.getByAddress(ip);
        } catch(UnknownHostException e) {
            return null;
        }
    }


    private void openWifiSocket(final InetAddress ip, final int PortNum) {

        addText("Listening " + ip + "port: " + PortNum);

        new Thread() {
            @Override
            public void run() {
                try {
                    addText("Opening wifi socket");
                    ServerSocket socket = new ServerSocket(PortNum);
                    //socket.bind(new InetSocketAddress(ip, PortNum));
                    addText("Wifi socket opened");
                    wifiSocket = socket;
                    while(true) {
                       // Socket s = socket.accept();
                        addText("Incoming connection..");
                        /*InetAddress remoteIp = s.getInetAddress();
                        final String addr = MyP2PHelper.ipAddressToString(remoteIp);

                        addText("Incoming connection from " + addr);

                        byte[] buffer = new byte[1024];
                        int bytes;

                        InputStream inStream = s.getInputStream();
                        bytes = inStream.read(buffer);

                        addText("received data " + bytes + " bytes.");
*/
                      //  s.close();
                    }
                } catch(IOException e) {
                    addText("openWifiSocket-Error: " + e.toString());
                }
            }
        }.start();
    }

    private void closeWifiSocket() {
        if(wifiSocket == null)
            return;
        addText("Closing wifi socket");
        try {
            wifiSocket.close();
            wifiSocket = null;
        } catch(IOException e) {
            addText("closeWifiSocket-Error: " + e.toString());
        }
    }

    private void connectByWifi(final InetAddress ip, final int PortNum) {

        addText("going to connect to " + ip + "port: " + PortNum);

        // show in UI
        //print("Connecting to " + ip);
        new Thread() {

            @Override
            public void run() {
                try {
                    Socket socket = new Socket();
                    socket.bind(null);
                    socket.connect(new InetSocketAddress(ip.getHostAddress(),PortNum), 5000);

                    addText("We are now actually conencted..");

                    //final String local = MyP2PHelper.ipAddressToString(s.getLocalAddress());
                    //addText.speak("Connected to " + ip + " from " + local);

                    /*
                    String sendData = "Hello there, what's up !!!!";

                    OutputStream outStream = s.getOutputStream();
                    outStream.write(sendData.getBytes());
                    */

                    socket.close();
                } catch(IOException e) {
                    addText("connectByWifi. tread, error: " + e.toString());
                }
            }
        }.start();
    }


    public void addText(String text) {
        Log.d("MyTeststst", text);
        ((TextView) findViewById(R.id.debugdataBox)).append(text + "\n");
    }

    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo p2pInfo) {

        try {
            if (p2pInfo.isGroupOwner) {
                clientSocket = null;

                addText("Connected as group owner");
                groupSocket = new GroupOwnerSocketHandler(myHandler,Integer.parseInt(SERVICE_PORT_INSTANCE),this);
                groupSocket.start();

            } else {
                addText("will now do socket connection with port : " + CLIENT_PORT_INSTANCE);
                groupSocket = null;

                clientSocket = new ClientSocketHandler(myHandler,p2pInfo.groupOwnerAddress,Integer.parseInt(CLIENT_PORT_INSTANCE),this);
                clientSocket.start();
            }
        } catch(Exception e) {
            addText("onConnectionInfoAvailable, error: " + e.toString());
        }
    }

    @Override
    public void onChannelDisconnected() {
        //need to implement reconnection here
        addText("onChannelDisconnected");
    }

    private class PeerReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            addText("Received intent: " + action);


            if(WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {

            } else if(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice device = intent.getParcelableExtra(EXTRA_WIFI_P2P_DEVICE);
                addText("Local device: " + MyP2PHelper.deviceToString(device));
            } else if(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {

                int state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);

                String persTatu = "Discovery state changed to ";

                if(state == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED){
                    persTatu = persTatu + "Stopped.";
                }else if(state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED){
                    persTatu = persTatu + "Started.";
                }else{
                    persTatu = persTatu + "unknown  " + state;
                }
                addText(persTatu);

            } else if (WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo.isConnected()) {
                    restartServiceDiscovery = false;
                    addText("PeerReceiver, we are connected !!!");
                    p2p.requestConnectionInfo(channel, that);

                } else {
                    addText("PeerReceiver, DISCONNECTED event !!");
                    if(restartServiceDiscovery){
                        addText(", Re-start discovery....");
                        startServiceDiscovery();
                    }
                }
            } else if (DSS_WIFIDIRECT_VALUES.equals(action)) {
                String s = intent.getStringExtra(DSS_WIFIDIRECT_MESSAGE);
                ((TextView) findViewById(R.id.debugdataBox)).append(s + "\n");
            }
        }
    }
 }
