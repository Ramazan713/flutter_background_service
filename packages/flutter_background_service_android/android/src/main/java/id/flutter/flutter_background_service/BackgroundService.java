package id.flutter.flutter_background_service;

import static android.os.Build.VERSION.SDK_INT;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class BackgroundService extends Service implements MethodChannel.MethodCallHandler {
    private static final String TAG = "BackgroundService";
    private static final String EVENT_DATA_SYNC_TIMEOUT = "BackgroundServiceDataSyncTimeout";
    private static final String DEFAULT_NOTIFICATION_CHANNEL_ID = "FOREGROUND_DEFAULT";
    static final String FALLBACK_NOTIFICATION_CHANNEL_ID = "FOREGROUND_FALLBACK";
    private static final String LOCK_NAME = BackgroundService.class.getName()
            + ".Lock";
    public static volatile WakeLock lockStatic = null; // notice static
    AtomicBoolean isRunning = new AtomicBoolean(false);
    private FlutterEngine backgroundEngine;
    private MethodChannel methodChannel;
    private Config config;
    private DartExecutor.DartEntrypoint dartEntrypoint;
    private boolean isManuallyStopped = false;
    private String notificationTitle;
    private String notificationContent;
    private String configuredNotificationChannelId;
    private String notificationChannelId;
    private int notificationId;
    private String configForegroundTypes;
    private String[] foregroundTypes;
    private Handler mainHandler;
    private boolean foregroundStartBlocked;
    private boolean dataSyncUnavailable;
    private boolean commandChannelReady;
    private boolean dataSyncTimeoutDispatched;
    private boolean dataSyncStopAcknowledged;
    private final Runnable dataSyncStopDeadline = () -> {
        if (!dataSyncStopAcknowledged) {
            Log.w(TAG, "dataSync stop was not acknowledged; stopping service");
            stopServiceManually();
        }
    };

    enum ForegroundStartResult { STARTED, MEDIA_PLAYBACK_ONLY, STOPPED }

    synchronized public static PowerManager.WakeLock getLock(Context context) {
        if (lockStatic == null) {
            PowerManager mgr = (PowerManager) context
                    .getSystemService(Context.POWER_SERVICE);
            lockStatic = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    LOCK_NAME);
            lockStatic.setReferenceCounted(true);
        }

        return lockStatic;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        FlutterBackgroundServicePlugin.servicePipe.addListener(listener);

        config = new Config(this);
        mainHandler = new Handler(Looper.getMainLooper());

        configuredNotificationChannelId = config.getNotificationChannelId();
        notificationChannelId = resolveNotificationChannelId(configuredNotificationChannelId);

        notificationTitle = config.getInitialNotificationTitle();
        notificationContent = config.getInitialNotificationContent();
        notificationId = config.getForegroundNotificationId();
        configForegroundTypes = config.getForegroundServiceTypes();
        if (updateNotificationInfo() == ForegroundStartResult.STOPPED) return;
        onStartCommand(null, -1, -1);
    }

    @Override
    public void onDestroy() {
        if (mainHandler != null) mainHandler.removeCallbacks(dataSyncStopDeadline);
        if (!isManuallyStopped && !config.isManuallyStopped()) {
            WatchdogReceiver.enqueue(this);
        } else {
            config.setManuallyStopped(true);
        }
        stopForeground(true);
        isRunning.set(false);

        if (backgroundEngine != null) {
            backgroundEngine.getServiceControlSurface().detachFromService();
            backgroundEngine.destroy();
            backgroundEngine = null;
        }

        FlutterBackgroundServicePlugin.servicePipe.removeListener(listener);
        methodChannel = null;
        dartEntrypoint = null;
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        super.onTimeout(startId, fgsType);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || fgsType != ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) {
            return;
        }
        if ((getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0) {
            if (setForegroundServiceTypes("mediaPlayback") != ForegroundStartResult.STOPPED) {
                markDataSyncUnavailable();
            }
        } else {
            stopServiceManually();
        }
    }

    private final Pipe.PipeListener listener = new Pipe.PipeListener() {
        @Override
        public void onReceived(JSONObject object) {
            receiveData(object);
        }
    };

    String resolveNotificationChannelId(String configuredChannelId) {
        if (SDK_INT < Build.VERSION_CODES.O) {
            return configuredChannelId == null ? DEFAULT_NOTIFICATION_CHANNEL_ID : configuredChannelId;
        }

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (configuredChannelId != null && notificationManager.getNotificationChannel(configuredChannelId) != null) {
            return configuredChannelId;
        }

        String fallbackChannelId = configuredChannelId == null
                ? DEFAULT_NOTIFICATION_CHANNEL_ID
                : FALLBACK_NOTIFICATION_CHANNEL_ID;
        if (notificationManager.getNotificationChannel(fallbackChannelId) == null) {
            NotificationChannel channel = new NotificationChannel(
                    fallbackChannelId,
                    "Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Executing process in background");
            channel.setSound(null, null);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);
        }
        return fallbackChannelId;
    }

    protected ForegroundStartResult updateNotificationInfo() {
        if (foregroundStartBlocked) return ForegroundStartResult.STOPPED;
        if (config.isForeground()) {
            notificationChannelId = resolveNotificationChannelId(configuredNotificationChannelId);
            String packageName = getApplicationContext().getPackageName();
            Intent i = getPackageManager().getLaunchIntentForPackage(packageName);

            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }

            PendingIntent pi = PendingIntent.getActivity(BackgroundService.this, 11, i, flags);
            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, notificationChannelId)
                    .setSmallIcon(R.drawable.ic_bg_service_small)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationContent)
                    .setContentIntent(pi);

            return startForegroundWithNotification(mBuilder.build());
        }
        return ForegroundStartResult.STARTED;
    }

    private ForegroundStartResult startForegroundWithNotification(Notification notification) {
        if (foregroundStartBlocked) return ForegroundStartResult.STOPPED;
        foregroundTypes = null;
        if (configForegroundTypes != null && !configForegroundTypes.isEmpty()) {
            foregroundTypes = configForegroundTypes.split(",");
        }
        int serviceType = ForegroundTypeMapper.getForegroundServiceType(foregroundTypes);
        if (dataSyncUnavailable && (serviceType & ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) != 0) {
            serviceType &= ~ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (serviceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) {
                stopServiceManually();
                return ForegroundStartResult.STOPPED;
            }
        }
        try {
            promoteToForeground(notification, serviceType);
            if (dataSyncUnavailable && serviceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) {
                configForegroundTypes = "mediaPlayback";
                config.setForegroundServiceTypes(configForegroundTypes);
                return ForegroundStartResult.MEDIA_PLAYBACK_ONLY;
            }
            return ForegroundStartResult.STARTED;
        } catch (SecurityException e) {
            Log.w(TAG, "Failed to start foreground service due to SecurityException - have you forgotten to request a permission? - " + e.getMessage());
        } catch (RuntimeException e) {
            // The platform exception does not exist before Android 12. Preserve unrelated failures.
            if (SDK_INT < Build.VERSION_CODES.S || !(e instanceof ForegroundServiceStartNotAllowedException)) throw e;
            boolean playbackAndSync = serviceType == (ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            boolean quotaExhausted = SDK_INT >= 35
                    && (serviceType & ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) != 0
                    && e.getMessage() != null
                    && (e.getMessage().equals("Time limit already exhausted for foreground service type dataSync")
                    // AOSP labels a combined bitmask "unknown"; of these two types only dataSync is time-limited.
                    || (playbackAndSync && e.getMessage().equals("Time limit already exhausted for foreground service type unknown")));
            if (quotaExhausted && playbackAndSync) {
                try {
                    promoteToForeground(notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    configForegroundTypes = "mediaPlayback";
                    config.setForegroundServiceTypes(configForegroundTypes);
                    Log.w(TAG, "dataSync unavailable; continuing with mediaPlayback only");
                    markDataSyncUnavailable();
                    return ForegroundStartResult.MEDIA_PLAYBACK_ONLY;
                } catch (RuntimeException fallbackError) {
                    if (!(fallbackError instanceof ForegroundServiceStartNotAllowedException) && !(fallbackError instanceof SecurityException)) throw fallbackError;
                    Log.w(TAG, "mediaPlayback fallback rejected; stopping service: " + fallbackError.getMessage());
                }
            } else {
                Log.w(TAG, (quotaExhausted ? "dataSync quota exhausted" : "Foreground start rejected (quota not confirmed)") + "; stopping service: " + e.getMessage());
            }
        }
        stopServiceManually();
        return ForegroundStartResult.STOPPED;
    }

    void promoteToForeground(Notification notification, int serviceType) {
        ServiceCompat.startForeground(this, notificationId, notification, serviceType);
    }

    private void markDataSyncUnavailable() {
        if (dataSyncUnavailable) return;
        dataSyncUnavailable = true;
        // This is a failure deadline, not a delivery delay. Listener readiness triggers delivery.
        mainHandler.postDelayed(dataSyncStopDeadline, 10000);
        dispatchDataSyncTimeout();
    }

    private void dispatchDataSyncTimeout() {
        if (!dataSyncUnavailable || dataSyncTimeoutDispatched || !commandChannelReady || methodChannel == null || foregroundStartBlocked) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("method", EVENT_DATA_SYNC_TIMEOUT);
            payload.put("args", JSONObject.NULL);
            dataSyncTimeoutDispatched = true;
            methodChannel.invokeMethod("onReceiveData", payload);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to dispatch dataSync timeout", e);
            stopServiceManually();
        }
    }

    private ForegroundStartResult updateForegroundServiceTypes() {
        if (foregroundStartBlocked) return ForegroundStartResult.STOPPED;
        if (!config.isForeground()) return ForegroundStartResult.STARTED;
        if (SDK_INT >= Build.VERSION_CODES.M) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            for (StatusBarNotification activeNotification : notificationManager.getActiveNotifications()) {
                if (activeNotification.getId() == notificationId && activeNotification.getTag() == null) {
                    return startForegroundWithNotification(activeNotification.getNotification());
                }
            }
        }
        return updateNotificationInfo();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (foregroundStartBlocked) return START_NOT_STICKY;
        prepareServiceStart(this, config);
        runService();

        return START_NOT_STICKY;
    }

    static void prepareServiceStart(Context context, Config config) {
        config.setManuallyStopped(false);
        WatchdogReceiver.enqueue(context);
    }

    @SuppressLint("WakelockTimeout")
    private void runService() {
        if (foregroundStartBlocked) return;
        try {
            if (isRunning.get() || (backgroundEngine != null && !backgroundEngine.getDartExecutor().isExecutingDart())) {
                Log.v(TAG, "Service already running, using existing service");
                return;
            }

            Log.v(TAG, "Starting flutter engine for background service");

            if (updateNotificationInfo() == ForegroundStartResult.STOPPED) return;
            getLock(getApplicationContext()).acquire();

            FlutterLoader flutterLoader = FlutterInjector.instance().flutterLoader();
            // initialize flutter if it's not initialized yet
            if (!flutterLoader.initialized()) {
                flutterLoader.startInitialization(getApplicationContext());
            }

            flutterLoader.ensureInitializationComplete(getApplicationContext(), null);

            isRunning.set(true);
            backgroundEngine = new FlutterEngine(this);

            // remove FlutterBackgroundServicePlugin (because its only for UI)
            backgroundEngine.getPlugins().remove(FlutterBackgroundServicePlugin.class);

            backgroundEngine.getServiceControlSurface().attachToService(BackgroundService.this, null, config.isForeground());

            methodChannel = new MethodChannel(backgroundEngine.getDartExecutor().getBinaryMessenger(), "id.flutter/background_service_android_bg", JSONMethodCodec.INSTANCE);
            methodChannel.setMethodCallHandler(this);

            dartEntrypoint = new DartExecutor.DartEntrypoint(flutterLoader.findAppBundlePath(), "package:flutter_background_service_android/flutter_background_service_android.dart", "entrypoint");

            final List<String> args = new ArrayList<>();
            long backgroundHandle = config.getBackgroundHandle();
            args.add(String.valueOf(backgroundHandle));


            backgroundEngine.getDartExecutor().executeDartEntrypoint(dartEntrypoint, args);

        } catch (UnsatisfiedLinkError e) {
            notificationContent = "Error " + e.getMessage();
            updateNotificationInfo();

            Log.w(TAG, "UnsatisfiedLinkError: After a reboot this may happen for a short period and it is ok to ignore then!" + e.getMessage());
        }
    }

    public void receiveData(JSONObject data) {
        if (methodChannel == null) return;
        try {
            final JSONObject arg = data;
            mainHandler.post(() -> {
                if (methodChannel == null) return;
                methodChannel.invokeMethod("onReceiveData", arg);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (isRunning.get() && !config.isManuallyStopped()) {
            WatchdogReceiver.enqueue(getApplicationContext(), 1000);
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        String method = call.method;

        try {
            if (method.equalsIgnoreCase("setNotificationInfo")) {
                JSONObject arg = (JSONObject) call.arguments;
                if (arg.has("title")) {
                    notificationTitle = arg.getString("title");
                    notificationContent = arg.getString("content");
                    updateNotificationInfo();
                    result.success(true);
                }
                return;
            }

            if (method.equalsIgnoreCase("setAutoStartOnBootMode")) {
                JSONObject arg = (JSONObject) call.arguments;
                boolean value = arg.getBoolean("value");
                config.setAutoStartOnBoot(value);
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("setForegroundServiceTypes")) {
                JSONObject arg = (JSONObject) call.arguments;
                ForegroundStartResult startResult = setForegroundServiceTypes(FlutterBackgroundServicePlugin.joinForegroundServiceTypes(arg.getJSONArray("types")));
                result.success(startResult == ForegroundStartResult.STARTED);
                return;
            }

            if (method.equalsIgnoreCase("setForegroundMode")) {
                JSONObject arg = (JSONObject) call.arguments;
                boolean value = arg.getBoolean("value");
                config.setIsForeground(value);
                if (value) {
                    if (updateNotificationInfo() == ForegroundStartResult.STOPPED) {
                        result.success(false);
                        return;
                    }
                    backgroundEngine.getServiceControlSurface().onMoveToForeground();
                } else {
                    stopForeground(true);
                    backgroundEngine.getServiceControlSurface().onMoveToBackground();
                }

                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("isForegroundMode")) {
                boolean value = config.isForeground();
                result.success(value);
                return;
            }

            if (method.equalsIgnoreCase("stopService")) {
                stopServiceManually();
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("sendData")) {
                try {
                    JSONObject data = (JSONObject) call.arguments;
                    if ("background_ready".equals(data.optString("method"))) {
                        commandChannelReady = true;
                        dispatchDataSyncTimeout();
                    } else if ((EVENT_DATA_SYNC_TIMEOUT + "Acknowledged").equals(data.optString("method")) && dataSyncTimeoutDispatched) {
                        dataSyncStopAcknowledged = true;
                        mainHandler.removeCallbacks(dataSyncStopDeadline);
                    }
                    if (FlutterBackgroundServicePlugin.mainPipe.hasListener()){
                        FlutterBackgroundServicePlugin.mainPipe.invoke((JSONObject) call.arguments);
                    }

                    result.success(true);
                } catch (Exception e) {
                    result.error("send-data-failure", e.getMessage(), e);
                }
                return;
            }

            if(method.equalsIgnoreCase("openApp")){
                try{
                    String packageName=  getPackageName();
                    Intent launchIntent= getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);

                        startActivity(launchIntent);
                        result.success(true);

                    }
                }catch (Exception e){
                    result.error("open app failure", e.getMessage(),e);

                }
                return;

            }
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }

        result.notImplemented();
    }

    private ForegroundStartResult setForegroundServiceTypes(String value) {
        if (foregroundStartBlocked) return ForegroundStartResult.STOPPED;
        configForegroundTypes = value;
        config.setForegroundServiceTypes(value);
        return updateForegroundServiceTypes();
    }

    private void stopServiceManually() {
        foregroundStartBlocked = true;
        if (mainHandler != null) mainHandler.removeCallbacks(dataSyncStopDeadline);
        isManuallyStopped = true;
        config.setManuallyStopped(true);
        WatchdogReceiver.remove(this);
        stopSelf();
    }
}
