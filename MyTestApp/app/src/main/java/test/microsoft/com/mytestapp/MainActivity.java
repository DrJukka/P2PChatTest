package test.microsoft.com.mytestapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.text.format.Time;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;


import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_P2P_DEVICE;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;



public class MainActivity extends ActionBarActivity
        implements WifiP2pManager.ConnectionInfoListener,WifiP2pManager.ChannelListener
{

    MyTextSpeech mySpeech = null;

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

    private WifiP2pManager.DnsSdServiceResponseListener serviceListener;
    private WifiP2pManager.PeerListListener peerListListener;
    enum ServiceState{
        NONE,
        DiscoverPeer,
        DiscoverService,
        ConnectingWifi,
        QueryConnection,
        ConnectedAsOwner,
        ConnectedAsClient
    }

    enum LastConnectionRole {
        NONE,
        GroupOwner,
        Client
    }

    LastConnectionRole mLastConnectionRole = LastConnectionRole.NONE;

    public ServiceState  myServiceState = ServiceState.NONE;

    private boolean doReConectWifi = false;
    int conCount = 0;
    GroupOwnerSocketHandler  groupSocket = null;
    ClientSocketHandler clientSocket = null;


    long tRequestPeers = 0;
    long tAddServiceRequest = 0;
    long tStartServiceDiscovery = 0;
    long tFromDcToSd = 0;
    long tGotService = 0;
    long tConnecting = 0;
    long tConnected = 0;
    long tGotConnectionInfo = 0;
    long tGotData = 0;
    long tGoBigtData = 0;
    long tDisconnected = 0;
    long tDataToDisconnect = 0;
    String otherPartyVersion ="";
    File dbgFile;
    OutputStream dbgFileOs;

    private int mInterval = 1000; // 1 second by default, can be changed later
    private int mPeerDiscoveryWatchdog = 0;
    // for read world usage, we could adjust the time, this is just for testing period
    private int mPeerDiscoveryWatchdogLimit = (60 * 2); // 2 times 60 seconds

    private Handler mHandler;
    private int timeCounter = 0;
    Runnable mStatusChecker = new Runnable() {
        @Override
        public void run() {
            // call function to update timer
            timeCounter = timeCounter + 1;
            ((TextView) findViewById(R.id.TimeBox)).setText("T: " + timeCounter);

            mPeerDiscoveryWatchdog = mPeerDiscoveryWatchdog + 1;
            //this is just to make absolutely sure, that if for some reason
            // peer discovery fails to start completely, we will be trying to
            // kick-start it after some not too small time limit
            // specific issue is Error 0 with Kitkat happening randomly
            if(mPeerDiscoveryWatchdogLimit < mPeerDiscoveryWatchdog){
                mPeerDiscoveryWatchdog = 0;
                mySpeech.speak("Watchdog for peer discovery resetted");
                addText("Watchdog for peer discovery resetted");
                startPeerDiscovery();
            }

            mHandler.postDelayed(mStatusChecker, mInterval);
        }
    };

    ChatManager chat = null;
    long msgByteCount = 0;
    Handler myHandler  = new Handler() {
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MESSAGE_READ:

                byte[] readBuf = (byte[]) msg.obj;
                // construct a string from the valid bytes in the buffer

                String readMessage = "";
                if(msg.arg1 < 30) {
                    msgByteCount = 0;
                    tGoBigtData = 0; // reset me.
                    tGotData = System.currentTimeMillis();
                    readMessage = new String(readBuf, 0, msg.arg1);

                    if(mLastConnectionRole == LastConnectionRole.Client) {
                        final Handler handler = new Handler();
                        handler.postDelayed(new Runnable() {
                            // just to make sure we both got the message
                            public void run() {
                                addText("Sending the big buffer now");
                                byte[] buffer = new byte[1048576]; //Megabyte buffer
                                new Random().nextBytes(buffer);
                                chat.write(buffer);
                                tGoBigtData = System.currentTimeMillis();
                                WriteDebugline();
                            }
                        }, 2000);
                    }
                    String[] separated = readMessage.split(":");
                    addText("Buddy: (" + conCount + "): " + separated[0] + "using version: " + separated[1]);
                    otherPartyVersion = separated[1];
                    mySpeech.speak(readMessage + " having version " + otherPartyVersion);
                    conCount = conCount + 1;
                    ((TextView) findViewById(R.id.CountBox)).setText("Msg: " + conCount);
                }else{
                    tGoBigtData = System.currentTimeMillis();
                    msgByteCount = msgByteCount + msg.arg1;
                    ((TextView) findViewById(R.id.CountBox)).setText("B: " + msgByteCount);
                    if(msgByteCount >= 1048576){
                        WriteDebugline();
                        ((TextView) findViewById(R.id.CountBox)).setText("Msg: " + conCount);
                        readMessage = "Megabyte received in " + ((tGoBigtData - tGotData)/1000) + " seconds";
                        addText(readMessage);
                        mySpeech.speak(readMessage);
                    }
                }
/*
                        final Handler handler = new Handler();
                        handler.postDelayed(new Runnable() {
                            // just to make sure we both got the message before we exit
                            public void run() {
                                disconnect();
                            }
                        }, 5000);
*/
                break;

            case MY_HANDLE:
                Object obj = msg.obj;
                chat = (ChatManager) obj;

                String helloBuffer = "Hello ";
                if(mLastConnectionRole == LastConnectionRole.Client){
                    helloBuffer = helloBuffer + "From Client :";
                }else{
                    helloBuffer = helloBuffer + "From Group owner :";
                }

                helloBuffer =  helloBuffer + Build.VERSION.SDK_INT;

                chat.write(helloBuffer.getBytes());
        }
    }
};


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        mySpeech = new MyTextSpeech(this);

        mHandler = new Handler();

        System.currentTimeMillis();

        Time t= new Time();
        t.setToNow();

        File path = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);

        String sFileName =  "/P2PTest"  + t.yearDay + t.hour+ t.minute + t.second + ".txt";

        try {
            dbgFile = new File(path, sFileName);
            dbgFileOs = new FileOutputStream(dbgFile);

            String dattaa = "Os ,Os other ,Type ,FoundService ,StartServiceDiscovery ,GotService ,Connecting ,Connected ,GotConnectionInfo ,GotData ,GotBigData ,FromDcToSd ,FromDataToDisconnect\n";
            dbgFileOs.write(dattaa.getBytes());
            dbgFileOs.flush();



            addText("File created:" + path + " ,filename : " + sFileName);
        }catch(Exception e){
            addText("FileWriter, create file error, :"  + e.toString() );
        }

   /*     Button sendButton = (Button) findViewById(R.id.SendButton);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (chat != null) {
                    chat.write(((EditText) findViewById(R.id.SendText)).getText().toString().getBytes());
                    //addText("data sent");
                } else {
                    addText("chat is null, can not send data");
                }
            }
        });*/

        Button clearButton = (Button) findViewById(R.id.button2);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((TextView) findViewById(R.id.debugdataBox)).setText("");
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
        doP2PInit();
    }
    private void doP2PInit() {

        if (p2p == null) {
            addText("This device does not support Wi-Fi Direct");
        }else {

            channel = p2p.initialize(this, getMainLooper(), this);

            receiver = new PeerReceiver();
            filter = new IntentFilter();
            filter.addAction(WIFI_P2P_STATE_CHANGED_ACTION);
            filter.addAction(WIFI_P2P_PEERS_CHANGED_ACTION);
            filter.addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
            registerReceiver(receiver, filter);

            peerListListener = new WifiP2pManager.PeerListListener() {

                public void onPeersAvailable(WifiP2pDeviceList peers) {
                    addText("Discovered peers:");

                    final WifiP2pDeviceList pers = peers;
                    String allInOne = "";
                    int numm = 0;
                    for (WifiP2pDevice peer : pers.getDeviceList()) {
                        numm++;
                        allInOne = allInOne + MyP2PHelper.deviceToString(peer) + ", ";
                        addText("\t" + MyP2PHelper.deviceToString(peer));
                    }
                    mySpeech.speak(numm + " peers discovered.");
                    addText(numm + " peers discovered.");

                    if(numm > 0){
                        startServiceDiscovery();
                        //stopPeerDiscovery(); //TODO: See if this is needed later
                    }else{
                        //TODO, add timer here to start peer discovery
                        startPeerDiscovery();
                    }
                }
            };
            serviceListener = new WifiP2pManager.DnsSdServiceResponseListener() {

                public void onDnsSdServiceAvailable(String instanceName, String serviceType, WifiP2pDevice device) {

                    addText("Service discovered, " + instanceName + " " + serviceType + " : " + MyP2PHelper.deviceToString(device));
                    if (serviceType.startsWith(SERVICE_TYPE)) {

                        tGotService = System.currentTimeMillis();
                        CLIENT_PORT_INSTANCE = instanceName;

                        WifiP2pConfig config = new WifiP2pConfig();
                        config.deviceAddress = device.deviceAddress;
                        config.wps.setup = WpsInfo.PBC;

                        p2p.connect(channel, config, new WifiP2pManager.ActionListener() {

                            @Override
                            public void onSuccess() {
                                addText("Connecting to service");
                                myServiceState = ServiceState.ConnectingWifi;
                                tConnecting = System.currentTimeMillis();
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                addText("Failed connecting to service : " + errorCode);
                                startPeerDiscovery();
                            }
                        });
                    } else {
                        addText("Not our Service, :" + SERVICE_TYPE + "!=" + serviceType + ":");
                        startPeerDiscovery();
                    }
                }
            };

            p2p.setDnsSdResponseListeners(channel, serviceListener, null);

            startLocalService();
            startPeerDiscovery();

            try{
                groupSocket = new GroupOwnerSocketHandler(myHandler,Integer.parseInt(SERVICE_PORT_INSTANCE),this);
                groupSocket.start();
            }catch (Exception e){
                addText("groupseocket error, :"  + e.toString() );
            }

            mStatusChecker.run();
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

        mHandler.removeCallbacks(mStatusChecker);

        stopDiscovery();
        stopPeerDiscovery();
        stopLocalServices();
        try {
            if (dbgFile != null) {
                dbgFileOs.close();
                dbgFile.delete();
            }
        }catch (Exception e){
            addText("dbgFile close error :"  + e.toString() );
        }
        if (receiver != null) {
            unregisterReceiver(receiver);
        }
    }

    private void startPeerDiscovery() {

        p2p.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            public void onSuccess() {
                addText("Started peer discovery");
                mPeerDiscoveryWatchdog = 0; // reset watchdog
                myServiceState = ServiceState.DiscoverPeer;
            }

            public void onFailure(int reason) {
                addText("Starting peer discovery failed, error code " + reason);
            }
        });
    }

    private void stopPeerDiscovery() {
        p2p.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            public void onSuccess() {addText("Stopped peer discovery");}
            public void onFailure(int reason) {addText("Stopping peer discovery failed, error code " + reason);}
        });
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

        // multiple active request appear to mess things up, thus checking whether cancellation always
        // would ease th task. We need this since, otherwise we get into situations where we either nmake multiple requests
        // or end up into situation where we don't have active discovery on.
        stopDiscovery();
        tAddServiceRequest = System.currentTimeMillis();

        //WifiP2pDnsSdServiceInfo service = WifiP2pDnsSdServiceInfo.newInstance( SERVICE_PORT_INSTANCE, SERVICE_TYPE, record);

        WifiP2pDnsSdServiceRequest request = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE);
        final Handler handler = new Handler();
        p2p.addServiceRequest(channel, request, new WifiP2pManager.ActionListener() {

            public void onSuccess() {
                addText("Added service request");
                // Calling discoverServices() after disconnection from Group
                // causes NO_SERVICE_REQUESTS error with pre-lollipop devices (Tested with Kitkat 4.4.4)
                // only way found so far is to disconnect wifi, and re-connect it again
                // http://stackoverflow.com/questions/21816730/wifidirect-discoverservices-keeps-failing-with-error-3-no-service-requests

                handler.postDelayed(new Runnable() {
                    //There are supposedly a possible race-condition bug with the service discovery
                    // thus to avoid it, we are delaying the service discovery start here
                    public void run() {

                        p2p.discoverServices(channel, new WifiP2pManager.ActionListener() {

                            public void onSuccess() {
                                addText("Started service discovery");
                                myServiceState = ServiceState.DiscoverService;

                                tStartServiceDiscovery = System.currentTimeMillis();
                                // use last disconnection time, to see how long it took to get here
                                // if we got errors, and multiple tries for starting discovery, we can see it from this value
                                if(tDisconnected != 0) {// first time the disconnection in zero, so lets skip that
                                    tFromDcToSd = (tStartServiceDiscovery - tDisconnected);
                                }else{
                                    tFromDcToSd = 0;
                                }
                            }

                            public void onFailure(int reason) {
                                addText("Starting service discovery failed, error code " + reason);

                                if (reason == WifiP2pManager.NO_SERVICE_REQUESTS
                                    // If we start getting this error, we either got the race condition
                                    // or we are client, that just got disconnected when group owner removed the group
                                    // anyways, sometimes only way, and 'nearly' always working fix is to
                                    // toggle Wifi off/on, it appears to reset what ever is blocking there.
                                || reason == WifiP2pManager.ERROR){
                                    // this happens randomly with Kitkat-to-Kitkat connections on client side.

                                    if (reason == WifiP2pManager.NO_SERVICE_REQUESTS){
                                        mySpeech.speak("Service Discovery error 3");
                                    }else{
                                        mySpeech.speak("Service Discovery generic zero error");
                                    }

                                    doReConectWifi = true;

                                    //It appears that with KitKat, this event also sometimes does corrupt
                                    // our local services advertising, so stopping & restarting (once connected)
                                    // to make sure we are discoverable still
                                    stopLocalServices();

                                    WifiManager wifiManager = (WifiManager) that.getSystemService(Context.WIFI_SERVICE);
                                    wifiManager.setWifiEnabled(false);
                                    //wait for WIFI_P2P_STATE_CHANGED_ACTION & do the re-connection
                                }
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



    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo p2pInfo) {

        tGotConnectionInfo = System.currentTimeMillis();

   /*     //when testing without data gottaget the time here as well as counting..
        tGotData = System.currentTimeMillis();
        conCount = conCount + 1;
        ((TextView) findViewById(R.id.CountBox)).setText("Msg: " + conCount);


        final Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            //We need to give client time to get connection information before we disconnect
            // otherwise it will never get Connected event, and just goes directly to Disconnected
            // with Kitkat 4.4.4 in clcient side this also can lead into problems where all calls to
            // p2p will just result error 2 (busy), and swithing Wlan on/off changes the error being
            // reported with Service discovery, and then the error give is 0
            // only way to gt out from that error, is re-starting the device..
            public void run() {
                disconnect();
            }
        }, 5000);*/


        try {
            if (p2pInfo.isGroupOwner) {
                addText("Connected as group owner, already listening!");
                myServiceState = ServiceState.ConnectedAsOwner;
                mLastConnectionRole = LastConnectionRole.GroupOwner;
                clientSocket = null;
            //  groupSocket = new GroupOwnerSocketHandler(myHandler,Integer.parseInt(SERVICE_PORT_INSTANCE),this);
            //    groupSocket.start();
            } else {
                addText("will now do socket connection with port : " + CLIENT_PORT_INSTANCE);
                mLastConnectionRole = LastConnectionRole.Client;
                myServiceState = ServiceState.ConnectedAsClient;
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

    public  void disconnect() {
        if (p2p != null && channel != null) {
            p2p.requestGroupInfo(channel, new WifiP2pManager.GroupInfoListener() {
                @Override
                public void onGroupInfoAvailable(WifiP2pGroup group) {
                    if (group != null && p2p != null && channel != null && group.isGroupOwner()) {
                        p2p.removeGroup(channel, new WifiP2pManager.ActionListener() {

                            @Override
                            public void onSuccess() {
                                addText("removeGroup onSuccess -");
                                myServiceState = ServiceState.NONE;
                            }

                            @Override
                            public void onFailure(int reason) {
                                addText("removeGroup onFailure -" + reason);
                            }
                        });
                    }
                }
            });
        }
    }
    public void WriteDebugline() {

        try {
            String dbgData = Build.VERSION.SDK_INT + " ," ;

            dbgData = dbgData + otherPartyVersion + " ,";

            if(mLastConnectionRole == LastConnectionRole.GroupOwner) {
                dbgData = dbgData + "GroupOwner ,";
            }else if(mLastConnectionRole == LastConnectionRole.Client){
                dbgData = dbgData  + "Client ,";
            }else {
                dbgData = dbgData + "Unknown ,";
            }

            dbgData = dbgData + (tAddServiceRequest - tRequestPeers) + " ,";
            dbgData = dbgData + (tStartServiceDiscovery - tAddServiceRequest) + " ,";

            dbgData = dbgData + (tGotService - tStartServiceDiscovery) + " ,";
            dbgData = dbgData + (tConnecting - tGotService) + " ,";
            dbgData = dbgData + (tConnected - tConnecting) + " ,";
            dbgData = dbgData + (tGotConnectionInfo - tConnected) + " ,";
            dbgData = dbgData + (tGotData - tGotConnectionInfo) + " ,";
            dbgData = dbgData + (tGoBigtData - tGotData) + " ,";

            dbgData = dbgData + (tFromDcToSd) + " ,";
            dbgData = dbgData + (tDataToDisconnect) + "\n";


            addText("write: " + dbgData);
            dbgFileOs.write(dbgData.getBytes());
            dbgFileOs.flush();

            addText("From start to data: " + ((tGotData - tAddServiceRequest) / 1000) + " seconds.");

            tAddServiceRequest = 0;
            tStartServiceDiscovery = 0;
            tFromDcToSd = 0;
            tGotService = 0;
            tConnecting = 0;
            tConnected = 0;
            tGotConnectionInfo = 0;
            //tGotData = 0;   // we want to check the time between disconnect & got data, its set right befire caling the function, thus reset not required
            tDisconnected = 0;

        }catch(Exception e){
            addText("dbgFile write error :"  + e.toString() );
        }
    }

    public void addText(String text) {
        timeCounter = 0;
        ((TextView) findViewById(R.id.TimeBox)).setText("T: " + timeCounter);
        Log.d("MyTeststst", text);
        ((TextView) findViewById(R.id.debugdataBox)).append(text + "\n");
    }

    private class PeerReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            addText("Received intent: " + action);


            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {

                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    if(doReConectWifi){
                        doReConectWifi = false;
                        // with Kitkat-to-Kitkat tests it was determined that local services were lost during the events
                        // thus, code was added to clear them when disconnecting the Wlan
                        // thus we need to get them added again in here.

                       startLocalService();
                    }
                    startPeerDiscovery();
                } else {
                    if(doReConectWifi){
                        WifiManager wifiManager = (WifiManager) that.getSystemService(Context.WIFI_SERVICE);
                        wifiManager.setWifiEnabled(true);
                    }
                }
            }else if(WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {

                if(myServiceState == ServiceState.NONE
                || myServiceState == ServiceState.DiscoverPeer) {

                    tRequestPeers = System.currentTimeMillis();

                    p2p.requestPeers(channel, peerListListener);
                }
            } else if(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                //WifiP2pDevice device = intent.getParcelableExtra(EXTRA_WIFI_P2P_DEVICE);
                //addText("Local device: " + MyP2PHelper.deviceToString(device));
            } else if(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {

                int state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
                String persTatu = "Discovery state changed to ";

                if(state == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED){
                    persTatu = persTatu + "Stopped.";
                    if(myServiceState == ServiceState.NONE
                    || myServiceState == ServiceState.DiscoverPeer) {
                    // we will get this event when we are connecting, and just about to get connection
                    // and trying to start discovery will fail with error 2 (Busy)
                    // thus we should simply avoid doing the discovery start request here
                    // Also with Kitkat 4.4.3, the Busy error is not given, instead we mess up the logic,
                    // and start peerdiscovery when we should actually be continuing the connecting etc. states.

                        //startPeerDiscovery();
                        // Disabling to test whether this would mess up incoming connection !
                    }
                }else if(state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED){
                    persTatu = persTatu + "Started.";
                }else{
                    persTatu = persTatu + "unknown  " + state;
                }
                addText(persTatu);

            } else if (WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo.isConnected()) {

                    addText("PeerReceiver, we are connected !!!");
                    myServiceState = ServiceState.QueryConnection;
                    tConnected = System.currentTimeMillis();
                    p2p.requestConnectionInfo(channel, that);

                } else
                {
                    tDisconnected = System.currentTimeMillis();
                    if(tGoBigtData != 0){
                        tDataToDisconnect = (tDisconnected - tGoBigtData);
                    }

                    addText("PeerReceiver, DISCONNECTED event !!, from Got data: " + ((tDisconnected - tGoBigtData) / 1000) + " seconds.");
                    myServiceState = ServiceState.NONE;

                    startPeerDiscovery();
                }
            } else if (DSS_WIFIDIRECT_VALUES.equals(action)) {
                String s = intent.getStringExtra(DSS_WIFIDIRECT_MESSAGE);
                ((TextView) findViewById(R.id.debugdataBox)).append(s + "\n");
            }
        }
    }
 }
