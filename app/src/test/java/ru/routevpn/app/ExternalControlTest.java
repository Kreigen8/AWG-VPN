package ru.routevpn.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk = {29, 35})
public final class ExternalControlTest {
    private final Context context = RuntimeEnvironment.getApplication();
    private final ConnectionPreferences preferences = new ConnectionPreferences(context);
    private static Profile profile(String id, String name) {
        Profile value = new Profile(); value.id = id; value.name = name;
        value.config = "private-config-never-in-an-intent"; value.packages.add("org.example.bank"); return value;
    }
    private final class Controller extends ExternalControl {
        final List<Profile> saved = new ArrayList<>(Arrays.asList(profile("alpha-id", "Alpha"), profile("bravo-id", "Bravo")));
        final List<Intent> commands = new ArrayList<>(); boolean allowed = true, failDispatch, changeSetting; int loads;
        Controller() { super(context); }
        @Override protected List<Profile> profiles() {
            loads++;
            if (changeSetting) { preferences.setExternalControl(false); preferences.setExternalControl(true); }
            return saved;
        }
        @Override protected String selectedId() { return "alpha-id"; }
        @Override protected boolean permissionGranted() { return allowed; }
        @Override protected RouteVpnService.State state() { return new RouteVpnService.State("DISCONNECTED", "", "", 0, 0, 0); }
        @Override protected void dispatch(Intent intent, boolean connect) {
            if (failDispatch) throw new IllegalStateException("secret exception content must not be returned");
            commands.add(new Intent(intent));
        }
    }
    @Test public void disabledByDefaultAndNoProfilesAreRead() {
        Controller value = new Controller(); assertFalse(preferences.externalControl());
        assertEquals(ExternalControl.Result.DISABLED, value.handle(new Intent(ExternalControl.UP)));
        assertEquals(0, value.loads); assertTrue(value.commands.isEmpty());
        new ExternalControlReceiver().onReceive(context, new Intent(ExternalControl.DOWN));
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }
    @Test public void selectedProfileAndNamedProfileDispatchOnlyPrivateServiceWhitelist() {
        preferences.setExternalControl(true); Controller value = new Controller();
        assertEquals(ExternalControl.Result.ACCEPTED, value.handle(new Intent(ExternalControl.UP).putExtra("config", "malicious replacement")));
        Intent command = value.commands.get(0);
        assertEquals(new ComponentName(context, RouteVpnService.class), command.getComponent());
        assertEquals(RouteVpnService.ACTION_EXTERNAL_CONNECT, command.getAction());
        assertEquals("alpha-id", command.getStringExtra(RouteVpnService.EXTRA_PROFILE_ID));
        assertEquals(2, command.getExtras().size()); assertFalse(command.hasExtra("config"));
        assertEquals(preferences.externalControlGeneration(), command.getLongExtra(RouteVpnService.EXTRA_EXTERNAL_GENERATION, -1));
        assertEquals(ExternalControl.Result.ACCEPTED, value.handle(new Intent(ExternalControl.UP).putExtra("tunnel", "Bravo")));
        assertEquals("bravo-id", value.commands.get(1).getStringExtra(RouteVpnService.EXTRA_PROFILE_ID));
        assertEquals("private-config-never-in-an-intent", value.saved.get(0).config);
        assertTrue(value.saved.get(0).packages.contains("org.example.bank"));
    }
    @Test public void downWithoutSelectorDoesNotOpenEncryptedStoreAndNamedDownUsesId() {
        preferences.setExternalControl(true); Controller value = new Controller();
        assertEquals(ExternalControl.Result.ACCEPTED, value.handle(new Intent(ExternalControl.DOWN)));
        assertEquals(0, value.loads); assertEquals(RouteVpnService.ACTION_EXTERNAL_DISCONNECT, value.commands.get(0).getAction());
        assertFalse(value.commands.get(0).hasExtra("profile_id"));
        assertEquals(ExternalControl.Result.ACCEPTED, value.handle(new Intent(ExternalControl.DOWN).putExtra("profile_id", "bravo-id")));
        assertEquals("bravo-id", value.commands.get(1).getStringExtra("profile_id"));
    }
    @Test public void malformedAndAmbiguousSelectorsNeverChooseAnArbitraryProfile() {
        preferences.setExternalControl(true); Controller value = new Controller();
        for (Intent request : new Intent[] {new Intent("unexpected"), new Intent(ExternalControl.UP).putExtra("tunnel", ""),
                new Intent(ExternalControl.UP).putExtra("tunnel", "Alpha\n"),
                new Intent(ExternalControl.UP).putExtra("tunnel", 123),
                new Intent(ExternalControl.UP).putExtra("tunnel", "Alpha").putExtra("profile_id", "alpha-id")})
            assertEquals(ExternalControl.Result.BAD_REQUEST, value.handle(request));
        assertEquals(ExternalControl.Result.PROFILE_NOT_FOUND, value.handle(new Intent(ExternalControl.UP).putExtra("tunnel", "Absent")));
        value.saved.add(profile("duplicate-id", "Alpha"));
        assertEquals(ExternalControl.Result.AMBIGUOUS_PROFILE, value.handle(new Intent(ExternalControl.UP).putExtra("tunnel", "Alpha")));
        assertTrue(value.commands.isEmpty());
    }
    @Test public void vpnConsentAndGenerationAreRecheckedWithoutLaunchingAnActivity() {
        preferences.setExternalControl(true); Controller value = new Controller(); value.allowed = false;
        assertEquals(ExternalControl.Result.VPN_PERMISSION_REQUIRED, value.handle(new Intent(ExternalControl.UP)));
        assertEquals(0, value.loads); value.allowed = true; value.changeSetting = true;
        assertEquals(ExternalControl.Result.DISABLED, value.handle(new Intent(ExternalControl.UP)));
        assertTrue(value.commands.isEmpty()); assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }
    @Test public void receiverQueueDoesNotReviveOldCommandsAfterSettingChanges() {
        preferences.setExternalControl(true); long receivedGeneration = preferences.externalControlGeneration();
        preferences.setExternalControl(false); preferences.setExternalControl(true);
        Controller value = new Controller();
        assertEquals(ExternalControl.Result.DISABLED, value.handle(new Intent(ExternalControl.UP), receivedGeneration));
        assertEquals(0, value.loads); assertTrue(value.commands.isEmpty());
    }
    @Test public void restrictedStartDoesNotOverwriteExistingConnectionPreferences() {
        preferences.request("already-saved"); preferences.setAutoReconnect(false); preferences.setExternalControl(true);
        Controller value = new Controller(); value.failDispatch = true;
        assertEquals(ExternalControl.Result.UNAVAILABLE, value.handle(new Intent(ExternalControl.UP)));
        assertTrue(preferences.wanted()); assertEquals("already-saved", preferences.profileId()); assertFalse(preferences.autoReconnect());
    }
    @Test public void manifestRequiresUserGrantedPermissionAndKeepsNativeServicesPrivate() throws Exception {
        PackageManager pm = context.getPackageManager();
        PermissionInfo permission = pm.getPermissionInfo(ExternalControl.PERMISSION, 0);
        assertEquals(context.getPackageName(), permission.packageName);
        assertEquals(PermissionInfo.PROTECTION_DANGEROUS, permission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
        android.content.pm.ActivityInfo receiver = pm.getReceiverInfo(new ComponentName(context, ExternalControlReceiver.class), 0);
        assertTrue(receiver.exported); assertEquals(ExternalControl.PERMISSION, receiver.permission);
        assertFalse(pm.getServiceInfo(new ComponentName(context, RouteVpnService.class), 0).exported);
        assertFalse(pm.getReceiverInfo(new ComponentName(context, RestoreReceiver.class), 0).exported);
    }
}
