// WebCall Copyright 2023 timur.mobi. All rights reserved.
//
// WebCallService.java is split in code sections
//
// section 0: imports and variables
//
// section 1: Android service methods: onBind(), onCreate(), onStartCommand(), onTaskRemoved()
//
// section 2: class WebCallServiceBinder with exposed methodes and subclasses:
//     startWebView(), webcallConnectType(), wakeupType(), callInProgress(),
//     activityDestroyed(), getCurrentUrl(), runJScode(), fileSelect()
//   class WebViewClient: to overrided selected webView methods:
//     shouldOverrideUrlLoading(), onPageFinished()
//   class WebChromeClient: to extend selected webview functionality:
//     onConsoleMessage(), onPermissionRequest(), getDefaultVideoPoster(), onShowFileChooser()
//   DownloadListener(): makes blob:-urls become downloadable
//
// section 3: class WebCallJSInterface with methods that can be called from javascript:
//   wsOpen(), wsSend(), wsClose(), wsExit(), isConnected(), wsClearCookies(), wsClearCache(),
//   rtcConnect(), callPickedUp(), peerConnect(), peerDisConnect(), storePreference(), etc.
//
// section 4: class WsClient with methods called by the Java WebSocket engine: onOpen(), onError(), 
//   onClose(), onMessage(), onSetSSLParameters(), onWebsocketPing(), onWebsocketPong(), etc.
//   AlarmReceiver class
//
// section 5: private utility methods
//   including the crucial reconnect-to-server mechanism

package timur.webcall.callee;

import android.annotation.TargetApi;
import android.app.Service;
import android.app.DownloadManager;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.preference.PreferenceManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Bundle;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.view.Window;
import android.view.Display;
import android.hardware.display.DisplayManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.ValueCallback;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.SslErrorHandler;
import android.webkit.ClientCertRequest;
import android.app.KeyguardManager;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.Notification;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.http.SslError;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.provider.MediaStore;
import android.provider.Settings;
import android.media.RingtoneManager;
import android.media.Ringtone;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.Manifest;
import android.annotation.SuppressLint;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Collection;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.Locale;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URISyntaxException;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.Queue;
import java.lang.reflect.Method;

import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;

// https://github.com/TooTallNate/Java-WebSocket
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.framing.Framedata;

import timur.webcall.callee.BuildConfig;

public class WebCallService extends Service {
	private final static String TAG = "WebCallService";
	private final static int NOTIF_ID = 1;
	private final static String NOTIF_CHANNEL_ID_LOW  = "123";
	private final static String NOTIF_CHANNEL_ID_HIGH = "124";
	private final static String startAlarmString = "timur.webcall.callee.START_ALARM"; // for alarmReceiver
	private final static Intent startAlarmIntent = new Intent(startAlarmString);
	private final static String awaitingCalls = "Ready to receive calls";

	// serverPingPeriodPlus corresponds to pingPeriod=60 in wsClient.go
	// after serverPingPeriodPlus secs with no pings, checkLastPing() considers server connection gone
	private final static int serverPingPeriodPlus = 2*60+10;

	private final static int ReconnectCounterMax = 120;   // max number of reconnect loops
	private final static int ReconnectDelayMaxSecs = 1200; // max number of delay secs per loop
	// the 1st 120 loops go from 10s to 1200s delay (average 600s, so 120*600 = 72000s = 1200m = 20h)
	// loops 121-150 are limited to 1200s delay (30*1200s = 36000s = 600m = 10h)
	// total time before reconnect is given up: 30h

	// loop counter: 1   2   3    4    5    6    7    8    9   10   11  12  13   14   15   16   17   18   19   20
	// delayPerLoop: 10s 20s 30s  40s  50s  60s  70s  80s  90s 100s 110 120 130  140  150  160  170  180  190  200
	// total delay:  10s 30s 60s 100s 150s 210s 280s 360s 450s 550  660 780 910 1030 1180 1340 1510 1690 1880 2080
	// 10th retry after ~10m, 20th retry after 35m, 30th retry after 90m, 

	// we do up to ReconnectCounterMax loops when we try to reconnect
	// loops are done in ca. 30s intervals; so 40 loops will take up close to 20min
	private final static int ReconnectCounterBeep = 10;   // make a beep after x reconnect loops
														  // is currently disabled
	private final static int ReconnectCounterScreen = 30; // turn screen on after x reconnect loops

	private static BroadcastReceiver networkStateReceiver = null; // for api < 24
	private static BroadcastReceiver dozeStateReceiver = null;
	private static BroadcastReceiver alarmReceiver = null;
	private static BroadcastReceiver powerConnectionReceiver = null;
	private static PowerManager powerManager = null;
	private static WifiManager wifiManager = null;
	private static WifiManager.WifiLock wifiLock = null; // if connected and haveNetworkInt=2
	private static Queue stringMessageQueue = new LinkedList<String>();
	private static ScheduledExecutorService scheduler = null;
	private static Runnable reconnecter = null;
	private static SharedPreferences prefs;
	private static ValueCallback<Uri[]> filePath; // for file selector
	private static AlarmManager alarmManager = null;
	private static WakeLock keepAwakeWakeLock = null; // PARTIAL_WAKE_LOCK (screen off)
	private static ConnectivityManager connectivityManager = null;
	private static ConnectivityManager.NetworkCallback myNetworkCallback = null;
	private static DisplayManager displayManager = null;
	private static String userAgentString = null;
	private static AudioManager audioManager = null;
	private static IntentFilter batteryStatusfilter = null;
	private static Intent batteryStatus = null;
	private static String webviewVersionString = "";
	private static WebSettings webSettings = null;
	private static int notificationID = 1;

	// wakeUpWakeLock used for wakeup from doze: FULL_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP (screen on)
	// wakeUpWakeLock is released by activity
	private static volatile WakeLock wakeUpWakeLock = null;

	// the websocket URL provided by the login service
	private static volatile String wsAddr = "";

	// all ws-communications with and from the signalling server go through wsClient
	private static volatile WebSocketClient wsClient = null;

	// haveNetworkInt describes the type of network cur in use: 0=noNet, 1=mobile, 2=wifi, 3=other
	private static volatile int haveNetworkInt = -1;

	// currentUrl contains the currently loaded URL
	private static volatile String currentUrl = null;

	// webviewMainPageLoaded is set true if currentUrl is pointing to the main page
	private static volatile boolean webviewMainPageLoaded = false;

	// callPickedUpFlag is set from pickup until peerConnect, activates proximitySensor
	private static volatile boolean callPickedUpFlag = false;

	// peerConnectFlag set if full mediaConnect is established
	private static volatile boolean peerConnectFlag = false;

	// peerDisconnnectFlag is set when call is ended by endWebRtcSession() -> peerDisConnect()
	// peerDisconnnectFlag will get cleared by rtcConnect() of the next call
	// peerDisconnnectFlag is used to detect 'ringing' state
	// !callPickedUpFlag && !peerConnectFlag && !peerDisconnnectFlag = ringing
	// !callPickedUpFlag && !peerConnectFlag && peerDisconnnectFlag  = not ringing
	private static volatile boolean peerDisconnnectFlag = false;

	// reconnectSchedFuture holds the currently scheduled reconnecter task
	private static volatile ScheduledFuture<?> reconnectSchedFuture = null;

	// reconnectBusy is set true while reconnecter is running
	// reconnectBusy can be set false to abort reconnecter
	private static volatile boolean reconnectBusy = false;

	// reconnectCounter is the reconnecter loop counter
	private static volatile int reconnectCounter = 0;

	// audioToSpeakerActive holds the current state of audioToSpeakerMode
	private static volatile boolean audioToSpeakerActive = false;

	// loginUrl will be constructed from webcalldomain + "/rtcsig/login"
	private static volatile String loginUrl = null;

	// pingCounter is the number of server pings received and processed
	private static volatile long pingCounter = 0l;

	// lastPingDate used by checkLastPing() to calculated seconds since last received ping
	private static volatile Date lastPingDate = null;

	// dozeIdle is set by dozeStateReceiver isDeviceIdleMode() and isInteractive()
	private static volatile boolean dozeIdle = false;

	private static volatile boolean charging = false;

	// alarmPendingDate holds the last time a (pending) alarm was scheduled
	private static volatile Date alarmPendingDate = null;
	private static volatile PendingIntent pendingAlarm = null;

	private static volatile String webviewCookies = null;
	private static volatile boolean soundNotificationPlayed = false;
	private static volatile boolean extendedLogsFlag = false;
	private static volatile boolean connectToServerIsWanted = false;
	private static volatile long wakeUpFromDozeSecs = 0; // last wakeUpFromDoze() time
	private static volatile long keepAwakeWakeLockStartTime = 0;
	private static volatile int lastMinuteOfDay = 0;
	private static volatile int proximityNear = -1;
	private static volatile boolean insecureTlsFlag = false;

	// below are variables backed by preference persistens
	private static volatile int beepOnLostNetworkMode = 0;
	private static volatile int startOnBootMode = 0;
	private static volatile int setWifiLockMode = 0;
	private static volatile int audioToSpeakerMode = 0;
	private static volatile int screenForWifiMode = 0;
	// keepAwakeWakeLockMS holds the sum of MS while keepAwakeWakeLock was held since midnight
	private static volatile long keepAwakeWakeLockMS = 0;

	private static volatile Lock lock = new ReentrantLock();

	private static volatile BroadcastReceiver serviceCmdReceiver = null;
	private static volatile boolean activityVisible = false;
	private static volatile boolean autoPickup = false;
	private static volatile MediaPlayer mediaPlayer = null;
	private static volatile boolean activityWasDiscarded = false;
	private static volatile boolean calleeIsReady = false;
	private static volatile boolean stopSelfFlag = false;
	private static volatile boolean ringFlag = false;
	private static volatile String textmode = "";
	private static volatile String lastStatusMessage = "";
	private static volatile Notification lastNotification = null;

	private volatile WebView myWebView = null;
	private volatile WebCallJSInterface webCallJSInterface = new WebCallJSInterface();
	private volatile WebCallJSInterfaceMini webCallJSInterfaceMini = new WebCallJSInterfaceMini();

	private Binder mBinder = null;
	private Context context = null;

	// section 1: android service methods
	@Override
	public IBinder onBind(Intent arg0) {
		Log.d(TAG,"onBind "+BuildConfig.VERSION_NAME);
		context = this;
		prefs = PreferenceManager.getDefaultSharedPreferences(context);
		mBinder = new WebCallServiceBinder();
		Log.d(TAG,"onBind return mBinder");
		return mBinder;
	}

	@Override
	public void onRebind(Intent arg0) {
		Log.d(TAG,"onRebind "+BuildConfig.VERSION_NAME);
		context = this;
	}

	@Override
	public void onCreate() {
		Log.d(TAG,"onCreate "+BuildConfig.VERSION_NAME+" "+Build.VERSION.SDK_INT);
		stopSelfFlag = false;

		alarmReceiver = new AlarmReceiver();
		registerReceiver(alarmReceiver, new IntentFilter(startAlarmString));

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			int importance = NotificationManager.IMPORTANCE_LOW;
			NotificationChannel notificationChannel1 = new NotificationChannel(
				NOTIF_CHANNEL_ID_LOW, "WebCall Status", NotificationManager.IMPORTANCE_LOW);
			getSystemService(NotificationManager.class).createNotificationChannel(notificationChannel1);

			NotificationChannel notificationChannel2 = new NotificationChannel(
				NOTIF_CHANNEL_ID_HIGH, "WebCall Incoming", NotificationManager.IMPORTANCE_HIGH);
			getSystemService(NotificationManager.class).createNotificationChannel(notificationChannel2);
		}

		// receive broadcast msgs from service and activity
		serviceCmdReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				//Log.d(TAG, "serviceCmdReceiver "+intent.toString());
				if(stopSelfFlag) {
					Log.d(TAG,"# serviceCmdReceiver skip on stopSelfFlag "+intent.toString());
					return;
				}

				String message = intent.getStringExtra("activityVisible");
				if(message!=null && message!="") {
					// activity is telling us that it is in front or not
					Log.d(TAG, "serviceCmdReceiver activityVisible "+message);
					if(message.equals("true")) {
						activityVisible = true;
					} else {
						activityVisible = false;
					}

					// statusMessage() (runJS) is not always executed in doze mode
					// here we display the lastStatusMessage again after wake from sleep
					if(lastStatusMessage!="") {
						runJS("showStatus('"+lastStatusMessage+"',-1);",null);
						lastStatusMessage="";
					}

					return;
				}

				message = intent.getStringExtra("denyCall");
				if(message!=null && message!="") {
					// user responded to the call-notification dialog by denying the call
					Log.d(TAG, "serviceCmdReceiver denyCall "+message);

					// stop secondary wakeIntents from rtcConnect()
					peerDisconnnectFlag = true;

					// close the notification by sending a new not-high-priority notification
					updateNotification(awaitingCalls,false);

					// disconnect caller / stop ringing
					if(myWebView!=null && webviewMainPageLoaded) {
						Log.w(TAG,"serviceCmdReceiver denyCall runJS('hangup()')");
						runJS("hangup(true,true,'userReject')",null);
					} else {
						if(wsClient==null) {
							Log.w(TAG,"# serviceCmdReceiver denyCall wsClient==null");
						} else {
							try {
								// stop the caller
								Log.w(TAG,"serviceCmdReceiver denyCall send cancel|disconnect");
								wsClient.send("cancel|disconnect");

								stopRinging("serviceCmdReceiver denyCall");

								final Runnable runnable2 = new Runnable() {
									public void run() {
										// become callee for next caller
										Log.w(TAG,"serviceCmdReceiver denyCall send init|");
										wsClient.send("init|");
									}
								};
								scheduler.schedule(runnable2, 500l, TimeUnit.MILLISECONDS);

							} catch(Exception ex) {
								Log.w(TAG,"# serviceCmdReceiver denyCall ex="+ex);
							}
						}
					}

					// clear queueWebRtcMessage / stringMessageQueue
					Log.w(TAG,"serviceCmdReceiver denyCall clear stringMessageQueue");
					while(!stringMessageQueue.isEmpty()) {
						stringMessageQueue.poll();
					}
					return;
				}

