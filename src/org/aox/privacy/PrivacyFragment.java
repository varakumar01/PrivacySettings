package org.aox.privacy;

import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import lineageos.providers.LineageSettings;

public class PrivacyFragment extends SettingsBasePreferenceFragment {
    private SwitchPreferenceCompat mScramble;
    private SwitchPreferenceCompat mTetherVpn;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.privacy, rootKey);

        // These two mirror system settings owned by other components, so they are
        // read back from the provider on every resume instead of persisted here.
        mScramble = findPreference("scramble_pin");
        mScramble.setOnPreferenceChangeListener((p, v) -> {
            LineageSettings.System.putInt(requireContext().getContentResolver(),
                    LineageSettings.System.LOCKSCREEN_PIN_SCRAMBLE_LAYOUT, (Boolean) v ? 1 : 0);
            return true;
        });

        mTetherVpn = findPreference("tether_vpn");
        mTetherVpn.setOnPreferenceChangeListener((p, v) -> {
            Settings.Secure.putInt(requireContext().getContentResolver(),
                    Settings.Secure.TETHERING_ALLOW_VPN_UPSTREAMS, (Boolean) v ? 1 : 0);
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        final var cr = requireContext().getContentResolver();
        mScramble.setChecked(LineageSettings.System.getInt(cr,
                LineageSettings.System.LOCKSCREEN_PIN_SCRAMBLE_LAYOUT, 0) != 0);
        mTetherVpn.setChecked(Settings.Secure.getInt(cr,
                Settings.Secure.TETHERING_ALLOW_VPN_UPSTREAMS, 0) != 0);
    }
}
