package org.aox.privacy;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Auto reboot while locked, and Wi-Fi / Bluetooth idle timeouts. Each timer is one
 * AlarmManager alarm; events only arm or disarm it, the alarm re-checks the real
 * state when it fires. Also rejects network access for newly installed apps while
 * the Datura "block new apps" switch is on.
 */
final class Controller extends BroadcastReceiver {
    private static final String ACT_REBOOT = "org.aox.privacy.REBOOT";
    private static final String ACT_WIFI = "org.aox.privacy.WIFI_OFF";
    private static final String ACT_BT = "org.aox.privacy.BT_OFF";
    // Settings.Global key, written by Datura's "Block new apps" switch.
    private static final String BLOCK_NEW_APPS = "aox_block_new_apps";

    private static final int[] BT_PROFILES = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET,
            BluetoothProfile.GATT, BluetoothProfile.HID_HOST};

    private final Context mCtx;
    private final AlarmManager mAlarms;
    private final SharedPreferences mPrefs;
    private final Set<Network> mWifiNets = new HashSet<>();
    private final Set<String> mAcl = new HashSet<>();
    // Strong ref: SharedPreferences only holds listeners weakly.
    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener =
            (sp, key) -> onPrefChanged(key);

    private boolean mRebootArmed;
    private boolean mWifiArmed;
    private boolean mBtArmed;

    Controller(Context ctx) {
        mCtx = ctx;
        mAlarms = ctx.getSystemService(AlarmManager.class);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    void start() {
        final IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(ACT_REBOOT);
        f.addAction(ACT_WIFI);
        f.addAction(ACT_BT);
        mCtx.registerReceiver(this, f, Context.RECEIVER_NOT_EXPORTED);

        final IntentFilter pkg = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pkg.addDataScheme("package");
        mCtx.registerReceiverForAllUsers(this, pkg, null, null, Context.RECEIVER_NOT_EXPORTED);

        mCtx.getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network n) { mWifiNets.add(n); evalWifi(); }
                    @Override public void onLost(Network n) { mWifiNets.remove(n); evalWifi(); }
                }, new Handler(Looper.getMainLooper()));
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefListener);
        evalWifi();
        evalBt();
    }

    @Override
    public void onReceive(Context c, Intent i) {
        final String a = i.getAction();
        switch (a) {
            case Intent.ACTION_SCREEN_OFF -> evalReboot(true);
            case Intent.ACTION_USER_PRESENT -> cancelReboot();
            case WifiManager.WIFI_STATE_CHANGED_ACTION -> evalWifi();
            case BluetoothAdapter.ACTION_STATE_CHANGED -> {
                if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) == BluetoothAdapter.STATE_OFF) {
                    mAcl.clear();
                }
                evalBt();
            }
            case BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                final BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice.class);
                if (d != null) {
                    if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) mAcl.add(d.getAddress());
                    else mAcl.remove(d.getAddress());
                }
                evalBt();
            }
            case Intent.ACTION_PACKAGE_ADDED -> blockNewApp(i);
            case ACT_REBOOT -> {
                mRebootArmed = false;
                if (minutes("auto_reboot") > 0
                        && mCtx.getSystemService(KeyguardManager.class).isDeviceLocked()) {
                    mCtx.getSystemService(PowerManager.class).reboot(null);
                }
            }
            case ACT_WIFI -> {
                mWifiArmed = false;
                final WifiManager wm = mCtx.getSystemService(WifiManager.class);
                if (minutes("wifi_timeout") > 0 && wm.isWifiEnabled() && mWifiNets.isEmpty()) {
                    wm.setWifiEnabled(false);
                }
            }
            case ACT_BT -> {
                mBtArmed = false;
                final BluetoothAdapter bt = adapter();
                if (minutes("bt_timeout") > 0 && bt != null && bt.isEnabled() && !btBusy(bt)) {
                    bt.disable();
                }
            }
        }
    }

    private void blockNewApp(Intent i) {
        if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                || Settings.Global.getInt(mCtx.getContentResolver(), BLOCK_NEW_APPS, 0) == 0) {
            return;
        }
        final int uid = i.getIntExtra(Intent.EXTRA_UID, -1);
        if (uid < 0 || i.getData() == null) return;
        try {
            final ApplicationInfo ai = mCtx.getPackageManager().getApplicationInfoAsUser(
                    i.getData().getSchemeSpecificPart(), 0, UserHandle.getUserId(uid));
            // System apps are also announced when a user is created; leave them alone.
            if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return;
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        NetworkPolicyManager.from(mCtx).addUidPolicy(uid, NetworkPolicyManager.POLICY_REJECT_ALL);
    }

    private void onPrefChanged(String key) {
        switch (key) {
            case "auto_reboot" -> { cancelReboot(); }
            case "wifi_timeout" -> { cancel(ACT_WIFI); mWifiArmed = false; evalWifi(); }
            case "bt_timeout" -> { cancel(ACT_BT); mBtArmed = false; evalBt(); }
        }
    }

    // --- auto reboot: armed on screen-off, disarmed on unlock; the alarm only
    // reboots if the device is still locked when it fires.
    private void evalReboot(boolean screenOff) {
        final int min = minutes("auto_reboot");
        if (min > 0 && screenOff && !mRebootArmed) {
            arm(ACT_REBOOT, min);
            mRebootArmed = true;
        }
    }

    private void cancelReboot() {
        cancel(ACT_REBOOT);
        mRebootArmed = false;
    }

    // --- Wi-Fi: armed while the radio is on with no Wi-Fi network up.
    private void evalWifi() {
        final int min = minutes("wifi_timeout");
        final boolean idle = min > 0 && mCtx.getSystemService(WifiManager.class).isWifiEnabled()
                && mWifiNets.isEmpty();
        if (idle && !mWifiArmed) {
            arm(ACT_WIFI, min);
            mWifiArmed = true;
        } else if (!idle && mWifiArmed) {
            cancel(ACT_WIFI);
            mWifiArmed = false;
        }
    }

    // --- Bluetooth: armed while the radio is on with nothing connected.
    private void evalBt() {
        final int min = minutes("bt_timeout");
        final BluetoothAdapter bt = adapter();
        final boolean idle = min > 0 && bt != null && bt.isEnabled() && !btBusy(bt);
        if (idle && !mBtArmed) {
            arm(ACT_BT, min);
            mBtArmed = true;
        } else if (!idle && mBtArmed) {
            cancel(ACT_BT);
            mBtArmed = false;
        }
    }

    private boolean btBusy(BluetoothAdapter bt) {
        if (!mAcl.isEmpty()) return true;
        for (int p : BT_PROFILES) {
            if (bt.getProfileConnectionState(p) == BluetoothProfile.STATE_CONNECTED) return true;
        }
        return false;
    }

    private BluetoothAdapter adapter() {
        return mCtx.getSystemService(BluetoothManager.class).getAdapter();
    }

    private int minutes(String key) {
        return Integer.parseInt(mPrefs.getString(key, "0"));
    }

    private PendingIntent pi(String action) {
        return PendingIntent.getBroadcast(mCtx, 0,
                new Intent(action).setPackage(mCtx.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void arm(String action, int minutes) {
        mAlarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + minutes * 60_000L, pi(action));
    }

    private void cancel(String action) {
        mAlarms.cancel(pi(action));
    }
}
