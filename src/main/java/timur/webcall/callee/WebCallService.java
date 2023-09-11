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
import org.json.JSONObject;
import org.json.JSONArray;

import timur.webcall.callee.BuildConfig;

public class WebCallService extends Service {
	private final static String TAG = "WebCallService";

	// on O+ NOTIF_ID* determines the priority, not PRIORITY_HIGH/LOW
	private final static int NOTIF_ID1 = 1;  // -> NOTIF_LOW
	private final static int NOTIF_ID2 = 2;  // -> NOTIF_HIGH
	private final static String NOTIF_LOW = "123";
	private final static String NOTIF_HIGH = "124";

	private final static String startAlarmString = "timur.webcall.callee.START_ALARM"; // for alarmReceiver
	private final static Intent startAlarmIntent = new Intent(startAlarmString);

	private final static String readyToReceiveCallsString = "Ready to receive calls";
	private final static String connectedToServerString = "Connected"; // same as readyToReceive, but during peerCon
	private final static String offlineMessage = "WebCall server disconnected"; // used to be "Offline";

	// for callInProgressNotification()
	private final static String callInProgressMessage = "Call in progress";

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
	private static NotificationManager notificationManager = null;
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
	protected static volatile WebSocketClient wsClient = null;

	// haveNetworkInt describes the type of network cur in use: 0=noNet, 1=mobile, 2=wifi, 3=other
	private static volatile int haveNetworkInt = -1;

	// currentUrl contains the currently loaded URL
	private static volatile String currentUrl = null;

	// webviewMainPageLoaded is set true if currentUrl is pointing to the main page
	private static volatile boolean webviewMainPageLoaded = false;

	// callPickedUpFlag is set from pickup until peerConnect, activates proximitySensor
	private static volatile boolean callPickedUpFlag = false;

	// peerConnectFlag is set if full mediaConnect is established
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

	// loginUrl and loginUserName will be constructed in setLoginUrl()
	private static volatile String loginUrl = null;
	private static volatile String loginUserName = null;

	// pingCounter is the number of server pings received and processed
	private static volatile long pingCounter = 0l;

	// lastPingDate used by checkLastPing() to calculated seconds since last received ping
	private static volatile Date lastPingDate = null;

	// dozeIdle is set by dozeStateReceiver isDeviceIdleMode() and isInteractive()
	private static volatile boolean dozeIdle = false;
	private static volatile long dozeIdleCounter = 0;

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
	private static volatile boolean incomingCall = false;
	private static volatile boolean activityVisible = false;
	private static volatile boolean autoPickup = false;
	private static volatile MediaPlayer ringPlayer = null;
	private static volatile boolean calleeIsReady = false;
	private static volatile boolean stopSelfFlag = false;
	private static volatile boolean ringFlag = false;
	private static volatile String textmode = "";
	private static volatile String lastStatusMessage = "";
	private static volatile WebView myWebView = null;

	private volatile WebCallJSInterface webCallJSInterface = new WebCallJSInterface();
	private volatile WebCallJSInterfaceMini webCallJSInterfaceMini = new WebCallJSInterfaceMini();

	protected static volatile int boundServiceClients = 0;
	protected static volatile boolean serviceAlive = false;
	private Context context = null;
	private static volatile boolean micMuteState = false;

	// section 1: android service methods
	@Override
	public IBinder onBind(Intent intent) {
		context = this;
		prefs = PreferenceManager.getDefaultSharedPreferences(context);
		Binder mBinder = new WebCallServiceBinder();
		boundServiceClients++;
		stopSelfFlag = false;
		Log.d(TAG,"onBind "+boundServiceClients+" "+intent.toString());
		return mBinder;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		boundServiceClients--;
		Log.d(TAG, "onUnbind "+boundServiceClients+" "+intent.toString());
		if(boundServiceClients<1) {
			if(connectToServerIsWanted) {
				Log.d(TAG, "onUnbind connectToServerIsWanted: no exitService() connected="+(wsClient!=null));
			} else {
				Log.d(TAG, "! onUnbind: exitService() ---- not called ---- connected="+(wsClient!=null));
				// not call exitService() because: "startForegroundService() did not then call Service.startForeground()"
				//exitService();
			}
		}
		return true; // true: call onRebind(Intent) later when new clients bind
	}

	@Override
	public void onRebind(Intent intent) {
		context = this;
		boundServiceClients++;
		stopSelfFlag = false;
		Log.d(TAG,"onRebind "+boundServiceClients+" "+intent.toString());
	}

