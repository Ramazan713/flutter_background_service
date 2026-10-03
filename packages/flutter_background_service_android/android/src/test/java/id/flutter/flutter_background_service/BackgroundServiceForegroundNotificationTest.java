package id.flutter.flutter_background_service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = Build.VERSION_CODES.Q)
public class BackgroundServiceForegroundNotificationTest {
    private BackgroundService service;
    private Config config;
    private NotificationManager notificationManager;
    private Notification runtimeNotification;

    @Before
    public void setUp() throws Exception {
        service = Robolectric.buildService(BackgroundService.class).get();
        config = new Config(service);
        config.setIsForeground(true);
        notificationManager = service.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(new NotificationChannel("audio", "Audio", NotificationManager.IMPORTANCE_LOW));
        }
        setField("config", config);
        setField("notificationId", 777);
        setField("notificationTitle", "Servis baslatiliyor");
        setField("notificationContent", "Preparing");
        setField("configForegroundTypes", "mediaPlayback");

        ResolveInfo launcher = new ResolveInfo();
        launcher.activityInfo = new ActivityInfo();
        launcher.activityInfo.packageName = service.getPackageName();
        launcher.activityInfo.name = service.getPackageName() + ".MainActivity";
        Intent launchIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(service.getPackageName());
        shadowOf(service.getPackageManager()).addResolveInfoForIntent(launchIntent, launcher);

        PendingIntent action = PendingIntent.getBroadcast(service, 7, new Intent("audio.pause"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(service, "audio") : new Notification.Builder(service);
        runtimeNotification = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Alafasy - Fatiha 1")
                .setContentText("Playing")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Pause", action).build())
                .build();
    }

    @Test
    public void FGS_NOTIF_001_typeUpdateReusesActiveRuntimeNotification() throws Exception {
        notificationManager.notify(777, runtimeNotification);
        Notification active = notificationManager.getActiveNotifications()[0].getNotification();

        updateTypes("mediaPlayback", "dataSync");

        assertSame(active, shadowOf(service).getLastForegroundNotification());
        assertEquals("Alafasy - Fatiha 1", shadowOf(service).getLastForegroundNotification().extras.getString(Notification.EXTRA_TITLE));
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.getForegroundServiceType());
        assertEquals("mediaPlayback,dataSync", config.getForegroundServiceTypes());
    }

    @Test
    public void FGS_NOTIF_002_missingForegroundIdUsesStartupFallback() throws Exception {
        notificationManager.notify(778, runtimeNotification);

        updateTypes("dataSync");

        assertEquals(777, shadowOf(service).getLastForegroundNotificationId());
        assertEquals("Servis baslatiliyor", shadowOf(service).getLastForegroundNotification().extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Preparing", shadowOf(service).getLastForegroundNotification().extras.getString(Notification.EXTRA_TEXT));
        assertEquals("FOREGROUND_DEFAULT", shadowOf(service).getLastForegroundNotification().getChannelId());
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.getForegroundServiceType());
    }

    @Test
    public void FGS_NOTIF_003_typeUpdatePreservesNotificationContract() throws Exception {
        notificationManager.notify(777, runtimeNotification);
        notificationManager.notify(779, new Notification.Builder(service, "audio").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Download").build());
        Notification active = notificationManager.getActiveNotifications()[0].getNotification();

        updateTypes("mediaPlayback", "dataSync");

        Notification foreground = shadowOf(service).getLastForegroundNotification();
        assertEquals(777, shadowOf(service).getLastForegroundNotificationId());
        assertEquals("audio", foreground.getChannelId());
        assertEquals("Playing", foreground.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(active.flags, foreground.flags);
        assertSame(active.actions, foreground.actions);
        assertSame(active.actions[0].actionIntent, foreground.actions[0].actionIntent);
        assertEquals(2, notificationManager.getActiveNotifications().length);
        assertEquals("Download", shadowOf(notificationManager).getNotification(null, 779).extras.getString(Notification.EXTRA_TITLE));
    }

    @Test
    public void FGS_NOTIF_004_combinedTypesAndPlaybackAgainPreserveRuntimeNotification() throws Exception {
        notificationManager.notify(777, runtimeNotification);

        updateTypes("mediaPlayback");
        updateTypes("mediaPlayback", "dataSync");
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.getForegroundServiceType());
        Notification active = notificationManager.getActiveNotifications()[0].getNotification();
        updateTypes("mediaPlayback");

        assertSame(active, shadowOf(service).getLastForegroundNotification());
        assertEquals("Alafasy - Fatiha 1", shadowOf(service).getLastForegroundNotification().extras.getString(Notification.EXTRA_TITLE));
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.getForegroundServiceType());
    }

    @Test
    @org.robolectric.annotation.Config(sdk = Build.VERSION_CODES.M)
    public void marshmallowReusesRuntimeNotification() throws Exception {
        notificationManager.notify(777, runtimeNotification);
        Notification active = notificationManager.getActiveNotifications()[0].getNotification();

        updateTypes("mediaPlayback");

        assertSame(active, shadowOf(service).getLastForegroundNotification());
        assertEquals("Alafasy - Fatiha 1", shadowOf(service).getLastForegroundNotification().extras.getString(Notification.EXTRA_TITLE));
    }

    private void updateTypes(String... types) throws Exception {
        JSONObject arguments = new JSONObject().put("types", new JSONArray(types));
        service.onMethodCall(new MethodCall("setForegroundServiceTypes", arguments), new MethodChannel.Result() {
            @Override public void success(Object result) { assertEquals(true, result); }
            @Override public void error(String code, String message, Object details) { throw new AssertionError(code); }
            @Override public void notImplemented() { throw new AssertionError("setForegroundServiceTypes not implemented"); }
        });
    }

    private void setField(String name, Object value) throws Exception {
        Field field = BackgroundService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