				message = intent.getStringExtra("showCall");
				if(message!=null && message!="") {
					// user responded to the call-notification dialog by switching to activity
					// this intent is coming from the started activity
					if(webviewMainPageLoaded) {
						if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
							// kickstart processWebRtcMessages()
							Log.d(TAG, "serviceCmdReceiver showCall "+message);
							processWebRtcMessages();
						}
					}
					return;
				}

				message = intent.getStringExtra("acceptCall");
				if(message!=null && message!="") {
					// user responded to the call-notification dialog by accepting the call
					// this intent is coming from the started activity
					if(webviewMainPageLoaded) {
						// autoPickup now
						if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
							Log.d(TAG, "serviceCmdReceiver processWebRtcMessages() + autoPickup on rtcConnect");
							processWebRtcMessages();
							stopRinging("serviceCmdReceiver acceptCall");
							autoPickup = true;
						} else {
							Log.d(TAG, "serviceCmdReceiver auto-pickup() now");
							runJS("pickup()",null);
						}
					} else {
						// autoPickup when we get connected as callee
						// if the next connect fails, we must reset this flag
						Log.d(TAG, "serviceCmdReceiver autoPickup delayed");
						autoPickup = true;
					}
					return;
				}

				message = intent.getStringExtra("dismissNotification");
				if(message!=null && message!="") {
					// user responded to the call-notification dialog and it needs to be closed
					// this intent is coming from the started activity
					Log.d(TAG, "serviceCmdReceiver dismissNotification "+message);

					// we can later close this notification by sending a new not-high priority notification
					updateNotification("Incoming WebCall",false);
					return;
				}

				Log.d(TAG, "serviceCmdReceiver no match");
			}
		};
		registerReceiver(serviceCmdReceiver, new IntentFilter("serviceCmdReceiver"));
	}

	@Override
	public int onStartCommand(Intent onStartIntent, int flags, int startId) {
		Log.d(TAG,"onStartCommand");
		context = this;

		if(batteryStatusfilter==null) {
			batteryStatusfilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
			//batteryStatus = context.registerReceiver(null, batteryStatusfilter);
		}

		if(scheduler==null) {
			scheduler = Executors.newScheduledThreadPool(20);
		}
		if(scheduler==null) {
			Log.d(TAG,"fatal: cannot create scheduledThreadPool");
			return 0;
		}

		if(powerManager==null) {
			powerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		}
		if(powerManager==null) {
			Log.d(TAG,"fatal: no access to PowerManager");
			return 0;
		}

		if(displayManager==null) {
			displayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
		}
		if(displayManager==null) {
			Log.d(TAG,"fatal: no access to DisplayManager");
			return 0;
		}

		if(audioManager==null) {
			audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		}
		if(audioManager==null) {
			Log.d(TAG,"fatal: no access to AudioManager");
			return 0;
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >=api23
			if(extendedLogsFlag) {
				// check with isIgnoringBatteryOptimizations()
				String packageName = context.getPackageName();
				boolean isIgnoringBatteryOpti = powerManager.isIgnoringBatteryOptimizations(packageName);
				Log.d(TAG, "onStartCommand ignoringBattOpt="+isIgnoringBatteryOpti);
			}
		}

		if(keepAwakeWakeLock==null) {
			// apps that are (partially) exempt from Doze and App Standby optimizations
			// can hold partial wake locks to ensure that the CPU is running and for 
			// the screen and keyboard backlight to be allowed to go off
			String logKey = "WebCall:keepAwakeWakeLock";
			if(userAgentString==null || userAgentString.indexOf("HUAWEI")>=0)
				logKey = "LocationManagerService"; // to avoid being killed on Huawei
			keepAwakeWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, logKey);
		}
		if(keepAwakeWakeLock==null) {
			Log.d(TAG,"fatal: no access to keepAwakeWakeLock");
			return 0;
		}

		if(powerConnectionReceiver==null) {
			// set initial charging state
			charging = isPowerConnected(context);
			Log.d(TAG,"onStartCommand charging="+charging);

			powerConnectionReceiver = new PowerConnectionReceiver();
			IntentFilter ifilter = new IntentFilter();
			ifilter.addAction(Intent.ACTION_POWER_CONNECTED);
			ifilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
			registerReceiver(powerConnectionReceiver, ifilter);
		}

		if(wifiManager==null) {
			wifiManager = (WifiManager)
				context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
		}
		if(wifiManager==null) {
			Log.d(TAG,"fatal: no access to WifiManager");
			return 0;
		}

		if(wifiLock==null) {
			String logKey = "WebCall:wifiLock";
			if(userAgentString==null || userAgentString.indexOf("HUAWEI")>=0)
				logKey = "LocationManagerService"; // to avoid being killed on Huawei
			wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, logKey);
		}
		if(wifiLock==null) {
			Log.d(TAG,"fatal: no access to wifiLock");
			return 0;
		}

		if(alarmManager==null) {
			alarmManager = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
		}
		if(alarmManager==null) {
			Log.d(TAG,"fatal: no access to alarmManager");
			return 0;
		}

		if(reconnecter==null) {
			reconnecter = newReconnecter();
		}
		if(reconnecter==null) {
			Log.d(TAG,"fatal: cannot create reconnecter");
			return 0;
		}

		if(connectivityManager==null) {
			connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
		}
		if(connectivityManager==null) {
			Log.d(TAG,"fatal: cannot get connectivityManager");
			return 0;
		}

		if(prefs==null) {
			prefs = PreferenceManager.getDefaultSharedPreferences(context);
		}
		try {
			audioToSpeakerMode = prefs.getInt("audioToSpeaker", 0);
			Log.d(TAG,"onStartCommand audioToSpeakerMode="+audioToSpeakerMode);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand audioToSpeakerMode ex="+ex);
		}

		try {
			beepOnLostNetworkMode = prefs.getInt("beepOnLostNetwork", 0);
			Log.d(TAG,"onStartCommand beepOnLostNetworkMode="+beepOnLostNetworkMode);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand beepOnLostNetworkMode ex="+ex);
		}

		setLoginUrl();

		try {
			startOnBootMode = prefs.getInt("startOnBoot", 0);
			Log.d(TAG,"onStartCommand startOnBootMode="+startOnBootMode);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand startOnBootMode ex="+ex);
		}

		try {
			setWifiLockMode = prefs.getInt("setWifiLock", 1);
			Log.d(TAG,"onStartCommand setWifiLockMode="+setWifiLockMode);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand setWifiLockMode ex="+ex);
		}

		try {
			screenForWifiMode = prefs.getInt("screenForWifi", 0);
			Log.d(TAG,"onStartCommand screenForWifiMode="+screenForWifiMode);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand screenForWifiMode ex="+ex);
		}

		try {
			keepAwakeWakeLockMS = prefs.getLong("keepAwakeWakeLockMS", 0);
			Log.d(TAG,"onStartCommand keepAwakeWakeLockMS="+keepAwakeWakeLockMS);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand keepAwakeWakeLockMS ex="+ex);
		}

		try {
			insecureTlsFlag = prefs.getBoolean("insecureTlsFlag", false);
			Log.d(TAG,"onStartCommand insecureTlsFlag="+insecureTlsFlag);
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand insecureTlsFlag ex="+ex);
		}

		try {
			String lastUsedVersionName = prefs.getString("versionName", "");
			Log.d(TAG,"onStartCommand lastUsed versionName="+lastUsedVersionName);
 			if(!lastUsedVersionName.equals(BuildConfig.VERSION_NAME)) {
				keepAwakeWakeLockMS = 0;
				storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
				Log.d(TAG,"onStartCommand version change, clear keepAwakeWakeLockMS");
			}
		} catch(Exception ex) {
			Log.d(TAG,"onStartCommand versionName ex="+ex);
		}

		/*
		final NetworkRequest requestForCellular =
			new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build();
		final NetworkRequest requestForWifi =
			new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
		final NetworkRequest requestForEthernet =
			new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build();
		final NetworkRequest requestForUSB =
			new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_USB).build();
		*/
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // >=api24
			// networkCallback code fully replaces checkNetworkState()
			myNetworkCallback = new ConnectivityManager.NetworkCallback() {

				//TODO: onAvailable() is doing nothing currently
				@Override
				public void onAvailable(Network network) {
					super.onAvailable(network);
					if(network!=null) {
						NetworkInfo netInfo = connectivityManager.getNetworkInfo(network);
						if(netInfo != null) {
							// getType() 1=wifi, 0=mobile (extra="internet.eplus.de")
							Log.d(TAG,"networkCallback onAvailable avail="+netInfo.getType()+" "+netInfo.getExtraInfo());
/*
// TODO is bindProcessToNetwork() supported on Build.VERSION_CODES.N (api24) or Build.VERSION_CODES.M (api25) ?
//							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
								// no matter what network type: bind our process to it
								// ALL networking sockets are bound to that network until bindProcessToNetwork(null)

// TODO either we un-bind it in onLost(), or we make sure we only bind once!
//								connectivityManager.bindProcessToNetwork(network);
//							}
// TODO do we need to bindProcessToNetwork(null) on unregister?
*/

							//need to be connected to server to do this?
							//runJS("newPeerCon();",null);
							//runJS("triggerOnIceCandidates();",null);
						} else {
							Log.d(TAG,"# networkCallback onAvailable netInfo==null");
						}
					} else {
						Log.d(TAG,"# networkCallback onAvailable network==null");
					}
				}

				@Override
				public void onLost(Network network) {
			        super.onLost(network);
					haveNetworkInt = -1;
					if(network!=null) {
						Log.d(TAG,"networkCallback onLost conWant="+connectToServerIsWanted);
						// check the type of network lost...
						NetworkInfo netInfo = connectivityManager.getNetworkInfo(network);
						if(netInfo != null) {
							if(netInfo.getType() == ConnectivityManager.TYPE_WIFI) {  // TYPE_WIFI==1
								Log.d(TAG,"networkCallback onLost wifi "+netInfo.getExtraInfo());
								statusMessage("Wifi lost",-1,true,false);
							} else {
								Log.d(TAG,"networkCallback onLost other "+netInfo.getType()+" "+netInfo.getExtraInfo());
								statusMessage("Network lost",-1,true,false);
							}
						} else {
							Log.d(TAG,"networkCallback onLost netInfo==null");
							if(haveNetworkInt==2) {
								statusMessage("Wifi lost",-1,true,false);
							} else {
								statusMessage("Network lost",-1,true,false);
							}
						}
					} else {
						Log.d(TAG,"networkCallback onLost netInfo==null");
						if(haveNetworkInt==2) {
							statusMessage("Wifi lost",-1,true,false);
						} else {
							statusMessage("Network lost",-1,true,false);
						}
					}
					if(wifiLock!=null && wifiLock.isHeld()) {
						Log.d(TAG,"networkCallback onLost wifiLock.release");
						wifiLock.release();
					}
					haveNetworkInt = 0;
					// note: onCapabilitiesChanged will not be called (on SDK <= 25)

					// if connected, do disconnect
					if(wsClient!=null) {
						// note: this may cause: onClose code=1000
						Log.d(TAG,"networkCallback onLost close old connection");
						wsClient.close();
						wsClient = null;
					}
					// note: reconnector will be automatically re-started when some network returns
				}

				@Override
				public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabi) {
					// comes only after onAvailable(), not after onLost()
					// this is why we wifiLock.release() in onLost()
					lock.lock();
					int newNetworkInt = 0;
					if(networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
						newNetworkInt = 1;
					} else if(networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
						newNetworkInt = 2;
					} else if(networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
							networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_USB)) {
						newNetworkInt = 3;
					}

					if(newNetworkInt!=haveNetworkInt) {
						Log.d(TAG,"networkCallback capab change: " + haveNetworkInt+" "+newNetworkInt+" "+
							" conWanted="+connectToServerIsWanted+
							" wsCon="+(wsClient!=null)+
							" wifi="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)+
							" cell="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)+
							" ether="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)+
							" vpn="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_VPN)+
							" wifiAw="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)+
							" usb="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_USB));
					}

					if(newNetworkInt!=2 && haveNetworkInt==2) {
						// lost wifi
						if(wifiLock!=null && wifiLock.isHeld()) {
							// release wifi lock
							wifiLock.release();
							Log.d(TAG,"networkCallback wifi->other wifiLock.release");
						}
					}
					boolean mustReconnectOnNetworkChange = false;
					if(newNetworkInt==1 && haveNetworkInt!=1) {
						// gained mobile
						if(connectToServerIsWanted) {
							statusMessage("Using mobile network",-1,false,false);
							mustReconnectOnNetworkChange = true;
						} else {
							Log.d(TAG,"networkCallback mobile but conWant==false");
						}
					}
					if(newNetworkInt==2 && haveNetworkInt!=2) {
						// gained wifi
						// lock wifi if required
						if(setWifiLockMode<=0) {
							// prefer wifi not enabled by user
							Log.d(TAG,"networkCallback gainWifi WifiLockMode off");
						} else if(wifiLock==null) {
							Log.d(TAG,"# networkCallback gainWifi wifiLock==null");
						} else if(wifiLock.isHeld()) {
							Log.d(TAG,"networkCallback gainWifi wifiLock isHeld already");
						} else {
							// enable wifi lock
							Log.d(TAG,"networkCallback gainWifi wifiLock.acquire");
							wifiLock.acquire();
						}

						if(connectToServerIsWanted) {
							statusMessage("Using Wifi network",-1,false,false);
							mustReconnectOnNetworkChange = true;
						} else {
							Log.d(TAG,"networkCallback gainWifi but conWant==false");
						}
					}
					if(newNetworkInt==3 && haveNetworkInt!=3) {
						// gained other net
						if(connectToServerIsWanted) {
							statusMessage("Using other network",-1,false,false);
							mustReconnectOnNetworkChange = true;
						} else {
							Log.d(TAG,"networkCallback gainOther but conWant==false");
						}
					}

					// gained new network: start reconnecter	// TODO sure?
					if(mustReconnectOnNetworkChange && connectToServerIsWanted && !reconnectBusy) {
						// call scheduler.schedule()
						if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
							Log.d(TAG,"networkCallback keepAwakeWakeLock.acquire");
							keepAwakeWakeLock.acquire(3 * 60 * 1000);
							keepAwakeWakeLockStartTime = (new Date()).getTime();
						}
						// set haveNetworkInt before scheduler in case scheduler starts reconnecter immediately
						haveNetworkInt = newNetworkInt;

						if(!reconnectBusy) {
							if(newNetworkInt==2) {
								statusMessage("Reconnect Wifi...",-1,true,false);
							} else if(newNetworkInt==1) {
								statusMessage("Reconnect Mobile...",-1,true,false);
							} else {
								statusMessage("Reconnect other...",-1,true,false);
							}
							if(wsClient!=null) {
								// disconnect old connection to avoid server re-login denial ("already/still logged in")
								// note: this will cause: onClose code=1000
								Log.d(TAG,"networkCallback disconnect old connection");
								wsClient.close();
								wsClient = null;
							}
							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								// why wait for the scheduled reconnecter job
								// let's cancel it and start in 3s from now
								// (so that the server has enough time to detect the disconnect)
								Log.d(TAG,"networkCallback cancel reconnectSchedFuture");
								if(reconnectSchedFuture.cancel(false)) {
									// next run reconnecter
									Log.d(TAG,"networkCallback restart reconnecter in 3s");
									reconnectSchedFuture = scheduler.schedule(reconnecter, 3 ,TimeUnit.SECONDS);
								}
							} else {
								Log.d(TAG,"networkCallback start reconnecter in 3s");
								reconnectSchedFuture = scheduler.schedule(reconnecter, 3, TimeUnit.SECONDS);
							}
						} else {
							Log.d(TAG,"networkCallback no reconnecter: reconnectBusy="+reconnectBusy);
						}
					}
					haveNetworkInt = newNetworkInt;
					lock.unlock();
				}

				//@Override
				//public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
				//	Log.d(TAG, "The default network changed link properties: " + linkProperties);
				//}
			};

			Log.d(TAG, "networkCallback init registerDefaultNetworkCallback");
			connectivityManager.registerDefaultNetworkCallback(myNetworkCallback);
			/*
			Log.d(TAG, "networkCallback init requestForCellular");
			connectivityManager.requestNetwork(requestForCellular, myNetworkCallback);

			Log.d(TAG, "networkCallback init requestForWifi");
			connectivityManager.requestNetwork(requestForWifi, myNetworkCallback);

			Log.d(TAG, "networkCallback init requestForEthernet");
			connectivityManager.requestNetwork(requestForEthernet, myNetworkCallback);

			Log.d(TAG, "networkCallback init requestForUSB");
			connectivityManager.requestNetwork(requestForUSB, myNetworkCallback);
			*/
		} else {
			// SDK_INT < Build.VERSION_CODES.N) // <api24
			checkNetworkState(false);
			if(networkStateReceiver==null) {
				networkStateReceiver = new BroadcastReceiver() {
					@Override
					public void onReceive(Context context, Intent intent) {
						if(extendedLogsFlag) {
							Log.d(TAG,"networkStateReceiver");
						}
						checkNetworkState(true);
					}
				};
				registerReceiver(networkStateReceiver,
					new IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
			}
			if(networkStateReceiver==null) {
				Log.d(TAG,"fatal: cannot create networkStateReceiver");
				return 0;
			}

/*
			ConnectivityManager.NetworkCallback cbWifi = new ConnectivityManager.NetworkCallback() {
				@Override
				public void onAvailable(Network network) {
					Log.d(TAG, "networkCallback onAvailable bind cbWifi");
// TODO is bindProcessToNetwork() supported if SDK_INT < Build.VERSION_CODES.N) // <api24
					connectivityManager.bindProcessToNetwork(network);

// TODO do we need to bindProcessToNetwork(null) on unregister?
				}
			};

			ConnectivityManager.NetworkCallback cbCellular = new ConnectivityManager.NetworkCallback() {
				@Override
				public void onAvailable(Network network) {
					Log.d(TAG, "networkCallback onAvailable bind cbCellular");
// TODO is bindProcessToNetwork() supported if SDK_INT < Build.VERSION_CODES.N) // <api24
					connectivityManager.bindProcessToNetwork(network);

// TODO do we need to bindProcessToNetwork(null) on unregister?
				}
			};
			Log.d(TAG, "networkCallback init requestNetwork cbWifi");
			connectivityManager.requestNetwork(requestForWifi, cbWifi);

			Log.d(TAG, "networkCallback init requestNetwork cbCellular");
			connectivityManager.requestNetwork(requestForCellular, cbCellular);
*/
		}


		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if(dozeStateReceiver==null) {
				dozeStateReceiver = new BroadcastReceiver() {
					//@RequiresApi(api = Build.VERSION_CODES.M)
					@Override public void onReceive(Context context, Intent intent) {
						// NOTE: when dozeStateReceiver strikes, we have already lost (or are about
						// to lose) our connection. dozeState is being activated BECAUSE without a
						// connected network, there is no need to keep the process interactive.

						if(powerManager.isDeviceIdleMode()) {
						    // the device is now in doze mode
							dozeIdle = true;
							Log.d(TAG,"dozeState idle");
							if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
								Log.d(TAG,"dozeState idle keepAwakeWakeLock.acquire");
								// stay awake 2s to defend against doze
								keepAwakeWakeLock.acquire(2000);
								keepAwakeWakeLockMS += 2000;
								storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
								keepAwakeWakeLockStartTime = (new Date()).getTime();
							}
							// this is a good situation to send a ping
							// if the connection is bad we will know much quicker
							if(wsClient!=null) {
								try {
									Log.d(TAG,"dozeState idle sendPing");
									wsClient.sendPing();
								} catch(Exception ex) {
									Log.d(TAG,"dozeState idle sendPing ex="+ex);
									wsClient = null;
								}
							}
							if(wsClient==null && connectToServerIsWanted) {
								// let's go straight to reconnecter
								statusMessage("Disconnected from WebCall server...",-1,true,false);

								if(reconnectSchedFuture==null && !reconnectBusy) {
									// if no reconnecter is scheduled at this time...
									// schedule a new reconnecter right away
									setLoginUrl();
									if(loginUrl!="") {
										Log.d(TAG,"dozeState idle re-login now url="+loginUrl);
										// hopefully network is avilable
										reconnectSchedFuture =
											scheduler.schedule(reconnecter,0,TimeUnit.SECONDS);
									}

								} else {
									Log.d(TAG,"dozeState idle no reconnecter: reconnectBusy="+reconnectBusy);
								}
							}

						} else if(powerManager.isInteractive()) {
							// the device just woke up from doze mode
							// most likely it will go to idle in about 30s
							boolean screenOn = isScreenOn();
							Log.d(TAG,"dozeState awake screenOn="+screenOn+" doze="+dozeIdle);
							dozeIdle = false;
							if(screenOn) {
								return;
							}

							if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
								Log.d(TAG,"dozeState awake keepAwakeWakeLock.acquire");
								// stay awake 2s to defend against doze
								keepAwakeWakeLock.acquire(2000);
								keepAwakeWakeLockMS += 2000;
								storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
								keepAwakeWakeLockStartTime = (new Date()).getTime();
							}

							wakeUpOnLoopCount(context);	// why ???

							if(wsClient!=null) {
								// close a prev connection
								Log.d(TAG,"dozeState awake wsClient.closeBlocking()...");
								WebSocketClient tmpWsClient = wsClient;
								wsClient = null;
								try {
									tmpWsClient.closeBlocking();
								} catch(Exception ex) {
									Log.d(TAG,"dozeState awake closeBlocking ex="+ex);
								}
							} else {
								Log.d(TAG,"dozeState awake wsClient==null");
							}

							if(reconnectSchedFuture==null && !reconnectBusy) {
								// if no reconnecter is scheduled at this time (by checkLastPing())
								// then schedule a new reconnecter
								// in 8s to give server some time to detect the discon
								setLoginUrl();
								if(loginUrl!="") {
									Log.d(TAG,"dozeState awake re-login in 2s url="+loginUrl);
									// hopefully network is avilable
									reconnectSchedFuture =
										scheduler.schedule(reconnecter,2,TimeUnit.SECONDS);
								}
							} else {
								Log.d(TAG,"dozeState awake no reconnecter: reconnectBusy="+reconnectBusy);
							}

						} else if(powerManager.isPowerSaveMode()) {
							// dozeIdle = ??? this never comes
							Log.d(TAG,"dozeState powerSave mode");
						}
					}
				};
				registerReceiver(dozeStateReceiver,
					new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
			}
		}

		if(wsClient!=null) {
			activityWasDiscarded = true;
			Log.d(TAG,"onStartCommand got existing wsClient "+activityWasDiscarded);
			// probably activity was discarded, got restarted, and now we see service is still connected
			//storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
		} else if(reconnectBusy) {
			Log.d(TAG,"onStartCommand got reconnectBusy");
			// TODO not sure about this
			storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
		} else {
/*
// TODO delete code: this was moved to connectHost() (on success)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
				// create notificationChannel to start service in foreground
				Log.d(TAG,"onStartCommand startForeground");
				startForeground(NOTIF_ID,buildFgServiceNotification("","",false));
			}
*/
			String webcalldomain = null;
			String username = null;
			try {
				webcalldomain = prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
				Log.d(TAG,"onStartCommand webcalldomain="+webcalldomain);
			} catch(Exception ex) {
				Log.d(TAG,"onStartCommand webcalldomain ex="+ex);
			}
			try {
				username = prefs.getString("username", "");
				Log.d(TAG,"onStartCommand username="+username);
			} catch(Exception ex) {
				Log.d(TAG,"onStartCommand username ex="+ex);
			}

			if(webcalldomain==null || webcalldomain.equals("")) {
				Log.d(TAG,"onStartCommand webcalldomain undefined");
				storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
			} else if(username==null || username.equals("")) {
				Log.d(TAG,"onStartCommand username undefined");
				storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
			} else {
				setLoginUrl();
				Log.d(TAG,"onStartCommand loginUrl="+loginUrl);
				boolean autoCalleeConnect = false;
				if(onStartIntent==null) {
					Log.d(TAG,"onStartCommand onStartIntent==null");
					// service restart after crash
					// autoCalleeConnect to webcall server if extraCommand.equals("connect")
					try {
						boolean connectWanted = prefs.getBoolean("connectWanted", false);
							//Log.d(TAG,"onStartCommand connectWanted force false");
							// connectWanted = false
						Log.d(TAG,"onStartCommand connectWanted="+connectWanted);
						autoCalleeConnect = connectWanted;
					} catch(Exception ex) {
						Log.d(TAG,"onStartCommand connectWanted ex="+ex);
					}
				} else {
					Log.d(TAG,"onStartCommand onStartIntent!=null");
					// let's see if service was started by boot...
					Bundle extras = onStartIntent.getExtras();
					if(extras==null) {
						Log.d(TAG,"onStartCommand extras==null");
						storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
					} else {
						String extraCommand = extras.getString("onstart");
						if(extraCommand==null) {
							Log.d(TAG,"onStartCommand extraCommand==null");
							storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
						} else {
							// service was started by boot (by WebCallServiceReceiver.java)
							if(!extraCommand.equals("") && !extraCommand.equals("donothing")) {
								Log.d(TAG,"onStartCommand extraCommand="+extraCommand);
							}
							if(extraCommand.equals("connect")) {
								// auto-connect (login) to webcall server is requested
								autoCalleeConnect = true;
							}
						}
					}
				}

				if(!autoCalleeConnect) {
					Log.d(TAG,"onStartCommand no autoCalleeConnect");
					connectToServerIsWanted = false;
					storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
					// autoCalleeConnect is only being used on boot and for restart of a crashed service
					// in all other cases the connection will be triggered by the activity
				} else {
					// this is like the user clicking goOnline
					if(reconnectBusy) {
						Log.d(TAG,"onStartCommand autoCalleeConnect but reconnectBusy");
					} else {
						connectToServerIsWanted = true;
						storePrefsBoolean("connectWanted",true); // used in case of service crash + restart
						Log.d(TAG,"onStartCommand autoCalleeConnect loginUrl="+loginUrl);
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							Log.d(TAG,"onStartCommand autoCalleeConnect cancel reconnectSchedFuture");
							reconnectSchedFuture.cancel(false);
							reconnectSchedFuture = null;
						}
						// NOTE: if we wait less than 15secs, our connection may establish
						// but will then be quickly disconnected - not sure why
						//statusMessage("onStartCommand autoCalleeConnect schedule reconnecter",false,false);
						reconnectSchedFuture = scheduler.schedule(reconnecter, 16, TimeUnit.SECONDS);
					}
				}
			}
		}
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		Log.d(TAG, "onDestroy");
		if(alarmReceiver!=null) {
			unregisterReceiver(alarmReceiver);
			alarmReceiver = null;
		}
		if(powerConnectionReceiver!=null) {
			unregisterReceiver(powerConnectionReceiver);
			powerConnectionReceiver = null;
		}
		if(networkStateReceiver!=null) {
			unregisterReceiver(networkStateReceiver);
			networkStateReceiver = null;
		}
		if(dozeStateReceiver!=null) {
			unregisterReceiver(dozeStateReceiver);
			dozeStateReceiver = null;
		}
		if(serviceCmdReceiver!=null) {
			// java.lang.IllegalArgumentException: Receiver not registered: timur.webcall.callee.WebCallService
			unregisterReceiver(serviceCmdReceiver);
			serviceCmdReceiver = null;
		}
		if(connectivityManager!=null && myNetworkCallback!=null) {
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // >=api24
				connectivityManager.unregisterNetworkCallback(myNetworkCallback);
				myNetworkCallback = null;
			}
		}
		if(mBinder!=null) {
			mBinder = null;
		}
		// TODO android10+: remove foreground service icon
	}

	@Override
	public boolean onUnbind(Intent intent) {
		Log.d(TAG, "onUnbind");
		webviewMainPageLoaded=false;
		return true; // true: call onRebind(Intent) later when new clients bind
	}

	@Override
	public void onTrimMemory(int level) {
		Log.d(TAG, "onTrimMemory level="+level);
		super.onTrimMemory(level);
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		// activity killed, service still alive
		super.onTaskRemoved(rootIntent);
		Log.d(TAG, "onTaskRemoved");
		webviewMainPageLoaded=false;
		webSettings = null;
		webviewCookies = null;
		//webCallJSInterface = null;
		//webCallJSInterfaceMini = null;
		if(myWebView!=null) {
			Log.d(TAG, "onTaskRemoved close webView");
			myWebView.destroy();
			myWebView = null;
		}
		currentUrl=null;
		activityVisible=false;
		calleeIsReady=false;
		System.gc();
	}


	// section 2: class WebCallServiceBinder with exposed methodes: 
	//     startWebView(), webcallConnectType(), wakeupType(), callInProgress(),
	//     activityDestroyed(), getCurrentUrl(), runJScode(), fileSelect()
	//   WebViewClient(): to overrided webView methods:
	//     shouldOverrideUrlLoading(), onPageFinished()
	//   DownloadListener(): makes blob:-urls become downloadable
	@SuppressLint("SetJavaScriptEnabled")
	class WebCallServiceBinder extends Binder {
		public void startWebView(View view) {
			String username = prefs.getString("username", "");
			Log.d(TAG, "startWebView creating myWebView for user="+username);

			myWebView = (WebView)view;

			webSettings = myWebView.getSettings();
			userAgentString = webSettings.getUserAgentString();
			Log.d(TAG, "startWebView ua="+userAgentString);

			webSettings.setJavaScriptEnabled(true);
			webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
			webSettings.setAllowFileAccessFromFileURLs(true);
			webSettings.setAllowFileAccess(true);
			webSettings.setAllowUniversalAccessFromFileURLs(true);
			webSettings.setMediaPlaybackRequiresUserGesture(false);
			webSettings.setDomStorageEnabled(true);
			webSettings.setAllowContentAccess(true);
			//Log.d(TAG, "done webSettings "+webSettings.getSaveFormData());

			myWebView.setDownloadListener(new DownloadListener() {
                @Override
                public void onDownloadStart(String url, String userAgent,
						String contentDisposition, String mimetype, long contentLength) {
					Log.d(TAG,"DownloadListener url="+url+" mime="+mimetype);
					if(url.startsWith("blob:")) {
						// this is for "downloading" files to disk, that were previously received from peer
						String fetchBlobJS =
							"javascript: var xhr=new XMLHttpRequest();" +
							"xhr.open('GET', '"+url+"', true);" +
							//"xhr.setRequestHeader('Content-type','application/vnd...;charset=UTF-8');" +
							"xhr.responseType = 'blob';" +
							"xhr.onload = function(e) {" +
							"    if (this.status == 200) {" +
							"        var blob = this.response;" +
							"        var reader = new FileReader();" +
							"        reader.readAsDataURL(blob);" +
							"        reader.onloadend = function() {" +
							"            base64data = reader.result;" +
							"            let aElements =document.querySelectorAll(\"a[href='"+url+"']\");"+
							"            if(aElements[0]) {" +
							//"                console.log('aElement='+aElements[0]);" +
							"                let filename = aElements[0].download;" +
							"                console.log('filename='+filename);" +
							"                Android.getBase64FromBlobData(base64data,filename);" +
							"            }" +
							"        };" +
							"    } else {" +
							"        console.log('this.status not 200='+this.status);" +
							"    }" +
							"};" +
							"xhr.send();";
						Log.d(TAG,"DownloadListener fetchBlobJS="+fetchBlobJS);
						myWebView.loadUrl(fetchBlobJS);
						// file will be stored in getBase64FromBlobData()
					} else {
						Intent intent = new Intent("webcall");
						intent.putExtra("filedownload", url);
						intent.putExtra("mimetype", mimetype);
						intent.putExtra("useragent", userAgent);
						//intent.putExtra("contentDisposition", contentDisposition);
						sendBroadcast(intent);
					}
				}
			});

			myWebView.setWebViewClient(new WebViewClient() {
				//@Override
				//public void onLoadResource(WebView view, String url) {
				//	if(url.indexOf("googleapis.com/")>=0 || url.indexOf("google-analytics.com/")>=0) {
				//		Log.d(TAG, "onLoadResource deny: " + url);
				//		return;
				//	}
				//	Log.d(TAG, "onLoadResource: " + url);
				//	super.onLoadResource(view, url);
				//}

				@SuppressWarnings("deprecation")
				@Override
				public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
					// must tell user
					Intent intent = new Intent("webcall");
					if(errorCode==ERROR_HOST_LOOKUP) {
						Log.d(TAG, "# onReceivedError HOST_LOOKUP "+description+" "+failingUrl);
						intent.putExtra("toast", "No Network?");
					} else if(errorCode==ERROR_UNKNOWN) {
						Log.d(TAG, "# onReceivedError UNKNOWN "+description+" "+failingUrl);
// TODO maybe this should not generate a toast
// "# onReceivedError UNKNOWN net::ERR_FAILED https://timur.mobi/callee/1980-phone-ringing.mp3"
//						intent.putExtra("toast", "Network error "+description);
					} else {
						Log.d(TAG, "# onReceivedError code="+errorCode+" "+description+" "+failingUrl);
						intent.putExtra("toast", "Error "+errorCode+" "+description);
					}
					sendBroadcast(intent);
				}

				@TargetApi(android.os.Build.VERSION_CODES.M)
				public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
					super.onReceivedError(view, req, err);
					// forward to old method (above)
					onReceivedError(view, err.getErrorCode(), err.getDescription().toString(),
						req.getUrl().toString());
				}

				@Override
				public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
					// this is called when webview does a https PAGE request and fails
					// error.getPrimaryError()
					// -1 = no error
					// 0 = not yet valid
					// 1 = SSL_EXPIRED
					// 2 = SSL_IDMISMATCH  certificate Hostname mismatch
					// 3 = SSL_UNTRUSTED   certificate authority is not trusted
					// 5 = SSL_INVALID
					// primary error: 3 certificate: Issued to: O=Internet Widgits Pty Ltd,ST=Some-State,C=AU;
					// Issued by: O=Internet Widgits Pty Ltd,ST=Some-State,C=AU;
					//  on URL: https://192.168.3.209:8068/callee/Timur?auto=1

					// TODO do only proceed if confirmed by the user
					// however, if we do proceed here, wsOpen -> connectHost(wss://...) will fail
					//   with onError ex javax.net.ssl.SSLHandshakeException
					if(insecureTlsFlag) {
						Log.d(TAG, "onReceivedSslError (proceed) "+error);
						handler.proceed();
					} else {
						// err can not be ignored
						// but this ssl error does not return an err in JS
						Log.d(TAG, "# onReceivedSslError "+error);
						super.onReceivedSslError(view, handler, error);
					}
				}

				//@Override
				//public void onReceivedClientCertRequest(WebView view, final ClientCertRequest request) {
				//	Log.d(TAG, "onReceivedClientCertRequest "+request);
				//	request.proceed(mPrivateKey, mCertificates);
				//}

				@SuppressWarnings("deprecation")
				@Override
				public boolean shouldOverrideUrlLoading(WebView view, String url) {
					final Uri uri = Uri.parse(url);
					Log.d(TAG, "shouldOverrideUrl "+url);
					return handleUri(uri);
				}

				//@TargetApi(Build.VERSION_CODES.N)
				@Override
				public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
					final Uri uri = request.getUrl();
					boolean override = handleUri(uri);
					Log.d(TAG, "shouldOverrideUrlL="+uri+" override="+override);
					return override;
				}

				private boolean handleUri(final Uri uri) {
					//Log.i(TAG, "handleUri " + uri);
					//final String host = uri.getHost();
					//final String scheme = uri.getScheme();
					final String path = uri.getPath();
					if(extendedLogsFlag) {
						Log.d(TAG, "handleUri path="+path+" scheme="+uri.getScheme());
					}

					if(path.startsWith("/webcall/update") && webviewMainPageLoaded) {
						// load update page into iframe
						String urlStr = uri.toString();
						// urlStr MUST NOT contain apostrophe
						String encodedUrl = urlStr.replace("'", "&#39;");
						Log.d(TAG, "open url ("+encodedUrl+") in iframe");
						String jsString = "openNews(\""+encodedUrl+"\")";
						Log.d(TAG, "runJS("+jsString+")");
						runJS(jsString,null);
						return true; // do not load this url into webview

					} else if(uri.getScheme().startsWith("file") ||
						(uri.getScheme().startsWith("http") && path.indexOf("/callee/")>=0)) {
						// "file:" and "http*://(anydomain)/callee/*" urls are processed in webview1
						// continue below

					} else {
						// uri NOT for webview1: ask activity to forward to ext browser (or our intent-filter)
						Log.i(TAG, "handleUri uri not for webview1; broadcast 'browse' to activity ("+uri+")");
						Intent intent = new Intent("webcall");
						intent.putExtra("browse", uri.toString());
						sendBroadcast(intent);
						return true; // do not load this url into webview
					}

					String username = prefs.getString("username", "");
					if(extendedLogsFlag) {
						Log.d(TAG, "handleUri username=("+username+")");
					}
					if(username==null || username.equals("")) {
						// the username is not stored in the prefs (or it is empty string)
						Log.d(TAG, "handleUri empty prefs username=("+username+")");
						// get username from callee-URL
						int idxCallee = path.indexOf("/callee/");
						if(idxCallee>=0) {
							username = path.substring(idxCallee+8);
							if(!username.startsWith("register") && username.indexOf("/")<0) {
								// store username from callee-URL into the prefs
								Log.d(TAG, "handleUri store username=("+username+")");
								storePrefsString("username",username);
							}
						}
					}
					return false; // continue to load this url
				}

				@Override
				public void onPageFinished(WebView view, String url){
					// Notify the host application that a page has finished loading
					//Log.d(TAG, "onPageFinished url=" + url);
					// first we want to know if url is just a hashchange
					// in which case we will NOT do anyhing special
					if(currentUrl!=null && webviewMainPageLoaded) {
						//Log.d(TAG, "onPageFinished currentUrl=" + currentUrl);
						// for onPageFinished we need currentUrl WITHOUT hash
						// for getCurrentUrl() called by activity onBackPressed()
						//   currentUrl must contain the full current url (WITH hash)
						// so here we create 'baseCurrentUrl' without hash for comparing
						// but we always keep the current url in currentUrl
						String baseCurrentUrl = currentUrl;
						int idxHash = baseCurrentUrl.indexOf("#");
						if(idxHash>=0) {
							baseCurrentUrl = baseCurrentUrl.substring(0,idxHash);
						}
						int idxArgs = baseCurrentUrl.indexOf("?");
						if(idxArgs>=0) {
							baseCurrentUrl = baseCurrentUrl.substring(0,idxArgs);
						}
						//Log.d(TAG, "onPageFinished baseCurrentUrl=" + baseCurrentUrl);
						if(url.startsWith(baseCurrentUrl)) {
							// url is just a hashchange; does not need onPageFinished processing
							// no need to execute onPageFinished() on hashchange or history back
							// here we cut off "auto=1"
							currentUrl = url.replace("?auto=1","");
							Log.d(TAG, "onPageFinished only hashchange=" + currentUrl);
							return;
						}
					}

					// if the url has changed (beyond a hashchange)
					// and if we ARE connected already -> call js:wakeGoOnline()
					// here we cut off "auto=1"
					currentUrl = url.replace("?auto=1","");
					Log.d(TAG, "onPageFinished currentUrl=" + currentUrl);
					//webviewMainPageLoaded = false;
					webviewCookies = CookieManager.getInstance().getCookie(currentUrl);
					//Log.d(TAG, "onPageFinished webviewCookies=" + webviewCookies);
					if(webviewCookies!=null) {
						storePrefsString("cookies", webviewCookies);
					}

					// TODO tmtmtm auch "/callee/mastodon" excluden?
					if(url.indexOf("/callee/")>=0 && url.indexOf("/callee/register")<0) {
						// webview has just finished loading the callee main page
						webviewMainPageLoaded = true;
						Intent brintent = new Intent("webcall");
						brintent.putExtra("state", "mainpage");
						sendBroadcast(brintent);

						if(wsClient==null) {
							Log.d(TAG, "onPageFinished main page not yet connected to server");
							// we now await "connectHost hostVerify Success net=x" ...
						} else {
							Log.d(TAG, "onPageFinished main page: already connected to server");
							// we are already connected to server (probably from before activity started)
							// we have to bring the just loaded callee.js online, too
							// wakeGoOnlineNoInit() makes sure:
							// - js:wsConn is set (to wsClient)
							// - UI in online state (green led + goOfflineButton enabled)
							// - does NOT send init
							// once this is done we can start processWebRtcMessages()
							runJS("wakeGoOnlineNoInit()", new ValueCallback<String>() {
								@Override
								public void onReceiveValue(String s) {
									Log.d(TAG,"onPageFinished main page: broadcast state connected");
									Intent brintent = new Intent("webcall");
									brintent.putExtra("state", "connected");
									sendBroadcast(brintent);

									if(calleeIsReady) { // gotStream2() -> calleeReady() did already happen
										// schedule delayed processWebRtcMessages()
										final Runnable runnable2 = new Runnable() {
											public void run() {
												if(calleeIsReady) {
													Log.d(TAG,"onPageFinished main page processWebRtcMessages");
													processWebRtcMessages();
												}
											}
										};
										scheduler.schedule(runnable2, 100l, TimeUnit.MILLISECONDS);
									} else {
										// processWebRtcMessages() will be called from calleeReady()
									}
								}
							});
						}
					} else {
						// this is NOT the callee main page
					}
				}
			});

			myWebView.setWebChromeClient(new WebChromeClient() {
				@Override
				public boolean onConsoleMessage(ConsoleMessage cm) {
					String msg = cm.message();
					if(!msg.startsWith("showStatus")) {
						// TODO msg can be very long
						Log.d(TAG,"con: "+msg + " L"+cm.lineNumber());
					}
					if(msg.equals("Uncaught ReferenceError: goOnline is not defined")) {
						if(wsClient==null) {
							// error loading callee page: most likely this is a domain name error
							// from base page - lets go back to base page
							//TODO also: if domain name is OK, but there is no network
							myWebView.loadUrl("file:///android_asset/index.html", null);
							return true;
						}
					}
					// TODO other "Uncaught Reference" may occure
					// "Uncaught ReferenceError: gentle is not defined" L1736

					if(msg.startsWith("showNumberForm pos")) {
						// showNumberForm pos 95.0390625 52.1953125 155.5859375 83.7421875 L1590
						String floatString = msg.substring(19).trim();
						//Log.d(TAG, "emulate tap floatString="+floatString);
						Intent intent = new Intent("webcall");
						intent.putExtra("simulateClick", floatString);
						sendBroadcast(intent);
					}
					return true;
				}

				@Override
				public void onPermissionRequest(PermissionRequest request) {
					String[] strArray = request.getResources();
					for(int i=0; i<strArray.length; i++) {
						Log.i(TAG, "onPermissionRequest "+i+" ("+strArray[i]+")");
						// we only grant the permission we want to grant
						if(strArray[i].equals("android.webkit.resource.AUDIO_CAPTURE") ||
						   strArray[i].equals("android.webkit.resource.VIDEO_CAPTURE")) {
							request.grant(strArray);
							break;
						}
						Log.w(TAG, "onPermissionRequest unexpected "+strArray[i]);
					}
				}

				@Override
				public Bitmap getDefaultVideoPoster() {
					// this replaces android's ugly default video poster with a dark grey background
					final Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565);
					Canvas canvas = new Canvas(bitmap);
					canvas.drawARGB(200, 2, 2, 2);
					return bitmap;
				}

				// handling input[type="file"] requests for android API 21+
				@Override
				public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, 
						FileChooserParams fileChooserParams) {
					// ValueCallback filePath will be set from fileSelect()
					filePath = filePathCallback;
					Log.d(TAG, "onShowFileChooser filePath="+filePath+" (from input[type='file'])");

					// tell activity to open file selector
					Intent intent = new Intent("webcall");
					intent.putExtra("forResults", "x"); // value is not relevant
					sendBroadcast(intent);
					// -> activity broadcastReceiver -> startActivityForResult() ->
					//    onActivityResult() -> fileSelect(results)
					return true;
				}
			});

			// let JS call java service code
