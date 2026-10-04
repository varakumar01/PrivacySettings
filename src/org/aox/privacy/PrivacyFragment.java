package org.aox.privacy;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.preference.Preference;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;

import java.util.concurrent.Executors;

public class PrivacyFragment extends SettingsBasePreferenceFragment {
    private Preference mDuress;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.privacy, rootKey);

        mDuress = findPreference("duress");
        mDuress.setOnPreferenceClickListener(p -> {
            showDuressDialog();
            return true;
        });
    }

    private void showDuressDialog() {
        final var ctx = requireContext();
        final int userId = UserHandle.myUserId();
        final LockPatternUtils lpu = new LockPatternUtils(ctx);
        final int type = lpu.getCredentialTypeForUser(userId);
        final boolean pin = type == LockPatternUtils.CREDENTIAL_TYPE_PIN;
        if (!pin && type != LockPatternUtils.CREDENTIAL_TYPE_PASSWORD) {
            Toast.makeText(ctx, R.string.duress_needs_pin, Toast.LENGTH_LONG).show();
            return;
        }
        final int input = (pin ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT)
                | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        final EditText cur = field(ctx, R.string.duress_current, input);
        final EditText neu = field(ctx, R.string.duress_new, input);
        final EditText again = field(ctx, R.string.duress_again, input);
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (20 * ctx.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(cur);
        box.addView(neu);
        box.addView(again);

        final boolean isSet = Duress.isSet(userId);
        final AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setTitle(R.string.duress_title)
                .setMessage(R.string.duress_message)
                .setView(box)
                .setPositiveButton(R.string.duress_save, null)
                .setNegativeButton(android.R.string.cancel, null);
        if (isSet) {
            b.setNeutralButton(R.string.duress_remove, null);
        }
        final AlertDialog d = b.create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final String c = cur.getText().toString();
                final String n = neu.getText().toString();
                if (n.length() < 4 || !n.equals(again.getText().toString())) {
                    toast(R.string.duress_mismatch);
                } else if (n.equals(c)) {
                    toast(R.string.duress_same);
                } else {
                    verifyThen(lpu, pin, c, userId, d, () -> Duress.set(n, userId));
                }
            });
            if (isSet) {
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                        verifyThen(lpu, pin, cur.getText().toString(), userId, d,
                                () -> Duress.clear(userId)));
            }
        });
        d.show();
    }

    private interface Action { void run() throws RemoteException; }

    /** Checks the current lock credential off the main thread, then runs the action. */
    private void verifyThen(LockPatternUtils lpu, boolean pin, String current, int userId,
            AlertDialog d, Action action) {
        Executors.newSingleThreadExecutor().execute(() -> {
            int result;  // R.string id to toast, or 0 on success
            try (LockscreenCredential cred = pin ? LockscreenCredential.createPin(current)
                    : LockscreenCredential.createPassword(current)) {
                if (lpu.checkCredential(cred, userId, null)) {
                    action.run();
                    result = 0;
                } else {
                    result = R.string.duress_wrong;
                }
            } catch (LockPatternUtils.RequestThrottledException | RemoteException e) {
                result = R.string.duress_wrong;
            }
            final int r = result;
            requireActivity().runOnUiThread(() -> {
                if (r == 0) {
                    d.dismiss();
                    updateDuressSummary();
                } else {
                    toast(r);
                }
            });
        });
    }

    private EditText field(android.content.Context ctx, int hint, int inputType) {
        final EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setInputType(inputType);
        return e;
    }

    private void toast(int res) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show();
    }

    private void updateDuressSummary() {
        mDuress.setSummary(Duress.isSet(UserHandle.myUserId())
                ? R.string.duress_summary_set : R.string.duress_summary_unset);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDuressSummary();
    }
}
