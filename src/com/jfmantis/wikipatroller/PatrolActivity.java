package com.jfmantis.wikipatroller;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class PatrolActivity extends Activity {
	TextView titleText, descriptionText, summaryText, diffText;
	Button nextButton, rvButton, rvVandalButton;

	Wiki wiki;
	Queue<Change> changeQueue;
	Set<Long> changesSeen;
	Change current;

	boolean juststarted = true; // whether the activity just started

	ScheduledExecutorService threadPool;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.patrol_layout);

		wiki = (Wiki) getIntent().getSerializableExtra("Wiki");

		changeQueue = new LinkedList<Change>();
		changesSeen = new HashSet<Long>();

		// set up UI
		summaryText = (TextView) findViewById(R.id.summaryText);
		titleText = (TextView) findViewById(R.id.titleText);
		descriptionText = (TextView) findViewById(R.id.descriptionText);
		diffText = (TextView) findViewById(R.id.diffText);

		titleText.setMovementMethod(LinkMovementMethod.getInstance());
		descriptionText.setMovementMethod(LinkMovementMethod.getInstance());

		// the three bottom buttons start out disabled
		nextButton = (Button) findViewById(R.id.nextButton);
		nextButton.setEnabled(false);

		rvButton = (Button) findViewById(R.id.revertButton);
		rvButton.setEnabled(false);

		rvVandalButton = (Button) findViewById(R.id.revertVandalButton);
		rvVandalButton.setEnabled(false);

		// revert & rc-fetching tasks run from this pool
		threadPool = Executors.newScheduledThreadPool(5);
	}

	@Override
	protected void onStart() {
		super.onStart();
		PreferenceManager.setDefaultValues(this, wiki.getUser(), 0, R.xml.preferences, false);
		SharedPreferences prefs = getSharedPreferences(wiki.getUser(), 0);
		boolean anonsOnly = prefs.getBoolean("pref_anonOnly", true);
		wiki.setAnonsOnly(anonsOnly);

		fetchRecentChanges();
	}

	@Override
	protected void onResume() {
		super.onResume();
	}

	@Override
	protected void onPause() {
		super.onPause();
	}

	@Override
	protected void onStop() {
		super.onStop();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.patrol, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.action_settings:
			Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);
			intent.putExtra("user", wiki.getUser());
			startActivity(intent);
			return true;
		case R.id.action_logout:
			logout();
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	public void onBackPressed() {
		logout();
	}

	public void revertButtonClicked(View view) {
		LayoutInflater inflater = LayoutInflater.from(this);
		View layout = inflater.inflate(R.layout.revert_dialog_layout, null);
		final EditText reasonEditText = (EditText) layout.findViewById(R.id.reasonInput);

		AlertDialog.Builder builder = new AlertDialog.Builder(this);

		builder.setView(layout);
		builder.setPositiveButton(R.string.revertButtonLabel, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				revert(reasonEditText.getText().toString());
			}
		});

		builder.setNegativeButton(android.R.string.cancel, null);

		builder.show();
	}

	public void revertVandalButtonClicked(View view) {
		revert("");
	}

	public void nextButtonClicked(View view) {
		if (changeQueue.size() < 5) {
			fetchRecentChanges();
			if (changeQueue.size() == 0) {
				return;
			}
		}

		current = changeQueue.remove();

		titleText.setText(makeLinkSpan(current.getTitle(), wiki.makeArticleURL(current.getTitle())));

		SpannableStringBuilder description = new SpannableStringBuilder();
		description.append(makeLinkSpan(current.getUser(), wiki.makeUserURL(current.getUser())));
		description.append(" . . ");
		description.append(current.getTime());
		description.append(" . . ");

		int lendiff = current.getLenDiff();
		String sizeString = "(" + (lendiff > 0 ? "+" : "") + lendiff + ")";

		int color = Color.BLACK;
		if (lendiff < 0) {
			color = Color.RED;
		} else if (lendiff > 0) {
			color = Color.GREEN;
		}

		SpannableString sizeSpan = new SpannableString(sizeString);
		sizeSpan.setSpan(new ForegroundColorSpan(color), 0, sizeSpan.length(), 0);
		if (Math.abs(lendiff) > 400) {
			sizeSpan.setSpan(new StyleSpan(Typeface.BOLD), 0, sizeSpan.length(), 0);
		}
		description.append(sizeSpan);

		descriptionText.setText(description);

		summaryText.setText(current.getSummary());
		diffText.setText(current.getDiff());

		if (changeQueue.size() == 0) {
			nextButton.setEnabled(false);
		}
	}

	public void revert(String reason) {
		new RevertTask().executeOnExecutor(threadPool, current, reason);
	}

	private void fetchRecentChanges() {
		new FetchRecentChangesTask().executeOnExecutor(threadPool, 10);
	}

	// first argument is Change object to revert
	// second argument is String describing the reason
	private class RevertTask extends AsyncTask<Object, Void, Wiki.RevertError> {
		String pagename;

		protected Wiki.RevertError doInBackground(Object... args) {
			Change change = (Change) args[0];
			String reason = (String) args[1];

			pagename = change.getTitle();

			try {
				return wiki.rollback(change, reason);
			} catch (Exception e) {
				return Wiki.RevertError.IOERROR;
			}
		}

		protected void onPostExecute(Wiki.RevertError error) {
			if (error == null) {
				Toast.makeText(getApplicationContext(), String.format(getString(R.string.reverted), pagename),
						Toast.LENGTH_LONG).show();
			} else {
				int stringid;

				switch (error) {
				case TOOLATE:
					stringid = R.string.toolate;
					break;
				case ALREADYROLLED:
					stringid = R.string.alreadyRolled;
					break;
				case NOCHANGE:
					stringid = R.string.noChange;
					break;
				case ONLYAUTHOR:
					stringid = R.string.onlyAuthor;
					break;
				case IOERROR:
					stringid = R.string.ioError;
					break;
				case UNKNOWN:
				default:
					stringid = R.string.unknownRevert;
					break;
				}

				Toast.makeText(getApplicationContext(), String.format(getString(stringid), pagename), Toast.LENGTH_LONG).show();
			}
		}
	}

	private class FetchRecentChangesTask extends AsyncTask<Integer, Void, Change[]> {
		protected Change[] doInBackground(Integer... args) {
			try {
				return wiki.fetchRecentChanges(args[0]);
			} catch (Exception e) {
				return null;
			}
		}

		protected void onPostExecute(Change[] changes) {
			if (changes == null) {
				Toast.makeText(getApplicationContext(), R.string.rcFetchError, Toast.LENGTH_LONG).show();
				return;
			}

			// add new edits to the queue
			for (Change change : changes) {
				if (!changesSeen.contains(change.getRevid())) {
					changeQueue.add(change);
					changesSeen.add(change.getRevid());
				}
			}

			if (changeQueue.size() > 0) {
				nextButton.setEnabled(true);
			} else {
				fetchRecentChanges(); // try again
			}

			// if this is the first time fetching recent changes, enable buttons
			// and show the edit
			if (juststarted) {
				juststarted = false;
				rvButton.setEnabled(true);
				rvVandalButton.setEnabled(true);
				nextButtonClicked(null);
			}
		}
	}

	private void logout() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage(R.string.reallyLogout);

		builder.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				if (wiki != null) {
					if (wiki.isLoggedIn()) {
						wiki.logout();
						finish();
					}
				}
			}
		});

		builder.setNegativeButton(android.R.string.no, null);

		builder.show();
	}

	private SpannableString makeLinkSpan(String text, String url) {
		SpannableString span = new SpannableString(text);
		span.setSpan(new CustomURLSpan(url), 0, text.length(), 0);
		return span;
	}

	class CustomURLSpan extends URLSpan {
		public CustomURLSpan(String url) {
			super(url);
		}

		@Override
		public void updateDrawState(TextPaint tp) {
			super.updateDrawState(tp);
			tp.setARGB(255, 0, 0, 0);
		}
	}
}
