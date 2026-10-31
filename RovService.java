package uk.co.rovcompanion;

import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Owns the BLE session independently of Activities, including when the screen is locked. */
public final class RovService extends Service implements RovBluetooth.Listener {
    static final String STOP="uk.co.rovcompanion.STOP", DISCONNECT="uk.co.rovcompanion.DISCONNECT";
    static final int NOTIFICATION_ID=100;
    private static final String CHANNEL="slider_connection";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final IBinder binder=new LocalBinder();
    private final ArrayDeque<String> logs=new ArrayDeque<>();
    private RovBluetooth.Listener observer;
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;
    private boolean ongoing,startingConnection;
    private RovBluetooth.State notifiedState;
    RovBluetooth controller;
    String selectedAddress="",selectedName="RŌV Pro";
    final class LocalBinder extends Binder { RovService service(){return RovService.this;} }

    @Override public void onCreate(){
        super.onCreate();
        notifications=getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Slider connection",NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Background slider control and Stop action");channel.setSound(null,null);channel.enableVibration(false);channel.setShowBadge(false);notifications.createNotificationChannel(channel);
        PowerManager power=getSystemService(PowerManager.class);
        wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ROVCompanion:movement");wakeLock.setReferenceCounted(false);
        controller=new RovBluetooth(getApplicationContext(),this);
        SharedPreferences prefs=getSharedPreferences("controls",MODE_PRIVATE);
        int oldMargin=Math.max(2,Math.min(15,prefs.getInt("endClearancePercent",5)));
        int a=prefs.getInt("range_start",oldMargin),b=prefs.getInt("range_end",100-oldMargin);
        try{controller.setWorkingRange(a,b);}catch(IllegalArgumentException error){controller.setWorkingRange(5,95);}
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    void attach(RovBluetooth.Listener listener){observer=listener;}
    void detach(RovBluetooth.Listener listener){if(observer==listener)observer=null;}
    void uiHidden(){controller.pauseDiscovery();}
    boolean connect(BluetoothDevice device,String name){
        selectedAddress=device.getAddress();selectedName=name;
        if(!keepAlive())return false;
        startingConnection=true;
        try{controller.connect(device);}finally{startingConnection=false;changed();}
        return true;
    }
    /** Called while the connection is initiated by a visible Activity. */
    boolean keepAlive(){
        if(ongoing)return true;
        try{
            startForeground(NOTIFICATION_ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            ongoing=true;notifiedState=controller.state;
            // A started service outlives the Activity's binding. Never restart motion after process death.
            startService(new Intent(this,RovService.class));return true;
        }catch(RuntimeException error){
            log("Background connection unavailable: "+error);ongoing=false;stopForeground(STOP_FOREGROUND_REMOVE);return false;
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(STOP.equals(action))controller.stop();
        else if(DISCONNECT.equals(action))controller.disconnect();
        if(!ongoing&&(controller.state==RovBluetooth.State.DISCONNECTED||controller.state==RovBluetooth.State.ERROR))stopSelf(startId);
        return START_NOT_STICKY;
    }
    private boolean active(){
        RovBluetooth.State s=controller.state;
        return s==RovBluetooth.State.CONNECTING||s==RovBluetooth.State.CONFIGURING||s==RovBluetooth.State.POSITIONING||s==RovBluetooth.State.MOVING||s==RovBluetooth.State.STOPPING;
    }
    private final Runnable renewWake=new Runnable(){public void run(){
        if(!ongoing||!active())return;
        wakeLock.acquire(10*60*1000L);handler.postDelayed(this,5*60*1000L);
    }};
    private void updateWake(){
        if(ongoing&&active()){
            if(!wakeLock.isHeld()){wakeLock.acquire(10*60*1000L);handler.postDelayed(renewWake,5*60*1000L);}
        }else{
            handler.removeCallbacks(renewWake);if(wakeLock.isHeld())wakeLock.release();
        }
    }
    @Override public void changed(){
        if(controller==null)return;
        if(controller.state==RovBluetooth.State.READY&&!selectedAddress.isEmpty())
            getSharedPreferences("controls",MODE_PRIVATE).edit().putString("last_slider",selectedAddress).apply();
        if(ongoing){
            if(!startingConnection&&(controller.state==RovBluetooth.State.DISCONNECTED||controller.state==RovBluetooth.State.ERROR)){
                ongoing=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            }else if(notifiedState!=controller.state){notifications.notify(NOTIFICATION_ID,notification());notifiedState=controller.state;}
        }
        updateWake();
        RovBluetooth.Listener current=observer;if(current!=null)current.changed();
    }
    @Override public void found(RovBluetooth.NearbyDevice device){RovBluetooth.Listener current=observer;if(current!=null)current.found(device);}
    @Override public void log(String line){logs.addLast(new SimpleDateFormat("HH:mm:ss.SSS",Locale.UK).format(new Date())+" "+line);while(logs.size()>1024)logs.removeFirst();}
    String diagnosticLog(){StringBuilder text=new StringBuilder();for(String line:logs)text.append(line).append('\n');return text.toString();}
    private PendingIntent command(String action,int code){return PendingIntent.getService(this,code,new Intent(this,RovService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(){
        String title="RŌV connected",detail="Connection stays on in the background";
        switch(controller.state){
            case CONNECTING:title="Connecting to RŌV";detail="Setting up slider controls";break;
            case CONFIGURING:title="Preparing your move";detail="Slider control continues in the background";break;
            case POSITIONING:title="Positioning your RŌV";detail="Moving to the loop's starting point";break;
            case MOVING:title="RŌV is moving";detail="Movement continues with the screen off";break;
            case STOPPING:title="Stopping your RŌV";detail="Waiting for the slider to confirm Stop";break;
            default:break;
        }
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setColor(MainActivity.BLUE)
            .setContentTitle(title).setContentText(detail).setContentIntent(PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if(controller.state==RovBluetooth.State.CONFIGURING||controller.state==RovBluetooth.State.POSITIONING||controller.state==RovBluetooth.State.MOVING)
            builder.addAction(new Notification.Action.Builder(null,"Stop",command(STOP,1)).build());
        builder.addAction(new Notification.Action.Builder(null,"Disconnect",command(DISCONNECT,2)).build());
        return builder.build();
    }
    @Override public void onDestroy(){
        observer=null;handler.removeCallbacksAndMessages(null);if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        if(controller!=null)controller.close();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