	@Override
	public void onDestroy() {
		Log.d(TAG, "onDestroy connected="+(wsClient!=null));
		if(alarmReceiver!=null) {
			Log.d(TAG, "onDestroy unregisterReceiver alarmReceiver");
			unregisterReceiver(alarmReceiver);
			alarmReceiver = null;
		}
		if(powerConnectionReceiver!=null) {
			Log.d(TAG, "onDestroy unregisterReceiver powerConnectionReceiver");
			unregisterReceiver(powerConnectionReceiver);
			powerConnectionReceiver = null;
		}
		if(networkStateReceiver!=null) {
			Log.d(TAG, "onDestroy unregisterReceiver networkStateReceiver");
			unregisterReceiver(networkStateReceiver);
			networkStateReceiver = null;
		}
		if(dozeStateReceiver!=null) {
			Log.d(TAG, "onDestroy unregisterReceiver dozeStateReceiver");
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				unregisterReceiver(dozeStateReceiver);
			}
			dozeStateReceiver = null;
		}
		if(serviceCmdReceiver!=null) {
			// java.lang.IllegalArgumentException: Receiver not registered: timur.webcall.callee.WebCallService
			Log.d(TAG,"onDestroy unregisterReceiver serviceCmdReceiver");
			unregisterReceiver(serviceCmdReceiver);
			serviceCmdReceiver = null;
		}
		if(connectivityManager!=null && myNetworkCallback!=null) {
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // >=api24
				Log.d(TAG,"onDestroy unregisterNetworkCallback");
				connectivityManager.unregisterNetworkCallback(myNetworkCallback);
			}
			myNetworkCallback = null;		// TODO no unregister?
			connectivityManager = null;
		}
		if(wifiLock!=null && wifiLock.isHeld()) {
			Log.d(TAG,"onDestroy wifiLock.release()");
			wifiLock.release();
		}
		connectToServerIsWanted = false; // to stop reconnecter
		if(wsClient!=null) {
			closeWsClient(false, "onDestroy");
		}
		if(keepAwakeWakeLock!=null && keepAwakeWakeLock.isHeld()) {
			Log.d(TAG,"onDestroy keepAwakeWakeLock.release");
			keepAwakeWakeLock.release();
		}

		//statusMessage("Service terminated",-1,true);

		// turn tile off
		postStatus("state", "deactivated");
		serviceAlive = false;
		Log.d(TAG, "onDestroy done");
	}

	@Override
	public void onTrimMemory(int level) {
		Log.d(TAG, "onTrimMemory level="+level);
		// level==20 when activity moves to the background
		super.onTrimMemory(level);
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		// activity killed (webview gone), service still alive
		super.onTaskRemoved(rootIntent);
		Log.d(TAG, "onTaskRemoved "+rootIntent.toString()+" connected="+(wsClient!=null));
		webSettings = null;
		webviewCookies = null;
		//webCallJSInterface = null;
		//webCallJSInterfaceMini = null;
		if(myWebView!=null) {
			Log.d(TAG, "onTaskRemoved close webView");
			myWebView.destroy();
			myWebView = null;
		}
		webviewMainPageLoaded = false;
		currentUrl=null;
		activityVisible=false;
		calleeIsReady=false;
		System.gc();
		Log.d(TAG, "onTaskRemoved done connected="+(wsClient!=null));
	}

	@Override
	public void onCreate() {
		Log.d(TAG,"onCreate "+BuildConfig.VERSION_NAME+" "+Build.VERSION.SDK_INT+" connected="+(wsClient!=null));
		stopSelfFlag = false;

		alarmReceiver = new AlarmReceiver();
		registerReceiver(alarmReceiver, new IntentFilter(startAlarmString));

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
				NOTIF_LOW, "WebCall", NotificationManager.IMPORTANCE_LOW));

			getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
				NOTIF_HIGH, "WebCall CALL", NotificationManager.IMPORTANCE_HIGH));
		}

   		// receive broadcast msgs from service and activity
		serviceCmdReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				//Log.d(TAG, "serviceCmdReceiver intent="+intent.toUri(0));
				// list all extras
				Bundle bundle = intent.getExtras();
				if(bundle != null) {
					for(String key : bundle.keySet()) {
						Log.d(TAG,"serviceCmdReceiver key="+key+" val="+(bundle.get(key)!=null ? bundle.get(key) : "-"));
					}
				}

				if(stopSelfFlag) {
					Log.d(TAG,"! serviceCmdReceiver skip on stopSelfFlag "+intent.toString());
					return;
				}

				String message = intent.getStringExtra("activityVisible");
				if(message!=null && message!="") {
					// activity is telling us that it is in front or not
					if(message.equals("true")) {
						if(!activityVisible) {
							activityVisible = true;
							Log.d(TAG, "serviceCmdReceiver activityVisible true connected="+(wsClient!=null));
							if(dozeIdleCounter>0) {
								postDozeAction();
							}
						}
					} else {
						if(activityVisible) {
							Log.d(TAG, "serviceCmdReceiver activityVisible false connected="+(wsClient!=null));
							activityVisible = false;
						}
					}
					return;
				}

				message = intent.getStringExtra("denyCall");
				if(message!=null && message!="") {
					// user responded to the call-notification dialog by rejecting the call
					Log.d(TAG, "serviceCmdReceiver denyCall "+message);

					// stop secondary wakeIntents from rtcConnect()
					peerDisconnnectFlag = true;

					String deniedCallerID = intent.getStringExtra("denyID");
					if(deniedCallerID!=null && deniedCallerID!="") {
						Log.d(TAG, "serviceCmdReceiver deniedCallerID="+deniedCallerID);
						// TODO add deniedCallerID to a list of rejected IDs
						notificationManager.cancel(NOTIF_ID2);
						return;
					}

					// disconnect caller / stop ringing
					if(wsClient==null) {
						Log.w(TAG,"# serviceCmdReceiver denyCall wsClient==null");
					} else if(myWebView!=null && webviewMainPageLoaded) {
						Log.w(TAG,"serviceCmdReceiver denyCall runJS('hangup(musthangup,,userReject)')");
						runJS("hangup(true,true,'userReject')",null);
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
					if(myWebView!=null && webviewMainPageLoaded) {
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
					// user responded to the 3-button call-notification dialog by accepting the call
					// this intent is coming from the started activity
					if(myWebView!=null && webviewMainPageLoaded) {
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
					// message should be "true"
					Log.d(TAG, "serviceCmdReceiver dismissNotification "+message);

					// we can later close this notification by sending a new not-high priority notification
					notificationManager.cancel(NOTIF_ID2);
					return;
				}

				message = intent.getStringExtra("muteMic");
				if(message!=null && message!="") {
					// user responded to the call-notification dialog and it needs to be closed
					// this intent is coming from the started activity
					if(myWebView!=null && webviewMainPageLoaded) {
						Log.d(TAG, "serviceCmdReceiver muteMic");
						runJS("muteMicElement.checked = !muteMicElement.checked; muteMic(muteMicElement.checked);",null);
					} else {
						Log.d(TAG, "# serviceCmdReceiver muteMic but no webview");
					}
					return;
				}

				message = intent.getStringExtra("hangup");
				if(message!=null && message!="") {
					// user responded to the call-notification dialog and it needs to be closed
					// this intent is coming from the started activity
					Log.d(TAG, "serviceCmdReceiver hangup "+message);
					// will set disconnectCaller=true
					// and call runJS('endWebRtcSession(true,connectToServerIsWanted)
					endWebRtcSession(true);
					return;
				}

				Log.d(TAG, "serviceCmdReceiver no match");
			}
		};
		registerReceiver(serviceCmdReceiver, new IntentFilter("serviceCmdReceiver"));
		serviceAlive = true;
		Log.d(TAG,"onCreate done connected="+(wsClient!=null));
	}

	@Override
	public int onStartCommand(Intent onStartIntent, int flags, int startId) {
		Log.d(TAG,"onStartCommand loginUrl="+loginUrl+" connected="+(wsClient!=null));
		context = this;

		if(connectivityManager==null) {
			Log.d(TAG,"onStartCommand connectivityManager==null");
			connectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
		} else {
			Log.d(TAG,"onStartCommand connectivityManager!=null");
		}
		if(connectivityManager==null) {
			Log.d(TAG,"# onStartCommand fatal cannot get connectivityManager");
			return 0;
		}

		if(notificationManager==null) {
			Log.d(TAG,"onStartCommand notificationManager==null");
			notificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
		} else {
			Log.d(TAG,"onStartCommand notificationManager!=null");
		}
		if(notificationManager==null) {
			Log.d(TAG,"# onStartCommand fatal cannot get notificationManager");
			return 0;
		}

		// note: haveNetworkInt keeps its old value through exitService() !!!
		int oldHaveNetworkInt = haveNetworkInt;
		checkNetworkState(false); // will set haveNetworkInt
		Log.d(TAG,"onStartCommand haveNetworkInt="+haveNetworkInt+" (old="+oldHaveNetworkInt+")");

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			// create notificationChannel to start service in foreground
			String msg = offlineMessage;
			if(wsClient!=null) {
				msg = readyToReceiveCallsString;
				if(callPickedUpFlag || peerConnectFlag) {
					msg = connectedToServerString;
				}
			}
			Log.d(TAG,"onStartCommand startForeground: "+msg);
			startForeground(NOTIF_ID1,buildServiceNotification(msg, NOTIF_LOW, NotificationCompat.PRIORITY_LOW));
			statusMessage(msg,-1,true);
		}

		if(batteryStatusfilter==null) {
			batteryStatusfilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
			//batteryStatus = context.registerReceiver(null, batteryStatusfilter);
		}

		if(scheduler==null) {
			Log.d(TAG,"onStartCommand Executors.newScheduledThreadPool(10)");
			scheduler = Executors.newScheduledThreadPool(10);
		}
		if(scheduler==null) {
			Log.d(TAG,"# onStartCommand fatal cannot create scheduledThreadPool");
			return 0;
		}

		if(powerManager==null) {
			powerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
		}
		if(powerManager==null) {
			Log.d(TAG,"# onStartCommand fatal no access to PowerManager");
			return 0;
		}

		if(displayManager==null) {
			displayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
		}
		if(displayManager==null) {
			Log.d(TAG,"# onStartCommand fatal no access to DisplayManager");
			return 0;
		}

		if(audioManager==null) {
			audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
		}
		if(audioManager==null) {
			Log.d(TAG,"# onStartCommand fatal no access to AudioManager");
			return 0;
		}

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // >=api23
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
			Log.d(TAG,"# onStartCommand fatal no access to keepAwakeWakeLock");
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
			Log.d(TAG,"# onStartCommand fatal no access to WifiManager");
			return 0;
		}

		if(wifiLock==null) {
			String logKey = "WebCall:wifiLock";
			if(userAgentString==null || userAgentString.indexOf("HUAWEI")>=0)
				logKey = "LocationManagerService"; // to avoid being killed on Huawei
			wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, logKey);
		}
		if(wifiLock==null) {
			Log.d(TAG,"# onStartCommand fatal no access to wifiLock");
			return 0;
		}

		if(alarmManager==null) {
			alarmManager = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
		}
		if(alarmManager==null) {
			Log.d(TAG,"# onStartCommand fatal no access to alarmManager");
			return 0;
		}

		if(reconnecter==null) {
			reconnecter = newReconnecter();
		}
		if(reconnecter==null) {
			Log.d(TAG,"# onStartCommand fatal cannot create reconnecter");
			return 0;
		}

		if(prefs==null) {
			prefs = PreferenceManager.getDefaultSharedPreferences(context);
		}
		try {
			audioToSpeakerMode = prefs.getInt("audioToSpeaker", 0);
			Log.d(TAG,"onStartCommand audioToSpeakerMode="+audioToSpeakerMode);
		} catch(Exception ex) {
			Log.d(TAG,"# onStartCommand audioToSpeakerMode ex="+ex);
		}

		try {
			beepOnLostNetworkMode = prefs.getInt("beepOnLostNetwork", 0);
			Log.d(TAG,"onStartCommand beepOnLostNetworkMode="+beepOnLostNetworkMode);
		} catch(Exception ex) {
			Log.d(TAG,"# onStartCommand beepOnLostNetworkMode ex="+ex);
		}

		setLoginUrl();

		try {
			startOnBootMode = prefs.getInt("startOnBoot", 0);
			Log.d(TAG,"onStartCommand startOnBootMode="+startOnBootMode);
		} catch(Exception ex) {
			Log.d(TAG,"# onStartCommand startOnBootMode ex="+ex);
		}

		try {
			setWifiLockMode = prefs.getInt("setWifiLockMode", 1);
			Log.d(TAG,"onStartCommand setWifiLockMode="+setWifiLockMode);
		} catch(Exception ex) {
			Log.d(TAG,"# onStartCommand setWifiLockMode ex="+ex);
		}

		try {
			screenForWifiMode = prefs.getInt("screenForWifi", 0);
			Log.d(TAG,"onStartCommand screenForWifiMode="+screenForWifiMode);
		} catch(Exception ex) {
			Log.d(TAG,"# onStartCommand screenForWifiMode ex="+ex);
		}

		try {
			keepAwakeWakeLockMS = prefs.getLong("keepAwakeWakeLockMS", 0);
			Log.d(TAG,"onStartCommand keepAwakeWakeLockMS="+keepAwakeWakeLockMS);
		} catch(Exception ex) {
			Log.d(TAG,"# onStartCommand keepAwakeWakeLockMS ex="+ex);
		}

		try {
			insecureTlsFlag = prefs.getBoolean("insecureTlsFlag", false);
			Log.d(TAG,"onStartCommand insecureTlsFlag="+insecureTlsFlag);
		} catch(Exception ex) {
			Log.d(TAG,"# onStartCommand insecureTlsFlag ex="+ex);
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
			Log.d(TAG,"# onStartCommand versionName ex="+ex);
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
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && myNetworkCallback==null) { // >=api24
			// networkCallback code fully replaces checkNetworkState()
			myNetworkCallback = new ConnectivityManager.NetworkCallback() {

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
						} else {
							Log.d(TAG,"# networkCallback onAvailable netInfo==null");
						}
					} else {
						Log.d(TAG,"# networkCallback onAvailable network==null");
					}

					// sometimes after onAvailable() we get onCapabilitiesChanged(), sometimes (on P9 in doze) we don't
					// let onAvailable() trigger networkChange( if haveNetworkInt!=oldNetworkInt
					lock.lock();
					int oldNetworkInt = haveNetworkInt;
					checkNetworkState(false);
					if(haveNetworkInt>0 && haveNetworkInt!=oldNetworkInt) {
						networkChange(haveNetworkInt,oldNetworkInt,"onAvailable");
					}
					lock.unlock();
				}

				@Override
				public void onLost(Network network) {
			        super.onLost(network);
					lock.lock();

					int oldNetworkInt = haveNetworkInt;
					haveNetworkInt = 0;

					// note: on SDK 25+ onCapabilitiesChanged() may also occur and may also call networkChange(
					if(haveNetworkInt!=oldNetworkInt) {
						networkChange(haveNetworkInt,oldNetworkInt,"onLost");
					}
					lock.unlock();
				}

				@Override
				public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabi) {
					// comes only after onAvailable(), not after onLost()
					// this is why we wifiLock.release() in onLost()
					lock.lock();
					int oldNetworkInt = haveNetworkInt;
					int newNetworkInt = 0;
					if(networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
						newNetworkInt = 1;
					} else if(networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
						newNetworkInt = 2;
					} else if(networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
							networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
							networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_USB)) {
						newNetworkInt = 3;
					}
					haveNetworkInt = newNetworkInt;

					if(haveNetworkInt!=oldNetworkInt) {
						Log.d(TAG,"networkCallback capabChange: " + oldNetworkInt+" -> "+haveNetworkInt+" "+
							" conWanted="+connectToServerIsWanted+
							" wsCon="+(wsClient!=null)+
							" wifi="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)+
							" cell="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)+
							" ether="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)+
							" vpn="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_VPN)+
							" wifiAw="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)+
							" usb="+networkCapabi.hasTransport(NetworkCapabilities.TRANSPORT_USB));
					}

					if(/*haveNetworkInt>0 &&*/ haveNetworkInt!=oldNetworkInt) {
						networkChange(haveNetworkInt,oldNetworkInt,"capabChange");
					}
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
							dozeIdleCounter++;
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
									Log.d(TAG,"# dozeState idle sendPing ex="+ex);
									wsClient = null;
								}
							}
							if(wsClient==null && connectToServerIsWanted) {
								// let's go straight to reconnecter
								statusMessage(offlineMessage,-1,true);

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

							postDozeAction();

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

//							wakeUpOnLoopCount(context);	// why ???

							if(wsClient!=null) {
								// close a prev connection
								closeWsClient(true, "dozeState awake");
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
							// dozeIdle = ??? this never fires
							Log.d(TAG,"dozeState powerSave mode");
						}
					}
				};
				registerReceiver(dozeStateReceiver,
					new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
			}
		}

		if(wsClient!=null) {
			Log.d(TAG,"! onStartCommand got existing wsClient ");
			// probably activity was discarded, got restarted, and now we see service is still connected
			//storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
		} else if(reconnectBusy) {
			Log.d(TAG,"! onStartCommand got reconnectBusy");
			// TODO not sure about this
			//storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
		} else {
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
				int autoCalleeStartDelay = 16; // seconds, default value for start from boot
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
					Log.d(TAG,"onStartCommand onStartIntent!=null "+onStartIntent.toString());
					// let's see if service was started by boot...
					Bundle extras = onStartIntent.getExtras();
					if(extras==null) {
						// started from tile
						Log.d(TAG,"onStartCommand extras==null");
						autoCalleeStartDelay = 1; // seconds, value for start from tile
							/*
							try {
								boolean connectWanted = prefs.getBoolean("connectWanted", false);
									//Log.d(TAG,"onStartCommand connectWanted force false");
									// connectWanted = false
								Log.d(TAG,"onStartCommand connectWanted="+connectWanted);
								autoCalleeConnect = connectWanted;
							} catch(Exception ex) {
								Log.d(TAG,"onStartCommand connectWanted ex="+ex);
							}
							*/
						// if started from tile, we go directly into connect mode
						// independent of pref "connectWanted"
						autoCalleeConnect = true;
					} else {
						String extraCommand = extras.getString("onstart");
						if(extraCommand==null) {
							Log.d(TAG,"onStartCommand extraCommand==null");
							storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
						} else {
							// service was started by boot (by WebCallServiceReceiver.java)
							if(!extraCommand.equals("") /*&& !extraCommand.equals("donothing")*/) {
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
						reconnectSchedFuture = scheduler.schedule(reconnecter, autoCalleeStartDelay, TimeUnit.SECONDS);
					}
				}
			}
		}
		return START_STICKY;
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
					//Intent intent = new Intent("webcall");
					if(errorCode==ERROR_HOST_LOOKUP) {
						Log.d(TAG, "# onReceivedError HOST_LOOKUP "+description+" "+failingUrl);
						//intent.putExtra("toast", "host lookup error. no network?");
						statusMessage("Connection error: Host lookup",-1,true);
					} else if(errorCode==ERROR_UNKNOWN) {
						Log.d(TAG, "# onReceivedError "+description+" "+failingUrl);
						//intent.putExtra("toast", "Network error "+description);
						statusMessage("Connection error: "+description+" "+failingUrl,-1,true);
					} else {
						Log.d(TAG, "# onReceivedError code="+errorCode+" "+description+" "+failingUrl);
						//intent.putExtra("toast", "Error "+errorCode+" "+description);
						statusMessage("Connection error code="+errorCode+" "+description+" "+failingUrl,-1,true);
					}
					//sendBroadcast(intent);
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

					if(path.startsWith("/webcall/update") && myWebView!=null && webviewMainPageLoaded) {
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
						postStatus("browse", uri.toString());
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
					if(currentUrl!=null && myWebView!=null && webviewMainPageLoaded) {
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
							//Log.d(TAG, "onPageFinished only hashchange=" + currentUrl);
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
						postStatus("state", "mainpage");

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
									postStatus("state","connected");

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
					if(msg.startsWith("Uncaught")) {
						Log.d(TAG,"# con: "+msg + " L"+cm.lineNumber());
					} else {
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
						postStatus("simulateClick", floatString);
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
					postStatus("forResults", "x"); // value is not relevant
					// -> activity broadcastReceiver -> startActivityForResult() ->
					//    onActivityResult() -> fileSelect(results)
					return true;
				}
			});

			// let JS call java service code
			myWebView.addJavascriptInterface(webCallJSInterface, "Android");

			// render base page - or main page if we are connected already
			currentUrl = "file:///android_asset/index.html";
			if(wsClient!=null) {
				username = prefs.getString("username", "");
				String webcalldomain =
					prefs.getString("webcalldomain", "").toLowerCase(Locale.getDefault());
				if(webcalldomain.equals("")) {
					Log.d(TAG,"startWebView cannot reconnect: webcalldomain is not set");
				} else if(username.equals("")) {
					Log.d(TAG,"startWebView cannot reconnect: username is not set");
				} else {
					currentUrl = "https://"+webcalldomain+"/callee/"+username;
				}
			}
			Log.d(TAG, "startWebView load currentUrl="+currentUrl);
			myWebView.loadUrl(currentUrl);

			Log.d(TAG, "startWebView version "+getWebviewVersion());
		}

		public boolean connectToServerIsWanted() {
			return connectToServerIsWanted;
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
				Log.d(TAG, "callInProgress pickedUp="+callPickedUpFlag+" peerConnect="+peerConnectFlag);
			} else {
				//Log.d(TAG, "callInProgress no");
			}
			return ret;
		}

		public int haveNetwork() {
			// used by tile
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
			webviewMainPageLoaded = false;
			myWebView = null;
			if(connectToServerIsWanted) {
				Log.d(TAG, "activityDestroyed got connectToServerIsWanted - do nothing");
				// do nothing
			} else if(reconnectBusy) {
				Log.d(TAG, "activityDestroyed got reconnectBusy - do nothing");
				// do nothing
			} else {
				//Log.d(TAG, "activityDestroyed exitService() ------------------- ");
				// hangup peercon, reset webview, clear callPickedUpFlag
				//exitService();

				Log.d(TAG, "activityDestroyed do nothing (no exitService)");
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
				} /*else if(haveNetworkInt==2) {
					if(wifiLock==null) {
						Log.d(TAG,"setWifiLock wifiLock==null");
					} else if(wifiLock.isHeld()) {
						Log.d(TAG,"setWifiLock wifiLock isHeld");
					} else {
						Log.d(TAG,"setWifiLock wifiLock.acquire");
						wifiLock.acquire();
					}
				}*/
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
			return ringFlag; // set by JS ringStart()
		}

		public void goOnline() {
			// called by tile
			connectToServerIsWanted = true;
			storePrefsBoolean("connectWanted",true);

			if(wsClient!=null) {
				Log.d(TAG, "! goOnline() already online");
//			} else if(haveNetworkInt<=0) {
//				Log.d(TAG, "! goOnline() no network");
// if we abort here, the tile icon will not be set

//			} else if(myWebView!=null && webviewMainPageLoaded) {
//				Log.d(TAG, "goOnline() -> runJS('goOnline();')");
//				runJS("goOnline(true,'service');",null);
			} else {
				Log.d(TAG, "goOnline() -> startReconnecter()");
				startReconnecter(false,0); // wakeIfNoNet=false, reconnectDelaySecs=0
			}
		}
		public void goOffline() {
			// called by tile
/*
			if(wsClient==null) {
				Log.d(TAG, "! goOffline() already offline");
				// may need to remove a msg like "No network. Waiting in standby..."
				removeNotification();
				// must also remove status msg and switch buttons (enable goOnline)
			} else

			if(myWebView!=null && webviewMainPageLoaded) {
				Log.d(TAG, "goOffline() -> runJS('goOffline();')");
				runJS("goOffline('service');",null);
			} else 
*/			{
				Log.d(TAG, "goOffline() -> disconnectHost()");
				disconnectHost(true,false); // sendNotif skipStopForeground
			}

			// deactivate the tile
			postStatus("state", "deactivated");
			if(myWebView!=null && webviewMainPageLoaded) {
				// wsConn=null, reconnect stays aktiv
				//Log.d(TAG,"goOffline -> js:wsOnClose2");
				//runJS("wsOnClose2()",null);
				// turn browser goOnlineSwitch to offline mode
				Log.d(TAG,"goOffline -> js:offlineAction");
				runJS("offlineAction()",null);
			}

			connectToServerIsWanted = false;
			storePrefsBoolean("connectWanted",false);
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
// does nothing: TODO can be outcommented as soon as 4.0 core client is online
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

			if(wsClient!=null) {
				statusMessage(readyToReceiveCallsString,-1,true);
			} else {
				statusMessage(offlineMessage,-1,true);
			}

			// turn speakerphone off - the idea is to always switch audio playback to the earpiece
			// on devices without an earpiece (tablets) this is expected to do nothing
			// we do it now here instead of at setProximity(true), because it is more reliable this way
			// will be reversed by peerDisConnect()
			//Log.d(TAG, "JS peerConnect(), speakerphone=false");
			//audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION); // deactivates speakerphone on P9
			//audioManager.setSpeakerphoneOn(false); // deactivates speakerphone on Gn
		}

		// added 1.4.7
		@android.webkit.JavascriptInterface
		public void muteStateChange(boolean muteState) {
			Log.d(TAG,"JS muteStateChange("+muteState+")"+
				" callPickedUpFlag="+callPickedUpFlag+" peerConnectFlag="+peerConnectFlag+" wsClient="+(wsClient!=null));
//			if(callPickedUpFlag || peerConnectFlag) {
				micMuteState = muteState;
//				updateNotification(""); // for the server: repeat lastStatusMessage
//			}
		}

		@android.webkit.JavascriptInterface
		public void peerDisConnect() {
			// called by JS endWebRtcSession()
			Log.d(TAG,"JS peerDisConnect()");

			// clear peerConnectFlag + callPickedUpFlag, cancel(NOTIF_ID2), stopRinging
			endPeerCon();

			autoPickup = false;		// ???

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

			if(wsClient==null && connectToServerIsWanted==false) {
				Log.d(TAG,"JS peerDisConnect(), wsClient==null and serverIsNotWanted -> removeNotification()");
				removeNotification();
			}
		}

		@android.webkit.JavascriptInterface
		public long keepAwakeMS() {
			return keepAwakeWakeLockMS;
		}

		@android.webkit.JavascriptInterface
		public boolean isNetwork() {
			// used by client.js
			return haveNetworkInt>0;
		}

		@android.webkit.JavascriptInterface
		public int haveNetwork() {
			// used by webcall.js
			return haveNetworkInt;
		}

		@android.webkit.JavascriptInterface
		public void toast(String msg) {
			postStatus("toast", msg);
		}

		@android.webkit.JavascriptInterface
		public void activityToFront() {
			bringActivityToFront();
		}

		@android.webkit.JavascriptInterface
		public void gotoBasepage() {
			// for client.js:clearCookie()
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
			// for /callee/mastodon/setup/setup.js
			if(clipText!=null) {
				Log.d(TAG, "setClipboard "+clipText);
				ClipData clipData = ClipData.newPlainText(null,clipText);
				ClipboardManager clipboard =
					(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
				if(clipboard!=null) {
					clipboard.setPrimaryClip(clipData);
					postStatus("toast", "Data copied to clipboard");
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
				// then we will send: updateNotification readyToReceiveCallsString
				// then we will broadcast: "state", "connected"
				return wsClient;
			}
			if(wsClient==null) {
				Log.d(TAG,"JS wsOpen wsClient==null addr="+setWsAddr);
				// if connectHost fails, it will send updateNotification(offlineMessage)
				WebSocketClient wsCli = connectHost(setWsAddr,false);
				Log.d(TAG,"JS wsOpen wsClient="+(wsCli!=null));
				if(wsCli!=null) {
					connectToServerIsWanted = true;
					storePrefsBoolean("connectWanted",true); // used in case of service crash + restart
					// when callee sends init and gets a confirmation
					// it will call calleeConnected() / calleeIsConnected()
					// then we will send: updateNotification readyToReceiveCallsString
					// then we will broadcast: "state", "connected"
				} else {
// TODO not sure
//					connectToServerIsWanted = false;
//					storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
				}
				return wsCli;
			}

			Log.d(TAG,"JS wsOpen return existing wsClient");
			connectToServerIsWanted = true;
			storePrefsBoolean("connectWanted",true); // used in case of service crash + restart
			// when callee sends init and gets a confirmation
			// it will call calleeConnected() / calleeIsConnected()
			// then we will send: updateNotification readyToReceiveCallsString
			// then we will broadcast: "state", "connected"
			return wsClient;
		}

		@android.webkit.JavascriptInterface
		public void jsGoOnline() {
			Log.d(TAG,"JS jsGoOnline() -> startReconnecter()");
			connectToServerIsWanted = true;
			storePrefsBoolean("connectWanted",true);
			startReconnecter(false,0); // wakeIfNoNet=false, reconnectDelaySecs=0
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
// TODO when we return true, we will abort gotStream2 (not call prepareCallee())
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
		public void clearLastStatus() {
			// client.js uses this to prevent postDozeAction() from overwriting the newest statusMsg from JS
			Log.d(TAG,"JS clearLastStatus()");
			lastStatusMessage="";
		}

		@android.webkit.JavascriptInterface
		public void wsSend(String str) {
			String logstr = str;
			if(logstr.length()>40) {
				logstr = logstr.substring(0,40);
			}
			if(wsClient==null) {
				// this may happen when service is in reconnect mode and the connection was lost
				// and the client tries to delete an entry
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

//			postStatus("state", "deactivated");

			// wsClient.closeBlocking() + wsClient=null
			disconnectHost(true,false); // sendNotif skipStopForeground

			storePrefsBoolean("connectWanted",false);
			Log.d(TAG,"JS wsClose done");
		}

		@android.webkit.JavascriptInterface
		public void wsClosex() {
			// called by JS:clearcache()
			Log.d(TAG,"JS wsClosex");

			//postStatus("state", "deactivated");

			// wsClient.closeBlocking() + wsClient=null
			disconnectHost(true,true); // sendNotif skipStopForeground

			// TODO this is wrong if used for clearCache+reload
			//storePrefsBoolean("connectWanted",false);
			Log.d(TAG,"JS wsClosex done");
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
		public boolean isActivityInteractive() {
			if(myWebView!=null && webviewMainPageLoaded && activityVisible) {
				//Log.d(TAG,"isActivityInteractive true");
				return true;
			}
			//Log.d(TAG,"isActivityInteractive false");
			return false;
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
			// activity openContextMenu
			postStatus("cmd", "menu");
		}

		@android.webkit.JavascriptInterface
		public void wsClearCache(final boolean autoreload, final boolean autoreconnect) {
			// used by webcall.js + callee.js (clearcache())
			if(myWebView!=null) {
				Log.d(TAG,"JS wsClearCache clearCache() "+autoreload+" "+autoreconnect);
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

			// 3-button call-notification dialog may have set autoPickup (via serviceCmdReceiver "acceptCall")
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
				// tell JS to not ring or blink
				// and no Accept call buttons are needed, pickup() was already called
				return true;
			}
			Log.d(TAG,"JS rtcConnect() no autoPickup");
			return false;
		}

		@android.webkit.JavascriptInterface
		public void callPickedUp() {
			Log.d(TAG,"JS callPickedUp() wsClient="+(wsClient!=null));
			// route audio to it's normal destination (to headset if connected)
			audioToSpeakerSet(false,false);
			callPickedUpFlag=true; // no peerConnect yet, this activates proximitySensor
		}


		@android.webkit.JavascriptInterface
		public void browse(String url) {
			Log.d(TAG,"JS browse("+url+")");
			postStatus("browse", url);
		}

		@android.webkit.JavascriptInterface
		public void wsExit() {
			// called by Exit button
			Log.d(TAG,"JS wsExit -> endWebRtcSession()");
			endWebRtcSession(true);

			// hangup peercon, clear callPickedUpFlag, stopRinging();
			Log.d(TAG,"JS wsExit -> endPeerCon()");
			endPeerCon();

			myWebView = null;
			webviewMainPageLoaded = false;

			// disconnect from webcall server
			Log.d(TAG,"JS wsExit -> disconnectHost()");
			disconnectHost(true,false); // sendNotif skipStopForeground

			// tell activity to force close
			Log.d(TAG,"JS wsExit shutdown activity");
			postStatus("cmd", "shutdown");

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
			ringFlag = true; // for isRingin()
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startRinging();
				return true;
			}
			return false;
		}

		@android.webkit.JavascriptInterface
		public boolean ringStop() {
			ringFlag = false; // for isRingin()
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				stopRinging("JS"); // from callee.js stopAllAudioEffects()
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
			// connection to server was opened, so we tell JS wsOnOpen()
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
				// this happens when service is launched by the tile
				//Log.d(TAG,"WsClient onOpen, but no myWebView or not webviewMainPageLoaded");
				//updateNotification(readyToReceiveCallsString,false);	// ??? too early? has init been sent?
				// yeah, we wait for init -> sessionId to show readyToReceiveCallsString
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
					statusMessage("Error: "+exString,-1,false);
				}
			}
		}

		@Override
		public void onClose(int code, String reason, boolean remote) {
			// code 1002: an endpoint is terminating the connection due to a protocol error
			// code 1006: connection was closed abnormally (locally)
			// code 1000: indicates a normal closure (when we click goOffline, or server forced disconnect)
			postStatus("state", "disconnected");

			autoPickup = false;

			if(reconnectBusy) {
				Log.d(TAG,"onClose skip busy (code="+code+" "+reason+")");
			} else if(code==1000) {
				// normal disconnect: shut down connection - do NOT reconnect
				Log.d(TAG,"onClose code=1000");
				wsClient = null;
				if(reconnectSchedFuture==null) {
					// if wsClient was closed by onDestroy, networkStateReceiver will be null
					if(networkStateReceiver!=null) {
						statusMessage(offlineMessage,-1,true);
					}
				}
				// deactivate the tile
				postStatus("state", "deactivated");
				if(myWebView!=null && webviewMainPageLoaded) {
					// reconnect stays aktiv, goOnlineSwitch stays aktiv, connectToServerIsWanted not cleared
					//runJS("wsOnClose2();", new ValueCallback<String>() {
					//	@Override
					//	public void onReceiveValue(String s) {
					//		Log.d(TAG,"runJS('wsOnClose2') completed: "+s);
					//	}
					//});
					runJS("offlineAction();",null);
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
					if(haveNetworkInt<=0 && screenForWifiMode>0) {
						if(wifiLock!=null && wifiLock.isHeld()) {
							Log.d(TAG,"onClose wifiLock release");
							wifiLock.release();
						}
					}
					*/

					// we need keepAwake so we can manage reconnect
					if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
						Log.d(TAG,"onClose keepAwakeWakeLock.acquire");
						keepAwakeWakeLock.acquire(3 * 60 * 1000); // 3 minutes max
						keepAwakeWakeLockStartTime = (new Date()).getTime();
					}

//					wakeUpOnLoopCount(context);	// why???

					// close prev connection
					if(wsClient!=null) {
						// closeBlocking() makes no sense here bc we received a 1006
						closeWsClient(false, "onClose");

						if(myWebView!=null && webviewMainPageLoaded) {
							runJS("wsOnClose2()",null); // set wsConn=null; abort blinkButtonFunc()
						}
						//Log.d(TAG,"onClose wsClient.close() done");

						// TODO problem is that the server may STILL think it is connected to this client
						// and that re-login below may fail with "already/still logged in" because of this
					} else {
						if(reconnectSchedFuture==null) {
							statusMessage(offlineMessage,-1,true);
						}
					}

					// we need to start a reconnecter, but only if we have a network
					// (and only if no reconnecter is already running or scheduled)
					// however a 1006 may quickly be followed by onLost()
					// this is why, if we have a network, we wait 150ms before we check haveNetworkInt again
					int delayReconnecter = 0;
					if(haveNetworkInt>0) {
						delayReconnecter = 150;
						Log.d(TAG,"onClose 1006: haveNetworkInt>0: delay start reconnecter by 150ms");
					}
					scheduler.schedule(new Runnable() {
						public void run() {
							if(reconnectSchedFuture!=null) {
								Log.d(TAG,"onClose 1006 no reconnecter: reconnectSchedFuture!=null");
							} else if(reconnectBusy) {
								Log.d(TAG,"onClose 1006 no reconnecter: reconnectBusy");
							} else if(haveNetworkInt<=0) {
								Log.d(TAG,"onClose 1006 no reconnecter: haveNetworkInt<=0");
							} else {
								// if no reconnecter is scheduled at this time (say, by checkLastPing())
								// then schedule a new reconnecter
								// schedule in 5s to give server some time to detect the discon
								setLoginUrl();
								if(loginUrl!="") {
									Log.d(TAG,"onClose 1006 start reconnecter in 5s url="+loginUrl);
									// TODO on P9 in some cases this reconnecter does NOT fire
									// these are cases where the cause of the 1006 was wifi lost (client side)
									// shortly after this 1006 we then receive a networkStateReceiver event with all null
									reconnectSchedFuture = scheduler.schedule(reconnecter,5,TimeUnit.SECONDS);
								}
							}
						}
					}, delayReconnecter, TimeUnit.MILLISECONDS);

				} else {
					// NOT 1006: TODO not exactly sure what to do with this
					// deactivate the tile
					postStatus("state", "deactivated");
					if(myWebView!=null && webviewMainPageLoaded) {
						// offlineAction(): disable offline-button and enable online-button
						runJS("offlineAction();",null);
					}

					if(code==-1) {
						// if code==-1 do not show statusMessage
						// as it could replace a prev statusMessage with a crucial error text
					} else {
						statusMessage("Connect error "+code+", not reconnecting",-1,true);
					}

					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						Log.d(TAG,"networkState cancel reconnectSchedFuture");
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
				Log.d(TAG,"! onMessage clearcache "+message);
				// TODO implement clearcache: force callee web-client reload
				return;
			}

			if(message.startsWith("textmode|")) {
				textmode = message.substring(9);
				if(textmode.equals("true")) {
					Log.d(TAG,"onMessage textmode=("+textmode+")");
				} else {
					textmode="";
					Log.d(TAG,"onMessage no textmode");
				}
				String argStr = "wsOnMessage2('"+message+"','service');";
				runJS(argStr,null);
				return;
			}

			if(message.startsWith("callerOffer|") && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
				// incoming call!!
				// for Android <= 9: wake activity via wakeIntent
				// send a wakeIntent with ACTIVITY_REORDER_TO_FRONT
				// secondary wakeIntent will be sent in rtcConnect()
				// activity will take over the call using callee.js in the webview
				// the ringing will also be done in the activity
				// whereas for Andr10+ we start ringing on "callerinfo"
				if(context==null) {
					Log.e(TAG,"# onMessage callerOffer: no context to wake activity");
				} else {
					Log.d(TAG,"onMessage callerOffer: "+
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
					//statusMessage("WebCall "+callerName+" "+callerID,-1,false);
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

				if(context==null) {
					Log.e(TAG,"# onMessage incoming call: "+contentText+", no context to wake activity");
				} else if(activityVisible) {
					Log.d(TAG,"onMessage incoming call: "+contentText+", activityVisible (do nothing)");
				} else {
					incomingCall(callerID,callerName,txtMsg,false);
					startRinging();
				}
			}

			if(message.startsWith("cancel|")) {
				// server or caller signalling end of call (or end of ringing)
				Log.d(TAG,"onMessage "+message);

				// dismiss the 3-button dialog (just in case)
				notificationManager.cancel(NOTIF_ID2);
				incomingCall = false;
				stopRinging(message);

				if(myWebView!=null && webviewMainPageLoaded) {
					// do nothing: callee.js will receive cancel
					//Log.d(TAG,"onMessage cancel, -> JS endWebRtcSession()");
					//endWebRtcSession(false);
				} else {
					// clear queueWebRtcMessage / stringMessageQueue
					while(!stringMessageQueue.isEmpty()) {
						stringMessageQueue.poll();
					}
					Log.d(TAG,"onMessage cancel, -> JS endPeerCon()");
					endPeerCon();
					if(wsClient!=null && connectToServerIsWanted) {
						Log.d(TAG,"onMessage cancel, send init...");
						wsClient.send("init|");
						// wait for sessionID
					} else {
						statusMessage(offlineMessage,-1,true);
					}
				}
				Log.d(TAG,"onMessage cancel done");
			}

			if(myWebView==null || !webviewMainPageLoaded ||
					(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (!isScreenOn() || !activityVisible)) ) {
				// we can not send messages (for instance callerCandidate's) into the JS 
				// if the page is not fully loaded (webviewMainPageLoaded==true)
				// in such cases we queue the WebRTC messages - until we see "sessionId|"
				if(message.startsWith("sessionId|")) {
					Log.d(TAG,"onMessage sessionId -> calleeIsConnected() (activity not running)");
					calleeIsConnected();
					incomingCall = false;
					return;
				}

				if(message.startsWith("waitingCallers|")) {
					String payload = message.substring(15);
					if(payload.length()>0) {
						try {
							JSONArray jArray = new JSONArray(payload);
							Log.d(TAG,"onMessage waitingCallers elements="+jArray.length());
							if(jArray.length()>0) {
								if(context==null) {
									Log.e(TAG,"# onMessage waitingCallers: payload="+payload+
										", no context to wake activity");
								} else if(activityVisible) {
									Log.d(TAG,"onMessage waitingCallers: payload="+payload+
										", activityVisible (do nothing)");
								} else {
									JSONObject oneObject = jArray.getJSONObject(0);
									String callerID = oneObject.getString("CallerID");
									String callerName = oneObject.getString("CallerName");
									String txtMsg = "(waiting)";
									if(jArray.length()>1) {
										txtMsg = "(more waiting...)";
									}
									incomingCall(callerID,callerName,txtMsg,true);
									//startRinging();
								}
							} else {
								notificationManager.cancel(NOTIF_ID2);
							}
						} catch(Exception ex) {
							Log.d(TAG,"# onMessage "+message+" json parse ex="+ex);
						}
					}
					return;
				}

				// always let callerOffer and missedCalls through
				// but everything else needs incomingCall==true to be processed
				if(!message.startsWith("callerOffer|") && !message.startsWith("missedCalls|") && !incomingCall) {
					Log.d(TAG,"onMessage "+message+", no incomingCall (activity not running)");
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
				String argStr = "wsOnMessage2('"+encodedMessage+"','service');";
				/*
				if(message.startsWith("callerOffer|")) {
					//Log.d(TAG,"onMessage callerOffer -> runJS() (activity running)");
				} else if(message.startsWith("missedCalls|")) {
					//Log.d(TAG,"onMessage missedCalls -> runJS() (activity running)");
				} else if(message.startsWith("callerCandidate|")) {
					//Log.d(TAG,"onMessage callerCandidate -> runJS() (activity running)");
				} else {
					//Log.d(TAG,"onMessage "+message+" -> runJS("+argStr+") (activity running)");
				}
				*/
				//Log.d(TAG,"onMessage runJS "+argStr);
				// forward message to signalingCommand() in callee.js
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
			if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { // api<24
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
					Log.d(TAG,"alarm skip reconnect, no connectToServerIsWanted");
				/*
				} else if(reconnectBusy) {
					// alarm only fires if device is in doze, and then reconnectSchedFuture does NOT fire
					// so we do NOT skip this alarm!
					// instead we canncel reconnectSchedFuture (if it is not done)
					// and startReconnecter
					Log.d(TAG,"alarm skip, reconnectBusy");
				*/
				} else if(haveNetworkInt<=0) {
					Log.d(TAG,"alarm skip reconnect, no network");
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
		if(ringPlayer!=null) {
			// ringtone already playing
			Log.d(TAG,"! startRinging skip: ringtone already playing");
			return;
		}

		// start playing ringtone
		Log.d(TAG,"startRinging");

		audioToSpeakerSet(audioToSpeakerMode>0,false);

		ringPlayer = new MediaPlayer();
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

		ringPlayer.setAudioAttributes(aa);
		ringPlayer.setLooping(true);
		try {
			AssetFileDescriptor ad = getResources().openRawResourceFd(R.raw.ringing);
			ringPlayer.setDataSource(ad.getFileDescriptor(), ad.getStartOffset(), ad.getLength());
			ad.close();

			ringPlayer.prepare();
			ringPlayer.start();
			// we stop ringing in multiple places, see: stopRinging()
		} catch(IOException ex) {
			Log.d(TAG,"# startRinging ringtone ex="+ex);
			ringPlayer.stop();
			ringPlayer = null;
		}
	}

	private void stopRinging(String comment) {
		// stop playing the ringtone
		Log.d(TAG,"stopRinging from=("+comment+")");
		if(ringPlayer!=null) {
			ringPlayer.stop();
			ringPlayer = null;
		} else {
			//Log.d(TAG,"stopRinging (was not active), from="+comment);
		}
	}

	private void calleeIsConnected() {
		// sessionId received
		Log.d(TAG,"calleeIsConnected()");

		postStatus("state","connected");

		// problem: statusMessage() (runJS) is not always executed in doze mode
		// we need a method to display the last msg when device gets out of doze
		// see: lastStatusMessage
		Log.d(TAG,"calleeIsConnected() status(readyToReceiveCallsString)");
		statusMessage(readyToReceiveCallsString,-1,true);

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
			//Log.d(TAG,"setLoginUrl webcalldomain="+webcalldomain);
		} catch(Exception ex) {
			Log.d(TAG,"# setLoginUrl webcalldomain ex="+ex);
			return;
		}
		try {
			// loginUserName = calleeID
			loginUserName = prefs.getString("username", "").toLowerCase(Locale.getDefault());
			//Log.d(TAG,"setLoginUrl username="+loginUserName);
		} catch(Exception ex) {
			Log.d(TAG,"# setLoginUrl username ex="+ex);
			return;
		}

		loginUrl = "https://"+webcalldomain+"/rtcsig/login?id="+loginUserName+
					"&ver="+ BuildConfig.VERSION_NAME+"_"+getWebviewVersion(); // +"&re=true";
		//Log.d(TAG,"setLoginUrl="+loginUrl);
	}

	private void startReconnecter(boolean wakeIfNoNet, int reconnectDelaySecs) {
		Log.d(TAG,"startReconnecter myWebView="+(myWebView!=null));
		if(wsClient!=null) {
			closeWsClient(false, "startReconnecter");
		}

//		if(haveNetworkInt<=0 && wakeIfNoNet && screenForWifiMode>0) {
//			Log.d(TAG,"startReconnecter noNetwork: wakeIfNoNet + screenForWifiMode");
//			wakeUpFromDoze();
//		}

		if(!reconnectBusy) {
			setLoginUrl();
			// TODO do we need to copy cookies here?
			if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
				Log.d(TAG,"startReconnecter cancel old then start new reconnectSchedFuture "+reconnectDelaySecs);
				reconnectSchedFuture.cancel(false);
				reconnectSchedFuture = null;
			} else {
				Log.d(TAG,"startReconnecter start reconnectSchedFuture "+reconnectDelaySecs+" "+(myWebView!=null));
			}
			reconnectSchedFuture = scheduler.schedule(reconnecter, reconnectDelaySecs, TimeUnit.SECONDS);
		} else {
			Log.d(TAG,"! startReconnecter skip: reconnectBusy="+reconnectBusy);
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
				postStatus("toast", "file "+filename+" stored in download directory");
			} catch(Exception ex) {
				// should never happen: activity fetches WRITE_EXTERNAL_STORAGE permission up front
				Log.d(TAG,"storeByteArrayToFile ex="+ex);
				postStatus("toast", "exception "+ex.toString());
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

				postStatus("toast", "file "+filename+" stored in download directory");
			}
			catch (IOException ex) {
				Log.d(TAG,"storeByteArrayToFile ex="+ex);
				if (uri != null) {
					// Don't leave an orphan entry in the MediaStore
					resolver.delete(uri, null, null);
				}

				postStatus("toast", "exception "+ex.toString());
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
			String argStr = "wsOnMessage2('"+encodedMessage+"','service');";
			//Log.d(TAG,"processWebRtcMessages runJS "+argStr);
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
					// forward queued message to signalingCommand() in callee.js
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

/*
	private void wakeUpFromDoze() {
		// this is for wakeing up WIFI; if wifi is switched off, doing this does not make sense
		if(wifiManager.isWifiEnabled()==false) {
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
*/

	private void clearCookies() {
		Log.d(TAG,"clearCookies");
		storePrefsString("cookies", "");
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
//				wakeUpOnLoopCount(context);
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

//					// for old Android devices: wakeUpFromDoze() may help with wifi connectivity
//					if(screenForWifiMode>0) {
//						Log.d(TAG,"reconnecter wakeUpFromDoze "+reconnectCounter);
//						wakeUpFromDoze();
//					}
					if(beepOnLostNetworkMode>0) {
						playSoundAlarm();
					}

					// while playSoundAlarm() plays, a "networkCallback network capab change" may arrive
					// we check haveNetworkInt again, bc it may have come in during playSoundAlarm()
					if(haveNetworkInt<=0) {
						// we pause reconnecter; if network comes back, checkNetworkState() will
						// schedule a new reconnecter if connectToServerIsWanted is set
						if(connectToServerIsWanted) {
							Log.d(TAG,"no network, reconnecter paused...");
							statusMessage("No network. Will reconnect...",-1,true);

							// let JS know that wsConn is gone
							if(myWebView!=null && webviewMainPageLoaded) {
								Log.d(TAG,"reconnecter -> js:wsOnClose2");
								runJS("wsOnClose2()",null);
							}
						} else {
							Log.d(TAG,"no network, reconnecter stopped");
							statusMessage("No network. Reconnector stopped.",-1,true);

							// let JS know that wsConn is gone
							if(myWebView!=null && webviewMainPageLoaded) {
								Log.d(TAG,"reconnecter -> js:offlineAction");
								runJS("offlineAction()",null);
							}
						}

						reconnectBusy = false;
						reconnectCounter = 0;
						return;
					}
				}

				if(!connectToServerIsWanted) {
					Log.d(TAG,"reconnecter not wanted, aborted");
					return;
				}

				setLoginUrl();
				Log.d(TAG,"reconnecter login "+loginUrl);

				statusMessage("Login "+loginUserName,-1,true);
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
							Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
							reconnectSchedFuture.cancel(false);
						}
						reconnectSchedFuture = null;
						reconnectCounter = 0;
						reconnectBusy = false;
						return;
					}
					String exString = "";
					int status = 0;
					try {
						Log.d(TAG,"reconnecter con.connect()");
						con.connect();
						status = con.getResponseCode();
						if(!connectToServerIsWanted) {
							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
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

						exString = ex.toString();
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
							Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
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
							closeWsClient(false, "reconnecter status!=200 before start");
						}
						if(reconnectCounter < ReconnectCounterMax) {
							int delaySecs = reconnectCounter*10;
							if(delaySecs>ReconnectDelayMaxSecs) {
								delaySecs = ReconnectDelayMaxSecs;
							}
							if(status!=0) {
								Log.d(TAG,"reconnecter fail status="+status+" retry in "+delaySecs+"sec");
								statusMessage("Failed to reconnect, will try again... (status="+status+")",-1,true);
							} else if(exString!="") {
								Log.d(TAG,"reconnecter fail ex="+exString+" retry in "+delaySecs+"sec");
								statusMessage("Failed to reconnect, will try again... (ex="+exString+")",-1,true);
							} else {
								Log.d(TAG,"reconnecter fail, retry in "+delaySecs+"sec");
								statusMessage("Failed to reconnect, will try again...",-1,true);
							}
							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								//Log.d(TAG,"cancel old schedFuture");
								reconnectSchedFuture.cancel(false);
								reconnectSchedFuture = null;
							} else {
								//Log.d(TAG,"no old schedFuture to cancel");
							}
							// let JS know that wsConn is gone
							if(myWebView!=null && webviewMainPageLoaded) {
								Log.d(TAG,"reconnecter -> js:wsOnClose2");
								runJS("wsOnClose2()",null);
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

						// give up reconnector, tried often enough
						Log.d(TAG,"reconnecter con.connect() fail. give up.");
						postStatus("state", "disconnected");
						postStatus("state", "deactivated");

						if(reconnectBusy) {
							// turn reconnecter off
							reconnectBusy = false;
							if(beepOnLostNetworkMode>0) {
								playSoundAlarm();
							}
							statusMessage("Gave up reconnecting",-1,true);
							if(myWebView!=null && webviewMainPageLoaded) {
								// offlineAction(): disable offline-button and enable online-button
								runJS("offlineAction();",null);
							}
							// we delay connectToServerIsWanted=false so that notifications will still be shown
							final Runnable runnable2 = new Runnable() {
								public void run() {
									connectToServerIsWanted = false;
									// reconnector is now off, but should the app be restarted, it should run again
									//storePrefsBoolean("connectWanted",false);
								}
							};
							scheduler.schedule(runnable2, 300l, TimeUnit.MILLISECONDS);
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
							Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
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
						Log.d(TAG,"# reconnecter login fail '"+wsAddr+"' give up "+reader.readLine()+
							" "+reader.readLine()+" "+reader.readLine()+" "+reader.readLine());
						statusMessage("Gave up reconnecting, "+response,-1,true);

						postStatus("state", "deactivated");
						if(wsAddr.equals("fatal") || wsAddr.equals("error") || wsAddr.equals("notregistered")) {
							bringActivityToFront();
							// in addition we do this to tell the tile to close the notification drawer
							postStatus("state", "openactivity");
						}

						// we delay connectToServerIsWanted=false so that notifications are still shown 
						final Runnable runnable2 = new Runnable() {
							public void run() {
								connectToServerIsWanted = false;
								storePrefsBoolean("connectWanted",false);
							}
						};
						scheduler.schedule(runnable2, 300l, TimeUnit.MILLISECONDS);

						if(myWebView!=null && webviewMainPageLoaded) {
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
							Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
							reconnectSchedFuture.cancel(false);
						}
						reconnectSchedFuture = null;
						reconnectCounter = 0;
						reconnectBusy = false;
						return;
					}

					Log.d(TAG,"connectHost("+wsAddr+") net="+haveNetworkInt+" "+(myWebView!=null));
					if(haveNetworkInt==2) {
						statusMessage("Connecting via Wifi...",-1,true);
					} else if(haveNetworkInt==1) {
						statusMessage("Connecting via Mobile...",-1,true);
					} else {
						statusMessage("Connecting..",-1,true);
					}

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
							statusMessage("Server lost, failed to reconnect, will trying again... ",-1,true);

							if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
								Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
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
							statusMessage("Gave up reconnecting",-1,true);
							if(myWebView!=null && webviewMainPageLoaded) {
								// offlineAction(): disable offline-button and enable online-button
								runJS("offlineAction();",null);
							}
						}
						reconnectBusy = false;
						reconnectCounter = 0;

						postStatus("state", "disconnected");
						postStatus("state", "deactivated");
						return;
					}

					// success - wsClient is set

					if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
						Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
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
					//statusMessage("reconnect to server",500,true,false);	// TODO statusMessage needed ???

					// we trust now that server will receive "init" and respond with "sessionId|"+codetag
					// onMessage() will receive this and call runJS(wsOnMessage2('sessionId|v3.5.5','service');)
					// this should call calleeIsConnected() - but this does not always work

					// calleeIsConnected() will send readyToReceiveCallsString notification
					// calleeIsConnected() will brodcast state connected

				} catch(Exception ex) {
					// this can be caused by webview not installed or just now uninstalled
					// "android.webkit.WebViewFactory$MissingWebViewPackageException: "
					//   "Failed to load WebView provider: No WebView installed

					postStatus("state", "disconnected");

					if(!connectToServerIsWanted) {
						// abort forced
						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
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

						statusMessage("Failed to reconnect, will try again...",-1,true);

						if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
							Log.d(TAG,"reconnecter cancel reconnectSchedFuture");
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
						statusMessage("Gave up reconnecting",-1,true);
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

					postStatus("state", "deactivated");
					return;
				}

				// send 'init' to register as callee, otherwise the server will kick us out
				// in response we will get sessionId and missedCalls
				Log.d(TAG,"reconnecter send init "+(myWebView!=null) +" "+webviewMainPageLoaded);
				try {
					wsClient.send("init|");
					// server is expected to send back: "sessionId|(serverCodetag)"

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
		stopSelfFlag = false;
		try {
			if(!setAddr.equals("")) {
				wsAddr = setAddr;
				if(auto) {
					// service reconnect: set auto=true telling server this is not a manual login
					wsAddr += "&auto=true";
				}
				Log.d(TAG,"connectHost create new WsClient");
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
							//Log.d(TAG,"connectHost wifiLock isHeld");
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
					// then we will send: updateNotification readyToReceiveCallsString
					// then we will broadcast: "state", "connected" (for tile)
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

		Log.d(TAG,"connectHost fail, clear wsClient, return null");
		wsClient = null;
		//updateNotification(offlineMessage);
		statusMessage(offlineMessage,-1,true);
		postStatus("state", "disconnected");
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

	    if(connectivityManager == null) {
			Log.d(TAG,"checkNetworkState connectivityManager==null");
			haveNetworkInt=0;
			return;
	    }

		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
	        NetworkCapabilities capabilities =
				connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
	        if(capabilities == null) {
				Log.d(TAG,"checkNetworkState capabilities==null");
				haveNetworkInt=0;
				return;
	        }
            if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
		        haveNetworkInt = 2;
				Log.d(TAG,"checkNetworkState TRANSPORT_WIFI");

				if(connectToServerIsWanted) {
					if(setWifiLockMode<=0) {
						Log.d(TAG,"checkNetworkState WifiLockMode off");
					} else if(wifiLock==null) {
						Log.d(TAG,"checkNetworkState wifiLock==null");
					} else if(wifiLock.isHeld()) {
						Log.d(TAG,"checkNetworkState wifiLock isHeld");
					} else {
						// enable wifi lock
						Log.d(TAG,"checkNetworkState wifiLock.acquire");
						wifiLock.acquire();
					}
				}
				return;
            }
            if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
		        haveNetworkInt = 1;
				Log.d(TAG,"checkNetworkState TRANSPORT_CELLULAR");
				return;
            }
            if(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) ||
               capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
		        haveNetworkInt = 3;
				Log.d(TAG,"checkNetworkState transport other");
				return;
            }
		} else {
			NetworkInfo netActiveInfo = connectivityManager.getActiveNetworkInfo();
			if(netActiveInfo==null) {
				Log.d(TAG,"checkNetworkState netActiveInfo==null");
				// this will make onClose postpone reconnect attempts
				haveNetworkInt=0;
				statusMessage("No network",-1,true);
				return;
			}

		    if(netActiveInfo.getType() == ConnectivityManager.TYPE_WIFI) { // ==1
		        haveNetworkInt = 2;
				Log.d(TAG,"checkNetworkState TYPE_WIFI");
				if(connectToServerIsWanted) {
					if(setWifiLockMode<=0) {
						Log.d(TAG,"checkNetworkState WifiLockMode off");
					} else if(wifiLock==null) {
						Log.d(TAG,"checkNetworkState wifiLock==null");
					} else if(wifiLock.isHeld()) {
						Log.d(TAG,"checkNetworkState wifiLock isHeld");
					} else {
						// enable wifi lock
						Log.d(TAG,"checkNetworkState wifiLock.acquire");
						wifiLock.acquire();
					}
				}
				return;
		    }
		    if(netActiveInfo.getType() == ConnectivityManager.TYPE_MOBILE) { // ==0
		        haveNetworkInt = 1;
				Log.d(TAG,"checkNetworkState TYPE_MOBILE");
				return;
		    }
		    if(netActiveInfo.getType() == ConnectivityManager.TYPE_VPN ||      // ==17
		              netActiveInfo.getType() == ConnectivityManager.TYPE_ETHERNET) { // ==9
		        haveNetworkInt = 3;
				Log.d(TAG,"checkNetworkState type other");
				return;
		    }
		}

		Log.d(TAG,"! checkNetworkState nothing");
		haveNetworkInt=0;
	}

	private void disconnectHost(boolean sendNotification, boolean skipStopForeground) {
		// called by wsClose() and wsExit()
		Log.d(TAG,"disconnectHost "+sendNotification+" "+skipStopForeground);

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
		}

		if(wsClient!=null) {
			// disable networkStateReceiver
			if(networkStateReceiver!=null) {
				Log.d(TAG,"disconnectHost unregister networkStateReceiver");
				unregisterReceiver(networkStateReceiver);
				networkStateReceiver = null;
			}
			// clearing wsClient, so that onClose (triggered by closeBlocking()) won't start new wakeIntent
			closeWsClient(true, "disconnectHost");
		}

		statusMessage(offlineMessage, -1, sendNotification);

		// TODO this did not change the state of the online/offline buttons/switch
		//      is also useless for the clearCache+reload use case

		// clear the offlineMessage from lastStatusMessage, so that it will not be pulled and shown from postDozeAction()
		Log.d(TAG,"disconnectHost clear lastStatusMessage");
		lastStatusMessage = "";
		postStatus("state", "disconnected");

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

// TODO tmtmtm: should any of these unregister methods (see onDestroy()) need to be called?
//		alarmReceiver serviceCmdReceiver networkStateReceiver
//      powerConnectionReceiver dozeStateReceiver myNetworkCallback

		if(skipStopForeground) {
			Log.d(TAG,"disconnectHost with skipStopForeground, removeNotification() not wanted");
		} else {
			connectToServerIsWanted = false;
			storePrefsBoolean("connectWanted",false); // used in case of service crash + restart
			postStatus("state", "deactivated");

			if(callPickedUpFlag || peerConnectFlag) {
				Log.d(TAG,"disconnectHost no skipStopForeground, but callInProgress");
			} else {
				Log.d(TAG,"disconnectHost -> removeNotification()");
				// remove the Android notification
				// we delay this so that the last notification msg (offlineMessage) is still shown
				scheduler.schedule(new Runnable() {
					public void run() {
						// no more notification msgs afte this
						removeNotification();
						Log.d(TAG,"disconnectHost delayed done");
					}
				}, 200l, TimeUnit.MILLISECONDS);
			}
		}
	}

	private void removeNotification() {
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			Log.d(TAG,"removeNotification delayed stopForeground()");
			stopForeground(true); // true = removeNotification
		}

		// without .cancel(NOTIF_ID1) our notification icon will not go away
		Log.d(TAG,"removeNotification notificationManager.cancel(NOTIF_ID1)");
		notificationManager.cancel(NOTIF_ID1);
	}

	private void endPeerCon() {
		// peercon has ended, stopRinging, clear peerConnectFlag + callPickedUpFlag
		Log.d(TAG, "endPeerCon");
		stopRinging("endPeerCon");
		callPickedUpFlag = false;
		peerConnectFlag = false;
		peerDisconnnectFlag = true;
	}

	private void endWebRtcSession(boolean disconnectCaller) {
		if(myWebView!=null && webviewMainPageLoaded) {
			Log.d(TAG, "endWebRtcSession runJS(endWebRtcSession("+
				"disconnectCaller="+disconnectCaller+",connectToServerIsWanted="+connectToServerIsWanted+"))");
			// 1st param: disconnectCaller
			runJS("endWebRtcSession("+disconnectCaller+","+connectToServerIsWanted+")", new ValueCallback<String>() {
				@Override
				public void onReceiveValue(String s) {
					//endPeerCon2();
				}
			});
		} else {
			Log.d(TAG, "endWebRtcSession no webview')");
		}
	}

	protected void runJS(final String str, final ValueCallback<String> myBlock) {
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
			// when problem striks, "runJS(...) post..." will be logged
			//                  but "runJS evalJS exec"  will be not logged
			if(!logstr.startsWith("wsOnMessage2")) {
				Log.d(TAG, "runJS("+logstr+") post...");
			}
			// Causes the Runnable r to be added to the message queue.
			// The runnable will be run on the thread to which this handler is attached.
			if(!myWebView.post(new Runnable() {
				@Override
				public void run() {
					// run() will run on the UI thread
					myWebView.removeCallbacks(this);
					// escape '\r\n' to '\\r\\n'
					final String str2 = str.replace("\\", "\\\\");
					//Log.d(TAG,"runJS evalJS "+str2);
					if(myWebView==null) {
						Log.d(TAG,"# runJS evalJS "+str2+" but no myWebView");
					} else if(!webviewMainPageLoaded && !str.equals("history.back()")) {
						Log.d(TAG,"# runJS evalJS "+str2+" but no webviewMainPageLoaded (and not history.back())");
					} else {
						// evaluateJavascript() instead of loadUrl()
						//Log.d(TAG,"runJS evalJS exec: "+str2);
						try {
							myWebView.evaluateJavascript(str2, myBlock);
						} catch(Exception ex) {
							Log.d(TAG,"# runJS evalJS ex="+ex);
						}
					}
				}
			})) {
				// Returns false on failure, usually because the looper processing the message queue is exiting.
				Log.d(TAG,"# runJS post runnable failed");
			}
		}
	}

