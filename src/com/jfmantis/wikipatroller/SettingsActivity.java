package com.jfmantis.wikipatroller;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.preference.PreferenceFragment;

public class SettingsActivity extends Activity {

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.settings_layout);

		String user = getIntent().getStringExtra("user");
		Fragment settingsFragment = new SettingsFragment();

		Bundle bundle = new Bundle();
		bundle.putString("user", user);
		settingsFragment.setArguments(bundle);

		getFragmentManager().beginTransaction().add(R.id.settings_layout, settingsFragment).commit();
	}

	public static class SettingsFragment extends PreferenceFragment {
		@Override
		public void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			String user = this.getArguments().getString("user");
			getPreferenceManager().setSharedPreferencesName(user);
			addPreferencesFromResource(R.xml.preferences);
		}
	}
}