//			webCallJSInterface = new WebCallJSInterface();
			myWebView.addJavascriptInterface(webCallJSInterface, "Android");

			// render base page - or main page if we are connected already
			currentUrl = "file:///android_asset/index.html";
			if(wsClient!=null) {
				username = prefs.getString("username", "");
				String webcalldomain =
					prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
				if(webcalldomain.equals("")) {
					Log.d(TAG,"onClose cannot reconnect: webcalldomain is not set");
				} else if(username.equals("")) {
					Log.d(TAG,"onClose cannot reconnect: username is not set");
				} else {
					currentUrl = "https://"+webcalldomain+"/callee/"+username;
				}
			}
			Log.d(TAG, "startWebView load currentUrl="+currentUrl);
			myWebView.loadUrl(currentUrl);

			Log.d(TAG, "startWebView version "+getWebviewVersion());
		}

		// webcallConnectType returns >0 if we are connected to webcall server signalling
		public int webcallConnectType() {
			if(reconnectBusy) {
	  			// while service is in the process of reconnecting, webcallConnectType() will return 3
				// this will prevent the activity to destroy itself and open the base-page on restart
				Log.d(TAG, "webcallConnectType ret 3 (reconnectBusy)");
				return 3; // reconnecting
			}
			if(wsClient!=null) {
				Log.d(TAG, "webcallConnectType return 1 (connected)");
				return 1;
			}
			// service is NOT connected to webcall server, webcallConnectType() will return 0
			Log.d(TAG, "webcallConnectType ret 0 (wsClient==null)");
			return 0;
		}

		// callInProgress() returns >0 when there is an incoming call (ringing) or the device is in-a-call
		public int callInProgress() {
			int ret = 0;
			if(callPickedUpFlag) {
				ret = 1; // waiting for full mediaConnect
			}
			if(peerConnectFlag) {
				ret = 2; // call in progress / mediaConnect
			}
			if(ret>0) {
				Log.d(TAG, "callInProgress ret="+ret+" pickedUp="+callPickedUpFlag+" peerConnect="+peerConnectFlag);
			} else {
				Log.d(TAG, "callInProgress no! pickedUp="+callPickedUpFlag+" peerConnect="+peerConnectFlag);
			}
			return ret;
		}

		public int haveNetwork() {
			return haveNetworkInt;
		}

		public void setProximity(boolean flagNear) {
			if(audioManager.isWiredHeadsetOn()) {
				Log.d(TAG, "setProximity near="+flagNear+" skip isWiredHeadsetOn");
			} else if(audioManager.isBluetoothA2dpOn()) {
				Log.d(TAG, "setProximity near="+flagNear+" skip isBluetoothA2dpOn");
			} else {
				Log.d(TAG, "setProximity near="+flagNear+" last="+proximityNear);
				if(flagNear) {
					if(proximityNear!=1) {
						// user is now holding device CLOSE TO HEAD
						//Log.d(TAG, "setProximity() near, speakerphone=false");
						Log.d(TAG, "setProximity near="+flagNear);
						audioManager.setMode(AudioManager.MODE_IN_CALL);
						audioManager.setSpeakerphoneOn(false); // deactivates speakerphone on Gn
						proximityNear = 1;
					}
				} else {
					// not near
					if(proximityNear!=0) {
						// user is now now holding device AWAY FROM HEAD
						//Log.d(TAG, "setProximity() away, speakerphone=true");
						Log.d(TAG, "setProximity near="+flagNear);
						audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
						audioManager.setSpeakerphoneOn(true); // activates speakerphone
						proximityNear = 0;
					}
				}
			}
		}

		public void releaseWakeUpWakeLock() {
			if(wakeUpWakeLock!=null && wakeUpWakeLock.isHeld()) {
				// this will let the screen time out
				wakeUpWakeLock.release();
				Log.d(TAG, "releaseWakeUpWakeLock() released");
			} else {
				Log.d(TAG, "releaseWakeUpWakeLock() not held");
			}
			wakeUpWakeLock = null;
			// TODO activity called this maybe we should now do the things that we would do on next alarmReceiver
		}

		public void activityDestroyed() {
			// activity is telling us that it is being destroyed
			// TODO this should set webviewPageLoaded=false, needed for next incoming call ???
			activityVisible = false;
			if(connectToServerIsWanted) {
				Log.d(TAG, "activityDestroyed got connectToServerIsWanted - do nothing");
				// do nothing
			} else if(reconnectBusy) {
				Log.d(TAG, "activityDestroyed got reconnectBusy - do nothing");
				// do nothing
			} else {
				Log.d(TAG, "activityDestroyed exitService()");
				// hangup peercon, reset webview, clear callPickedUpFlag
				exitService();
			}
		}

		public int audioToSpeaker(int val) {
			if(val>=0) {
				Log.d(TAG, "audioToSpeakerSet="+val);
				audioToSpeakerMode = val;
				audioToSpeakerSet(audioToSpeakerMode>0,true);
			}
			return audioToSpeakerMode;
		}

		public int beepOnLostNetwork(int val) {
			if(val>=0) {
				Log.d(TAG, "beepOnLostNetwork="+val);
				beepOnLostNetworkMode = val;
				storePrefsInt("beepOnLostNetwork", beepOnLostNetworkMode);
			}
			return beepOnLostNetworkMode;
		}

		public int startOnBoot(int val) {
			if(val>=0) {
				Log.d(TAG, "startOnBoot="+val);
				startOnBootMode = val;
				storePrefsInt("startOnBoot", startOnBootMode);
			}
			return startOnBootMode;
		}

		public int setWifiLock(int val) {
			if(val>=0) {
				Log.d(TAG, "setWifiLock="+val);
				setWifiLockMode = val;
				storePrefsInt("setWifiLockMode", setWifiLockMode);
				if(setWifiLockMode<1) {
					if(wifiLock==null) {
						Log.d(TAG,"setWifiLock wifiLock==null");
					} else if(!wifiLock.isHeld()) {
						Log.d(TAG,"setWifiLock wifiLock not isHeld");
					} else {
						Log.d(TAG,"setWifiLock wifiLock release");
						wifiLock.release();
					}
				} else if(haveNetworkInt==2) {
					if(wifiLock==null) {
						Log.d(TAG,"setWifiLock wifiLock==null");
					} else if(wifiLock.isHeld()) {
						Log.d(TAG,"setWifiLock wifiLock isHeld");
					} else {
						Log.d(TAG,"setWifiLock wifiLock.acquire");
						wifiLock.acquire();
					}
				}
			}
			return setWifiLockMode;
		}

		public int screenForWifi(int val) {
			if(val>=0) {
				Log.d(TAG, "screenForWifi="+val);
				screenForWifiMode = val;
				storePrefsInt("screenForWifi", screenForWifiMode);
			}
			return screenForWifiMode;
		}

		public String captureLogs() {
			return saveSystemLogs();
		}

		public boolean extendedLogs(int val) {
			if(val>0 && !extendedLogsFlag) {
				extendedLogsFlag = true;
			} else if(val==0 && extendedLogsFlag) {
				extendedLogsFlag = false;
			}
			return(extendedLogsFlag);
		}

		public String getCurrentUrl() {
			//Log.d(TAG, "getCurrentUrl currentUrl="+currentUrl);
			return currentUrl;
		}

		public void runJScode(String str) {
			// for instance, this lets the activity run "history.back()"
			if(str.startsWith("history.back()")) {
				Log.d(TAG, "runJScode history.back()");
			}
			// TODO apparently calling history.back() does not execute onbeforeunload
			runJS(str,null);
		}

		public void fileSelect(Uri[] results) {
			Log.d(TAG, "fileSelect results="+results);
			if(results!=null) {
				filePath.onReceiveValue(results);
				filePath = null;
			}
		}

		public boolean getInsecureTlsFlag() {
			return insecureTlsFlag;
		}

		public WebCallJSInterface getWebCallJSInterface() {
			return webCallJSInterface;
		}

		public WebCallJSInterfaceMini getWebCallJSInterfaceMini() {
			return webCallJSInterfaceMini;
		}

		public boolean isRinging() {
			return ringFlag;
		}
	}

	// section 3: class WebCallJSInterface with methods that can be called from javascript:
	//   wsOpen(), wsSend(), wsClose(), wsExit(), isConnected(), wsClearCookies(), wsClearCache(),
	//   rtcConnect(), callPickedUp(), peerConnect(), peerDisConnect(), storePreference()

	public class WebCallJSInterfaceMini {
		static final String TAG = "WebCallJSIntrfMini";

		WebCallJSInterfaceMini() {
		}

		@android.webkit.JavascriptInterface
		public String getVersionName() {
			return BuildConfig.VERSION_NAME;
		}

		@android.webkit.JavascriptInterface
		public String webviewVersion() {
			return getWebviewVersion();
		}

		@android.webkit.JavascriptInterface
		public void prepareDial() {
			// does nothing
			// turn speakerphone off - the idea is to always switch audio playback to the earpiece
			// on devices without an earpiece (tablets) this is expected to do nothing
			// we do it now here instead of at setProximity(true), because it is more reliable this way
			// will be reversed by peerDisConnect()
			// TODO API>=31 use audioManager.setCommunicationDevice(speakerDevice);
			// see https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)

			//Log.d(TAG, "JS prepareDial(), speakerphone=false");
			//audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION); // deactivates speakerphone on P9
			//audioManager.setSpeakerphoneOn(false); // deactivates speakerphone on Gn
		}

		@android.webkit.JavascriptInterface
		public void peerConnect() {
			// aka mediaConnect
			Log.d(TAG,"JS peerConnect() - mediaConnect");
			peerConnectFlag=true;
			callPickedUpFlag=false;
			// turn speakerphone off - the idea is to always switch audio playback to the earpiece
			// on devices without an earpiece (tablets) this is expected to do nothing
			// we do it now here instead of at setProximity(true), because it is more reliable this way
			// will be reversed by peerDisConnect()
			//Log.d(TAG, "JS peerConnect(), speakerphone=false");
			//audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION); // deactivates speakerphone on P9
			//audioManager.setSpeakerphoneOn(false); // deactivates speakerphone on Gn
		}

		@android.webkit.JavascriptInterface
		public void peerDisConnect() {
			// called by endWebRtcSession()
			Log.d(TAG,"JS peerDisConnect()");
			if(peerConnectFlag) { // aka mediaConnect
				// we want to show "Peer disconnect" ONLY if we had a media connect
				statusMessage("Peer disconnect",500,false,false);
			} else {
				// if we did not have a media connect, we may need to dismiss the notification bubble
				// it may still be visible
				// we can close the notification by sending a new not-high-priority notification
				// we want to send one updateNotification() in any case
				if(wsClient!=null) {
					// display awaitingCalls ONLY if we are still ws-connected
					updateNotification(awaitingCalls,false);
				} else {
					// otherwise display "Offline"
					updateNotification("Offline",false);
				}
			}
			peerConnectFlag = false;

			callPickedUpFlag = false;
			peerDisconnnectFlag = true;
			autoPickup = false;

			stopRinging("peerDisConnect");

			if(audioManager!=null) {
				if(audioManager.isWiredHeadsetOn()) {
					Log.d(TAG, "JS peerDisConnect() isWiredHeadsetOn: skip setSpeakerphoneOn(true)");
				} else if(audioManager.isBluetoothA2dpOn()) {
					Log.d(TAG, "JS peerDisConnect() isBluetoothA2dpOn: skip setSpeakerphoneOn(true)");
				} else {
					Log.d(TAG, "JS peerDisConnect(), speakerphone=true");
					audioManager.setSpeakerphoneOn(true);
				}
			}

			// this is used for ringOnSpeakerOn
			audioToSpeakerSet(audioToSpeakerMode>0,false);
		}

		@android.webkit.JavascriptInterface
		public long keepAwakeMS() {
			return keepAwakeWakeLockMS;
		}

		@android.webkit.JavascriptInterface
		public boolean isNetwork() {
			return haveNetworkInt>0;
		}

		@android.webkit.JavascriptInterface
		public void toast(String msg) {
			Intent intent = new Intent("webcall");
			intent.putExtra("toast", msg);
			sendBroadcast(intent);
		}

		@android.webkit.JavascriptInterface
		public void gotoBasepage() {
			if(myWebView!=null) {
				// loadUrl() must be called on main thread
				myWebView.post(new Runnable() {
					@Override
					public void run() {
						if(myWebView!=null) {
							myWebView.loadUrl("file:///android_asset/index.html", null);
						}
					}
				});
			}
		}

		@android.webkit.JavascriptInterface
		public void setClipboard(String clipText) {
			if(clipText!=null) {
				Log.d(TAG, "setClipboard "+clipText);
				ClipData clipData = ClipData.newPlainText(null,clipText);
				ClipboardManager clipboard =
					(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
				if(clipboard!=null) {
					clipboard.setPrimaryClip(clipData);
					Intent intent = new Intent("webcall");
					intent.putExtra("toast", "Data copied to clipboard");
					sendBroadcast(intent);
				}
				return;
			}
		}
	}


	public class WebCallJSInterface extends WebCallJSInterfaceMini {
		static final String TAG = "WebCallJSIntrf";

		WebCallJSInterface() {
		}

		@android.webkit.JavascriptInterface
		public WebSocketClient wsOpen(String setWsAddr) {
			// js code wants to open a websocket connection
			// wsOpen() does not start reconnecter (reconnecter will be started if the connection is lost)
			// wsOpen() does NOT call runJS("wakeGoOnline()") (does not send "init|") JS takes care of this
			if(reconnectBusy && wsClient!=null) {
				Log.d(TAG,"JS wsOpen reconnectBusy return existing wsClient");
				connectToServerIsWanted = true;
				storePrefsBoolean("connectWanted",true); // used in case of service crash + restart

				// when callee sends init and gets a confirmation
				// it will call calleeConnected() / calleeIsConnected()
				// then we will send: updateNotification awaitingCalls
				// then we will broadcast: "state", "connected"
				return wsClient;
			}
			if(wsClient==null) {
				Log.d(TAG,"JS wsOpen wsClient==null addr="+setWsAddr);
				// if connectHost fails, it will send updateNotification("Offline")
				WebSocketClient wsCli = connectHost(setWsAddr,false);
				Log.d(TAG,"JS wsOpen wsClient="+(wsCli!=null));
				if(wsCli!=null) {
					connectToServerIsWanted = true;
					storePrefsBoolean("connectWanted",true); // used in case of service crash + restart
					// when callee sends init and gets a confirmation
					// it will call calleeConnected() / calleeIsConnected()
					// then we will send: updateNotification awaitingCalls
					// then we will broadcast: "state", "connected"
				} else {
					connectToServerIsWanted = false;
					storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
				}
				return wsCli;
			}

			Log.d(TAG,"JS wsOpen return existing wsClient "+activityWasDiscarded);
			connectToServerIsWanted = true;
			storePrefsBoolean("connectWanted",true); // used in case of service crash + restart
			// when callee sends init and gets a confirmation
			// it will call calleeConnected() / calleeIsConnected()
			// then we will send: updateNotification awaitingCalls
			// then we will broadcast: "state", "connected"

			// in case the activity was discarded, we need to call:
			if(activityWasDiscarded) {
				activityWasDiscarded = false;
				if(myWebView==null) {
					Log.d(TAG,"# JS wsOpen return existing wsClient: activityWasDiscarded but myWebView==null");
				} else if(!webviewMainPageLoaded) {
					Log.d(TAG,"# JS wsOpen return existing wsClient activityWasDiscarded !webviewMainPageLoaded");
				} else {
					Log.d(TAG,"JS wsOpen return existing wsClient");
				}
			} else {
				Log.d(TAG,"JS wsOpen return existing wsClient: no activityWasDiscarded");
			}

			return wsClient;
		}

		@android.webkit.JavascriptInterface
		public boolean calleeReady() {
			// called from gotStream2()
			if(calleeIsReady) {
				// this is NOT the 1st calleeReady()
				// we only start processWebRtcMessages() on the 1st call
				Log.d(TAG,"JS calleeReady() -> calleeIsReady was already set");
				return false;
			}

			calleeIsReady = true;
			if(!stringMessageQueue.isEmpty()) {
				Log.d(TAG,"JS calleeReady() -> processWebRtcMessages()");
				// we delay calling processWebRtcMessages() bc otherwise JS code will receive:
				// "cmd callerCandidate !peerCon.remoteDescription"
				// "callerOffer setRemoteDescription" needs some time to complete
				final Runnable runnable2 = new Runnable() {
					public void run() {
						Log.d(TAG,"onPageFinished main page: processWebRtcMessages start");
						processWebRtcMessages();
					}
				};
				scheduler.schedule(runnable2, 100l, TimeUnit.MILLISECONDS);
				return true;
			}
			Log.d(TAG,"JS calleeReady() no queued WebRtcMessages()");
			return false;
		}

		@android.webkit.JavascriptInterface
		public void calleeConnected() {
			Log.d(TAG,"JS calleeConnected()");
			calleeIsConnected();
		}

		@android.webkit.JavascriptInterface
		public void wsSend(String str) {
			String logstr = str;
			if(logstr.length()>40) {
				logstr = logstr.substring(0,40);
			}
			if(wsClient==null) {
				// THIS SHOULD NEVER HAPPEN (maybe wsConn wrongly set in client js)
				Log.w(TAG,"# JS wsSend wsClient==null "+logstr);
			} else {
				if(extendedLogsFlag) {
					Log.d(TAG,"JS wsSend "+logstr);
				}
				try {
					wsClient.send(str);
				} catch(Exception ex) {
					Log.d(TAG,"JS wsSend ex="+ex);
					// TODO
					return;
				}
			}
		}

		@android.webkit.JavascriptInterface
		public void wsClose() {
			// called by JS:goOffline()
			Log.d(TAG,"JS wsClose");
			connectToServerIsWanted = false;
			storePrefsBoolean("connectWanted",false); // used in case of service crash + restart

/* done by disconnectHost()
			calleeIsReady = false;
			if(pendingAlarm!=null) {
				alarmManager.cancel(pendingAlarm);
				pendingAlarm = null;
				alarmPendingDate = null;
			}

			// if reconnect loop is running, cancel it
			if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
				Log.d(TAG,"JS wsClose cancel reconnectSchedFuture");
				reconnectSchedFuture.cancel(false);
				reconnectSchedFuture = null;
				statusMessage("Stopped reconnecting",-1,true,false); // manually
			}
			// this is needed for wakelock and wifilock to be released
			reconnectBusy = false;
*/
			// wsClient.closeBlocking() + wsClient=null
			disconnectHost(true);
			Log.d(TAG,"JS wsClose done");
		}

		@android.webkit.JavascriptInterface
		public int isConnected() {
			if(reconnectBusy) {
				return 1;
			}
			if(wsClient!=null) {
				return 2;
			}
			return 0;
		}

		@android.webkit.JavascriptInterface
		public void wsClearCookies() {
			// used by WebCallAndroid
			clearCookies();
		}

		@android.webkit.JavascriptInterface
		public void insecureTls(boolean flag) {
			insecureTlsFlag = flag;
			Log.d(TAG,"JS insecureTlsFlag="+insecureTlsFlag);
			storePrefsBoolean("insecureTlsFlag", insecureTlsFlag);
		}

		@android.webkit.JavascriptInterface
		public void menu() {
			Intent intent = new Intent("webcall");
			intent.putExtra("cmd", "menu");
			sendBroadcast(intent);
		}

		@android.webkit.JavascriptInterface
		public void wsClearCache(final boolean autoreload, final boolean autoreconnect) {
			// used by webcall.js + callee.js (clearcache())
			if(myWebView!=null) {
				Log.d(TAG,"JS wsClearCache clearCache()");
				myWebView.post(new Runnable() {
					@Override
					public void run() {
						myWebView.clearCache(true);
						Log.d(TAG,"JS wsClearCache clearCache() done");
						if(autoreload) {
							// immediate execution of reload() will NOT execute JS code
							//Log.d(TAG,"JS wsClearCache reload("+autoreconnect+")");
							//reload(autoreconnect);

							// but if we wait a little, we can reload and autostart with no problem
							final Runnable runnable2 = new Runnable() {
								public void run() {
									Log.d(TAG,"JS wsClearCache delayed reload("+autoreconnect+")");
									reload(autoreconnect);
									// at this point the old JS is killed and the new JS will be started
								}
							};
							scheduler.schedule(runnable2, 100l, TimeUnit.MILLISECONDS);
						}
					}
				});
				long nowSecs = new Date().getTime();
				storePrefsLong("lastClearCache", nowSecs);
			} else {
				Log.d(TAG,"JS wsClearCache myWebView==null");
			}
		}

		@android.webkit.JavascriptInterface
		public void reload(boolean autoconnect) {
			if(myWebView==null) {
				Log.d(TAG,"# JS reload("+currentUrl+") myWebView==null");
			} else {
				// get rid of #... in currentUrl
				String baseCurrentUrl = currentUrl;
				int idxHash = baseCurrentUrl.indexOf("#");
				if(idxHash>=0) {
					baseCurrentUrl = baseCurrentUrl.substring(0,idxHash);
				}
				int idxArgs = baseCurrentUrl.indexOf("?");
				if(idxArgs>=0) {
					baseCurrentUrl = baseCurrentUrl.substring(0,idxArgs);
				}

				currentUrl = baseCurrentUrl;
				if(autoconnect) {
					currentUrl += "?auto=1";
					Log.d(TAG,"JS reload("+currentUrl+") autoconnect");
				} else {
					Log.d(TAG,"JS reload("+currentUrl+") no autoconnect");
				}
				final String reloadUrl = currentUrl;
				currentUrl=null;
				myWebView.post(new Runnable() {
					@Override
					public void run() {
						myWebView.loadUrl(reloadUrl);
						Log.d(TAG,"JS reload("+reloadUrl+") done");
					}
				});
			}
		}

		@android.webkit.JavascriptInterface
		public int androidApiVersion() {
			Log.d(TAG,"JS androidApiVersion() "+Build.VERSION.SDK_INT);
			return Build.VERSION.SDK_INT;
		}

		@android.webkit.JavascriptInterface
		public boolean rtcConnect() {
			Log.d(TAG,"JS rtcConnect()");

			// making sure this is activated (if it is enabled)
			audioToSpeakerSet(audioToSpeakerMode>0,false);
			peerDisconnnectFlag = false;

			if(activityVisible) {
				Log.d(TAG,"JS rtcConnect() with activityVisible: not bringActivityToFront");
			} else if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
				// while phone is still ringing, keep sending wakeIntent to bringActivityToFront
				final Runnable bringActivityToFront = new Runnable() {
					public void run() {
						if(!callPickedUpFlag && !peerConnectFlag && !peerDisconnnectFlag) {
							Log.d(TAG,"JS rtcConnect() bringActivityToFront loop");
							// wake activity for incoming call
							// this is our secondary wakeIntent with ACTIVITY_REORDER_TO_FRONT
							long eventMS = (new Date()).getTime();
							Intent wakeIntent =
								new Intent(context, WebCallCalleeActivity.class)
									.putExtra("wakeup", "call")
									.putExtra("date", eventMS);
							wakeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
								Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY |
								Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
							context.startActivity(wakeIntent);
							scheduler.schedule(this, 3, TimeUnit.SECONDS);
						} else {
							Log.d(TAG,"JS rtcConnect() bringActivityToFront end");
						}
					}
				};
				scheduler.schedule(bringActivityToFront, 0, TimeUnit.SECONDS);
			} else {
				//Log.d(TAG,"JS rtcConnect() "+Build.VERSION.SDK_INT+" >= "+Build.VERSION_CODES.Q+" do nothing");
			}

			if(autoPickup) {
				autoPickup = false;
				Log.d(TAG,"JS rtcConnect() autoPickup...");

				// allow time for callee.js to switch from online/offline to answer/reject layout
				final Runnable runnable2 = new Runnable() {
					public void run() {
						runJS("pickup()",null);
					}
				};
				scheduler.schedule(runnable2, 500l, TimeUnit.MILLISECONDS);
				return true;
			}
			Log.d(TAG,"JS rtcConnect() no autoPickup");
			return false;
		}

		@android.webkit.JavascriptInterface
		public void callPickedUp() {
			Log.d(TAG,"JS callPickedUp()");
			// route audio to it's normal destination (to headset if connected)
			audioToSpeakerSet(false,false);
			callPickedUpFlag=true; // no peerConnect yet, this activates proximitySensor
		}


		@android.webkit.JavascriptInterface
		public void browse(String url) {
			Log.d(TAG,"JS browse("+url+")");
			Intent intent = new Intent("webcall");
			intent.putExtra("browse", url);
			sendBroadcast(intent);
		}

		@android.webkit.JavascriptInterface
		public void wsExit() {
			// called by Exit button
/* done by disconnectHost()
			if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
				reconnectSchedFuture.cancel(false);
				reconnectSchedFuture = null;
			}
			reconnectBusy = false;
*/
			// hangup peercon, clear callPickedUpFlag, reset webview
			endPeerConAndWebView();

			// disconnect from webcall server
			Log.d(TAG,"JS wsExit disconnectHost()");
			disconnectHost(true);

			// tell activity to force close
			Log.d(TAG,"JS wsExit shutdown activity");
			Intent intent = new Intent("webcall");
			intent.putExtra("cmd", "shutdown");
			sendBroadcast(intent);

			exitService();
			Log.d(TAG,"JS wsExit done");
		}

		@android.webkit.JavascriptInterface
		public String readPreference(String pref) {
			// used by WebCallAndroid
			String str = prefs.getString(pref, "");
			if(extendedLogsFlag) {
				Log.d(TAG, "JS readPreference "+pref+" = "+str);
			}
			return str;
		}

		@android.webkit.JavascriptInterface
		public boolean readPreferenceBool(String pref) {
			// used by WebCallAndroid
			boolean bool = prefs.getBoolean(pref, false);
			if(extendedLogsFlag) {
				Log.d(TAG, "JS readPreferenceBool "+pref+" = "+bool);
			}
			return bool;
		}

		@android.webkit.JavascriptInterface
		public long readPreferenceLong(String pref) {
			long val = prefs.getLong(pref, 0);
			if(extendedLogsFlag) {
				Log.d(TAG, "JS readPreferenceLong "+pref+" = "+val);
			}
			return val;
		}

		@android.webkit.JavascriptInterface
		public void storePreference(String pref, String str) {
			// used by WebCallAndroid
			storePrefsString(pref,str);
			Log.d(TAG, "JS storePreference "+pref+" "+str+" stored");
		}

		@android.webkit.JavascriptInterface
		public void storePreferenceBool(String pref, boolean bool) {
			// used by WebCallAndroid
			storePrefsBoolean(pref,bool);
			Log.d(TAG, "JS storePreferenceBool "+pref+" "+bool+" stored");
		}

		@android.webkit.JavascriptInterface
		public void storePreferenceLong(String pref, long val) {
			// used by WebCallAndroid
			storePrefsLong(pref,val);
			Log.d(TAG, "JS storePreferenceLong "+pref+" "+val+" stored");
		}

		@android.webkit.JavascriptInterface
		public void getBase64FromBlobData(String base64Data, String filename) throws IOException {
			// used by WebCallAndroid
			Log.d(TAG,"JS getBase64FromBlobData "+filename+" "+base64Data.length());
			int skipHeader = base64Data.indexOf("base64,");
			if(skipHeader>=0) {
				base64Data = base64Data.substring(skipHeader+7);
			}
			//Log.d(TAG,"JS base64Data="+base64Data);
			byte[] blobAsBytes = Base64.decode(base64Data,Base64.DEFAULT);
			Log.d(TAG,"JS bytearray len="+blobAsBytes.length);

			//Log.d(TAG,"JS getBase64FromBlobData data="+base64Data);
			storeByteArrayToFile(blobAsBytes,filename);
		}

		@android.webkit.JavascriptInterface
		public boolean ringStart() {
			ringFlag = true;
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startRinging();
				return true;
			}
			return false;
		}

		@android.webkit.JavascriptInterface
		public boolean ringStop() {
			ringFlag = false;
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				stopRinging("JS");
				return true;
			}
			return false;
		}
	}

	// section 4: class WsClient with methods called by the Java WebSocket engine: onOpen(), onError(), 
	//   onClose(), onMessage(), onSetSSLParameters(), onWebsocketPing(), onWebsocketPong()
	public class WsClient extends WebSocketClient {
		static final String TAG = "WebCallWebSock";

		public WsClient(URI serverUri, Draft draft) {
			super(serverUri, draft);
			//Log.d(TAG,"constructor with draft "+serverUri);
		}

		public WsClient(URI serverURI) {
			super(serverURI);
			//Log.d(TAG,"constructor "+serverURI);
		}

		@Override
		public void onOpen(ServerHandshake handshakedata) {
			// connection was opened, so we tell JS code
			if(myWebView!=null && webviewMainPageLoaded) {
				Log.d(TAG,"WsClient onOpen -> js:wsOnOpen");
				// wsOnOpen may come too early and cause:
				// D WebCallService: con: Uncaught ReferenceError: wsOnOpen is not defined L1
				//runJS("wsOnOpen()",null);
				final Runnable runnable2 = new Runnable() {
					public void run() {
						runJS("wsOnOpen()",null);
					}
				};
				scheduler.schedule(runnable2, 500l, TimeUnit.MILLISECONDS);
			} else {
				Log.d(TAG,"WsClient onOpen, but not webviewMainPageLoaded");
				//updateNotification(awaitingCalls,false);	// ??? too early? has init been sent?
			}
		}

		@Override
		public void onError(Exception ex) {
			String exString = ex.toString();
			Log.d(TAG,"onError ex "+exString);

			// javax.net.ssl.SSLException: Read error: ssl=0x75597c5e80: 
			//    I/O error during system call, Connection reset by peer
			// occurs when we lose the connection to our webcall server

			// javax.net.ssl.SSLException: Read error: ssl=0xaa02e3c8: 
			//    I/O error during system call, Software caused connection abort
			// occurs when we lose connection to our webcall server (N7 without ext power)

			// javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException:
			//    Trust anchor for certification path not found

			if(exString!=null && exString!="") {
				if(exString.indexOf("Read error") >=0) {
					if(extendedLogsFlag) {
						Log.d(TAG,"onError hide from JS: "+exString);
					}
				} else {
					statusMessage("error: "+exString,-1,false,false);
				}
			}
		}

		@Override
		public void onClose(int code, String reason, boolean remote) {
			// code 1002: an endpoint is terminating the connection due to a protocol error
			// code 1006: connection was closed abnormally (locally)
			// code 1000: indicates a normal closure (when we click goOffline, or server forced disconnect)
			Intent brintent = new Intent("webcall");
			brintent.putExtra("state", "disconnected");
			sendBroadcast(brintent);
			autoPickup = false;

			if(reconnectBusy) {
				Log.d(TAG,"onClose skip busy (code="+code+" "+reason+")");
			} else if(code==1000) {
				// normal disconnect: shut down connection - do NOT reconnect
				Log.d(TAG,"onClose code=1000");
				wsClient = null;
				if(reconnectSchedFuture==null) {
					statusMessage("disconnected from WebCall server",-1,true,false);
				}
				if(myWebView!=null && webviewMainPageLoaded) {
					// disable offline-button and enable online-button
					// TODO etwas stimmt aber nicht:
					// connectToServerIsWanted wird hier noch nicht gelöscht, später kommt noch ein alarm + reconnect
//					runJS("wsOnClose2();",null);
					runJS("wsOnClose2();", new ValueCallback<String>() {
						@Override
						public void onReceiveValue(String s) {
							Log.d(TAG,"runJS('wsOnClose2') completed: "+s);
						}
					});
				}
			} else {
				Log.d(TAG,"onClose code="+code+" reason="+reason);

				if(code==1006) {
					// connection to webcall server has been interrupted and must be reconnected asap
					// normally this happens "all of a sudden"
					// but on N9 I have seen this happen on server restart
					// problem can ne, that we are right now in doze mode (deep sleep)
					// in deep sleep we cannot create new network connections
					// in order to establish a new network connection, we need to bring device out of doze

					/*
					// what is screenForWifiMode doing here?
					if(haveNetworkInt==0 && screenForWifiMode>0) {
						if(wifiLock!=null && wifiLock.isHeld()) {
							Log.d(TAG,"onClose wifiLock release");
							wifiLock.release();
						}
					}
					*/

					if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
						Log.d(TAG,"onClose keepAwakeWakeLock.acquire");
						keepAwakeWakeLock.acquire(3 * 60 * 1000); // 3 minutes max
						keepAwakeWakeLockStartTime = (new Date()).getTime();
					}

					wakeUpOnLoopCount(context);	// why???

					// close prev connection
					if(wsClient!=null) {
						WebSocketClient tmpWsClient = wsClient;
						wsClient = null;
						/*
						// closeBlocking() makes no sense here bc we received a 1006
						// it would also hang
						Log.d(TAG,"onClose wsClient.closeBlocking()...");
						try {
							tmpWsClient.closeBlocking();
						} catch(Exception ex) {
							Log.d(TAG,"onClose wsClient.closeBlocking ex="+ex);
						}
						*/

						// TODO maybe we should send websocket.CloseMessage ???
						Log.d(TAG,"onClose wsClient.close()...");
						tmpWsClient.close();

						if(myWebView!=null && webviewMainPageLoaded) {
							Log.d(TAG,"onClose runJS('wsOnClose2()'");
							runJS("wsOnClose2()",null); // set wsConn=null; abort blinkButtonFunc()
						}
						Log.d(TAG,"onClose wsClient.close() done");

						// TODO problem is that the server may STILL think it is connected to this client
						// and that re-login below may fail with "already/still logged in" because of this

					} else {
						if(reconnectSchedFuture==null) {
							statusMessage("disconnected from WebCall server",-1,true,false);
						}
					}

					if(reconnectSchedFuture==null && !reconnectBusy) {
						// if no reconnecter is scheduled at this time (say, by checkLastPing())
						// then schedule a new reconnecter
						// schedule in 5s to give server some time to detect the discon
						setLoginUrl();
						if(loginUrl!="") {
							Log.d(TAG,"onClose re-login in 5s url="+loginUrl);
							// TODO on P9 in some cases this reconnecter does NOT fire
							// these are cases where the cause of the 1006 was wifi lost (client side)
							// shortly after this 1006 we then receive a networkStateReceiver event with all null
							reconnectSchedFuture = scheduler.schedule(reconnecter,5,TimeUnit.SECONDS);
						}
					} else {
						Log.d(TAG,"onClose no reconnecter: reconnectBusy="+reconnectBusy);
					}

				} else {
					// NOT 1006: TODO not exactly sure what to do with this
					if(myWebView!=null && webviewMainPageLoaded) {
						// offlineAction(): disable offline-button and enable online-button
						runJS("offlineAction();",null);
						// TODO: wanted=false ?
						// TODO: notif "Offline" ?
					}

					if(code==-1) {
						// if code==-1 do not show statusMessage
						// as it could replace a prev statusMessage with a crucial error text
					} else {
						statusMessage("Connection error "+code+". Not reconnecting.",-1,true,false);
					}

					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						reconnectSchedFuture.cancel(false);
						reconnectSchedFuture = null;
					}
					reconnectBusy = false;
					if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
						long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
						Log.d(TAG,"networkState keepAwakeWakeLock.release +"+wakeMS);
						keepAwakeWakeLockMS += wakeMS;
						storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
						keepAwakeWakeLock.release();
					}
				}
				Log.d(TAG,"onClose done");
			}
		}

		@Override
		public void onMessage(String message) {
			//Log.d(TAG,"onMessage '"+message+"'");

			lastPingDate = new Date();

			if(message.startsWith("dummy|")) {
				Log.d(TAG,"onMessage dummy "+message);
				return;
			}

			if(message.equals("clearcache")) {
				Log.d(TAG,"onMessage clearcache "+message);
				// TODO implement clearcache: force callee web-client reload
				return;
			}

			if(message.startsWith("textmode|")) {
				textmode = message.substring(9);
				Log.d(TAG,"onMessage textmode "+message);
				return;
			}

			if(message.startsWith("callerOffer|") && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
				// incoming call!!
				// for Android <= 9: wake activity via wakeIntent
				// send a wakeIntent with ACTIVITY_REORDER_TO_FRONT
				// secondary wakeIntent will be sent in rtcConnect()
				if(context==null) {
					Log.e(TAG,"onMessage incoming call, but no context to wake activity");
				} else {
					Log.d(TAG,"onMessage incoming call "+
						new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
					long eventMS = (new Date()).getTime();
					Intent wakeIntent =
						new Intent(context, WebCallCalleeActivity.class)
							.putExtra("wakeup", "call")
							.putExtra("date", eventMS);
					wakeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
						Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY |
						Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
					context.startActivity(wakeIntent);
					//statusMessage("WebCall "+callerName+" "+callerID,-1,false,false);
				}
			}

			if(message.startsWith("callerInfo|") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				// incoming call!!
				// for Android 10+ (SDK >= Q): wake activity via notification channel
				// - Accept button to wake activity and pickup call
				// - Switch button to wake activity and only switch to it
				// - Deny button to "denyCall"
				// we use "callerInfo|" instead of "callerOffer|"
				// bc for Android10+ we can display callerID and callerName in the notification

				String payload = message.substring(11);

				String callerID = "";
				String callerName = "";
				String txtMsg = "";
				String[] toks = payload.split("\t");
				if(toks.length>=1) {
					callerID = toks[0];
					if(toks.length>=2) {
						callerName = toks[1];
						if(toks.length>=3) {
							txtMsg = toks[2];
						}
					}
				}

				String contentText = callerName+" "+callerID;
				if(textmode.equals("true")) { // set by signalingCommand()
					contentText += " TextMode ";
				}
				if(txtMsg!="") {
					contentText += " \""+txtMsg+"\""; // greeting msg
				}
				Log.d(TAG,"onMessage incoming call: "+contentText);

				if(context==null) {
					Log.e(TAG,"onMessage incoming call, but no context to wake activity");
				} else if(activityVisible) {
					Log.d(TAG,"onMessage incoming call, activityVisible (do nothing)");
				} else {
					// activity is NOT visible
					Date wakeDate = new Date();
					//Log.d(TAG,"onMessage incoming call "+
					//	new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(wakeDate));
					Log.d(TAG,"onMessage incoming call Android10+ notification");

					long eventMS = wakeDate.getTime();

					Intent acceptIntent = new Intent(context, WebCallCalleeActivity.class);
					acceptIntent.putExtra("wakeup", "pickup");
					acceptIntent.putExtra("date", eventMS);

					Intent switchToIntent =	new Intent(context, WebCallCalleeActivity.class);
					switchToIntent.putExtra("wakeup", "call");
					switchToIntent.putExtra("date", eventMS);

					Intent denyIntent = new Intent("serviceCmdReceiver");
					denyIntent.putExtra("denyCall", "true");

					NotificationCompat.Builder notificationBuilder =
						new NotificationCompat.Builder(context, NOTIF_CHANNEL_ID_HIGH)
							.setSmallIcon(R.mipmap.notification_icon)
							.setContentTitle("Incoming WebCall")
							.setCategory(NotificationCompat.CATEGORY_CALL)
							//.setLights(0xff00ff00, 300, 100)			// TODO does not seem to work on N7

							// on O+ setPriority is ignored in favor of NOTIF_CHANNEL_ID_HIGH
							.setPriority(NotificationCompat.PRIORITY_HIGH)

							.addAction(R.mipmap.notification_icon,"Accept",
								PendingIntent.getActivity(context, 2, acceptIntent,
									PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))

							.addAction(R.mipmap.notification_icon,"Switch",
								PendingIntent.getActivity(context, 3, switchToIntent,
									PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))

							.addAction(R.mipmap.notification_icon,"Deny",
								PendingIntent.getBroadcast(context, 4, denyIntent,
									PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))

							//.setAutoCancel(true) // any click will close the notification
							// if this is false, any click will switchTo activity

							// we are using our own ringtone (see startRinging())
							//.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

							// clicking on the area behind the action buttons will (also) switchTo activty
							.setFullScreenIntent(
								PendingIntent.getActivity(context, 1, switchToIntent,
									PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE), true)

							.setContentText(contentText);

					Notification notification = notificationBuilder.build();

					NotificationManager notificationManager =
						(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
					notificationManager.notify(NOTIF_ID, notification);

					startRinging();
				}
			}

			if(message.startsWith("cancel|")) {
				// server or caller signalling end of call
				if(myWebView==null || !webviewMainPageLoaded ||
					  (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (!isScreenOn() || !activityVisible)) ) {
					Log.d(TAG,"onMessage cancel got !myWebView or !webviewMainPageLoaded");

					// clear queueWebRtcMessage / stringMessageQueue
					while(!stringMessageQueue.isEmpty()) {
						stringMessageQueue.poll();
					}

					stopRinging("cancel|");

					if(wsClient!=null) {
						wsClient.send("init|");
					} else {
						updateNotification("",false);
					}
					return;
				}

				Log.d(TAG,"onMessage cancel got myWebView + webviewMainPageLoaded");
				//updateNotification(awaitingCalls,false);
			}

			if(myWebView==null || !webviewMainPageLoaded ||
					(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (!isScreenOn() || !activityVisible)) ) {
				// we can not send messages (for instance callerCandidate's) into the JS 
				// if the page is not fully loaded (webviewMainPageLoaded==true)
				// in such cases we queue the WebRTC messages - until we see "sessionId|"
				if(message.startsWith("sessionId|")) {
					Log.d(TAG,"onMessage sessionId -> calleeIsConnected()");
					calleeIsConnected();
					return;
				}

				String shortMessage = message;
				if(message.length()>24) {
					shortMessage = message.substring(0,24);
				}
				//Log.d(TAG,"onMessage queueWebRtcMessage("+shortMessage+") "+
				//	webviewMainPageLoaded+" "+myWebView);
				queueWebRtcMessage(message);
				// same as stringMessageQueue.add(message);
			} else {
				// webviewMainPageLoaded is set by onPageFinished() when a /callee/ url has been loaded
				// NOTE: message MUST NOT contain apostrophe (') characters
				String encodedMessage = message.replace("'", "&#39;");
				String argStr = "wsOnMessage2('"+encodedMessage+"','serv-direct');";

				if(message.startsWith("sessionId|")) {
					Log.d(TAG,"onMessage sessionId -> runJS("+argStr+")");
				}
				//Log.d(TAG,"onMessage runJS "+argStr);
				// this message goes straight to callee.js signalingCommand()
// tmtmtm TODO is not always executed
				runJS(argStr,null);
			}
		}

		@Override
		public void onMessage(ByteBuffer message) {
			//this is not being used
			Log.d(TAG,"onMessage ! ByteBuffer");
		}

		@Override
		public void onSetSSLParameters(SSLParameters sslParameters) {
			// this method is only supported on Android >= 24 (Nougat)
			// in wsOpen() we do host verification ourselves
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				Log.d(TAG,"onSetSSLParameters "+sslParameters);
				super.onSetSSLParameters(sslParameters);
			} else {
				Log.d(TAG,"# onSetSSLParameters "+sslParameters+" not supported "+
					Build.VERSION.SDK_INT+" < "+Build.VERSION_CODES.N);
			}
		}

		@Override
		public void onWebsocketPong(WebSocket conn, Framedata f) {
			// a pong from the server in response to our ping
			// note: if doze mode is active, many of our ws-pings (by Timer) do not execute
			// and then we also don't receive the acompaning server-pongs
			if(extendedLogsFlag) {
				Log.d(TAG,"onWebsocketPong "+currentDateTimeString());
			}
			super.onWebsocketPong(conn,f); // without calling this we crash (at least on P9)
		}

		@Override
		public void onWebsocketPing(WebSocket conn, Framedata f) {
			// a ping from the server to which we respond with a pong
			// this apparently works even if we are in doze mode
			// maybe we should use the AlarmManager to wake ourself up (every 15m)
			if(wsClient==null) {
				// apparently this never happens
				Log.d(TAG,"onWebsocketPing wsClient==null "+currentDateTimeString());
				// don't pong back
				return;
			}

			pingCounter++;
			if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
				// in case keepAwakeWakeLock was acquired before, say, by "dozeStateReceiver idle"
				long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
				Log.d(TAG,"onWebsocketPing keepAwakeWakeLock.release +"+wakeMS);
				keepAwakeWakeLockMS += wakeMS;
				storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
				keepAwakeWakeLock.release();
			}

			Date currentDate = new Date();
			Calendar calNow = Calendar.getInstance();
			int hours = calNow.get(Calendar.HOUR_OF_DAY);
			int minutes = calNow.get(Calendar.MINUTE);
			int currentMinuteOfDay = ((hours * 60) + minutes);
			if(currentMinuteOfDay<lastMinuteOfDay) {
				Log.d(TAG,"new day clear old keepAwakeWakeLockMS "+keepAwakeWakeLockMS);
				keepAwakeWakeLockMS = 0;
				storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
			}

			if(extendedLogsFlag) {
				Log.d(TAG,"onWebsocketPing "+pingCounter+" net="+haveNetworkInt+" "+
					keepAwakeWakeLockMS+" "+BuildConfig.VERSION_NAME+" "+
					new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US).format(currentDate));
			}
			lastPingDate = currentDate;
			lastMinuteOfDay = currentMinuteOfDay;

			super.onWebsocketPing(conn,f); // will send a pong
		}
	}

	public class PowerConnectionReceiver extends BroadcastReceiver {
		private static final String TAG = "WebCallPower";
		public PowerConnectionReceiver() {
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if(intent.getAction().equals(Intent.ACTION_POWER_CONNECTED)) {
				Log.d(TAG,"POWER_CONNECTED");
				charging = true;
			} else if(intent.getAction().equals(Intent.ACTION_POWER_DISCONNECTED)) {
				Log.d(TAG,"POWER_DISCONNECTED");
				charging = false;
			} else {
				Log.d(TAG,"POWER_? "+intent.getAction());
			}

			if(wsClient!=null) {
				Log.d(TAG, "power event wsClient is set: checkLastPing");
				checkLastPing(true,0);
			} else {
				// we are disconnected: if connectToServerIsWanted, connect to server
				if(!connectToServerIsWanted) {
					Log.d(TAG,"power event wsClient not set, no connectToServerIsWanted");
				} else if(reconnectBusy) {
					Log.d(TAG,"power event wsClient not set, reconnectBusy");
				} else {
					Log.d(TAG,"power event wsClient not set, startReconnecter");
					startReconnecter(true,0);
				}
			}
		}
	}

	public class AlarmReceiver extends BroadcastReceiver {
		private static final String TAG = "WebCallAlarm";
		public void onReceive(Context context, Intent intent) {
			// we have requested wakeup out of doze every 10-15 minutes
			// now we check if we are still receiving pings from the server
			if(pendingAlarm==null) {
				// user is possibly on basepage still
				Log.w(TAG,"abort on pendingAlarm==null");
			}
			pendingAlarm = null;
			alarmPendingDate = null;

			if(!connectToServerIsWanted) {
				Log.w(TAG,"abort on !connectToServerIsWanted");
				return;
			}

			batteryStatus = context.registerReceiver(null, batteryStatusfilter);
			//int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
			//boolean usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB;
			//boolean acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC;
			int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
			int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
			float batteryPct = level * 100 / (float)scale;


			Log.d(TAG,"net="+haveNetworkInt+ " awakeMS="+keepAwakeWakeLockMS+
				" pings="+pingCounter+ " "+batteryPct+
				" "+BuildConfig.VERSION_NAME+
				" "+currentDateTimeString());
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { // <api24
				checkNetworkState(false);
			}
			if(haveNetworkInt>0) {
				// this is a good time to send a ping
				// if the connection is bad we will know much quicker
				if(wsClient!=null) {
					try {
						if(extendedLogsFlag) {
							Log.d(TAG,"sendPing");
						}
						wsClient.sendPing();
					} catch(Exception ex) {
						// possibly: org.java_websocket.exceptions.WebsocketNotConnectedException
						Log.d(TAG,"sendPing ex="+ex);
						wsClient = null;
					}
				}
			}

			if(wsClient!=null) {
				//Log.d(TAG,"alarm checkLastPing()");
				checkLastPing(true,0);
			} else {
				if(!connectToServerIsWanted) {
					Log.d(TAG,"alarm skip, no connectToServerIsWanted");
				/*
				} else if(reconnectBusy) {
					// alarm only fires if device is in doze, and then reconnectSchedFuture does NOT fire
					// so we do NOT skip this alarm!
					// instead we canncel reconnectSchedFuture (if it is not done)
					// and startReconnecter
					Log.d(TAG,"alarm skip, reconnectBusy");
				*/
				} else {
					// stop reconnectSchedFuture, clear reconnectBusy
					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						Log.d(TAG,"alarm reconnectSchedFuture.cancel");
						reconnectSchedFuture.cancel(false);
						reconnectSchedFuture = null;
					}
					reconnectBusy = false;

					Log.d(TAG,"alarm startReconnecter");
					startReconnecter(true,0);
				}
			}

			// always request a followup alarm
			pendingAlarm = PendingIntent.getBroadcast(context, 0, startAlarmIntent, PendingIntent.FLAG_IMMUTABLE);
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				if(extendedLogsFlag) {
					Log.d(TAG,"alarm setAndAllowWhileIdle");
				}
				alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
					SystemClock.elapsedRealtime() + 15*60*1000, pendingAlarm);
			} else {
				// for Android 5 and below:
				if(extendedLogsFlag) {
					Log.d(TAG,"alarm set");
				}
				// many devices do min 16min
				alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
					SystemClock.elapsedRealtime() + 15*60*1000, pendingAlarm);
			}
			alarmPendingDate = new Date();
		}
	}


	// section 5: private methods

	private void startRinging() {
		if(mediaPlayer!=null) {
			// ringtone already playing
			Log.d(TAG,"startRinging skip: ringtone already playing");
			return;
		}

		// start playing ringtone
		Log.d(TAG,"startRinging");
		audioToSpeakerSet(audioToSpeakerMode>0,false);

		mediaPlayer = new MediaPlayer();
		AudioAttributes aa = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
				//.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
				.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
				//.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
				//.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
				.setLegacyStreamType(AudioManager.STREAM_RING)
				.build();

		// int vol = audioManager.getStreamVolume(AudioManager.STREAM_RING);
		// int maxvol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
		// Log.d(TAG,"mediaPlayer AudioManager.STREAM_RING vol="+vol+" maxvol="+maxvol);
		// Log.d(TAG,"mediaPlayer aa.getVolumeControlStream() "+aa.getVolumeControlStream());
		// vol = audioManager.getStreamVolume(aa.getVolumeControlStream());
		// maxvol = audioManager.getStreamMaxVolume(aa.getVolumeControlStream());
		// Log.d(TAG,"mediaPlayer aa.getVolumeControlStream vol="+vol+" maxvol="+maxvol);

		mediaPlayer.setAudioAttributes(aa);
		mediaPlayer.setLooping(true);
		try {
			AssetFileDescriptor ad = getResources().openRawResourceFd(R.raw.ringing);
			mediaPlayer.setDataSource(ad.getFileDescriptor(), ad.getStartOffset(), ad.getLength());
			ad.close();

			mediaPlayer.prepare();
			mediaPlayer.start();
			// we stop ringing in multiple places, see: stopRinging()
		} catch(IOException ex) {
			Log.d(TAG,"# startRinging ringtone ex="+ex);
			mediaPlayer.stop();
			mediaPlayer = null;
		}
	}

	private void stopRinging(String comment) {
		// stop playing the ringtone
		if(mediaPlayer!=null) {
			Log.d(TAG,"stopRinging, from: "+comment);
			mediaPlayer.stop();
			mediaPlayer = null;
		} else {
			//Log.d(TAG,"stopRinging (was not active), from "+comment);
		}
	}

	private void calleeIsConnected() {
		Log.d(TAG,"calleeIsConnected()");

		// problem: statusMessage() (runJS) is not always executed in doze mode
		// we need a method to display the last msg when device gets out of doze
		// see: lastStatusMessage
		statusMessage(awaitingCalls,-1,true,false);

		Intent brintent = new Intent("webcall");
		brintent.putExtra("state", "connected");
		sendBroadcast(brintent);

		// peerConCreateOffer() does not trigger onIceCandidate() callbacks
		//runJS("peerConCreateOffer();",null);

		// TODO if wsClient!=null but calleeIsConnected() is NOT called, what does this mean?
		// especially for webcallConnectType() ?
	}

	private void setLoginUrl() {
		loginUrl="";
		String webcalldomain = null;
		String username = null;
		try {
			webcalldomain = prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
			Log.d(TAG,"setLoginUrl webcalldomain="+webcalldomain);
		} catch(Exception ex) {
			Log.d(TAG,"# setLoginUrl webcalldomain ex="+ex);
			return;
		}
		try {
			username = prefs.getString("username", "").toLowerCase(Locale.getDefault());
			Log.d(TAG,"setLoginUrl username="+username);
		} catch(Exception ex) {
			Log.d(TAG,"# setLoginUrl username ex="+ex);
			return;
		}

		loginUrl = "https://"+webcalldomain+"/rtcsig/login?id="+username+
					"&ver="+ BuildConfig.VERSION_NAME+"_"+getWebviewVersion(); // +"&re=true";
		Log.d(TAG,"setLoginUrl="+loginUrl);
	}

	private void startReconnecter(boolean wakeIfNoNet, int reconnectDelaySecs) {
		Log.d(TAG,"startReconnecter");
		if(wsClient!=null) {
			// close() the old (damaged) wsClient connection
			// closeBlocking() makes no sense here bc server has stopped sending pings
			WebSocketClient tmpWsClient = wsClient;
			wsClient = null;
			tmpWsClient.close();
		}

		if(haveNetworkInt==0 && wakeIfNoNet && screenForWifiMode>0) {
			Log.d(TAG,"startReconnecter haveNoNetwork: wakeIfNoNet + screenForWifiMode");
			wakeUpFromDoze();
		}

		setLoginUrl();
		if(!reconnectBusy) {
// tmtmtm
			// TODO do we need to copy cookies here?
			if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
				reconnectSchedFuture.cancel(false);
				reconnectSchedFuture = null;
			}
			reconnectSchedFuture = scheduler.schedule(reconnecter, reconnectDelaySecs, TimeUnit.SECONDS);
		} else {
			Log.d(TAG,"startReconnecter no reconnecter: reconnectBusy="+reconnectBusy);
		}
	}

	private void checkLastPing(boolean wakeIfNoNet, int reconnectDelaySecs) {
		if(!connectToServerIsWanted) {
			Log.d(TAG,"checkLastPing !connectToServerIsWanted abort");
			return;
		}
		if(extendedLogsFlag) {
			Log.d(TAG,"checkLastPing");
		}
		boolean needKeepAwake = false;
		boolean needReconnecter = false;
		if(lastPingDate!=null) {
			// if lastPingDate is too old, then there was a network disconnect
			// and the server has given up on us: we need to start reconnecter
			Date newDate = new Date();
			long diffInMillies = Math.abs(newDate.getTime() - lastPingDate.getTime());
			if(diffInMillies > serverPingPeriodPlus*1000) { // 130000ms
				// server pings have dropped, we need to start a reconnecter
				needKeepAwake = true;
				needReconnecter = true;
				Log.d(TAG,"checkLastPing diff="+diffInMillies+"ms TOO OLD");
			} else {
				if(extendedLogsFlag) {
					Log.d(TAG,"checkLastPing diff="+diffInMillies+" < "+(serverPingPeriodPlus*1000));
				}
			}
		}
		if(reconnectBusy) {
			// if we are in a reconnect already (after a detected disconnect)
			// get a KeepAwake wakelock (it will be released automatically)
			Log.d(TAG,"checkLastPing reconnectBusy");
			needKeepAwake = true;
			needReconnecter = false;
		}

		if(needKeepAwake) {
			/*if(charging) {
				Log.d(TAG,"checkLastPing charging, no keepAwakeWakeLock change");
			} else*/ if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
				Log.d(TAG,"checkLastPing keepAwakeWakeLock.acquire");
				keepAwakeWakeLock.acquire(3 * 60 * 1000);
				keepAwakeWakeLockStartTime = (new Date()).getTime();
			} else if(keepAwakeWakeLock!=null) {
				Log.d(TAG,"checkLastPing keepAwakeWakeLock.isHeld");
			} else {
				Log.d(TAG,"checkLastPing cannot keepAwakeWakeLock.acquire");
			}
		}
		if(connectToServerIsWanted && needReconnecter) {
			Log.d(TAG,"checkLastPing startReconnecter");
			startReconnecter(wakeIfNoNet,reconnectDelaySecs);
		}
	}

	private void storeByteArrayToFile(byte[] blobAsBytes, String filename) {
		String androidFolder = Environment.DIRECTORY_DOWNLOADS;
		String mimeType = URLConnection.guessContentTypeFromName(filename);
		String filenameLowerCase = filename.toLowerCase(Locale.getDefault());
		/* right now we do NOT treat jpg/png's different from other files
		if(filenameLowerCase.endsWith(".jpg") || filenameLowerCase.endsWith(".jpeg")) {
			androidFolder = Environment.DIRECTORY_DCIM;
			mimeType = "image/jpg";
		} else if(filenameLowerCase.endsWith(".png")) {
			androidFolder = Environment.DIRECTORY_DCIM;
			mimeType = "image/png";
		}
		*/
		Log.d(TAG,"storeByteArrayToFile filename="+filename+" folder="+androidFolder+" mime="+mimeType);

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { // <10 <api29
			final File dwldsPath = new File(Environment.getExternalStoragePublicDirectory(
				Environment.DIRECTORY_DOWNLOADS) + "/"+ filename);
			Log.d(TAG,"store to "+dwldsPath+" (andr "+Build.VERSION.SDK_INT+" <28)");
			int hasWriteStoragePermission = 0;
			try {
				FileOutputStream os = new FileOutputStream(dwldsPath, false);
				os.write(blobAsBytes);
				os.flush();
				os.close();
				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "file "+filename+" stored in download directory");
				sendBroadcast(intent);
			} catch(Exception ex) {
				// should never happen: activity fetches WRITE_EXTERNAL_STORAGE permission up front
				Log.d(TAG,"storeByteArrayToFile ex="+ex);
				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "exception "+ex);
				sendBroadcast(intent);
			}
		} else {
			// store to download folder in Android 10+
			final Bitmap bitmap;
			final Bitmap.CompressFormat format;

			final ContentValues values = new ContentValues();
			values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
			values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
			values.put(MediaStore.MediaColumns.RELATIVE_PATH, androidFolder);

			final ContentResolver resolver = context.getContentResolver();
			Uri uri = null;

			try {
				final Uri contentUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
				Log.d(TAG,"B store to "+contentUri+" (andr "+Build.VERSION.SDK_INT+" >=29)");
				try {
					uri = resolver.insert(contentUri, values);
				} catch(Exception ex) {
					Log.d(TAG,"resolver.insert ex="+ex);
				}

				if (uri == null)
					throw new IOException("Failed to create new MediaStore record.");

				Log.d(TAG,"C uri="+uri);
				try (final OutputStream os = resolver.openOutputStream(uri)) {
					if (os == null) {
						throw new IOException("Failed to open output stream.");
					}
					os.write(blobAsBytes);
					os.flush();
					os.close();
				}
				//resolver.delete(uri, null, null);

				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "file "+filename+" stored in download directory");
				sendBroadcast(intent);
			}
			catch (IOException ex) {
				Log.d(TAG,"storeByteArrayToFile ex="+ex);
				if (uri != null) {
					// Don't leave an orphan entry in the MediaStore
					resolver.delete(uri, null, null);
				}

				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "exception "+ex);
				sendBroadcast(intent);
			}
		}
	}

	private void queueWebRtcMessage(String message) {
		// we do not queue msgs that start with "missedCalls|"
		if(!message.startsWith("missedCalls|")) {
			stringMessageQueue.add(message);
		}
	}

	// push all queued rtcMessages into callee.js signalingCommand()
	// will be started from wsSend()
	private void processWebRtcMessages() {
		if(myWebView!=null && webviewMainPageLoaded && !stringMessageQueue.isEmpty()) {
			String message = (String)(stringMessageQueue.poll());
			// message MUST NOT contain apostrophe
			String encodedMessage = message.replace("'", "&#39;");
			String argStr = "wsOnMessage2('"+encodedMessage+"','serv-process');";
			Log.d(TAG,"processWebRtcMessages runJS "+argStr);
			/*
			// we wait till runJS has been processed before we runJS the next
			runJS(argStr, new ValueCallback<String>() {
				@Override
				public void onReceiveValue(String s) {
					// continue with next msg
					processWebRtcMessages();
				}
			});
			*/
			// schedule delayed runJS()
			final Runnable runnable2 = new Runnable() {
				public void run() {
					// do runJS and wait till it has been processed before we do the next runJS
					runJS(argStr, new ValueCallback<String>() {
						@Override
						public void onReceiveValue(String s) {
							// continue with next msg
							processWebRtcMessages();
						}
					});
				}
			};
			scheduler.schedule(runnable2, 50l, TimeUnit.MILLISECONDS);

		} else {
			Log.d(TAG,"processWebRtcMessages end");

			if(autoPickup) {
				// reset autoPickup in 5s, so that it can be used in rtcConnect
				// and for it to definitely get cleared, even if rtcConnect does not occur
				final Runnable runnable2 = new Runnable() {
					public void run() {
						Log.d(TAG,"processWebRtcMessages delayed autoPickup=false");
						autoPickup = false;
					}
				};
				scheduler.schedule(runnable2, 5000l, TimeUnit.MILLISECONDS);
			}
		}
	}

	private void wakeUpFromDoze() {
		if(wifiManager.isWifiEnabled()==false) {
			// this is for wakeing up WIFI; if wifi is switched off, doing this does not make sense
			Log.d(TAG,"wakeUpFromDoze denied, wifi is not enabled");
			return;
		}

		// prevent multiple calls
		long nowSecs = new Date().getTime();
		long sinceLastCallSecs = nowSecs - wakeUpFromDozeSecs;
		if(sinceLastCallSecs < 3) {
			// wakeUpFromDoze was executed less than 3secs ago
			Log.d(TAG,"wakeUpFromDoze denied, was called "+sinceLastCallSecs+" secs ago");
			return;
		}
		wakeUpFromDozeSecs = nowSecs;

		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { // <api24
			// is disconnected -> wakeup to help wifi
			// we use this to wake the device so we can establish NEW network connections for reconnect
			// step a: bring webcall activity to front via intent
			Log.d(TAG,"wakeUpFromDoze webcallToFrontIntent");
			Intent webcallToFrontIntent =
				new Intent(context, WebCallCalleeActivity.class).putExtra("wakeup", "wake");
			webcallToFrontIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
				Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY |
				Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
			context.startActivity(webcallToFrontIntent);
		}

		// step b: invoke FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP 
		//          to wake the device (+screen) from deep sleep
		// NOTE: this is needed bc XHR may not work in deep sleep - and bc wifi may not come back
		Log.d(TAG,"wakeUpFromDoze FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP");
		if(powerManager==null) {
			powerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		}

		if(wakeUpWakeLock!=null && wakeUpWakeLock.isHeld()) {
			Log.d(TAG,"wakeUpFromDoze wakeUpWakeLock.release()");
			wakeUpWakeLock.release();
		}
		Log.d(TAG,"wakeUpFromDoze wakeUpWakeLock.acquire(20s)");
		String logKey = "WebCall:wakeUpWakeLock";
		if(userAgentString==null || userAgentString.indexOf("HUAWEI")>=0)
			logKey = "LocationManagerService"; // to avoid being killed on Huawei
		wakeUpWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK|
			PowerManager.ACQUIRE_CAUSES_WAKEUP, logKey);
		wakeUpWakeLock.acquire(10 * 1000);
		// will be released by activity after 3s by calling releaseWakeUpWakeLock()
	}

	private void clearCookies() {
		Log.d(TAG,"clearCookies");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
			CookieManager.getInstance().removeAllCookies(null);
			CookieManager.getInstance().flush();
		} else {
			CookieSyncManager cookieSyncMngr=CookieSyncManager.createInstance(context);
			cookieSyncMngr.startSync();
			CookieManager cookieManager=CookieManager.getInstance();
			cookieManager.removeAllCookie();
			cookieManager.removeSessionCookie();
			cookieSyncMngr.stopSync();
			cookieSyncMngr.sync();
		}
	}

	private Runnable newReconnecter() {
		reconnecter = new Runnable() {
			public void run() {
				if(!connectToServerIsWanted) {
					Log.d(TAG,"! reconnecter start, not wanted "+reconnectCounter+" net="+haveNetworkInt+" "+
						currentDateTimeString());
					return;
				}

				/*
				reconnectSchedFuture = null;
				if(wsClient!=null) {
					Log.d(TAG,"reconnecter already connected");
					reconnectCounter = 0;
					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						reconnectSchedFuture.cancel(false);
						reconnectSchedFuture = null;
					}
					reconnectBusy = false;
					return;
				}
				*/
				reconnectBusy = true;
				Log.d(TAG,"reconnecter start "+reconnectCounter+" net="+haveNetworkInt+" "+
					currentDateTimeString());
				wakeUpOnLoopCount(context);
				reconnectCounter++;

				if(haveNetworkInt<=0) {
					// we have no network: it makes no sense to try to reconnect any longer
					// we just wait for a new-network event via networkCallback or networkStateReceiver
					// we release keepAwakeWakeLock
					if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
						long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
						Log.d(TAG,"reconnecter waiting for net; keepAwakeWakeLock.release +"+wakeMS);
						keepAwakeWakeLockMS += wakeMS;
						storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
						keepAwakeWakeLock.release();
					}

					// for very old Android releases
					if(screenForWifiMode>0) {
						Log.d(TAG,"reconnecter wakeUpFromDoze "+reconnectCounter);
						wakeUpFromDoze();
					}
					if(beepOnLostNetworkMode>0) {
// TODO
						// while playSoundAlarm() plays, a "networkCallback network capab change" may come in
						playSoundAlarm();
					}

					// we check haveNetworkInt again, bc it may have come in during playSoundAlarm()
					if(haveNetworkInt<=0) {
						// we pause reconnecter; if network comes back, checkNetworkState() will
						// schedule a new reconnecter if connectToServerIsWanted is set
						Log.d(TAG,"reconnecter no network, reconnect paused...");
						if(!connectToServerIsWanted) {
							statusMessage("No network. Reconnect paused.",-1,true,false);
						} else {
							statusMessage("No network.",-1,true,false);
						}
						reconnectBusy = false;
						reconnectCounter = 0;
						//runJS("offlineAction();",null); // goOnline enabled, goOffline disabled
						return;
					}
				}

				if(!connectToServerIsWanted) {
					Log.d(TAG,"reconnecter not wanted, aborted");
					return;
				}

				setLoginUrl();
				Log.d(TAG,"reconnecter login "+loginUrl);
				statusMessage("Login...",-1,true,false);
				try {
					URL url = new URL(loginUrl);
					//Log.d(TAG,"reconnecter openCon("+url+")");

					if(insecureTlsFlag) {
						Log.d(TAG,"reconnecter allow insecure https");
						try {
							TrustManager[] trustAllCerts = new TrustManager[] { 
								new X509TrustManager() {
									public X509Certificate[] getAcceptedIssuers() {
										X509Certificate[] myTrustedAnchors = new X509Certificate[0];
										return myTrustedAnchors;
									}
									@Override
									public void checkClientTrusted(X509Certificate[] certs, String authType) {}

									@Override
									public void checkServerTrusted(X509Certificate[] certs, String authType) {}
								}
							};
							SSLContext sslContext = SSLContext.getInstance("TLS");
							sslContext.init(null, trustAllCerts, new SecureRandom());
							SSLSocketFactory factory = sslContext.getSocketFactory();
						    HttpsURLConnection.setDefaultSSLSocketFactory(factory);
						} catch(Exception ex) {
							Log.w(TAG,"reconnecter allow insecure https ex="+ex);
						}
					}

					HttpsURLConnection con = (HttpsURLConnection)url.openConnection();
					con.setConnectTimeout(22000);
					con.setReadTimeout(10000);

					if(insecureTlsFlag) {
						// avoid: "javax.net.ssl.SSLPeerUnverifiedException: Hostname 192.168.0.161 not verified"
						// on reconnect on LineageOS
						con.setHostnameVerifier(new HostnameVerifier() {
							@Override
							public boolean verify(String hostname, SSLSession session) {
								//HostnameVerifier hv = new org.apache.http.conn.ssl.StrictHostnameVerifier();
								//boolean ret = hv.verify("192.168.0.161", session);
								Log.d(TAG,"reconnecter HostnameVerifier accept "+hostname);
								return true;
							}
						});
					}

					CookieManager.getInstance().setAcceptCookie(true);
					if(webviewCookies==null) {
						webviewCookies = CookieManager.getInstance().getCookie(loginUrl);
					}
					if(webviewCookies!=null) {
						//if(extendedLogsFlag) {
							Log.d(TAG,"reconnecter con.setRequestProperty(webviewCookies)");
						//}
						con.setRequestProperty("Cookie", webviewCookies);
						storePrefsString("cookies", webviewCookies);
					} else {
						String newWebviewCookies = prefs.getString("cookies", "");
						//if(extendedLogsFlag) {
							Log.d(TAG,"reconnecter con.setRequestProperty(prefs:cookies)");
						//}
						con.setRequestProperty("Cookie", newWebviewCookies);
					}
					con.setRequestProperty("Connection", "close"); // this kills keep-alives TODO???
					BufferedReader reader = null;
					if(!reconnectBusy) {
						Log.d(TAG,"reconnecter abort");
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
							reconnectSchedFuture.cancel(false);
						}
						reconnectSchedFuture = null;
						reconnectBusy = false;
						return;
					}

					if(!connectToServerIsWanted) {
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);
						}
						reconnectSchedFuture = null;
						reconnectCounter = 0;
						reconnectBusy = false;
						return;
					}
					int status=0;
					try {
						Log.d(TAG,"reconnecter con.connect()");
						con.connect();
						status = con.getResponseCode();
						if(!connectToServerIsWanted) {
							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								reconnectSchedFuture.cancel(false);
							}
							reconnectSchedFuture = null;
							reconnectCounter = 0;
							reconnectBusy = false;
							return;
						}
						if(status!=200) {
							Log.d(TAG,"reconnecter http login statusCode="+status+" fail");
						} else {
							Log.d(TAG,"reconnecter http login statusCode="+status+" OK");
							try {
								reader = new BufferedReader(
									new InputStreamReader(con.getInputStream()));
							} catch(Exception ex) {
								// for instance java.net.SocketTimeoutException
								Log.d(TAG,"reconnecter con.getInputStream() retry ex="+ex);
								reader = new BufferedReader(
									new InputStreamReader(con.getInputStream()));
							}
						}
					} catch(Exception ex) {
						status = 0;
						Log.d(TAG,"reconnecter con.connect()/getInputStream() ex="+ex);
						// in some cases it DOES NOT make sense to continue reconnecter
						// javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException:
						//   Trust anchor for certification path not found.

						// in many other cases it DOES make sense to continue reconnecter
						// java.net.ConnectException: failed to connect to /192.168.0.161 (port 8068)
						//   after 22000ms: isConnected failed: EHOSTUNREACH (No route to host)
						//
						// java.net.ConnectException: failed to connect to /192.168.0.161 (port 8068)
						//   after 22000ms: isConnected failed: ECONNREFUSED (Connection refused)
						//
						// java.net.ConnectException: Failed to connect to /192.168.0.161:8068
						//
						// javax.net.ssl.SSLHandshakeException: Chain validation failed

						String exString = ex.toString();
						//if(exString.indexOf("SSLHandshakeException")>=0) {
						if(exString.indexOf("Trust anchor for certification path not found")>=0) {
							// turn reconnecter off
// TODO: java.net.UnknownHostException: Unable to resolve host "hostname.com": No address associated with hostname
// happens on P9 lite on LineageOS due to bug in wifi driver
							connectToServerIsWanted = false;
							storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
						} else {
							// keep reconnecter running
						}
					}

					if(!connectToServerIsWanted) {
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);
						}
						reconnectSchedFuture = null;
						reconnectCounter = 0;
						reconnectBusy = false;
						return;
					}

					if(status!=200) {
						// network error: retry login
						if(wsClient!=null) {
							// wsClient must be null before we start reconnecter
							wsClient.close();
							wsClient = null;
						}
						if(reconnectCounter < ReconnectCounterMax) {
							int delaySecs = reconnectCounter*10;
							if(delaySecs>ReconnectDelayMaxSecs) {
								delaySecs = ReconnectDelayMaxSecs;
							}
							Log.d(TAG,"reconnecter reconnect status="+status+" retry in "+delaySecs+"sec");

							boolean prio = false;
							if(reconnectCounter==ReconnectCounterBeep) {
								prio = true;
							}
							statusMessage("Failed to reconnect. Will try again...",-1,true,prio);
							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								Log.d(TAG,"cancel old schedFuture");
								reconnectSchedFuture.cancel(false);
								reconnectSchedFuture = null;
							} else {
								Log.d(TAG,"no old schedFuture to cancel");
							}
							reconnectSchedFuture =
								scheduler.schedule(reconnecter, delaySecs, TimeUnit.SECONDS);
							if(reconnectSchedFuture==null) {
								Log.d(TAG,"scheduled reconnect in "+delaySecs+"sec reconnectSchedFuture==null");
							} else {
								Log.d(TAG,"scheduled reconnect in "+delaySecs+"sec done="+reconnectSchedFuture.isDone());
							}
							return;
						}
						Log.d(TAG,"reconnecter con.connect() fail. give up.");
						if(reconnectBusy) {
							if(beepOnLostNetworkMode>0) {
								playSoundAlarm();
							}
							statusMessage("Gave up reconnecting",-1,true,true);
							if(myWebView!=null && webviewMainPageLoaded) {
								// offlineAction(): disable offline-button and enable online-button
								runJS("offlineAction();",null);
							}
							reconnectBusy = false;
							// turn reconnecter off
							connectToServerIsWanted = false;
							// TODO: not sure about this
							//storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
						}
						if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
							long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
							Log.d(TAG,"reconnecter keepAwakeWakeLock.release +"+wakeMS);
							keepAwakeWakeLockMS += wakeMS;
							storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
							keepAwakeWakeLock.release();
						}
						reconnectCounter = 0;
						return;
					}

					// status==200
					if(!connectToServerIsWanted || !reconnectBusy) {
						// abort forced
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);
						}
						reconnectSchedFuture = null;
						reconnectCounter = 0;
						reconnectBusy = false;
						return;
					}

					String response = reader.readLine();
					String[] tokens = response.split("\\|"); // this means split on pipe (backslash as escape)
					Log.d(TAG,"reconnecter response tokens length="+tokens.length);
					wsAddr = tokens[0];

					if(wsAddr.equals("fatal") || wsAddr.equals("error") || wsAddr.equals("busy") ||
					   wsAddr.equals("noservice") || wsAddr.equals("notregistered") || tokens.length<3) {
						// login error: give up reconnecter
						boolean wasReconnectBusy = reconnectBusy;
						reconnectBusy = false;
						reconnectCounter = 0;
						connectToServerIsWanted = false;
						storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
						Log.d(TAG,"reconnecter login fail '"+wsAddr+"' give up "+reader.readLine()+
							" "+reader.readLine()+" "+reader.readLine()+" "+reader.readLine());
						statusMessage("Gave up reconnecting. "+response,-1,true,true);

						if(myWebView!=null /*&& webviewMainPageLoaded*/) {
							// offlineAction(): disable offline-button and enable online-button
							runJS("offlineAction();", new ValueCallback<String>() {
								@Override
								public void onReceiveValue(String s) {
									if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
										long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
										Log.d(TAG,"reconnecter keepAwakeWakeLock.release +"+wakeMS);
										keepAwakeWakeLockMS += wakeMS;
										storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);

										if(wasReconnectBusy) {
											if(beepOnLostNetworkMode>0) {
												playSoundAlarm();
											}
										}
										if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
											keepAwakeWakeLock.release();
										}
									}
								}
							});
						} else {
							if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
								long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
								Log.d(TAG,"reconnecter keepAwakeWakeLock.release +"+wakeMS);
								keepAwakeWakeLockMS += wakeMS;
								storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);

								if(wasReconnectBusy) {
									if(beepOnLostNetworkMode>0) {
										playSoundAlarm();
									}
								}
								keepAwakeWakeLock.release();
							}
						}
						return;
					}

					if(!connectToServerIsWanted || !reconnectBusy) {
						// abort forced
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);
						}
						reconnectSchedFuture = null;
						reconnectCounter = 0;
						reconnectBusy = false;
						return;
					}

					statusMessage("Connecting..",-1,true,false);
					//Log.d(TAG,"reconnecter connectHost("+wsAddr+")");
					// connectHost() will send updateNotification()
					// connectHost() will set and return wsClient on success
					connectHost(wsAddr,true);

					if(wsClient==null) {
						// fail
						if(reconnectCounter<ReconnectCounterMax) {
							int delaySecs = reconnectCounter*10;
							if(delaySecs>ReconnectDelayMaxSecs) {
								delaySecs = ReconnectDelayMaxSecs;
							}
							Log.d(TAG,"reconnecter reconnect retry in "+delaySecs+"sec");

							//Log.d(TAG,"reconnecter connectHost() fail - retry...");
							boolean prio = false;
							if(reconnectCounter==ReconnectCounterBeep) {
								prio = true;
							}
							statusMessage("Failed to reconnect. Will try again...",-1,true,prio);

							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								reconnectSchedFuture.cancel(false);
								reconnectSchedFuture = null;
							}
							reconnectSchedFuture =
								scheduler.schedule(reconnecter, delaySecs, TimeUnit.SECONDS);
							return;
						}
						Log.d(TAG,"reconnecter connectHost() fail - give up");
						if(reconnectBusy) {
							if(beepOnLostNetworkMode>0) {
								playSoundAlarm();
							}
							statusMessage("Gave up reconnecting.",-1,true,true);
							if(myWebView!=null && webviewMainPageLoaded) {
								// offlineAction(): disable offline-button and enable online-button
								runJS("offlineAction();",null);
							}
						}
						reconnectBusy = false;
						reconnectCounter = 0;

						Intent brintent = new Intent("webcall");
						brintent.putExtra("state", "disconnected");
						sendBroadcast(brintent);
						return;
					}

					// success - wsClient is set

					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						reconnectSchedFuture.cancel(false);
						reconnectSchedFuture = null;
					}

					if(currentUrl==null) {
						setLoginUrl();
						currentUrl = loginUrl;
						Log.d(TAG,"reconnecter set currentUrl="+currentUrl);
					}

					// full success
					reconnectBusy = false;
					reconnectCounter = 0;
					Log.d(TAG,"reconnecter connectHost() success net="+haveNetworkInt);
					//statusMessage("Reconnect to server",500,true,false);	// TODO statusMessage needed ???

					// we trust now that server will receive "init" and respond with "sessionId|"+codetag
					// onMessage() will receive this and call runJS(wsOnMessage2('sessionId|v3.5.5','serv-direct');)
					// this should call calleeIsConnected() - but this does not always work
