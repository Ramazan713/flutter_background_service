package id.flutter.flutter_background_service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.content.Intent;
import android.os.Build;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = Build.VERSION_CODES.P)
public class BackgroundServiceMainIsolateStopTest {
    private Application context;
    private Config config;
    private AlarmManager alarmManager;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        config = new Config(context);
        alarmManager = (AlarmManager) context.getSystemService(Application.ALARM_SERVICE);
        config.setManuallyStopped(false);
        WatchdogReceiver.remove(context);
    }

    @After
    public void tearDown() {
        WatchdogReceiver.remove(context);
        config.setManuallyStopped(false);
    }

    @Test
    public void mainIsolateStopMarksManualStateRemovesWatchdogAndStopsService() {
        WatchdogReceiver.enqueue(context);
        assertFalse(shadowOf(alarmManager).getScheduledAlarms().isEmpty());

        FlutterBackgroundServicePlugin.stopService(context, config);

        assertTrue(config.isManuallyStopped());
        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isEmpty());
        Intent stoppedService = shadowOf(context).getNextStoppedService();
        assertNotNull(stoppedService);
        assertEquals(BackgroundService.class.getName(), stoppedService.getComponent().getClassName());
    }

    @Test
    public void onDestroyDoesNotRequeueWatchdogAfterExternalManualStop() throws Exception {
        BackgroundService service = Robolectric.buildService(BackgroundService.class).get();
        Config serviceConfig = new Config(service);
        serviceConfig.setManuallyStopped(true);
        Field configField = BackgroundService.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(service, serviceConfig);

        service.onDestroy();

        assertTrue(shadowOf(alarmManager).getScheduledAlarms().isEmpty());
    }

    @Test
    public void normalServiceStartClearsManualStopState() {
        config.setManuallyStopped(true);

        BackgroundService.prepareServiceStart(context, config);

        assertFalse(config.isManuallyStopped());
    }
}
