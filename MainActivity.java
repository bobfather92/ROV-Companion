package uk.co.rovcompanion;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.location.LocationManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity implements RovBluetooth.Listener {
    static final int PAPER=0xfff5f6fa,WHITE=Color.WHITE,INK=0xff191e2c,MUTED=0xff737c91,BLUE=0xff3759ed,LINE=0xffe6e9f1,RED=0xffd84d51,GREEN=0xff15966b;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String,RovBluetooth.NearbyDevice> nearby=new LinkedHashMap<>();
    private RovBluetooth controller;
    private RovService service;
    private boolean bound;
    private final ServiceConnection serviceConnection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            service=((RovService.LocalBinder)binder).service();controller=service.controller;
            selectedAddress=service.selectedAddress;selectedName=service.selectedName;
            if(surface==null)buildUi();
            if(foreground){service.attach(MainActivity.this);restoreSession();}
        }
        @Override public void onServiceDisconnected(ComponentName name){service=null;controller=null;if(foreground)issue("Connection\ninterrupted.","Reopen Companion to reconnect to your slider.");}
    };
    private SharedPreferences prefs;
    private AutoConnectGate autoGate;
    private FrameLayout surface;
    private LinearLayout discovery,controlScreen;
    private TextView connectionTitle,connectionDetail,connectionTag,retry,help,liveTitle,liveDetail,rangeValue,rangeEndValue,rangeDetail,rangeEdit,speedValue,easeValue,motionAction,toA,toB;
    private ImageView discoveryImage;
    private StudioViews.Pulse pulse;
    private StudioViews.Check success;
    private StudioViews.Rail rail;
    private View rangeCard;
    private SeekBar speed,ease;
    private Switch loop;
    private boolean foreground,resumeDiscovery=true,permissionInFlight,settingsInFlight,manualPause,manualSelection,revealPending,sessionRevealed,showingControls,reconnectAfterStop,addressSaved;
    private boolean directionRight=true;
    private String selectedAddress="",selectedName="RŌV Pro";
    private Runnable revealFinish;
    private Dialog chooser,activeSheet;
    private LinearLayout chooserList;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("controls",MODE_PRIVATE);
        autoGate=new AutoConnectGate(prefs.getString("last_slider",""));
        directionRight=prefs.getBoolean("direction_right",true);
        getWindow().setStatusBarColor(PAPER);getWindow().setNavigationBarColor(PAPER);
        bound=bindService(new Intent(this,RovService.class),serviceConnection,Context.BIND_AUTO_CREATE);
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private GradientDrawable outline(int color,int radius){GradientDrawable d=shape(color,radius);d.setStroke(dp(1),LINE);return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setLineSpacing(dp(3),1);return t;}
    private TextView strong(String value,int size,int color){TextView t=text(value,size,color);t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    private void gap(LinearLayout parent,int size){parent.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(size)));}
    private void buttonSemantics(View v,String description){v.setContentDescription(description);v.setFocusable(true);v.setClickable(true);v.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View view,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(view,info);info.setClassName(Button.class.getName());}});}
    private TextView action(String label,int bg,int color,Runnable click){TextView t=strong(label,16,color);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(54));t.setPadding(dp(16),dp(12),dp(16),dp(12));t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x183759ed),shape(bg,18),shape(WHITE,18)));buttonSemantics(t,label);t.setOnClickListener(v->click.run());return t;}
    private void enabled(View view,boolean value){view.setEnabled(value);view.setAlpha(value?1f:.4f);}
    private LinearLayout card(){LinearLayout l=column();l.setPadding(dp(18),dp(18),dp(18),dp(18));l.setBackground(outline(WHITE,24));return l;}
    private void addCard(LinearLayout parent,View child){parent.addView(child,new LinearLayout.LayoutParams(-1,-2));gap(parent,12);}
    private LinearLayout row(View left,View right){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.addView(left,new LinearLayout.LayoutParams(0,-2,1));r.addView(right,new LinearLayout.LayoutParams(-2,-2));return r;}
    private TextView eyebrow(String value){TextView t=strong(value,10,MUTED);t.setLetterSpacing(.14f);return t;}
    private View brandRow(){return brandRow(false);}
    private View brandRow(boolean controls){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand=column();TextView logo=strong("ROV",25,INK);logo.setLetterSpacing(.08f);brand.addView(logo);gap(brand,3);brand.addView(eyebrow("C O M P A N I O N"));
        row.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        if(controls){
            FrameLayout product=new FrameLayout(this);product.setBackground(shape(WHITE,16));product.setClipToOutline(true);
            ImageView image=productImage();image.setId(R.id.control_product_image);image.setScaleType(ImageView.ScaleType.FIT_CENTER);product.addView(image,new FrameLayout.LayoutParams(-1,-1));
            LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(dp(102),dp(60));imageParams.rightMargin=dp(12);row.addView(product,imageParams);
        }
        FrameLayout menu=new FrameLayout(this);menu.setBackground(shape(WHITE,22));buttonSemantics(menu,"Connection and settings");menu.setOnClickListener(v->showSettings());
        menu.addView(new StudioViews.More(this),new FrameLayout.LayoutParams(-1,-1));row.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));return row;
    }
    private ImageView productImage(){ImageView image=new ImageView(this);image.setImageResource(R.drawable.slider);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setContentDescription("Rhino RŌV Pro camera slider");return image;}
    private void buildUi(){
        surface=new FrameLayout(this);surface.setBackgroundColor(PAPER);setContentView(surface);
        surface.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());surface.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        buildDiscovery();buildControls();showDiscovery();
        surface.post(()->{WindowInsetsController bars=getWindow().getDecorView().getWindowInsetsController();if(bars!=null)bars.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);});
    }
    private void buildDiscovery(){
        discovery=column();discovery.setId(R.id.discovery_screen);discovery.setPadding(dp(24),dp(22),dp(24),dp(22));surface.addView(discovery,new FrameLayout.LayoutParams(-1,-1));
        discovery.addView(brandRow());
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);LinearLayout content=column();content.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(content);discovery.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        connectionTag=eyebrow("AUTOMATIC CONNECTION");connectionTag.setTextColor(BLUE);content.addView(connectionTag);gap(content,15);
        connectionTitle=strong("Finding your\nslider.",34,INK);connectionTitle.setId(R.id.connection_title);content.addView(connectionTitle);gap(content,13);
        connectionDetail=text("Turn on your RŌV. We’ll connect when it’s nearby.",15,MUTED);content.addView(connectionDetail);gap(content,28);
        FrameLayout hero=new FrameLayout(this);hero.setBackground(shape(WHITE,28));hero.setClipToOutline(true);content.addView(hero,new LinearLayout.LayoutParams(-1,dp(246)));
        discoveryImage=productImage();discoveryImage.setAlpha(0f);discoveryImage.setScaleX(.94f);discoveryImage.setScaleY(.94f);hero.addView(discoveryImage,new FrameLayout.LayoutParams(-1,dp(190),Gravity.TOP));
        pulse=new StudioViews.Pulse(this);hero.addView(pulse,new FrameLayout.LayoutParams(dp(132),dp(132),Gravity.CENTER));
        success=new StudioViews.Check(this);success.setId(R.id.success_mark);success.setVisibility(View.GONE);FrameLayout.LayoutParams checkParams=new FrameLayout.LayoutParams(dp(76),dp(76),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);checkParams.bottomMargin=dp(14);hero.addView(success,checkParams);
        gap(content,22);
        retry=action("Try again",BLUE,WHITE,()->{manualPause=false;manualSelection=false;resumeDiscovery=true;beginConnection();});retry.setVisibility(View.GONE);content.addView(retry,new LinearLayout.LayoutParams(-1,-2));gap(content,8);
        help=action("Connection help",PAPER,MUTED,this::showConnectionHelp);content.addView(help,new LinearLayout.LayoutParams(-1,-2));
        TextView footer=text("RŌV Pro · Motion control",12,MUTED);footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(10),0,0);discovery.addView(footer);
    }
    private SeekBar slider(int max,int progress,int id,String description){
        SeekBar bar=new SeekBar(this);bar.setId(id);bar.setMax(max);bar.setProgress(progress);bar.setContentDescription(description);bar.setPadding(dp(14),0,dp(14),0);bar.setSplitTrack(false);
        GradientDrawable back=shape(LINE,4),fill=shape(BLUE,4);ClipDrawable clip=new ClipDrawable(fill,Gravity.LEFT,ClipDrawable.HORIZONTAL);
        LayerDrawable track=new LayerDrawable(new Drawable[]{back,clip});track.setId(0,android.R.id.background);track.setId(1,android.R.id.progress);
        for(int i=0;i<2;i++){track.setLayerHeight(i,dp(5));track.setLayerGravity(i,Gravity.CENTER_VERTICAL);}bar.setProgressDrawable(track);
        GradientDrawable thumb=shape(WHITE,20);thumb.setSize(dp(22),dp(22));thumb.setStroke(dp(3),BLUE);bar.setThumb(thumb);bar.setThumbOffset(dp(11));return bar;
    }
    private void buildControls(){
        controlScreen=column();controlScreen.setId(R.id.control_screen);controlScreen.setVisibility(View.GONE);surface.addView(controlScreen,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout body=new FrameLayout(this);controlScreen.addView(body,new LinearLayout.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.setPadding(0,0,0,dp(164));body.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=column();content.setPadding(dp(22),dp(20),dp(22),dp(12));scroll.addView(content);
        content.addView(brandRow(true));gap(content,20);
        liveTitle=strong("Ready to move.",25,INK);content.addView(liveTitle);gap(content,7);
        liveDetail=text("●  RŌV Pro connected",13,GREEN);content.addView(liveDetail);gap(content,18);
        LinearLayout range=card();range.setId(R.id.range_card);rangeCard=range;
        rangeEdit=strong("Adjust  ↗",12,BLUE);range.addView(row(eyebrow("TRAVEL RANGE"),rangeEdit));gap(range,10);
        rangeValue=strong("A  5%",15,INK);rangeEndValue=strong("B  95%",15,INK);range.addView(row(rangeValue,rangeEndValue));
        rail=new StudioViews.Rail(this);range.addView(rail,new LinearLayout.LayoutParams(-1,dp(36)));
        rangeDetail=text("90% of your rail · End clearance active",11,MUTED);range.addView(rangeDetail);
        buttonSemantics(range,"Adjust start and end positions");range.setOnClickListener(v->showRangeEditor());addCard(content,range);
        LinearLayout motion=card();speedValue=strong("25%",14,BLUE);motion.addView(row(strong("Speed",15,INK),speedValue));
        speed=slider(100,prefs.getInt("speed",25),R.id.speed_slider,"Move speed");motion.addView(speed,new LinearLayout.LayoutParams(-1,dp(48)));
        View divider=new View(this);divider.setBackgroundColor(LINE);motion.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));gap(motion,13);
        easeValue=strong("20%",14,BLUE);motion.addView(row(strong("Ease in / out",15,INK),easeValue));
        ease=slider(50,prefs.getInt("ease",20),R.id.ease_slider,"Ease in and out");motion.addView(ease,new LinearLayout.LayoutParams(-1,dp(48)));addCard(content,motion);
        loop=new Switch(this);loop.setId(R.id.loop_toggle);loop.setChecked(prefs.getBoolean("loop",false));loop.setContentDescription("Loop between selected positions");
        loop.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{BLUE,0xffb4bac9}));
        LinearLayout directions=new LinearLayout(this);directions.setPadding(dp(3),dp(3),dp(3),dp(3));directions.setBackground(shape(WHITE,15));
        toA=action("←  To A",PAPER,MUTED,()->selectDirection(false));toB=action("To B  →",PAPER,MUTED,()->selectDirection(true));
        toA.setTextSize(14);toB.setTextSize(14);toA.setPadding(dp(8),dp(6),dp(8),dp(6));toB.setPadding(dp(8),dp(6),dp(8),dp(6));toA.setSingleLine(true);toB.setSingleLine(true);toA.setMinHeight(dp(48));toB.setMinHeight(dp(48));directions.addView(toA,new LinearLayout.LayoutParams(0,dp(48),1));directions.addView(toB,new LinearLayout.LayoutParams(0,dp(48),1));
        TextView note=text("Keeps moving when you switch apps or lock the screen.",11,MUTED);note.setGravity(Gravity.CENTER);content.addView(note);
        LinearLayout dock=column();dock.setPadding(dp(22),dp(12),dp(22),dp(14));dock.setBackgroundColor(PAPER);
        LinearLayout options=new LinearLayout(this);options.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout loopGroup=new LinearLayout(this);loopGroup.setGravity(Gravity.CENTER_VERTICAL);loopGroup.addView(strong("Loop",14,INK));LinearLayout.LayoutParams switchParams=new LinearLayout.LayoutParams(-2,dp(48));switchParams.leftMargin=dp(10);loopGroup.addView(loop,switchParams);
        options.addView(loopGroup,new LinearLayout.LayoutParams(0,-2,1));options.addView(directions,new LinearLayout.LayoutParams(dp(176),dp(54)));dock.addView(options);gap(dock,9);
        motionAction=action("Start move  →",BLUE,WHITE,this::motionPressed);motionAction.setId(R.id.motion_action);dock.addView(motionAction,new LinearLayout.LayoutParams(-1,dp(58)));body.addView(dock,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int value,boolean user){refreshValues();if(user)prefs.edit().putInt(bar==speed?"speed":"ease",value).apply();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}};
        speed.setOnSeekBarChangeListener(listener);ease.setOnSeekBarChangeListener(listener);
        loop.setOnCheckedChangeListener((button,checked)->{prefs.edit().putBoolean("loop",checked).apply();syncControls();});refreshValues();selectDirection(directionRight);
    }
    private void refreshValues(){if(speedValue!=null){speedValue.setText(speed.getProgress()+"%");easeValue.setText(ease.getProgress()+"%");}}
    private void selectDirection(boolean right){directionRight=right;prefs.edit().putBoolean("direction_right",right).apply();if(toA==null)return;toA.setBackground(shape(right?WHITE:BLUE,12));toB.setBackground(shape(right?BLUE:WHITE,12));toA.setTextColor(right?MUTED:WHITE);toB.setTextColor(right?WHITE:MUTED);toA.setSelected(!right);toB.setSelected(right);}
    private boolean linked(){if(controller==null)return false;RovBluetooth.State s=controller.state;return s==RovBluetooth.State.READY||s==RovBluetooth.State.CONFIGURING||s==RovBluetooth.State.POSITIONING||s==RovBluetooth.State.MOVING||s==RovBluetooth.State.STOPPING;}
    private void motionPressed(){if(controller==null)return;if(controller.state==RovBluetooth.State.READY)controller.move(directionRight,speed.getProgress(),ease.getProgress(),loop.isChecked());else controller.stop();}
    private void showDiscovery(){if(discovery==null)return;showingControls=false;controlScreen.setVisibility(View.GONE);discovery.setVisibility(View.VISIBLE);}
    private void showControls(){
        if(!foreground||!linked())return;
        revealPending=false;sessionRevealed=true;showingControls=true;pulse.active(false);
        controlScreen.setAlpha(0);controlScreen.setTranslationY(dp(10));controlScreen.setVisibility(View.VISIBLE);discovery.setVisibility(View.GONE);
        controlScreen.animate().alpha(1).translationY(0).setDuration(260).start();syncControls();
    }
    private void searching(){
        cancelReveal();sessionRevealed=false;showDiscovery();connectionTag.setText("AUTOMATIC CONNECTION");connectionTag.setTextColor(BLUE);
        connectionTitle.setText(manualSelection?"Choose your\nslider.":"Finding your\nslider.");connectionDetail.setText(manualSelection?"Open nearby devices to choose a slider.":"Turn on your RŌV. We’ll connect when it’s nearby.");
        success.setVisibility(View.GONE);discoveryImage.setAlpha(0f);pulse.setVisibility(View.VISIBLE);pulse.active(foreground);retry.setVisibility(View.GONE);
    }
    private void connecting(){
        showDiscovery();connectionTag.setText("SLIDER FOUND");connectionTitle.setText("Connecting\nto your RŌV.");connectionDetail.setText("Setting up your controls. One moment.");
        pulse.active(false);pulse.setVisibility(View.GONE);success.setVisibility(View.GONE);retry.setVisibility(View.GONE);
        discoveryImage.animate().alpha(1).scaleX(1).scaleY(1).setDuration(340).setInterpolator(new DecelerateInterpolator()).start();
    }
    private void revealConnection(){
        if(revealPending||sessionRevealed||!foreground)return;
        revealPending=true;showDiscovery();connectionTag.setText("CONNECTED");connectionTag.setTextColor(GREEN);connectionTitle.setText("You’re\nconnected.");connectionDetail.setText("Your slider is ready. Let’s make the next shot.");
        connectionTitle.announceForAccessibility("Slider connected");
        pulse.active(false);pulse.setVisibility(View.GONE);retry.setVisibility(View.GONE);discoveryImage.animate().alpha(1).scaleX(1).scaleY(1).setDuration(250).start();success.setVisibility(View.VISIBLE);success.reveal();
        revealFinish=()->{if(revealPending&&foreground&&linked())showControls();};ui.postDelayed(revealFinish,ValueAnimator.areAnimatorsEnabled()?1050:180);
    }
    private void cancelReveal(){revealPending=false;if(revealFinish!=null)ui.removeCallbacks(revealFinish);}
    private void issue(String title,String detail){
        if(foreground)resumeDiscovery=false;
        cancelReveal();sessionRevealed=false;pulse.active(false);pulse.setVisibility(View.GONE);success.setVisibility(View.GONE);showDiscovery();
        connectionTag.setText("LET’S GET CONNECTED");connectionTag.setTextColor(BLUE);connectionTitle.setText(title);connectionDetail.setText(detail);discoveryImage.setAlpha(.7f);retry.setVisibility(View.VISIBLE);
    }
    private void beginConnection(){
        if(controller==null||!foreground||permissionInFlight||settingsInFlight||manualPause)return;
        if(controller.state==RovBluetooth.State.SCANNING||controller.state==RovBluetooth.State.CONNECTING||linked())return;
        resumeDiscovery=false;reconnectAfterStop=false;searching();
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            connectionTitle.setText("Meet your\nslider.");connectionDetail.setText("Allow Nearby devices so Companion can find and connect to your RŌV.");permissionInFlight=true;requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},1);return;
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            connectionTitle.setText("One quick\npermission.");connectionDetail.setText("Compatibility discovery needs precise Location while using the app. We don’t record GPS position.");permissionInFlight=true;requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},2);return;
        }
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!prefs.getBoolean("notification_asked",false)){
            prefs.edit().putBoolean("notification_asked",true).apply();connectionTitle.setText("Control it\nanywhere.");connectionDetail.setText("Allow notifications to stop the slider from your lock screen or while using another app.");permissionInFlight=true;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},3);return;
        }
        if(!controller.enabled()){settingsInFlight=true;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),100);return;}
        LocationManager location=getSystemService(LocationManager.class);
        if(location==null||!location.isLocationEnabled()){settingsInFlight=true;connectionDetail.setText("Turn on Location for compatibility discovery, then return here.");startActivityForResult(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),101);return;}
        autoGate.reset();nearby.clear();log("Opening automatic discovery. Motion remains user controlled.");controller.scan();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);permissionInFlight=false;resumeDiscovery=false;
        if(request==3){ui.post(this::beginConnection);return;}
        boolean granted=results.length>0;for(int result:results)granted&=result==PackageManager.PERMISSION_GRANTED;
        if(granted)ui.post(this::beginConnection);else issue("Permission\nneeded.",request==1?"Allow Nearby devices in app settings, then try again.":"Allow precise Location while using the app for compatibility discovery.");
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);settingsInFlight=false;resumeDiscovery=false;
        LocationManager location=getSystemService(LocationManager.class);
        boolean ready=request==100?controller.enabled():location!=null&&location.isLocationEnabled();
        if(ready)ui.post(this::beginConnection);else issue(request==100?"Bluetooth\nis off.":"Location\nis off.","Turn it on, then tap Try again to find your slider.");
    }
    private void restoreSession(){
        if(linked()){sessionRevealed=true;showControls();}
        else if(controller.state==RovBluetooth.State.CONNECTING)connecting();
        else if(resumeDiscovery&&!manualPause&&!permissionInFlight&&!settingsInFlight)ui.post(this::beginConnection);
    }
    @Override protected void onStart(){super.onStart();foreground=true;if(service!=null){service.attach(this);restoreSession();}}
    @Override protected void onResume(){super.onResume();if(resumeDiscovery&&!manualPause&&!permissionInFlight&&!settingsInFlight)ui.post(this::beginConnection);}
    @Override protected void onStop(){foreground=false;resumeDiscovery=!manualPause;cancelReveal();if(pulse!=null)pulse.active(false);if(activeSheet!=null)activeSheet.dismiss();if(service!=null){service.detach(this);service.uiHidden();}super.onStop();}
    @Override protected void onDestroy(){if(service!=null)service.detach(this);ui.removeCallbacksAndMessages(null);if(bound){unbindService(serviceConnection);bound=false;}super.onDestroy();}
    @Override public void found(RovBluetooth.NearbyDevice device){
        nearby.put(device.address,device);if(chooserList!=null)populateChooser();
        if(!foreground||manualSelection)return;
        if(autoGate.candidate(device.address,device.advertisesRov))connectDevice(device);
    }
    private void connectDevice(RovBluetooth.NearbyDevice device){
        if(controller.state!=RovBluetooth.State.SCANNING&&controller.state!=RovBluetooth.State.DISCONNECTED&&controller.state!=RovBluetooth.State.ERROR)return;
        autoGate.manualChoice();selectedAddress=device.address;selectedName=device.name;addressSaved=false;
        if(chooser!=null)chooser.dismiss();chooserList=null;connecting();if(!service.connect(device.device,device.name))issue("Connection\nunavailable.","Android couldn’t start background slider control. Check app permissions and try again.");
    }
    @Override public void changed(){
        if(connectionTitle==null||controller==null)return;
        RovBluetooth.State state=controller.state;
        if(linked()){
            if(!selectedAddress.isEmpty()&&!addressSaved){autoGate.verified(selectedAddress);prefs.edit().putString("last_slider",selectedAddress).apply();addressSaved=true;}
            if(state==RovBluetooth.State.READY&&!sessionRevealed)revealConnection();
            else if(state!=RovBluetooth.State.READY&&!showingControls&&foreground)showControls();
            syncControls();
        }else if(state==RovBluetooth.State.CONNECTING)connecting();
        else if(state==RovBluetooth.State.SCANNING){if(!revealPending&&!showingControls)pulse.active(foreground);}
        else if(state==RovBluetooth.State.ERROR||state==RovBluetooth.State.DISCONNECTED){
            if(state==RovBluetooth.State.DISCONNECTED&&controller.message.isEmpty())return;
            issue(manualPause?"Slider\ndisconnected.":state==RovBluetooth.State.ERROR?"Let’s try\nthat again.":"Slider not\nfound yet.",manualPause?"Tap Try again whenever you’re ready.":connectionIssue(state));
            if(reconnectAfterStop&&foreground){reconnectAfterStop=false;ui.post(this::beginConnection);}
            else if(resumeDiscovery&&foreground&&!manualPause&&!permissionInFlight&&!settingsInFlight)ui.post(this::beginConnection);
        }
    }
    private String connectionIssue(RovBluetooth.State state){
        String message=controller.message;
        if(message.contains("30 seconds"))return "Android paused discovery. Wait 30 seconds, then try again.";
        if(message.contains("missing required"))return "This device isn’t a compatible RŌV slider. Choose another device.";
        if(message.contains("Stop was not confirmed"))return "We couldn’t confirm Stop. Check the slider before reconnecting.";
        if(message.contains("overcurrent"))return "The slider reported a motor load error. Check it before reconnecting.";
        if(message.contains("starting end"))return "We couldn’t confirm the starting position. Check the slider, then reconnect.";
        return state==RovBluetooth.State.ERROR?"The connection was interrupted. Check your slider is on and nearby, then try again.":"Check that your RŌV is switched on and nearby, then try again.";
    }
    private void syncControls(){
        if(motionAction==null||controller==null)return;boolean ready=controller.state==RovBluetooth.State.READY;
        for(View v:new View[]{speed,ease,loop,toA,toB})enabled(v,ready);
        rangeCard.setEnabled(ready);rangeEdit.setAlpha(ready?1f:.4f);
        int a=controller.rangeStartPercent,b=controller.rangeEndPercent;
        rangeValue.setText("A  "+a+"%");rangeEndValue.setText("B  "+b+"%");rangeDetail.setText((b-a)+"% of your rail · "+(a==0&&b==100?"Full travel":a>0&&b<100?"End clearance active":"Physical endpoint selected"));
        rangeCard.setContentDescription("Working range, A "+a+" percent to B "+b+" percent. Adjust positions.");
        rail.range(a,b,controller.travel>0&&controller.position>=0?controller.position*100f/controller.travel:-1);
        String label;
        switch(controller.state){
            case READY:liveTitle.setText("Ready to move.");liveDetail.setText("●  RŌV Pro connected");liveDetail.setTextColor(GREEN);label=(loop.isChecked()?"Start loop":"Start move")+(directionRight?"  →":"  ←");break;
            case POSITIONING:liveTitle.setText("Finding your starting point.");liveDetail.setText("●  Moving to "+(directionRight?"A":"B")+" before looping");liveDetail.setTextColor(BLUE);label="■  Stop positioning";break;
            case CONFIGURING:liveTitle.setText("Setting up your move.");liveDetail.setText("●  Preparing the slider");liveDetail.setTextColor(BLUE);label="■  Stop setup";break;
            case STOPPING:liveTitle.setText("Bringing it to a stop.");liveDetail.setText("Checking the slider is idle");liveDetail.setTextColor(MUTED);label="Stopping…";break;
            default:liveTitle.setText(loop.isChecked()?"Keep the shot moving.":"Your shot is in motion.");liveDetail.setText("●  "+(loop.isChecked()?"Looping between A & B":"Moving to "+(directionRight?"B":"A")));liveDetail.setTextColor(BLUE);label="■  Stop movement";break;
        }
        motionAction.setText(label);motionAction.setContentDescription(label);motionAction.setBackground(shape(ready?BLUE:controller.state==RovBluetooth.State.STOPPING?MUTED:RED,18));enabled(motionAction,controller.state!=RovBluetooth.State.STOPPING);
    }
    private Dialog sheet(String title,LinearLayout content){
        Dialog dialog=new Dialog(this);LinearLayout root=column();root.setPadding(dp(24),dp(24),dp(24),dp(24));root.setBackground(shape(WHITE,26));root.addView(strong(title,23,INK));gap(root,20);root.addView(content);
        dialog.setContentView(root);activeSheet=dialog;dialog.setOnDismissListener(d->{if(activeSheet==dialog)activeSheet=null;});Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);WindowManager.LayoutParams p=window.getAttributes();p.dimAmount=.32f;window.setAttributes(p);window.setGravity(Gravity.BOTTOM);window.setLayout(-1,-2);}return dialog;
    }
    private void showRangeEditor(){
        if(controller.state!=RovBluetooth.State.READY)return;
        LinearLayout content=column();StudioViews.Rail preview=new StudioViews.Rail(this);content.addView(preview,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView aLabel=strong("A · "+controller.rangeStartPercent+"%",15,INK),bLabel=strong("B · "+controller.rangeEndPercent+"%",15,INK);
        content.addView(aLabel);SeekBar a=slider(100,controller.rangeStartPercent,R.id.range_start,"Start position A percent");content.addView(a,new LinearLayout.LayoutParams(-1,dp(48)));
        gap(content,10);content.addView(bLabel);SeekBar b=slider(100,controller.rangeEndPercent,R.id.range_end,"End position B percent");content.addView(b,new LinearLayout.LayoutParams(-1,dp(48)));
        gap(content,10);content.addView(text("Choose any section from 0–100%, with A and B at least 10% apart. 0% and 100% use the physical endpoints. Saving does not move the slider.",13,MUTED));gap(content,22);
        Dialog dialog=sheet("Choose A and B.",content);
        SeekBar.OnSeekBarChangeListener changes=new SeekBar.OnSeekBarChangeListener(){boolean updating;public void onProgressChanged(SeekBar bar,int value,boolean user){if(updating)return;updating=true;if(bar==a&&value>b.getProgress()-10)a.setProgress(b.getProgress()-10);if(bar==b&&value<a.getProgress()+10)b.setProgress(a.getProgress()+10);aLabel.setText("A · "+a.getProgress()+"%");bLabel.setText("B · "+b.getProgress()+"%");preview.range(a.getProgress(),b.getProgress(),-1);updating=false;}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}};
        a.setOnSeekBarChangeListener(changes);b.setOnSeekBarChangeListener(changes);preview.range(a.getProgress(),b.getProgress(),-1);
        TextView save=action("Save positions",BLUE,WHITE,()->{if(controller.setWorkingRange(a.getProgress(),b.getProgress())){prefs.edit().putInt("range_start",a.getProgress()).putInt("range_end",b.getProgress()).apply();dialog.dismiss();syncControls();}});save.setId(R.id.range_save);content.addView(save,new LinearLayout.LayoutParams(-1,-2));dialog.show();dialog.getWindow().setLayout(-1,-2);
    }
    private void showSettings(){
        LinearLayout content=column();content.addView(text("RŌV Companion 1.0.0",13,MUTED));gap(content,18);Dialog dialog=sheet("Slider settings",content);
        content.addView(text("Your slider stays connected and moving in the background. Use Stop in the notification, or return here to control it.",13,MUTED));gap(content,12);
        content.addView(action("Notification settings",PAPER,INK,()->{dialog.dismiss();startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}));gap(content,8);
        content.addView(action("Reconnect slider",PAPER,INK,()->{dialog.dismiss();manualSelection=false;restartConnection(false);}));gap(content,8);
        content.addView(action("Choose a different slider",PAPER,INK,()->{dialog.dismiss();manualSelection=true;autoGate.forget();prefs.edit().remove("last_slider").apply();restartConnection(true);}));gap(content,8);
        content.addView(action("Copy diagnostics",PAPER,INK,()->{copyDiagnostics();dialog.dismiss();}));gap(content,8);
        content.addView(action("Disconnect",WHITE,RED,()->{dialog.dismiss();manualPause=true;resumeDiscovery=false;controller.disconnect();}));dialog.show();dialog.getWindow().setLayout(-1,-2);
    }
    private void restartConnection(boolean choose){manualPause=false;resumeDiscovery=false;reconnectAfterStop=true;sessionRevealed=false;controller.disconnect();if(!linked()&&controller.state!=RovBluetooth.State.CONNECTING){reconnectAfterStop=false;ui.post(this::beginConnection);}if(choose)ui.postDelayed(this::showChooser,350);}
    private void showConnectionHelp(){
        LinearLayout content=column();content.addView(text("Switch on your RŌV and close the original RŌV Motion app. Bluetooth and Location must be enabled for compatibility discovery.",15,MUTED));gap(content,18);Dialog dialog=sheet("Get connected.",content);
        content.addView(action("Nearby devices",PAPER,INK,()->{dialog.dismiss();showChooser();}));gap(content,8);content.addView(action("App permissions",PAPER,INK,()->{dialog.dismiss();settingsInFlight=true;startActivityForResult(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())),102);}));gap(content,8);content.addView(action("Copy diagnostics",PAPER,INK,()->{copyDiagnostics();dialog.dismiss();}));dialog.show();dialog.getWindow().setLayout(-1,-2);
    }
    private void showChooser(){
        if(!foreground||isFinishing()||isDestroyed())return;
        chooserList=column();ScrollView scroll=new ScrollView(this);scroll.addView(chooserList);LinearLayout content=column();content.addView(scroll,new LinearLayout.LayoutParams(-1,dp(300)));chooser=sheet("Nearby devices",content);populateChooser();chooser.setOnDismissListener(d->chooserList=null);chooser.show();chooser.getWindow().setLayout(-1,-2);
    }
    private void populateChooser(){
        if(chooserList==null)return;chooserList.removeAllViews();if(nearby.isEmpty())chooserList.addView(text("No devices yet. Try another scan with the slider switched on.",14,MUTED));
        for(RovBluetooth.NearbyDevice device:nearby.values()){String name=device.name.equals("Unnamed Bluetooth device")?"Bluetooth device · "+device.address.substring(Math.max(0,device.address.length()-5)):device.name;chooserList.addView(action(name+"  ·  "+device.rssi+" dBm",PAPER,INK,()->connectDevice(device)));gap(chooserList,8);}
    }
    @Override public void log(String line){if(service!=null)service.log(line);}
    private void copyDiagnostics(){ClipboardManager clipboard=getSystemService(ClipboardManager.class);clipboard.setPrimaryClip(ClipData.newPlainText("ROV diagnostics",diagnostics()));Toast.makeText(this,"Diagnostics copied",Toast.LENGTH_SHORT).show();}
    private String diagnostics(){StringBuilder s=new StringBuilder("ROV Companion 1.0.0\n");s.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE).append('\n');s.append("Nearby scan: ").append(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED).append("; connect: ").append(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED).append("; precise Location: ").append(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED).append('\n');LocationManager lm=getSystemService(LocationManager.class);s.append("Location enabled: ").append(lm!=null&&lm.isLocationEnabled()).append('\n');s.append("Notifications enabled: ").append(getSystemService(NotificationManager.class).areNotificationsEnabled()).append('\n');if(controller!=null)s.append("Working range: A=").append(controller.rangeStartPercent).append("% B=").append(controller.rangeEndPercent).append("%\n");if(service!=null)s.append(service.diagnosticLog());return s.toString();}
}