// tmtmtm TODO calleeIsConnected() is not always called

					// calleeIsConnected() will send awaitingCalls notification
					// calleeIsConnected() will brodcast state connected

				} catch(Exception ex) {
					// this can be caused by webview not installed or just now uninstalled
					// "android.webkit.WebViewFactory$MissingWebViewPackageException: "
					//   "Failed to load WebView provider: No WebView installed

					Intent brintent = new Intent("webcall");
					brintent.putExtra("state", "disconnected");
					sendBroadcast(brintent);

					if(!connectToServerIsWanted) {
						// abort forced
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);
						}
						reconnectSchedFuture = null;
						reconnectCounter = 0;
						reconnectBusy = false;
						return;
					}

					// if "No WebView installed" we abort reconnecter
					String exString = ex.toString();
					if(exString.indexOf("No WebView installed")>=0) {
						reconnectCounter = ReconnectCounterMax;
					}

					ex.printStackTrace();
					if(reconnectCounter<ReconnectCounterMax) {
						int delaySecs = reconnectCounter*10;
						if(delaySecs>ReconnectDelayMaxSecs) {
							delaySecs = ReconnectDelayMaxSecs;
						}
						Log.d(TAG,"reconnecter reconnect ex="+ex+" retry in "+delaySecs+"sec");

						boolean prio = false;
						if(reconnectCounter==ReconnectCounterBeep) {
							prio = true;
						}
						statusMessage("Failed to reconnect. Will try again...",-1,true,prio);

						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							reconnectSchedFuture.cancel(false);
							reconnectSchedFuture = null;
						}
						reconnectSchedFuture =
							scheduler.schedule(reconnecter, delaySecs, TimeUnit.SECONDS);
						return;
					}
					Log.d(TAG,"reconnecter reconnect ex="+ex+" give up");
					if(reconnectBusy) {
						if(beepOnLostNetworkMode>0) {
							playSoundAlarm();
						}
						statusMessage("Gave up reconnecting.",-1,true,true);
						if(myWebView!=null && webviewMainPageLoaded) {
							// offlineAction(): disable offline-button and enable online-button
							runJS("offlineAction();",null);
						}
						if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
							long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
							Log.d(TAG,"reconnecter keepAwakeWakeLock.release +"+wakeMS);
							keepAwakeWakeLockMS += wakeMS;
							storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
							keepAwakeWakeLock.release();
						}
						reconnectBusy = false;
					}
					reconnectCounter = 0;
					return;
				}

				// send 'init' to register as callee
				// otherwise the server will kick us out
				Log.d(TAG,"reconnecter send init "+(myWebView!=null)+" "+webviewMainPageLoaded);
				try {
					wsClient.send("init|");
// TODO server expected to send "sessionId|(serverCodetag)"

					if(myWebView!=null && webviewMainPageLoaded) {
						Log.d(TAG,"reconnecter call js:wakeGoOnlineNoInit()...");
						// wakeGoOnlineNoInit() makes sure:
						// - js:wsConn is set (to wsClient)
						// - UI in online state (green led + goOfflineButton enabled)
						runJS("wakeGoOnlineNoInit();", new ValueCallback<String>() {
							@Override
							public void onReceiveValue(String s) {
								if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
									long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
									Log.d(TAG,"reconnecter keepAwakeWakeLock.release 2 +"+wakeMS);
									keepAwakeWakeLockMS += wakeMS;
									storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
									if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
										keepAwakeWakeLock.release();
									}
								}
							}
						});
					}

					if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
						long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
						Log.d(TAG,"reconnecter keepAwakeWakeLock.release 2 +"+wakeMS);
						keepAwakeWakeLockMS += wakeMS;
						storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
						keepAwakeWakeLock.release();
					}
				} catch(Exception ex) {
					Log.d(TAG,"reconnecter send init ex="+ex);
					// ignore
				}
				return;
			} // end of run()
		};
		return reconnecter;
	}

	private WebSocketClient connectHost(String setAddr, boolean auto) {
		Log.d(TAG,"connectHost("+setAddr+")");
		try {
			if(!setAddr.equals("")) {
				wsAddr = setAddr;
				if(auto) {
					// service reconnect: set auto=true telling server this is not a manual login
					wsAddr += "&auto=true";
				}
				wsClient = new WsClient(new URI(wsAddr));
			}
			if(wsClient==null) {
				Log.e(TAG,"# connectHost wsClient==null");
				return null;
			}
			// client-side ping-interval (default: 60 seconds)
			// see: https://github.com/TooTallNate/Java-WebSocket/wiki/Lost-connection-detection
			wsClient.setConnectionLostTimeout(0); // we turn off client pings

			if(setAddr.startsWith("wss")) {
				if(insecureTlsFlag) {
					Log.d(TAG,"connectHost allow insecure wss");
					try {
						TrustManager[] trustAllCerts = new TrustManager[] { 
							new X509TrustManager() {
								public X509Certificate[] getAcceptedIssuers() {
									X509Certificate[] myTrustedAnchors = new X509Certificate[0];
									return myTrustedAnchors;
								}
								@Override
								public void checkClientTrusted(X509Certificate[] certs, String authType) {}

								@Override
								public void checkServerTrusted(X509Certificate[] certs, String authType) {}
							}
						};
						SSLContext sslContext = SSLContext.getInstance("TLS");
						sslContext.init(null, trustAllCerts, new SecureRandom());
						SSLSocketFactory factory = sslContext.getSocketFactory();
						wsClient.setSocket(factory.createSocket());
						// onSetSSLParameters() will now be called
					} catch(Exception ex) {
						Log.w(TAG,"connectHost allow insecure wss ex="+ex);
					}
				}
			}

			Log.d(TAG,"connectHost connectBlocking...");
			boolean isOpen = wsClient.connectBlocking();
			// ssl error: onError ex javax.net.ssl.SSLHandshakeException:
			// java.security.cert.CertPathValidatorException: Trust anchor for certification path not found
			Log.d(TAG,"connectHost connectBlocking done isOpen="+isOpen);
			if(isOpen) {
			// Self hostVerify
			// the next 25 lines (and the override of onSetSSLParameters below) 
			// are only needed for API < 24 "N"
			// github.com/TooTallNate/Java-WebSocket/wiki/No-such-method-error-setEndpointIdentificationAlgorithm
				boolean hostVerifySuccess = true;
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { // < 24 (< Android 7)
					Log.d(TAG,"connectHost self hostVerify");
					HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
					SSLSocket socket = (SSLSocket)wsClient.getSocket();
					SSLSession s = socket.getSession();
					// self-hostVerify is using 
					// hostName from wsAddr (wss://timur.mobi:8443/ws?wsid=5367...)
					String hostName = "timur.mobi"; // default
					int idxDblSlash = wsAddr.indexOf("//");
					if(idxDblSlash>0) {
						hostName = wsAddr.substring(idxDblSlash+2);
						int idxColon = hostName.indexOf(":");
						if(idxColon<0) {
							idxColon = hostName.indexOf("/");
						}
						if(idxColon>0) {
							hostName = hostName.substring(0,idxColon);
						}
					}
					Log.d(TAG,"connectHost hostName "+hostName);
					if(!hv.verify(hostName, s)) {
						Log.d(TAG,"connectHost self-hostVerify fail on "+s.getPeerPrincipal());
						hostVerifySuccess = false;
					}
				}

				if(hostVerifySuccess) {
					Log.d(TAG,"connectHost hostVerify Success net="+haveNetworkInt);
					audioToSpeakerSet(audioToSpeakerMode>0,false);

					if(currentUrl==null) {
						setLoginUrl();
						currentUrl = loginUrl;
						Log.d(TAG,"connectHost set currentUrl="+currentUrl);
					}

					if(currentUrl!=null) {
						if(extendedLogsFlag) {
							Log.d(TAG,"connectHost get cookies from currentUrl="+currentUrl);
						}
						if(!currentUrl.equals("")) {
							webviewCookies = CookieManager.getInstance().getCookie(currentUrl);
							if(extendedLogsFlag) {
								Log.d(TAG,"connectHost webviewCookies="+webviewCookies);
							}
							if(webviewCookies!=null && !webviewCookies.equals("")) {
								storePrefsString("cookies", webviewCookies);
							}
						}
					}

					if(haveNetworkInt==2) {
						// we are connected over wifi
						if(setWifiLockMode<=0) {
							Log.d(TAG,"connectHost WifiLockMode off");
						} else if(wifiLock==null) {
							Log.d(TAG,"connectHost wifiLock==null");
						} else if(wifiLock.isHeld()) {
							Log.d(TAG,"connectHost wifiLock isHeld");
						} else {
							// enable wifi lock
							Log.d(TAG,"connectHost wifiLock.acquire");
							wifiLock.acquire();
						}
					}

					long diffInMillies = 0;
					if(alarmPendingDate!=null) {
						diffInMillies = Math.abs(new Date().getTime() - alarmPendingDate.getTime());
						if(diffInMillies > 18*60*1000) {
							// an alarm is already set, but it is too old
							if(pendingAlarm!=null) {
								alarmManager.cancel(pendingAlarm);
								pendingAlarm = null;
							}
							alarmPendingDate = null;
						}
					}
					if(alarmPendingDate==null) {
						pendingAlarm =
						  PendingIntent.getBroadcast(context, 0, startAlarmIntent, PendingIntent.FLAG_IMMUTABLE);
						if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
							if(extendedLogsFlag) {
								Log.d(TAG,"connectHost alarm setAndAllowWhileIdle");
							}
							alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
								SystemClock.elapsedRealtime() + 15*60*1000, pendingAlarm);
						} else {
							// for Android 5 and below only:
							if(extendedLogsFlag) {
								Log.d(TAG,"connectHost alarm set");
							}
							// 15*60*1000 will be very likely be ignored; P9 does minimal 16min
							alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
								SystemClock.elapsedRealtime() + 15*60*1000, pendingAlarm);
						}
						alarmPendingDate = new Date();
					} else {
						if(extendedLogsFlag) {
							Log.d(TAG,"connectHost alarm pending age="+diffInMillies);
						}
					}

					// an alarm event (checkLastPing) striking now could report "diff TOO OLD"
					// to prevent this from happening:
					lastPingDate = new Date();

					// when callee sends init and gets a confirmation
					// it will call calleeConnected() / calleeIsConnected()
					// then we will send: updateNotification awaitingCalls
					// then we will broadcast: "state", "connected"

					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
						// create notificationChannel to start service in foreground
						Log.d(TAG,"onStartCommand startForeground");
						startForeground(NOTIF_ID,buildFgServiceNotification("","",false));
					}

					return wsClient;
				}
			}
		} catch(URISyntaxException ex) {
			Log.e(TAG,"connectHost URISyntaxException",ex);
		} catch(InterruptedException ex) {
			Log.e(TAG,"connectHost InterruptedException",ex);
		} catch(SSLPeerUnverifiedException ex) {
			Log.e(TAG,"connectHost SSLPeerUnverifiedException",ex);
		}

		Log.d(TAG,"connectHost fail, return null");
		wsClient = null;

		updateNotification("Offline",false);

		Intent brintent = new Intent("webcall");
		brintent.putExtra("state", "disconnected");
		sendBroadcast(brintent);

		return null;
	}

	// checkNetworkState() is for API <= 23 (Android 6) only; for higher API's we use networkCallback
	private void checkNetworkState(boolean restartReconnectOnNetwork) {
		// sets haveNetworkInt = 0,1,2,3
		// if wifi connected -> wifiLock.acquire(), otherwise -> wifiLock.release()
		// on gain of any network: call scheduler.schedule(reconnecter)
		// but checkNetworkState() is not reliable
		// problem: wifi icon visible but netActiveInfo==null
		// problem: wifi icon NOT visible but netTypeName=="WIFI"
		if(extendedLogsFlag) {
			Log.d(TAG,"checkNetworkState");
		}

/* TODO: latest: cm.getNetworkCapabilities(cm.activeNetwork).hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
		ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
		    if (cm != null) {
		        NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
		        if (capabilities != null) {
		            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
		                result = 2;
		            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
		                result = 1;
		            } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
		                result = 3;
		            }
		        }
		    }
		} else {
		    if (cm != null) {
		        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
		        if (activeNetwork != null) {
		            // connected to the internet
		            if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
		                result = 2;
		            } else if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) {
		                result = 1;
		            } else if (activeNetwork.getType() == ConnectivityManager.TYPE_VPN) {
		                result = 3;
		            }
		        }
		    }
		}
*/
		NetworkInfo netActiveInfo = connectivityManager.getActiveNetworkInfo();
		NetworkInfo wifiInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		NetworkInfo mobileInfo = connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
		if((netActiveInfo==null || !netActiveInfo.isConnected()) &&
				(wifiInfo==null || !wifiInfo.isConnected()) &&
				(mobileInfo==null || !mobileInfo.isConnected())) {
			// no network is connected
			Log.d(TAG,"networkState netActiveInfo/wifiInfo/mobileInfo==null "+wsClient+" "+reconnectBusy);
			if(connectToServerIsWanted) {
				statusMessage("No network",-1,true,false);
			}
			if(wifiLock!=null && wifiLock.isHeld() && !connectToServerIsWanted) {
				// release wifi lock
				Log.d(TAG,"networkState wifiLock.release");
				wifiLock.release();
			}
			haveNetworkInt=0;
			return;
		}

		String netTypeName = "";
		if(netActiveInfo!=null) {
			netTypeName = netActiveInfo.getTypeName();
		}
		if(extendedLogsFlag) {
			Log.d(TAG,"networkState netActiveInfo="+netActiveInfo+" "+(wsClient!=null) +
				" "+reconnectBusy+" "+netTypeName);
		}
		if((netTypeName!=null && netTypeName.equalsIgnoreCase("WIFI")) ||
				(wifiInfo!=null && wifiInfo.isConnected())) {
			// wifi is connected: need wifi-lock
			if(haveNetworkInt==2) {
				return;
			}
			Log.d(TAG,"networkState connected to wifi");
			haveNetworkInt=2;
			if(setWifiLockMode<=0) {
				Log.d(TAG,"networkState WifiLockMode off");
			} else if(wifiLock==null) {
				Log.d(TAG,"networkState wifiLock==null");
			} else if(wifiLock.isHeld()) {
				Log.d(TAG,"networkState wifiLock isHeld");
			} else {
				// enable wifi lock
				Log.d(TAG,"networkState wifiLock.acquire");
				wifiLock.acquire();
			}
			if(connectToServerIsWanted && !reconnectBusy) {
				// if we are supposed to be connected and A) reconnecter is NOT in progress 
				// or B) reconnecter IS in progress, but is waiting idly for network to come back
				if(restartReconnectOnNetwork) {
					if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
						Log.d(TAG,"networkState connected to wifi keepAwakeWakeLock.acquire");
						keepAwakeWakeLock.acquire(3 * 60 * 1000);
						keepAwakeWakeLockStartTime = (new Date()).getTime();
					}
					if(!reconnectBusy) {
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							// why wait for the scheduled reconnecter (in 8 or 60s)?
							// let's cancel it and start a new one right away
							Log.d(TAG,"networkState connected to wifi cancel reconnectSchedFuture");
							if(reconnectSchedFuture.cancel(false)) {
								// cancel successful - run reconnecter right away
								Log.d(TAG,"networkState connected to wifi restart recon");
								reconnectSchedFuture = scheduler.schedule(reconnecter, 0, TimeUnit.SECONDS);
							}
						} else {
							Log.d(TAG,"networkState connected to wifi restart recon");
							reconnectSchedFuture = scheduler.schedule(reconnecter, 0, TimeUnit.SECONDS);
						}
					} else {
						Log.d(TAG,"networkState connected wifi: no reconnecter: reconnectBusy="+reconnectBusy);
					}
				}
			} else {
				// if we are NOT supposed to be connected 
				// or we are, but reconnecter is in progress and is NOT waiting for network to come back
				Log.d(TAG,"networkState wifi !connectToServerIsWanted "+connectToServerIsWanted);
			}

		} else if((netActiveInfo!=null && netActiveInfo.isConnected()) ||
				(mobileInfo!=null && mobileInfo.isConnected())) {
			// connected via mobile (or whatever) no need for wifi-lock
			if(haveNetworkInt==1) {
				return;
			}
			Log.d(TAG,"networkState connected to something other than wifi");
			if(wifiLock!=null && wifiLock.isHeld()) {
				// release wifi lock
				Log.d(TAG,"networkState wifiLock.release");
				wifiLock.release();
			}
			haveNetworkInt=1;
			if(connectToServerIsWanted && !reconnectBusy) {
				// we don't like to wait for the scheduled reconnecter job
				// let's cancel it and start it right away
				if(restartReconnectOnNetwork) {
					if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
						Log.d(TAG,"networkState connected to net keepAwakeWakeLock.acquire");
						keepAwakeWakeLock.acquire(3 * 60 * 1000);
						keepAwakeWakeLockStartTime = (new Date()).getTime();
					}
					if(!reconnectBusy) {
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							// why wait for the scheduled reconnecter job
							// let's cancel and start it right away
							Log.d(TAG,"networkState connected to net cancel reconnectSchedFuture");
							if(reconnectSchedFuture.cancel(false)) {
								// cancel successful - run reconnecter right away
								Log.d(TAG,"networkState connected to net restart recon");
								reconnectSchedFuture = scheduler.schedule(reconnecter, 0, TimeUnit.SECONDS);
							}
						} else {
							Log.d(TAG,"networkState connected to net restart recon");
							reconnectSchedFuture = scheduler.schedule(reconnecter, 0, TimeUnit.SECONDS);
						}
					} else {
						Log.d(TAG,"networkState connected other: no reconnecter: reconnectBusy="+reconnectBusy);
					}
				}
			} else {
				Log.d(TAG,"networkState mobile !connectToServerIsWanted or reconnectBusy");
			}

		} else {
			// no network at all
			// if we should be connected to webcall server, we need to do something
			statusMessage("No network",-1,true,false);
			Log.d(TAG,"networkState connected to nothing - auto wake...");
			// this will make onClose postpone reconnect attempts
			haveNetworkInt=0;
		}
	}

	private void disconnectHost(boolean sendNotification) {
		// called by wsClose() and wsExit()
		Log.d(TAG,"disconnectHost...");
		calleeIsReady = false;
		if(pendingAlarm!=null) {
			alarmManager.cancel(pendingAlarm);
			pendingAlarm = null;
			alarmPendingDate = null;
		}

		// if reconnect loop is running, cancel it
		if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
			Log.d(TAG,"disconnectHost reconnectSchedFuture.cancel");
			reconnectSchedFuture.cancel(false);
			reconnectSchedFuture = null;
			statusMessage("Stopped reconnecting",-1,true,false); // manually
		}

		if(wsClient!=null) {
			// disable networkStateReceiver
			if(networkStateReceiver!=null) {
				Log.d(TAG,"disconnectHost unregister networkStateReceiver");
				unregisterReceiver(networkStateReceiver);
				networkStateReceiver = null;
			}
			// clearing wsClient, so that onClose (triggered by closeBlocking()) won't start new wakeIntent
			WebSocketClient tmpWsClient = wsClient;
			wsClient = null;
			try {
				Log.d(TAG,"disconnectHost wsClient.closeBlocking");
				tmpWsClient.closeBlocking();
			} catch(InterruptedException ex) {
				Log.e(TAG,"# disconnectHost InterruptedException",ex);
			}
		}

		// this is needed for wakelock and wifilock to be released
		reconnectBusy = false;
		if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
			long wakeMS = (new Date()).getTime() - keepAwakeWakeLockStartTime;
			Log.d(TAG,"disconnectHost keepAwakeWakeLock.release +"+wakeMS);
			keepAwakeWakeLockMS += wakeMS;
			storePrefsLong("keepAwakeWakeLockMS", keepAwakeWakeLockMS);
			keepAwakeWakeLock.release();
		}
		if(wifiLock!=null && wifiLock.isHeld()) {
			// release wifi lock
			Log.d(TAG,"disconnectHost wifiLock.release");
			wifiLock.release();
		}

		Intent brintent = new Intent("webcall");
		brintent.putExtra("state", "disconnected");
		sendBroadcast(brintent);

		statusMessage("Offline", -1, sendNotification,false);
		lastStatusMessage = "";

