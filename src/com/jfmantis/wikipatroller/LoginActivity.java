package com.jfmantis.wikipatroller;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

public class LoginActivity extends Activity {
	EditText wikiDomain, usernameInput, passwordInput;
	Button loginButton;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.login_layout);

		// fill drop-down suggestion list with all Wikimedia project domains
		ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.wiki_list,
				android.R.layout.simple_dropdown_item_1line);
		AutoCompleteTextView wikiChooser = (AutoCompleteTextView) findViewById(R.id.wikiChooser);
		wikiChooser.setAdapter(adapter);

		wikiDomain = wikiChooser;
		usernameInput = (EditText) findViewById(R.id.usernameInput);
		passwordInput = (EditText) findViewById(R.id.passwordInput);
		loginButton = (Button) findViewById(R.id.loginButton);

		// attempt to login when enter is pressed in the password field
		passwordInput.setOnEditorActionListener(new OnEditorActionListener() {
			@Override
			public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
				if (actionId == EditorInfo.IME_ACTION_DONE) {
					loginButtonClicked(view);
				}
				return false;
			}
		});

	}

	@Override
	public void onStart() {
		super.onStart();
		usernameInput.requestFocus();
	}

	public void loginButtonClicked(View view) {
		String domain = wikiDomain.getText().toString();
		String username = usernameInput.getText().toString();
		String password = passwordInput.getText().toString();

		if (domain.length() == 0) {
			Toast.makeText(this, R.string.noDomain, Toast.LENGTH_LONG).show();
			return;
		}

		if (username.length() == 0) {
			Toast.makeText(this, R.string.noUsername, Toast.LENGTH_LONG).show();
			return;
		}

		if (password.length() == 0) {
			Toast.makeText(this, R.string.noPassword, Toast.LENGTH_LONG).show();
			return;
		}

		new LoginTask().execute(domain, username, password);
	}

	private class LoginTask extends AsyncTask<String, Void, Wiki.LoginError> {
		Wiki wiki;

		protected void onPreExecute() {
			wikiDomain.setEnabled(false);
			usernameInput.setEnabled(false);
			passwordInput.setEnabled(false);
			loginButton.setEnabled(false);
		}

		protected Wiki.LoginError doInBackground(String... args) {
			wiki = new Wiki(args[0]);

			try {
				return wiki.login(args[1], args[2]);
			} catch (Exception e) {
				return Wiki.LoginError.IOERROR;
			}
		}

		protected void onPostExecute(Wiki.LoginError error) {
			if (error == null) {
				Intent intent = new Intent(getApplicationContext(), PatrolActivity.class);
				intent.putExtra("Wiki", wiki);
				startActivity(intent);
			} else {
				int stringid;

				switch (error) {
				case BLOCKED:
					stringid = R.string.blocked;
					break;
				case INVALID:
					stringid = R.string.invalid;
					break;
				case MISSING:
					stringid = R.string.missing;
					break;
				case ROLLBACK:
					stringid = R.string.rollback;
					break;
				case THROTTLED:
					stringid = R.string.throttled;
					break;
				case WRONGPASS:
					stringid = R.string.wrongpass;
					break;
				case IOERROR:
					stringid = R.string.ioError;
					break;
				case UNKNOWN:
				default:
					stringid = R.string.unknownLogin;
					break;
				}

				Toast.makeText(getApplicationContext(), stringid, Toast.LENGTH_LONG).show();
			}

			wikiDomain.setEnabled(true);
			usernameInput.setEnabled(true);
			passwordInput.setEnabled(true);
			loginButton.setEnabled(true);

			usernameInput.setText("");
			passwordInput.setText("");
			usernameInput.requestFocus();
		}
	}
}