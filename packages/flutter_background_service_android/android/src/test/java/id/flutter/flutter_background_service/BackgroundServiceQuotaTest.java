package id.flutter.flutter_background_service;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.ServiceInfo;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.JSONMethodCodec;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public class BackgroundServiceQuotaTest {
    private RejectingService service;
    private Config config;
    private Notification notification;
    private final List<String> messages = new ArrayList<>();
    private static final String QUOTA = "Time limit already exhausted for foreground service type dataSync";

    public static class RejectingService extends BackgroundService {
        int rejectAt = 1;
        boolean rejectFallback;
        RuntimeException rejection = Build.VERSION.SDK_INT >= 31 ? new ForegroundServiceStartNotAllowedException(QUOTA) : new SecurityException("permission denied");
        final List<Integer> types = new ArrayList<>();
        final List<Notification> notifications = new ArrayList<>();

        @Override void promoteToForeground(Notification notification, int type) {
            types.add(type);
            notifications.add(notification);
            if (types.size() == rejectAt || rejectFallback) throw rejection;
        }
    }

    @Before public void setUp() throws Exception {
        service = Robolectric.buildService(RejectingService.class).get();
        config = new Config(service);
        config.setIsForeground(true);
        config.setManuallyStopped(false);
        setField("config", config);
        setField("mainHandler", new Handler(Looper.getMainLooper()));
        ResolveInfo launcher = new ResolveInfo();
        launcher.activityInfo = new ActivityInfo();
        launcher.activityInfo.packageName = service.getPackageName();
        launcher.activityInfo.name = service.getPackageName() + ".MainActivity";
        shadowOf(service.getPackageManager()).addResolveInfoForIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(service.getPackageName()), launcher);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(service, "audio") : new Notification.Builder(service);
        notification = builder.setSmallIcon(android.R.drawable.ic_media_play).build();
    }

    @Test public void dataSyncOnlyStopsAndLaterLifecycleDoesNotRestartOrCreateEngine() throws Exception {
        WatchdogReceiver.enqueue(service);
        assertEquals(BackgroundService.ForegroundStartResult.STOPPED, promote("dataSync"));
        assertTrue(config.isManuallyStopped());
        assertTrue(shadowOf(service).isStoppedBySelf());
        assertEquals(BackgroundService.START_NOT_STICKY, service.onStartCommand(null, 0, 1));
        assertEquals(1, service.types.size());
        assertNull(getField("backgroundEngine"));
        service.onDestroy();
        assertTrue(shadowOf(service.getSystemService(AlarmManager.class)).getScheduledAlarms().isEmpty());
        BackgroundService.prepareServiceStart(service, config);
        assertFalse(config.isManuallyStopped());
        RejectingService next = Robolectric.buildService(RejectingService.class).get();
        next.rejectAt = -1;
        service = next;
        setField("config", config);
        assertEquals(BackgroundService.ForegroundStartResult.STARTED, promote("mediaPlayback"));
    }

    @Test public void bothStartupPromotionPathsStopBeforeEngineStartup() {
        for (int rejectionPoint : new int[]{1, 2}) {
            config.setForegroundServiceTypes("dataSync");
            RejectingService starting = Robolectric.buildService(RejectingService.class).get();
            starting.rejectAt = rejectionPoint;
            starting.onCreate();
            assertTrue(shadowOf(starting).isStoppedBySelf());
            assertFalse(starting.isRunning.get());
            assertEquals(rejectionPoint, starting.types.size());
            starting.onStartCommand(null, 0, 2);
            assertEquals(rejectionPoint, starting.types.size());
            starting.onDestroy();
        }
    }

    @Test public void combinedFallbackPreservesNotificationAndPersistsPlayback() throws Exception {
        assertEquals(BackgroundService.ForegroundStartResult.MEDIA_PLAYBACK_ONLY, promote("mediaPlayback,dataSync"));
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, (int) service.types.get(1));
        assertSame(notification, service.notifications.get(0));
        assertSame(notification, service.notifications.get(1));
        assertEquals("mediaPlayback", config.getForegroundServiceTypes());
        assertEquals(true, getField("dataSyncUnavailable"));
        assertFalse(config.isManuallyStopped());
        // A second startup/type update must never retry the unavailable dataSync type.
        promote("mediaPlayback,dataSync");
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, (int) service.types.get(2));
    }

    @Test public void rejectedFallbackStopsInsteadOfReportingStarted() throws Exception {
        service.rejectFallback = true;
        assertEquals(BackgroundService.ForegroundStartResult.STOPPED, promote("mediaPlayback,dataSync"));
        assertTrue(config.isManuallyStopped());
        assertEquals(false, getField("dataSyncUnavailable"));
    }

    @Test public void combinedBitmaskQuotaMessageFromAndroid16AllowsPlaybackFallback() throws Exception {
        service.rejection = new ForegroundServiceStartNotAllowedException("Time limit already exhausted for foreground service type unknown");
        assertEquals(BackgroundService.ForegroundStartResult.MEDIA_PLAYBACK_ONLY, promote("mediaPlayback,dataSync"));
        assertEquals("mediaPlayback", config.getForegroundServiceTypes());
    }

    @Test public void unknownQuotaMessageWithoutExactCombinedTypesDoesNotAllowFallback() throws Exception {
        service.rejection = new ForegroundServiceStartNotAllowedException("Time limit already exhausted for foreground service type unknown");
        assertEquals(BackgroundService.ForegroundStartResult.STOPPED, promote("mediaPlayback"));
        assertEquals(1, service.types.size());
    }

    @Test public void runningTypeUpdateReportsDataSyncRejectedAndReusesActiveNotification() throws Exception {
        setField("notificationId", 777);
        NotificationManager manager = service.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("audio", "Audio", 2));
        manager.notify(777, notification);
        Notification active = manager.getActiveNotifications()[0].getNotification();
        JSONObject args = new JSONObject().put("types", new JSONArray(new String[]{"mediaPlayback", "dataSync"}));
        service.onMethodCall(new MethodCall("setForegroundServiceTypes", args), new MethodChannel.Result() {
            @Override public void success(Object value) { assertEquals(false, value); }
            @Override public void error(String code, String message, Object details) { fail(code); }
            @Override public void notImplemented() { fail("not implemented"); }
        });
        assertSame(active, service.notifications.get(0));
        assertSame(active, service.notifications.get(1));
        assertEquals("mediaPlayback", config.getForegroundServiceTypes());
    }

    @Test public void nonQuotaRejectionNeverUsesPlaybackFallback() throws Exception {
        service.rejection = new ForegroundServiceStartNotAllowedException("mAllowStartForeground false");
        assertEquals(BackgroundService.ForegroundStartResult.STOPPED, promote("mediaPlayback,dataSync"));
        assertEquals(1, service.types.size());
        assertEquals(false, getField("dataSyncUnavailable"));
    }

    @Test public void mediaPlaybackAndSecurityFailureHaveExplicitResults() throws Exception {
        service.rejectAt = -1;
        assertEquals(BackgroundService.ForegroundStartResult.STARTED, promote("mediaPlayback"));
        service.rejectAt = 2;
        service.rejection = new SecurityException("permission denied");
        assertEquals(BackgroundService.ForegroundStartResult.STOPPED, promote("dataSync"));
        assertTrue(config.isManuallyStopped());
    }

    @Test public void unrelatedRuntimeExceptionIsNotHidden() throws Exception {
        service.rejection = new IllegalArgumentException("programming error");
        try {
            promote("dataSync");
            fail("Expected original failure");
        } catch (InvocationTargetException error) {
            assertSame(service.rejection, error.getCause());
        }
    }

    @Test
    @org.robolectric.annotation.Config(sdk = 23)
    public void securityFailureBeforeAndroid12StopsWithoutReferencingUnavailableException() throws Exception {
        assertEquals(BackgroundService.ForegroundStartResult.STOPPED, promote("dataSync"));
        assertTrue(config.isManuallyStopped());
    }

    @Test public void pendingEventWaitsForReadinessAndRequiresCancellationAcknowledgement() throws Exception {
        promote("mediaPlayback,dataSync");
        attachChannel();
        assertTrue(messages.isEmpty());
        sendEvent("background_ready");
        sendEvent("background_ready");
        assertEquals(1, messages.size());
        assertEquals("BackgroundServiceDataSyncTimeout", messages.get(0));
        sendEvent("BackgroundServiceDataSyncTimeoutAcknowledged");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertFalse(config.isManuallyStopped());
    }

    @Test public void missingEventDeliveryOrAcknowledgementStopsService() throws Exception {
        promote("mediaPlayback,dataSync");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertTrue(config.isManuallyStopped());
    }

    @Test public void deliveredEventWithoutAcknowledgementStillStopsService() throws Exception {
        attachChannel();
        sendEvent("background_ready");
        promote("mediaPlayback,dataSync");
        assertEquals(1, messages.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertTrue(config.isManuallyStopped());
    }

    private void attachChannel() throws Exception {
        BinaryMessenger messenger = new BinaryMessenger() {
            @Override public void send(String channel, java.nio.ByteBuffer message) { send(channel, message, null); }
            @Override public void send(String channel, java.nio.ByteBuffer message, BinaryReply reply) {
                message.flip();
                MethodCall call = JSONMethodCodec.INSTANCE.decodeMethodCall(message);
                messages.add(((JSONObject) call.arguments).optString("method"));
            }
            @Override public void setMessageHandler(String channel, BinaryMessageHandler handler) {}
        };
        setField("methodChannel", new MethodChannel(messenger, "test", JSONMethodCodec.INSTANCE));
    }

    private void sendEvent(String event) throws Exception {
        service.onMethodCall(new MethodCall("sendData", new JSONObject().put("method", event)), new MethodChannel.Result() {
            @Override public void success(Object value) { assertEquals(true, value); }
            @Override public void error(String code, String message, Object details) { fail(code + ": " + message); }
            @Override public void notImplemented() { fail("not implemented"); }
        });
    }

    private BackgroundService.ForegroundStartResult promote(String types) throws Exception {
        setField("configForegroundTypes", types);
        Method method = BackgroundService.class.getDeclaredMethod("startForegroundWithNotification", Notification.class);
        method.setAccessible(true);
        return (BackgroundService.ForegroundStartResult) method.invoke(service, notification);
    }

    private Object getField(String name) throws Exception {
        Field field = BackgroundService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = BackgroundService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