// TODO tmtmtm: should any of these unregister methods (see onDestroy()) need to be called?
//		alarmReceiver serviceCmdReceiver networkStateReceiver
//      powerConnectionReceiver dozeStateReceiver myNetworkCallback mBinder

		// remove the Android notification
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			Log.d(TAG,"disconnectHost stopForeground()");
			stopForeground(true); // true = removeNotification

			// without notificationManager.cancel(NOTIF_ID) our notification icon will not go
			Log.d(TAG,"disconnectHost notificationManager.cancel(NOTIF_ID)");
			NotificationManager notificationManager =
				(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
			notificationManager.cancel(NOTIF_ID);
		}

		Log.d(TAG,"disconnectHost done");
	}

	private void endPeerConAndWebView() {
		// hangup peercon, reset webview, clear callPickedUpFlag
		// called by Exit button
		Log.d(TAG, "endPeerConAndWebView");
		if(wsClient!=null) {
			if(myWebView!=null && webviewMainPageLoaded) {
				Log.d(TAG, "endPeerConAndWebView runJS('endWebRtcSession()')");
				// we need to call endPeerConAndWebView2() after JS:endWebRtcSession() returns
				runJS("endWebRtcSession(true,false)", new ValueCallback<String>() {
					@Override
					public void onReceiveValue(String s) {
						endPeerConAndWebView2();
					}
				});
			} else {
				// should never get here, but safety first
				Log.d(TAG, "endPeerConAndWebView myWebView==null')");
				endPeerConAndWebView2();
			}
		} else {
			Log.d(TAG, "endPeerConAndWebView wsClient==null");
			endPeerConAndWebView2();
		}
	}

	private void endPeerConAndWebView2() {
		// reset session (otherwise android could cache these and next start will not open correctly)
		Log.d(TAG, "endPeerConAndWebView2()");
		myWebView = null;
		webviewMainPageLoaded = false;
		callPickedUpFlag=false;
	}

	private void runJS(final String str, final ValueCallback<String> myBlock) {
		// str can be very long, we just log the 1st 30 chars
		String logstr = str;
		if(logstr.length()>40) {
			logstr = logstr.substring(0,40);
		}
		if(myWebView==null) {
			Log.d(TAG, "# runJS("+logstr+") but no webview");
		} else if(!webviewMainPageLoaded && !str.equals("history.back()")) {
			Log.d(TAG, "# runJS("+logstr+") but no webviewMainPageLoaded");
		} else {
			Log.d(TAG, "runJS("+logstr+") post...");
			myWebView.post(new Runnable() {
				@Override
				public void run() {
					// escape '\r\n' to '\\r\\n'
					final String str2 = str.replace("\\", "\\\\");
					//Log.d(TAG,"runJS evalJS "+str2);
					if(myWebView==null) {
						Log.d(TAG,"# runJS evalJS "+str2+" but no myWebView");
					} else if(!webviewMainPageLoaded && !str.equals("history.back()")) {
						Log.d(TAG,"# runJS evalJS "+str2+" but no webviewMainPageLoaded (and not history.back())");
					} else {
						// evaluateJavascript() instead of loadUrl()
						//Log.d(TAG,"runJS evalJS exec "+str2);
						myWebView.evaluateJavascript(str2, myBlock);
					}
				}
			});
		}
	}

	private void wakeUpOnLoopCount(Context context) {
		// when reconnecter keeps looping without getting connected we are probably in doze mode
		// at loop number ReconnectCounterBeep we want to create a beep
		// at loop number ReconnectCounterScreen we want to try wakeUpFromDoze()
		boolean probablyInDoze = false;
		if(haveNetworkInt<=0) {
			probablyInDoze = true;
		} else if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M && powerManager.isDeviceIdleMode()) {
			probablyInDoze = true;
		} else if(!isScreenOn()) {
			probablyInDoze = true;
		} else if(!isPowerConnected(context)) {
			probablyInDoze = true;
		}

		Log.d(TAG,"wakeUpOnLoopCount net="+haveNetworkInt+" inDoze="+probablyInDoze);
		if(probablyInDoze) {
			if(reconnectCounter==ReconnectCounterBeep) {
				if(beepOnLostNetworkMode>0) {
					// playSoundNotification(); // currently deactivated
				}
			} else if(reconnectCounter==ReconnectCounterScreen) {
				Log.d(TAG,"wakeUpOnLoopCount (no net + reconnectCounter==ReconnectCounterScreen)");
				if(screenForWifiMode>0) {
					wakeUpFromDoze();
				}
			}
		}
	}

	private boolean isScreenOn() {
		for(Display display : displayManager.getDisplays()) {
			//Log.d(TAG,"isScreenOff state="+display.getState());
			// STATE_UNKNOWN = 0
			// STATE_OFF = 1
			// STATE_ON = 2
			// STATE_DOZE = 3
			// STATE_DOZE_SUSPEND = 4
			// STATE_VR = 5 (api 26)
			// STATE_ON_SUSPEND = 6 (api 28)
			if(display.getState() == Display.STATE_ON) { // == 2
				return true;
			}
		}
		return false;
	}

	private boolean isPowerConnected(Context context) {
		Intent intent = context.registerReceiver(null,
			new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
		return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
				plugged == BatteryManager.BATTERY_PLUGGED_USB;
	}

	@SuppressWarnings({"unchecked", "JavaReflectionInvocation"})
	private void audioToSpeakerSet(boolean set, boolean showUser) {
		// this is used for ringOnSpeakerOn
		// set=false: route audio to it's normal destination (to headset if connected)
		// set=true:  route audio to speaker (even if headset is connected)
		// called by callPickedUp()
		if(extendedLogsFlag) {
			Log.d(TAG,"audioToSpeakerSet "+set+" (prev="+audioToSpeakerActive+")");
		}
		if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) { // <= 27
			// this works on Android 5-8 but not on Android 9+
			try {
				Class audioSystemClass = Class.forName("android.media.AudioSystem");
				java.lang.reflect.Method setForceUse =
					audioSystemClass.getMethod("setForceUse", int.class, int.class);
				if(set) {
					setForceUse.invoke(null, 1, 1); // FOR_MEDIA, FORCE_SPEAKER
					if(showUser) {
						Intent intent = new Intent("webcall");
						intent.putExtra("toast", "Ring on speaker activated");
						sendBroadcast(intent);
					}
				} else {
					setForceUse.invoke(null, 1, 0); // FOR_MEDIA, DON'T FORCE_SPEAKER
					if(showUser) {
						Intent intent = new Intent("webcall");
						intent.putExtra("toast", "Ring on speaker disabled");
						sendBroadcast(intent);
					}
				}
				audioToSpeakerActive = set;
				Log.d(TAG,"audioToSpeakerSet setForceUse "+set);
				storePrefsInt("audioToSpeaker", audioToSpeakerMode);
			} catch(Exception ex) {
				Log.d(TAG,"audioToSpeakerSet "+set+" ex="+ex);
				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "Ring on speaker not available");
				sendBroadcast(intent);
				audioToSpeakerMode = 0;
				storePrefsInt("audioToSpeaker", audioToSpeakerMode);
			}
		} else {
			// TODO Android 9+ implementation needed
			// see: setAudioRoute(ROUTE_SPEAKER) from android/telecom/InCallService is needed
			// https://developer.android.com/reference/android/telecom/InCallService
			// audioToSpeakerActive = set;
			if(set) {
				Intent intent = new Intent("webcall");
				intent.putExtra("toast", "Ring on speaker not available");
				sendBroadcast(intent);
			}
			audioToSpeakerMode = 0;
			storePrefsInt("audioToSpeaker", audioToSpeakerMode);
		}
	}

	private String saveSystemLogs() {
		final String logFileName = "webcall-log-"+
				new SimpleDateFormat("yyyy-MM-dd-HH-mm", Locale.US).format(new Date()) + ".txt";
		Log.d(TAG,"saveSystemLogs fileName="+logFileName);

		class ProcessTestRunnable implements Runnable {
			Process p;
			BufferedReader br;

			ProcessTestRunnable(Process p) {
				this.p = p;
				//Log.d(TAG,"saveSystemLogs ProcessTestRunnable constr");
			}

			public void run() {
				//Log.d(TAG,"saveSystemLogs ProcessTestRunnable run");
				int linesAccepted=0;
				int linesDenied=0;
				try {
					br = new BufferedReader(new InputStreamReader(p.getInputStream()));
					StringBuilder strbld = new StringBuilder();
					String line = null;
					while((line = br.readLine()) != null) {
						String lowerLine = line.toLowerCase(Locale.getDefault());
						if(lowerLine.indexOf("webcall")>=0 ||
						   lowerLine.indexOf("androidruntime")>=0 ||
						   lowerLine.indexOf("system.err")>=0 ||
						   lowerLine.indexOf("offline")>=0 ||
						   lowerLine.indexOf("wifiLock")>=0 ||
						   lowerLine.indexOf("waking")>=0 ||
						   lowerLine.indexOf("dozing")>=0 ||
						   lowerLine.indexOf("killing")>=0 ||
						   lowerLine.indexOf("anymotion")>=0) {
							strbld.append(line+"\n");
							linesAccepted++;
						} else {
							linesDenied++;
						}
					}

					Log.d(TAG,"saveSystemLogs accepted="+linesAccepted+" denied="+linesDenied);
					String dumpStr = strbld.toString();
					Log.d(TAG,"saveSystemLogs store "+dumpStr.length()+" bytes");
					storeByteArrayToFile(dumpStr.getBytes(),logFileName);
				}
				catch(IOException ex) {
					ex.printStackTrace();
				}
			}
		}

		try {
			//Log.d(TAG,"saveSystemLogs ProcessBuilder");
			ProcessBuilder pb = new ProcessBuilder("logcat","-d");
			pb.redirectErrorStream(true); // redirect the error stream to stdout
			//Log.d(TAG,"saveSystemLogs pb.start()");
			Process p = pb.start(); // start the process
			//Log.d(TAG,"saveSystemLogs new ProcessTestRunnable(p)).start()");
			new Thread(new ProcessTestRunnable(p)).start();
			//Log.d(TAG,"saveSystemLogs p.waitFor()");
			p.waitFor();
			return logFileName;
		}
		catch(Exception ex) {
			ex.printStackTrace();
			return null;
		}
	}

	private void playSoundNotification() {
		Log.d(TAG,"playSoundNotification");
		ToneGenerator toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); // volume
		//toneGen1.startTone(ToneGenerator.TONE_CDMA_PIP,120); // duration
		toneGen1.startTone(ToneGenerator.TONE_SUP_INTERCEPT_ABBREV,200); // duration
		soundNotificationPlayed = true;
	}

	/* currently not being used
	private void playSoundConfirm() {
		// very simple short beep to indicate a network problem (maybe just temporary)
		if(soundNotificationPlayed) {
			Log.d(TAG,"playSoundConfirm");
			ToneGenerator toneGen1 = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); // volume
			toneGen1.startTone(ToneGenerator.TONE_SUP_CONFIRM,120); // duration
			soundNotificationPlayed = false;
		}
	}
	*/

	private void playSoundAlarm() {
		// typical TYPE_NOTIFICATION sound to indicate we given up on reconnect (severe)
		// used for beepOnLostNetworkMode
		Log.d(TAG,"playSoundAlarm");
		Ringtone r = RingtoneManager.getRingtone(context.getApplicationContext(), 
			RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
		r.play();
	}

	private void statusMessage(String msg, int timeoutMs, boolean notifi, boolean important) {
		// webcall status msg + android notification (if notifi + important are true)
		//Log.d(TAG,"statusMessage: "+msg+" n="+notifi+" i="+important);
		lastStatusMessage = "";
		if(myWebView==null) {
			Log.d(TAG,"! statusMessage: "+msg+" n="+notifi+" i="+important+" skip no webview");
		} else if(!webviewMainPageLoaded) {
			Log.d(TAG,"! statusMessage: "+msg+" n="+notifi+" i="+important+" skip notOnMainPage");
		} else if(msg=="") {
			Log.d(TAG,"! statusMessage: "+msg+" n="+notifi+" i="+important+" skip msg empty");
		} else {
			// encodedMsg MUST NOT contain apostrophe
			String encodedMsg = msg.replace("'", "&#39;");
			lastStatusMessage = encodedMsg;
			//runJS("showStatus('"+encodedMsg+"',"+timeoutMs+");",null);
			runJS("showStatus('"+encodedMsg+"',"+timeoutMs+");", new ValueCallback<String>() {
				@Override
				public void onReceiveValue(String s) {
					Log.d(TAG,"statusMessage completed: "+encodedMsg);
				}
			});
		}

		if(notifi && connectToServerIsWanted) {
			updateNotification(msg, important);
		}
	}

	private void updateNotification(String msg, boolean important) {
		// android notification
		if(msg=="") {
			Log.d(TAG,"# updateNotification msg empty");
		} else if(stopSelfFlag) {
			Log.d(TAG,"# updateNotification msg="+msg+" important="+important+" skip on stopSelfFlag");
		} else if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { // < 26
			Log.d(TAG,"updateNotification msg="+msg+" SKIP sdk="+Build.VERSION.SDK_INT+" smaller than O (26)");
		} else {
//			Log.d(TAG,"updateNotification msg="+msg+" important="+important);
			NotificationManager notificationManager =
				(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
			String title = "";
			lastNotification = buildFgServiceNotification(title, msg, important);
			/*
			if(msg.equals("Incoming WebCall")) {
				Log.d(TAG,"updateNotification 'Incoming WebCall' setLights");
				notification.ledARGB = Color.argb(255, 0, 255, 0);
				notification.flags |= Notification.FLAG_SHOW_LIGHTS;
				notification.ledOnMS = 200;
				notification.ledOffMS = 300;
			}
			*/
			notificationManager.notify(NOTIF_ID, lastNotification);
		}
	}

	private Notification buildFgServiceNotification(String title, String msg, boolean important) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			String notifChannel = NOTIF_CHANNEL_ID_LOW;
			if(important) {
				notifChannel = NOTIF_CHANNEL_ID_HIGH;
			}
			Intent notificationIntent = new Intent(this, WebCallCalleeActivity.class);
			PendingIntent pendingIntent =
				PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
			if(title.equals("")) {
				title = "WebCall";
			}
			if(msg.equals("")) {
				msg = "Offline";
			}

			Log.d(TAG,"buildFgServiceNotification title="+title+" msg="+msg+" !="+important);
			NotificationCompat.Builder notificationBuilder =
				new NotificationCompat.Builder(this, notifChannel)
						.setContentTitle(title) // 1st line showing in top-bar
						.setContentText(msg) // 2nd line showing in top-bar
						.setSmallIcon(R.mipmap.notification_icon)
						//.setDefaults(0)
						.setContentIntent(pendingIntent);
			/*
			if(msg.equals("Incoming WebCall")) {
				Log.d(TAG,"buildFgServiceNotification 'Incoming WebCall' setLights");
				notificationBuilder.setLights(0xff00ff00, 300, 100);
				notificationBuilder.setPriority(Notification.PRIORITY_MAX);
			}
			*/
			return notificationBuilder.build();
		}
		return null;
	}

	private void exitService() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			// remove the forground-notification
			Log.d(TAG,"exitService stopForeground()");
			stopForeground(true); // true = removeNotification
		}

		// kill the service itself
		Log.d(TAG, "exitService stopSelf()");
		stopSelfFlag = true;
		onDestroy();
		stopSelf();
	}

	private void storePrefsString(String key, String value) {
		SharedPreferences.Editor prefed = prefs.edit();
		prefed.putString(key, value);
		prefed.commit();
	}

	private void storePrefsBoolean(String key, boolean value) {
		SharedPreferences.Editor prefed = prefs.edit();
		prefed.putBoolean(key, value);
		prefed.commit();
	}

	private void storePrefsInt(String key, int value) {
		SharedPreferences.Editor prefed = prefs.edit();
		prefed.putInt(key, value);
		prefed.commit();
	}

	private void storePrefsLong(String key, long value) {
		SharedPreferences.Editor prefed = prefs.edit();
		prefed.putLong(key, value);
		prefed.commit();
	}

	private String currentDateTimeString() {
		return new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.US).format(new Date());
	}

	private String getWebviewVersion() {
		if(webviewVersionString.equals("")) {
			PackageInfo webviewPackageInfo = getCurrentWebViewPackageInfo();
			if(webviewPackageInfo != null) {
				webviewVersionString = webviewPackageInfo.versionName;
			}
		}
		return webviewVersionString;
	}

	@SuppressWarnings({"unchecked", "JavaReflectionInvocation"})
	private PackageInfo getCurrentWebViewPackageInfo() {
		PackageInfo pInfo = null;
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			Log.d(TAG, "getCurrentWebViewPackageInfo for O+");
			pInfo = WebView.getCurrentWebViewPackage();
		} else {
			try {
				Log.d(TAG, "getCurrentWebViewPackageInfo for M+");
				Class webViewFactory = Class.forName("android.webkit.WebViewFactory");
				Method method = webViewFactory.getMethod("getLoadedPackageInfo");
				pInfo = (PackageInfo)method.invoke(null);
			} catch(Exception e) {
				//Log.d(TAG, "getCurrentWebViewPackageInfo for M+ ex="+e);
			}
			if(pInfo==null) {
				try {
					Log.d(TAG, "getCurrentWebViewPackageInfo for M+ (2)");
					Class webViewFactory = Class.forName("com.google.android.webview.WebViewFactory");
					Method method = webViewFactory.getMethod("getLoadedPackageInfo");
					pInfo = (PackageInfo) method.invoke(null);
				} catch(Exception e2) {
					//Log.d(TAG, "getCurrentWebViewPackageInfo for M+ (2) ex="+e2);
				}
			}
			if(pInfo==null) {
				try {
					Log.d(TAG, "getCurrentWebViewPackageInfo for M+ (3)");
					Class webViewFactory = Class.forName("com.android.webview.WebViewFactory");
					Method method = webViewFactory.getMethod("getLoadedPackageInfo");
					pInfo = (PackageInfo)method.invoke(null);
				} catch(Exception e2) {
					//Log.d(TAG, "getCurrentWebViewPackageInfo for M+ (3) ex="+e2);
				}
			}
		}
		if(pInfo!=null) {
			Log.d(TAG, "getCurrentWebViewPackageInfo pInfo set");
		}
		return pInfo;
	}
}

