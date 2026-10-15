package org.schabi.newpipe.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.preference.Preference;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.util.ThemeHelper;

public class AppearanceSettingsFragment extends BasePreferenceFragment {

    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        final String themeKey = getString(R.string.theme_key);
        // the key of the active theme when settings were opened (or recreated after theme change)
        final String startThemeKey = defaultPreferences
                .getString(themeKey, getString(R.string.default_theme_value));
        final String autoDeviceThemeKey = getString(R.string.auto_device_theme_key);
        findPreference(themeKey).setOnPreferenceChangeListener((preference, newValue) -> {
            if (newValue.toString().equals(autoDeviceThemeKey)) {
                Toast.makeText(getContext(), getString(R.string.select_night_theme_toast),
                        Toast.LENGTH_LONG).show();
            }

            applyThemeChange(startThemeKey, themeKey, newValue);
            return false;
        });

        final String[] recreateKeys = {
                getString(R.string.disable_animations_key),
                getString(R.string.use_poppins_font_key)
        };
        for (final String key : recreateKeys) {
            final Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceChangeListener((preference, newValue) -> {
                    // the new value is stored once this returns true; recreate afterwards
                    defaultPreferences.edit().putBoolean(Constants.KEY_THEME_CHANGE, true).apply();
                    final androidx.fragment.app.FragmentActivity activity = getActivity();
                    if (activity != null) {
                        activity.getWindow().getDecorView().post(
                                () -> ActivityCompat.recreate(activity));
                    }
                    return true;
                });
            }
        }

        final String nightThemeKey = getString(R.string.night_theme_key);
        if (startThemeKey.equals(autoDeviceThemeKey)) {
            final String startNightThemeKey = defaultPreferences
                    .getString(nightThemeKey, getString(R.string.default_night_theme_value));

            findPreference(nightThemeKey).setOnPreferenceChangeListener((preference, newValue) -> {
                applyThemeChange(startNightThemeKey, nightThemeKey, newValue);
                return false;
            });
        } else {
            // disable the night theme selection
            final Preference preference = findPreference(nightThemeKey);
            if (preference != null) {
                preference.setEnabled(false);
                preference.setSummary(getString(R.string.night_theme_available,
                        getString(R.string.auto_device_theme_title)));
            }
        }
    }

    @Override
    public boolean onPreferenceTreeClick(final Preference preference) {
        if (getString(R.string.caption_settings_key).equals(preference.getKey())) {
            try {
                startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
            } catch (final ActivityNotFoundException e) {
                Toast.makeText(getActivity(), R.string.general_error, Toast.LENGTH_SHORT).show();
            }
        }

        return super.onPreferenceTreeClick(preference);
    }

    private void applyThemeChange(final String beginningThemeKey,
                                  final String themeKey,
                                  final Object newValue) {
        defaultPreferences.edit().putBoolean(Constants.KEY_THEME_CHANGE, true).apply();
        defaultPreferences.edit().putString(themeKey, newValue.toString()).apply();

        final int nightModeBefore = AppCompatDelegate.getDefaultNightMode();
        ThemeHelper.setDayNightMode(requireContext(), newValue.toString());

        // AppCompat already recreates every activity by itself when the night mode really changes
        // (light <-> dark). Recreating again here made the activities (and the video player
        // attached to them) restart 2-3 times in a row, which is what made playback stutter.
        // Only recreate manually when AppCompat did not (e.g. dark <-> black).
        if (!newValue.equals(beginningThemeKey) && getActivity() != null
                && nightModeBefore == AppCompatDelegate.getDefaultNightMode()) {
            ActivityCompat.recreate(getActivity());
        }
    }
}
