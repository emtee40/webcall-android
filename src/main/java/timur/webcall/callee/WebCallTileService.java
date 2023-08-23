package timur.webcall.callee;

import android.os.Build;
import android.content.Intent;
import android.service.quicksettings.TileService;
import android.service.quicksettings.Tile;
import android.graphics.drawable.Icon;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.app.PendingIntent;
import android.os.IBinder;
import android.util.Log;

import java.util.Locale;
import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class WebCallTileService extends TileService {
	private final static String TAG = "WebCallTile";
	private static WebCallService.WebCallServiceBinder webCallServiceBinder = null;
	private static ScheduledExecutorService scheduler = null;
	private static volatile boolean waitingForScheduler = false;
	private static volatile boolean unbindServiceDenied = false;
	private static volatile boolean tilesVisible = false;
	private static volatile int getStateLoopCounter = 0;

	private static BroadcastReceiver broadcastReceiver = null;

	// Called when the user adds your tile.
	@Override
	public void onTileAdded() {
		super.onTileAdded();
		Log.d(TAG,"onTileAdded");
	}

	// Called when the user removes your tile.
	@Override
	public void onTileRemoved() {
		super.onTileRemoved();
		Log.d(TAG,"onTileRemoved");
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG,"onCreate");
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.d(TAG,"onDestroy");
		if(webCallServiceBinder!=null) {
			Log.d(TAG,"onDestroy unbindService");
			unbindService(serviceConnection);
			webCallServiceBinder = null;
		}
		if(broadcastReceiver!=null) {
			Log.d(TAG, "onDestroy unregister broadcastReceiver");
			unregisterReceiver(broadcastReceiver);
			// -> WebCallService: activityDestroyed exitService()
			broadcastReceiver = null;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		super.onBind(intent);
		Log.d(TAG,"onBind intent="+intent.toString());
		Context context = this;

		if(scheduler==null) {
			Log.d(TAG,"onBind Executors.newScheduledThreadPool(20)");
			scheduler = Executors.newScheduledThreadPool(2);
		}

		// to receive (pending-)intent msgs from the service
		if(broadcastReceiver==null) {
			broadcastReceiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					String state = intent.getStringExtra("state");
					if(state!=null && !state.equals("")) {
						if(state.equals("connected")) {
							// user is now connected as callee to webcall server
							Log.d(TAG, "broadcastReceiver wsCon state="+state);
							if(!tilesVisible) {
								Log.d(TAG, "broadcastReceiver requestListeningState");
								requestListeningState(context, new ComponentName(context, WebCallTileService.class));
							}
							updateTile(true);
						} else if(state.equals("disconnected")) {
							Log.d(TAG, "broadcastReceiver wsCon state="+state);
							// "temporary disconnect" must NOT set button off
							//updateTile(false);
						} else if(state.equals("deactivated")) {
							// "server giving up reconnecting" must set button off
							Log.d(TAG, "broadcastReceiver wsCon state="+state);
							if(!tilesVisible) {
								Log.d(TAG, "broadcastReceiver requestListeningState");
								requestListeningState(context, new ComponentName(context, WebCallTileService.class));
							}
							updateTile(false);
						} else if(state.equals("openactivity")) {
							// service wants us to open webcall activity and close the drawer
							Intent webcallToFrontIntent =
								new Intent(context, WebCallCalleeActivity.class).putExtra("wakeup", "wake");
							webcallToFrontIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
								Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY |
								Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
//							if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//								PendingIntent pendingIntent = PendingIntent.getBroadcast(
//										context, 0, webcallToFrontIntent, PendingIntent.FLAG_IMMUTABLE);
//								startActivityAndCollapse(pendingIntent);
//							} else {
								startActivityAndCollapse(webcallToFrontIntent);
//							}

						} else {
							//Log.d(TAG, "! broadcastReceiver unexpected state="+state);
						}
						return;
					}

					Log.d(TAG, "! broadcastReceiver unknown state="+state);
				}
			};
			registerReceiver(broadcastReceiver, new IntentFilter("webcall"));
		}

		if(webCallServiceBinder==null) {
			bindService(false);
		} else {
			Log.d(TAG,"onBind service already bound");
		}

		// active tiles may request to send an update to the System while their process is alive
		Log.d(TAG,"onBind requestListeningState");
		requestListeningState(context, new ComponentName(context, WebCallTileService.class));
	    return super.onBind(intent);
	}


	// Called when your app can update your tile.
	@Override
	public void onStartListening() {
		super.onStartListening();
		Log.d(TAG,"onStartListening");
		tilesVisible = true;
	}

	// Called when this tile moves out of the listening state.
	@Override
	public void onStopListening() {
		super.onStopListening();
		Log.d(TAG,"onStopListening");
		tilesVisible = false;
		Log.d(TAG,"onStopListening requestListeningState...");
		requestListeningState(this, new ComponentName(this, WebCallTileService.class));
	}

	// Called when the user taps on your tile in an active or inactive state.
	@Override
	public void onClick() {
		super.onClick();
		Log.d(TAG,"onClick...");

		if(webCallServiceBinder==null) {
			long delayMS = 100l;
			Log.d(TAG,"onClick no webCallServiceBinder delayMS="+delayMS);
			final Runnable runnable = new Runnable() {
				public void run() {
					if(webCallServiceBinder==null) {
						Log.d(TAG,"onClick no webCallServiceBinder2 bindService(forceCreate) + drop click");
						bindService(true);
						// drop the click; lets see if user is happy with current state
/*
						long delayMS2 = 200l; // should only take <20ms incl starting
						Log.d(TAG,"onClick no webCallServiceBinder2 delayMS2="+delayMS2);
						scheduler.schedule(this, delayMS2, TimeUnit.MILLISECONDS);
*/
					} else {
						Log.d(TAG,"onClick delayed toggle()");
						toggle();
					}
				}
			};
			scheduler.schedule(runnable, delayMS, TimeUnit.MILLISECONDS);
		} else {
			Log.d(TAG,"onClick immediate toggle()");
			toggle();
		}
	}

	private void toggle() {
		if(webCallServiceBinder==null) {
			Log.d(TAG,"# toggle skip, no webCallServiceBinder");
			// restart service?
			//bindService();
			return;
		}

		boolean isActive = getServiceStatus();
		isActive = !isActive;
		//Log.d(TAG,"toggle to state active="+isActive);
		if(isActive) {
//			if(webCallServiceBinder.haveNetwork() <= 0) {
//				// is it useless to goOnline without a network?
//			}
			Log.d(TAG,"toggle goOnline()");
			webCallServiceBinder.goOnline();
			// goOnline() can fail; broadcastReceiver events may call updateTile() with connect state
		} else {
			Log.d(TAG,"toggle goOffline()");
			webCallServiceBinder.goOffline();
			//updateTile(isActive);
		}
	}

	private boolean getServiceStatus() {
		boolean isActive = false;
		//isActive = WebCallService.wsClient!=null;
		if(webCallServiceBinder==null) {
			Log.d(TAG,"! getServiceStatus no webCallServiceBinder");
		} else {
			if(webCallServiceBinder.connectToServerIsWanted()) {
				isActive = webCallServiceBinder.webcallConnectType()>0;
			}
		}
		//Log.d(TAG,"getServiceStatus isActive="+isActive);
		return isActive;
	}

	private void updateTile(boolean isActive) {
		Tile tile = this.getQsTile();
		String newLabel;
		int newState;

		if(isActive) {
			newState = Tile.STATE_ACTIVE;
			newLabel = String.format(Locale.US, "%s %s",
					getString(R.string.tileservice_label),
					getString(R.string.service_active));
		} else {
			newState = Tile.STATE_INACTIVE;
			newLabel = String.format(Locale.US, "%s %s",
					getString(R.string.tileservice_label),
					getString(R.string.service_inactive));
		}

		Log.d(TAG,"updateTile isActive="+isActive+" "+newLabel);
		tile.setLabel(newLabel);
		tile.setState(newState);

		// let the tile to pick up changes
		tile.updateTile();
	}

	private void bindService(boolean force) {
		Intent serviceIntent = new Intent(this, WebCallService.class);
		Log.d(TAG,"bindService");
		if(!force && bindService(serviceIntent, serviceConnection, 0)) {
			Log.d(TAG,"bindService true");
		} else {
			Log.d(TAG,"bindService false");

			// service initialization
			//serviceIntent.putExtra("onstart", "donothing");
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // >= 26
				// foreground service
				Log.d(TAG,"bindService foreground service");
				startForegroundService(serviceIntent);
				// note: doing stopSelf() without first doing stopForeground() can crash our service
			} else {
				// regular service
				Log.d(TAG,"bindService regular service");
				startService(serviceIntent); // -> service.onStartCommand()
			}

			// here we bind the service, so that we can call goOnline()/goOffline()
			//bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
			if(bindService(serviceIntent, serviceConnection, 0)) {
				Log.d(TAG,"bindService requested");
			} else {
				Log.d(TAG,"bindService requested failed");
			}
		}
	}

	private ServiceConnection serviceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			// activity is connected to service (says nothing about connectivity to webcall server)
			webCallServiceBinder = (WebCallService.WebCallServiceBinder)service;
			if(webCallServiceBinder==null) {
				Log.d(TAG, "# onServiceConnected bind service failed");
			} else {
				Log.d(TAG, "onServiceConnected bind service success -> updateTile");
				updateTile(getServiceStatus());
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			Log.d(TAG, "onServiceDisconnected");
			if(webCallServiceBinder!=null) {
				Log.d(TAG,"onServiceDisconnected unbindService");
				webCallServiceBinder = null;
				//if(serviceConnection!=null) {
				//	unbindService(serviceConnection);
				//}
				updateTile(false);
			}
		}
	};
}