/*
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
*/
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
						postStatus("toast", "Ring on speaker activated");
					}
				} else {
					setForceUse.invoke(null, 1, 0); // FOR_MEDIA, DON'T FORCE_SPEAKER
					if(showUser) {
						postStatus("toast", "Ring on speaker disabled");
					}
				}
				audioToSpeakerActive = set;
				Log.d(TAG,"audioToSpeakerSet setForceUse "+set);
				storePrefsInt("audioToSpeaker", audioToSpeakerMode);
			} catch(Exception ex) {
				Log.d(TAG,"audioToSpeakerSet "+set+" ex="+ex);
				postStatus("toast", "Ring on speaker not available");
				audioToSpeakerMode = 0;
				storePrefsInt("audioToSpeaker", audioToSpeakerMode);
			}
		} else {
			// TODO Android 9+ implementation needed
			// see: setAudioRoute(ROUTE_SPEAKER) from android/telecom/InCallService is needed
			// https://developer.android.com/reference/android/telecom/InCallService
			// audioToSpeakerActive = set;
			if(set) {
				postStatus("toast", "Ring on speaker not available");
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


	private void incomingCall(String callerID, String callerName, String txtMsg, boolean waitingCaller) {
		String contentText = callerName+" "+callerID;
		if(textmode.equals("true")) { // set by signalingCommand()
			contentText += " TextMode ";
		}
		if(txtMsg!="") {
			contentText += " \""+txtMsg+"\""; // greeting msg
		}

		incomingCall = true;

		// activity is NOT visible
		Date wakeDate = new Date();
		//Log.d(TAG,"onMessage incoming call: "+
		//	new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(wakeDate));
		Log.d(TAG,"onMessage incoming call: "+contentText+", Android10+ notification");

		long eventMS = wakeDate.getTime();

		Intent acceptIntent = new Intent(context, WebCallCalleeActivity.class);
		acceptIntent.putExtra("wakeup", "pickup");
		acceptIntent.putExtra("date", eventMS);

		Intent switchToIntent =	new Intent(context, WebCallCalleeActivity.class);
		switchToIntent.putExtra("wakeup", "call");
		switchToIntent.putExtra("date", eventMS);

		Intent denyIntent = new Intent("serviceCmdReceiver");
		denyIntent.putExtra("denyCall", "true");
		if(waitingCaller) {
			denyIntent.putExtra("denyID", callerID);
		}

		NotificationCompat.Builder notificationBuilder =
			new NotificationCompat.Builder(context, NOTIF_HIGH)
				.setSmallIcon(R.mipmap.notification_icon)
				.setContentTitle("WebCall incoming")
				.setOngoing(true)
				.setCategory(NotificationCompat.CATEGORY_CALL)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				//.setLights(0xff00ff00, 300, 100)			// TODO does not seem to work on N7

				// on O+ setPriority is ignored in favor of notifChannel (NOTIF_P2P)
				.setPriority(NotificationCompat.PRIORITY_HIGH)

				.addAction(R.mipmap.notification_icon,"Accept",
					PendingIntent.getActivity(context, 2, acceptIntent,
						PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))

				.addAction(R.mipmap.notification_icon,"Switch",
					PendingIntent.getActivity(context, 3, switchToIntent,
						PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))

				.addAction(R.mipmap.notification_icon,"Reject",
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
		notificationManager.notify(NOTIF_ID2, notification);
		Log.d(TAG,"onMessage incoming call: "+contentText+", Android10+ notification sent");

		// send log RING to server
		String ringMsg = "log|callee Incoming /";
		// do this later when all deployed server can cope without 3rd arg
		//String ringMsg = "log|callee Incoming";
		Log.d(TAG,"wsClient.send RING-log "+ringMsg);
		try {
			wsClient.send(ringMsg);
		} catch(Exception ex) {
			Log.d(TAG,"# wsClient.send "+ringMsg+" ex="+ex.toString());
		}

		//startRinging();
	}

	private void statusMessage(String message, int timeoutMs, boolean notifi) {
		// webcall status msg + android notification (if notifi + important are true)
		//Log.d(TAG,"statusMessage: "+message+" n="+notifi);
		if(myWebView==null) {
			Log.d(TAG,"statusMessage: "+message+" n="+notifi+" skip: no webview");
		} else if(!webviewMainPageLoaded) {
			Log.d(TAG,"statusMessage: "+message+" n="+notifi+" skip: notOnMainPage");
		} else if(peerConnectFlag || callPickedUpFlag) {
			Log.d(TAG,"statusMessage: skip while peerCon: "+message);
		} else if(message.equals("")) {
			Log.d(TAG,"statusMessage: no display of empty message");
		} else {
			String dispMsg = message;
			Log.d(TAG,"statusMessage: "+dispMsg+" (n="+notifi+")");
			// encodedMsg MUST NOT contain apostrophe
			String encodedMsg = dispMsg.replace("'", "&#39;");
			runJS("showStatus('"+encodedMsg+"',"+timeoutMs+");",null);
			//runJS("showStatus('"+encodedMsg+"',"+timeoutMs+");", new ValueCallback<String>() {
			//	@Override
			//	public void onReceiveValue(String s) {
			//		Log.d(TAG,"statusMessage completed: "+encodedMsg);
			//	}
			//});

			// may throw: "# con: Uncaught ReferenceError: showStatus is not defined L1"
		}

		if(notifi) {
			updateNotification(message);
		}
	}

	private void updateNotification(String message) {
		// on O+ channelID/notifID determines the priority, not important (PRIORITY_HIGH/LOW)
		if(message.equals("")) {
			// if message is empty, updateNotification + callInProgressNotification will display lastStatusMessage again
			message = lastStatusMessage;
			Log.d(TAG,"updateNotification msg is empty, set to lastStatusMessage="+message);
		} else {
			lastStatusMessage = message;
			//Log.d(TAG,"updateNotification lastStatusMessage set to message="+message);
		}
		if(stopSelfFlag) {
			Log.d(TAG,"updateNotification msg="+message+" skip on stopSelfFlag");
		} else if(Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) { // < 19
			Log.d(TAG,"updateNotification msg="+message+" SKIP sdk="+Build.VERSION.SDK_INT+" smaller than K (19)");
		} else {
			if(peerConnectFlag) {
				Log.d(TAG,"updateNotification message="+message+" (->callInProg)");
				callInProgressNotification(message);
				return;
			}

			if(!message.equals("")) {
				//String dispMsg = "Server: "+message;
				String dispMsg = message;
				Log.d(TAG,"updateNotification message="+dispMsg);
				Notification notif = buildServiceNotification(dispMsg, NOTIF_LOW, NotificationCompat.PRIORITY_LOW);
				notificationManager.notify(NOTIF_ID1, notif);
			}
		}
	}

	private void callInProgressNotification(String message) {
		if(context==null) {
			Log.e(TAG,"# callInProgressNotification no context");
			return;
		}

		Date wakeDate = new Date();
		long eventMS = wakeDate.getTime();

		Intent muteIntent = new Intent("serviceCmdReceiver");
		muteIntent.putExtra("muteMic", "true");		// like "denyCall"

		Intent hangupIntent = new Intent("serviceCmdReceiver");
		hangupIntent.putExtra("hangup", "true");		// like "denyCall"

		Intent switchToIntent =	new Intent(context, WebCallCalleeActivity.class);
		switchToIntent.putExtra("wakeup", "call");
		switchToIntent.putExtra("date", eventMS);

		String title = callInProgressMessage;
		String muteButtonLabel = "Mute";
		if(micMuteState) {
			title = callInProgressMessage+" (mic muted)";
			muteButtonLabel = "Unmute";
		}

		String dispMsg = message;
		if(dispMsg.equals(readyToReceiveCallsString)) {
			dispMsg = connectedToServerString; // "Connected in standby"
		}

		if(!dispMsg.equals("")) {
			dispMsg = "WebCall Server: "+dispMsg;
		}

		Log.d(TAG,"callInProgressNotif title="+title+" dispMsg="+dispMsg);

		NotificationCompat.Builder notificationBuilder =
			new NotificationCompat.Builder(context, NOTIF_LOW)
				.setSmallIcon(R.mipmap.notification_icon)
				.setContentTitle(title)
				.setOngoing(true)
				.setCategory(NotificationCompat.CATEGORY_CALL)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

				// on O+ setPriority is ignored in favor of notifChannel (NOTIF_P2P)
				.setPriority(NotificationCompat.PRIORITY_HIGH)

				.addAction(R.mipmap.notification_icon, "Hangup",
					PendingIntent.getBroadcast(context, 2, hangupIntent,
						PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))

				.addAction(R.mipmap.notification_icon, muteButtonLabel,
					PendingIntent.getBroadcast(context, 3, muteIntent,
						PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))

				//.setAutoCancel(true) // any click will close the notification
				// if this is false, any click will switchTo activity

				// we are using our own ringtone (see startRinging())
				//.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

				// clicking on the area behind the action buttons will (also) switchTo activty
				.setFullScreenIntent(
					PendingIntent.getActivity(context, 1, switchToIntent,
						PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE), true)

				.setContentText(dispMsg);

		Notification notification = notificationBuilder.build();
		notificationManager.notify(NOTIF_ID1, notification);
		//Log.d(TAG,"callInProgressNotification: Android10+ notification sent");
	}

	private Notification buildServiceNotification(String msg, String notifChannel, int prio) {
		// only call with Build.VERSION.SDK_INT >= Build.VERSION_CODES.O // >= 26
		Intent notificationIntent = new Intent(this, WebCallCalleeActivity.class);
		PendingIntent pendingIntent =
			PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

		//Log.d(TAG,"buildServiceNotification msg="+msg+" chl="+notifChannel+" !="+important);
		NotificationCompat.Builder notificationBuilder =
			new NotificationCompat.Builder(this, notifChannel)
					.setContentTitle(msg) // 1st line
					.setPriority(prio) // on O+ setPriority is ignored in favor of notifChannel
					.setOngoing(true)
					//.setContentText(msg) // 2nd line
					.setSmallIcon(R.mipmap.notification_icon)
					.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
					.setContentIntent(pendingIntent);
		return notificationBuilder.build();
	}

	private void exitService() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
			// remove the forground-notification
			Log.d(TAG,"exitService stopForeground()");
			stopForeground(true); // true = removeNotification
		}

		// kill the service itself
		Log.d(TAG, "exitService stopSelf()");
		onDestroy();
		stopSelfFlag = true;
		// stopSelf must not be called without a preceeding startForeground
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

	private void networkChange(int newNetworkInt, int oldNetworkInt, String comment) {
		// called by onAvailable() or onCapabilitiesChanged()
		Log.d(TAG,"networkChange start "+comment+" old="+oldNetworkInt+" new="+newNetworkInt);
		if(newNetworkInt!=2 /*&& oldNetworkInt==2*/) {
			// lost wifi
			if(wifiLock!=null && wifiLock.isHeld()) {
				// release wifi lock
				Log.d(TAG,"networkChange wifi gone -> wifiLock.release");
				wifiLock.release();
			}
		}

		if(newNetworkInt<=0 && oldNetworkInt>0) {
			// lost network
			if(oldNetworkInt==2) {
				Log.d(TAG,"networkChange lost Wifi");
				statusMessage("Wifi network lost",-1,true);
			} else if(oldNetworkInt==1) {
				Log.d(TAG,"networkChange lost mobile");
				statusMessage("Mobile network lost",-1,true);
			} else {
				Log.d(TAG,"networkChange lost "+haveNetworkInt);
				statusMessage("Network lost",-1,true);
			}
		} else {
			// gained network
			if(!connectToServerIsWanted || reconnectBusy) {
				if(newNetworkInt>0 && newNetworkInt!=oldNetworkInt) {
					if(newNetworkInt==2) {
						Log.d(TAG,"networkChange gained Wifi");
						statusMessage("Wifi network ready",-1,true);
					} else if(newNetworkInt==1) {
						Log.d(TAG,"networkChange gained mobile");
						statusMessage("Mobile network ready",-1,true);
					} else {
						Log.d(TAG,"networkChange gained "+newNetworkInt);
						statusMessage("Network ready",-1,true);
					}
				}
				if(!connectToServerIsWanted) {
					Log.d(TAG,"networkChange abort conWant==false");
				} else if(reconnectBusy) {
					Log.d(TAG,"networkChange abort reconnectBusy");
				}
			} else {
				// start reconnecter (independent of whether we have a network or not)
				Log.d(TAG,"networkChange start...");
				if(newNetworkInt==2) {
					statusMessage("Reconnecting via Wifi...",-1,true);
					if(oldNetworkInt!=2) {
						if(setWifiLockMode<=0) {
							// prefer wifi not enabled by user
							Log.d(TAG,"networkChange gainWifi WifiLockMode off");
						} else if(wifiLock==null) {
							Log.d(TAG,"# networkChange gainWifi wifiLock==null");
						} else if(wifiLock.isHeld()) {
							Log.d(TAG,"networkChange gainWifi wifiLock isHeld already");
						} else {
							// enable wifi lock
							Log.d(TAG,"networkChange gainWifi wifiLock.acquire");
							wifiLock.acquire();
						}
					}
				} else if(newNetworkInt==1) {
					statusMessage("Reconnecting via Mobile...",-1,true);
				} else if(newNetworkInt>0) {
					statusMessage("Reconnecting...",-1,true);
				} else {
					// this would overwrite the 'network lost' msg
					statusMessage("Reconnecting...",-1,true);
				}

				// we need keepAwake to manage the reconnect
				if(keepAwakeWakeLock!=null && !keepAwakeWakeLock.isHeld()) {
					Log.d(TAG,"networkChange keepAwakeWakeLock.acquire");
					keepAwakeWakeLock.acquire(3 * 60 * 1000);
					keepAwakeWakeLockStartTime = (new Date()).getTime();
				}

				if(wsClient!=null) {
					// disconnect old connection to avoid server re-login denial ("already/still logged in")
					// note: this may cause: onClose code=1000 ("normal closure")
					closeWsClient(false, "networkChange");
				}

				// call scheduler.schedule()
				if(reconnectSchedFuture!=null && !reconnectSchedFuture.isDone()) {
					// we don't want to wait more than 3s
					// but we also don't want to wait less than 3s
					//   so that server has enough time to detect this disconnect

					// why wait for the scheduled reconnecter job
					// let's cancel it and start in 3s from now
					// (3s, so that the server has enough time to detect the disconnect)
					Log.d(TAG,"networkChange cancel reconnectSchedFuture");
					if(reconnectSchedFuture.cancel(false)) {
						// next run reconnecter
						Log.d(TAG,"networkChange restart reconnecter in 3s");
						reconnectSchedFuture = scheduler.schedule(reconnecter, 3 ,TimeUnit.SECONDS);
					}
				} else {
					Log.d(TAG,"networkChange start reconnecter in 3s");
					reconnectSchedFuture = scheduler.schedule(reconnecter, 3, TimeUnit.SECONDS);
				}
			}
		}
	}

	private void closeWsClient(boolean blocking, String from) {
		WebSocketClient tmpWsClient = wsClient;
		wsClient = null;
		if(tmpWsClient==null) {
			if(blocking) {
				Log.d(TAG,"! "+from+" closeWsClient wsClient was null");
			} else {
				Log.d(TAG,"! "+from+" closeWsClient none-blocking wsClient was null");
			}
			return;
		}
		try {
			if(blocking) {
				Log.d(TAG,from+" closeWsClient blocking...");
				tmpWsClient.closeBlocking();
			} else {
				Log.d(TAG,from+" closeWsClient none-blocking...");
				tmpWsClient.close();
			}
		} catch(Exception ex) {
			if(blocking) {
				Log.d(TAG,"# "+from+" closeWsClient blocking ex="+ex);
			} else {
				Log.d(TAG,"# "+from+" closeWsClient none-blocking ex="+ex);
			}
		}

		if(blocking) {
			Log.d(TAG,from+" closeWsClient blocking done");
		} else {
			Log.d(TAG,from+" closeWsClient none-blocking done");
		}
	}

	private void postStatus(String key, String status) {
		Log.d(TAG,"postStatus "+key+" "+status);
		sendBroadcast(new Intent("webcall").putExtra(key,status));
	}

	private void bringActivityToFront() {
		Log.d(TAG, "bringActivityToFront");
		Intent webcallToFrontIntent =
			new Intent(context, WebCallCalleeActivity.class).putExtra("wakeup", "wake");
		webcallToFrontIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
			Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY |
			Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		context.startActivity(webcallToFrontIntent);
	}

	private void postDozeAction() {
		Log.d(TAG, "postDozeAction dozeIdleCounter="+dozeIdleCounter+" ("+lastStatusMessage+")");
		dozeIdleCounter=0;
		if(lastStatusMessage!="") {
			// runJS(statusMessage)) is not always executed while in doze mode
			// so we need a method to display the last msg when we get out of doze

			// TODO problem with this: that JS statusMessage() acts independent of service
			// so lastStatusMessage is not always the last status msg
			Log.d(TAG,"postDozeAction showStatus lastStatusMessage="+lastStatusMessage);
			runJS("showStatus('"+lastStatusMessage+"',-1);",null);
			lastStatusMessage="";
		}
	}
}

