package uk.co.rovcompanion;

import android.app.*;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;

/** Runs the real Android views. Connected states are injected; no physical Bluetooth claim. */
public class UiWalkthrough extends Instrumentation {
    MainActivity activity;
    File output;
    private Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private void set(Object object,String name,Object value)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private void main(Runnable task){runOnMainSync(task);waitForIdleSync();}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void shell(String command)throws Exception{
        try(android.os.ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand(command);FileInputStream input=new FileInputStream(descriptor.getFileDescriptor())){byte[] bytes=new byte[1024];while(input.read(bytes)!=-1){}}
    }
    private Notification sliderNotification(){
        for(android.service.notification.StatusBarNotification notification:getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications())
            if(notification.getId()==RovService.NOTIFICATION_ID)return notification.getNotification();
        throw new AssertionError("Background notification exists");
    }
    private void snap(String name)throws Exception{
        waitForIdleSync();Thread.sleep(250);
        Bitmap bitmap=getUiAutomation().takeScreenshot();check(bitmap!=null,"Screenshot available");
        try(FileOutputStream out=new FileOutputStream(new File(output,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            output=new File(getTargetContext().getExternalFilesDir(null),"ui-walkthrough");output.mkdirs();
            Intent launch=new Intent(getTargetContext(),MainActivity.class);launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=(MainActivity)startActivitySync(launch);Thread.sleep(1800);waitForIdleSync();
            main(()->{try{
                RovBluetooth c=(RovBluetooth)field(activity,"controller");
                check(c.state==RovBluetooth.State.SCANNING||c.state==RovBluetooth.State.CONNECTING,"Opening app automatically starts discovery");
            }catch(Exception e){throw new RuntimeException(e);}});
            snap("01_discovery");
            main(()->{try{
                RovBluetooth c=(RovBluetooth)field(activity,"controller");
                android.bluetooth.BluetoothManager manager=getTargetContext().getSystemService(android.bluetooth.BluetoothManager.class);
                android.bluetooth.BluetoothDevice device=manager.getAdapter().getRemoteDevice("00:04:05:19:00:02");
                activity.found(new RovBluetooth.NearbyDevice(device,"ROV",-65,true,true));
                check(c.state==RovBluetooth.State.CONNECTING||c.state==RovBluetooth.State.ERROR,"Matching advertisement automatically attempts connection");
                if(c.state==RovBluetooth.State.CONNECTING)check((Boolean)field(field(activity,"service"),"ongoing"),"Foreground service survives the scan-to-connect transition");
                check(activity.findViewById(R.id.success_mark).getVisibility()!=View.VISIBLE,"Do not show success before GATT initialization");
            }catch(Exception e){throw new RuntimeException(e);}});
            // Inject readiness solely to validate native UI rendering and state transitions.
            main(()->{try{
                RovBluetooth c=(RovBluetooth)field(activity,"controller");c.disconnect();
                c.travel=5800;c.position=2000;c.state=RovBluetooth.State.READY;
                set(activity,"selectedAddress","");set(activity,"sessionRevealed",false);
                activity.changed();
                android.os.Handler handler=(android.os.Handler)field(activity,"ui");handler.removeCallbacks((Runnable)field(activity,"revealFinish"));
            }catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(750);snap("02_connected");
            main(()->{try{((Runnable)field(activity,"revealFinish")).run();}catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(450);snap("03_controls");
            main(()->{try{
                android.graphics.Rect visible=new android.graphics.Rect();View action=activity.findViewById(R.id.motion_action);
                check(action.getGlobalVisibleRect(visible)&&visible.height()==action.getHeight(),"Primary action remains fully visible");
                for(String name:new String[]{"toA","toB"}){
                    TextView direction=(TextView)field(activity,name);
                    check(direction.getPaint().measureText(direction.getText().toString())<=direction.getWidth()-direction.getPaddingLeft()-direction.getPaddingRight(),"Direction label fits at chosen font size");
                }
                View image=activity.findViewById(R.id.control_product_image);
                check(image.getGlobalVisibleRect(visible)&&visible.top<200&&visible.centerX()>activity.getResources().getDisplayMetrics().widthPixels/2,"Product photo is in the upper right corner");
            }catch(Exception e){throw new RuntimeException(e);}});
            main(()->{View range=activity.findViewById(R.id.range_card);check(range.isEnabled(),"Ready range editor enabled");range.performClick();});
            main(()->{try{
                Dialog dialog=(Dialog)field(activity,"activeSheet");
                ((SeekBar)dialog.findViewById(R.id.range_start)).setProgress(0);
                ((SeekBar)dialog.findViewById(R.id.range_end)).setProgress(100);
                for(int id:new int[]{R.id.range_start,R.id.range_end}){
                    SeekBar bar=dialog.findViewById(id);check(bar.getMin()==0&&bar.getMax()==100,"Range controls expose 0–100%");
                    check(bar.getPaddingLeft()>=bar.getThumb().getIntrinsicWidth()/2&&bar.getPaddingRight()>=bar.getThumb().getIntrinsicWidth()/2,"Thumb fits at both physical endpoints");
                }
            }catch(Exception e){throw new RuntimeException(e);}});
            snap("04_range_editor");
            main(()->{try{
                Dialog dialog=(Dialog)field(activity,"activeSheet");dialog.findViewById(R.id.range_save).performClick();
                RovBluetooth c=(RovBluetooth)field(activity,"controller");
                check(c.rangeStartPercent==0&&c.rangeEndPercent==100,"Full 0–100% range saved to controller");
                check(getTargetContext().getSharedPreferences("controls",0).getInt("range_start",-1)==0,"A=0 persists");
                check(getTargetContext().getSharedPreferences("controls",0).getInt("range_end",0)==100,"B=100 persists");
                check(c.state==RovBluetooth.State.READY,"Saving range sends no movement");
                // Hold a test operation in flight: Stop must queue behind it, without a real GATT link.
                OperationQueue queue=new OperationQueue(new OperationQueue.Clock(){public void later(Runnable task,long ms){}public void cancel(Runnable task){}},message->{throw new AssertionError(message);});
                queue.add(new OperationQueue.Operation("test-barrier",true,()->true,value->{}));set(c,"queue",queue);
                c.state=RovBluetooth.State.MOVING;c.position=3000;
                RovService service=(RovService)field(activity,"service");check(service.keepAlive(),"Real connectedDevice foreground service starts");service.changed();
                check(!activity.findViewById(R.id.range_card).isEnabled(),"Range locks while moving");
                check(!activity.findViewById(R.id.speed_slider).isEnabled(),"Speed locks while moving");
                check(((TextView)activity.findViewById(R.id.motion_action)).getText().toString().contains("Stop"),"Stop remains available");
            }catch(Exception e){throw new RuntimeException(e);}});
            snap("05_moving");
            RovBluetooth retained=(RovBluetooth)field(activity,"controller");
            RovService session=(RovService)field(activity,"service");
            check(sliderNotification().extras.getString(Notification.EXTRA_TITLE).contains("moving"),"Notification reflects movement");
            shell("input keyevent 3");Thread.sleep(600);
            main(()->{try{
                check(!(Boolean)field(activity,"foreground"),"Activity reaches background");
                check(retained.state==RovBluetooth.State.MOVING,"Switching apps leaves movement active");
                check(((android.os.PowerManager.WakeLock)field(session,"wakeLock")).isHeld(),"CPU wake lock held during motion");
                check(field(session,"observer")==null,"Background Activity is detached from BLE listener");
            }catch(Exception e){throw new RuntimeException(e);}});
            shell("input keyevent 223");Thread.sleep(500);
            main(()->check(retained.state==RovBluetooth.State.MOVING,"Screen off leaves movement active"));
            shell("input keyevent 224");shell("input keyevent 82");
            main(()->activity.finish());Thread.sleep(300);
            activity=(MainActivity)startActivitySync(launch);Thread.sleep(600);waitForIdleSync();
            main(()->{try{
                RovBluetooth c=(RovBluetooth)field(activity,"controller");
                check(c==retained&&field(activity,"service")==session,"New Activity reuses existing service and controller");
                check(c.state==RovBluetooth.State.MOVING,"Reopening app does not stop/restart movement");
                check(activity.findViewById(R.id.control_screen).getVisibility()==View.VISIBLE,"Reopened app immediately restores controls");
                check(activity.findViewById(R.id.discovery_screen).getVisibility()==View.GONE,"Live session does not restart discovery");
            }catch(Exception e){throw new RuntimeException(e);}});
            shell("input keyevent 3");Thread.sleep(300);
            shell("cmd statusbar expand-notifications");Thread.sleep(400);snap("06_background_notification");shell("cmd statusbar collapse");
            Notification notification=sliderNotification();boolean sent=false;
            for(Notification.Action action:notification.actions)if("Stop".contentEquals(action.title)){action.actionIntent.send();sent=true;break;}
            check(sent,"Notification exposes a Stop PendingIntent");Thread.sleep(300);
            main(()->{try{
                check(retained.state==RovBluetooth.State.STOPPING,"Notification Stop reaches production controller while Activity is hidden");
                check(((android.os.PowerManager.WakeLock)field(session,"wakeLock")).isHeld(),"Keep CPU awake until Stop is confirmed");
                retained.state=RovBluetooth.State.READY;session.changed();
                check(!((android.os.PowerManager.WakeLock)field(session,"wakeLock")).isHeld(),"Release wake lock when idle");
            }catch(Exception e){throw new RuntimeException(e);}});
            getTargetContext().startActivity(launch);Thread.sleep(400);waitForIdleSync();
            main(()->{try{
                RovBluetooth c=(RovBluetooth)field(activity,"controller");
                check(activity.findViewById(R.id.discovery_screen).getVisibility()==View.GONE,"Stop-to-ready does not replay connection animation");
                check(activity.findViewById(R.id.range_card).isEnabled(),"Ready restores editing");
                c.disconnect();
            }catch(Exception e){throw new RuntimeException(e);}});
            Thread.sleep(350);check(getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications().length==0,"Disconnect removes ongoing notification");
            result.putString("result","PASS: discovery/reveal, corner photo, full 0–100% range save, foreground service, Home/screen-off movement preservation, Activity recreation/session reuse, notification Stop dispatch, idle wake-lock release, disconnect cleanup. BLE readiness/movement and the in-flight queue barrier were injected; no physical slider.");
            result.putString("screenshots",output.getAbsolutePath());finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("failure",error.toString());android.util.Log.e("ROV_UI_TEST","UI check failed",error);finish(Activity.RESULT_CANCELED,result);}
    }
}
