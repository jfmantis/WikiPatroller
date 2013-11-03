package com.jfmantis.wikipatroller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import name.fraser.neil.plaintext.diff_match_patch;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;

public class Wiki implements Serializable {

	// Generally, these error enums are used for errors that would happen during
	// normal operation -- wrong password, edit conflict, etc. Real errors, like
	// connection and parsing problems, are dealt with through exceptions.

	enum LoginError {
		INVALID, MISSING, BLOCKED, ROLLBACK, WRONGPASS, THROTTLED, UNKNOWN, IOERROR
	}

	enum RevertError {
		TOOLATE, ALREADYROLLED, ONLYAUTHOR, NOCHANGE, UNKNOWN, IOERROR
	}

	private static final long serialVersionUID = -2398152104080597156L;

	private String domain, username;
	private boolean loggedin = false;
	private boolean anonsonly = true;

	private diff_match_patch differ;

	public Wiki(String domain) {
		if (domain == null || domain.length() == 0) {
			this.domain = "en.wikipedia.org";
		}
		this.domain = domain;

		RequestBuilder.setDomain(domain);
		RequestBuilder.setFormat("json");

		differ = new diff_match_patch();
	}

	public boolean getAnonsOnly() {
		return anonsonly;
	}

	public void setAnonsOnly(boolean b) {
		anonsonly = b;
	}

	public String getDomain() {
		return domain;
	}

	public boolean isLoggedIn() {
		return loggedin;
	}

	public String getUser() {
		return username;
	}

	public String makeArticleURL(String name) {
		return "http://" + domain + "/wiki/" + name;
	}

	public String makeUserURL(String name) {
		return makeArticleURL("User:" + name);
	}

	public synchronized void logout() {
		loggedin = false;
		RequestBuilder.clearCookies();
	}

	public LoginError login(String username, String password) throws IOException, JSONException {
		LoginError err = checkIfValidUser(username);
		if (err != null) {
			return err;
		}

		// get login token
		RequestBuilder request = new RequestBuilder();
		request.addParam("action", "login");
		request.addParam("lgname", username);

		String loginToken = parseJSON(request.post()).getJSONObject("login").getString("token");

		// actually login
		request.addParam("lgpassword", password);
		request.addParam("lgtoken", loginToken);

		String result = parseJSON(request.post()).getJSONObject("login").getString("result");

		if (result.equals("Success")) {
			this.username = username;
			loggedin = true;
			return null;
		} else if (result.equals("WrongPass") || result.equals("WrongPluginPass")) {
			return LoginError.WRONGPASS;
		} else if (result.equals("Throttled")) {
			return LoginError.THROTTLED;
		} else {
			return LoginError.UNKNOWN;
		}
	}

	public Change[] fetchRecentChanges(int count) throws IOException, Exception {
		// get recent change list
		RequestBuilder request = new RequestBuilder();
		request.addParam("action", "query");
		request.addParam("list", "recentchanges");
		request.addParam("rcshow", (anonsonly ? "anon" : ""));
		request.addParam("rcnamespace", "0");
		request.addParam("rctype", "edit");
		request.addParam("rcprop", "user|comment|title|ids|sizes|timestamp|flags");
		request.addParam("rclimit", count);

		JSONArray rcArray = parseJSON(request.get()).getJSONObject("query").getJSONArray("recentchanges");

		if (rcArray.length() != count) {
			throw new Exception("Unexpected number of results = " + rcArray.length());
		}

		// parse into Change objects
		// if a page appears more than once, only take the most recent edit
		Set<Long> pageids = new HashSet<Long>();
		ArrayList<Change> changes = new ArrayList<Change>();
		for (int i = 0; i < count; i++) {
			Change change = new Change(rcArray.getJSONObject(i));
			if (pageids.contains(change.getPageid()) == false) {
				changes.add(change);
				pageids.add(change.getPageid());
			}
		}

		long[] revids = new long[changes.size() * 2];
		for (int i = 0; i < changes.size(); i++) {
			revids[i * 2 + 0] = changes.get(i).getRevid();
			revids[i * 2 + 1] = changes.get(i).getOldrevid();
		}

		// get current and previous text of each edit
		JSONObject pages = getRevisions(revids);

		for (int i = 0; i < changes.size(); i++) {
			JSONArray revisions = pages.getJSONObject(Long.toString(changes.get(i).getPageid())).getJSONArray("revisions");
			String oldtext = revisions.getJSONObject(0).getString("*");
			String newtext = revisions.getJSONObject(1).getString("*");
			changes.get(i).setDiff(genDiff(oldtext, newtext));
			changes.get(i).setPrevUser(revisions.getJSONObject(0).getString("user"));
		}

		Change[] array = new Change[changes.size()];
		changes.toArray(array);
		return array;
	}

	public RevertError rollback(Change change, String reason) throws IOException, Exception {
		// get token
		RequestBuilder tokenBuilder = new RequestBuilder();
		tokenBuilder.addParam("action", "query");
		tokenBuilder.addParam("prop", "revisions");
		tokenBuilder.addParam("rvtoken", "rollback");
		tokenBuilder.addParam("titles", change.getTitle());

		JSONObject responseObject = parseJSON(tokenBuilder.get());
		JSONObject revisionObject = responseObject.getJSONObject("query").getJSONObject("pages")
				.getJSONObject(Long.toString(change.getPageid())).getJSONArray("revisions").getJSONObject(0);

		long lastrevid = revisionObject.getLong("revid");
		if (lastrevid != change.getRevid()) {
			return RevertError.TOOLATE;
		}

		String token = revisionObject.getString("rollbacktoken");

		// now actually rollback
		String summary = makeSummary(change.getUser(), reason, change.getPrevUser());

		RequestBuilder rollbackRequest = new RequestBuilder();
		rollbackRequest.addParam("action", "rollback");
		rollbackRequest.addParam("title", change.getTitle());
		rollbackRequest.addParam("user", change.getUser());
		rollbackRequest.addParam("summary", summary);
		rollbackRequest.addParam("markbot", false);
		rollbackRequest.addParam("token", token);

		responseObject = parseJSON(rollbackRequest.post());
		if (responseObject.has("rollback")) {
			Long revid = responseObject.getJSONObject("rollback").getLong("revid");
			Long oldrevid = responseObject.getJSONObject("rollback").getLong("old_revid");
			if (revid == oldrevid) {
				return RevertError.NOCHANGE;
			} else {
				return null;
			}
		} else if (responseObject.has("error")) {
			String code = responseObject.getJSONObject("error").getString("code");
			if (code.equals("alreadyrolled")) {
				return RevertError.ALREADYROLLED;
			} else if (code.equals("onlyauthor")) {
				return RevertError.ONLYAUTHOR;
			} else {
				return RevertError.UNKNOWN;
			}
		} else {
			return RevertError.IOERROR;
		}
	}

	// returns object with {"pageid1":{}, "pageid2":{}, etc.}
	public JSONObject getRevisions(long[] revids) throws IOException, Exception {
		StringBuilder revidBuilder = new StringBuilder();
		for (int i = 0; i < revids.length; i++) {
			revidBuilder.append(revids[i]);
			if (i < revids.length - 1) {
				revidBuilder.append("|");
			}
		}

		RequestBuilder request = new RequestBuilder();
		request.addParam("action", "query");
		request.addParam("prop", "revisions");
		request.addParam("rvprop", "content|user|comment|title|ids|sizes|timestamp|flags");
		request.addParam("revids", revidBuilder.toString());

		JSONObject response = parseJSON(request.get());
		return response.getJSONObject("query").getJSONObject("pages");
	}

	private LoginError checkIfValidUser(String username) throws IOException, JSONException {
		RequestBuilder request = new RequestBuilder();
		request.addParam("action", "query");
		request.addParam("list", "users");
		request.addParam("usprop", "editcount|groups|rights|blockinfo|registration");
		request.addParam("ususers", username);

		JSONObject user = parseJSON(request.get()).getJSONObject("query").getJSONArray("users").getJSONObject(0);

		// user name must be valid
		if (user.has("invalid")) {
			return LoginError.INVALID;
		}

		// user must exist
		if (user.has("missing")) {
			return LoginError.MISSING;
		}

		// can't be blocked
		if (user.has("blockid")) {
			return LoginError.BLOCKED;
		}

		// must be rollbacker
		boolean rollbacker = false;
		JSONArray rights = user.getJSONArray("rights");
		for (int i = 0; i < rights.length(); i++) {
			if (rights.getString(i).equals("rollback")) {
				rollbacker = true;
				break;
			}
		}

		if (rollbacker == false) {
			return LoginError.ROLLBACK;
		}

		return null;
	}

	private JSONObject parseJSON(String string) throws JSONException {
		return (JSONObject) new JSONTokener(string).nextValue();
	}

	private String makeSummary(String user, String reason, String prevUser) {
		String myPage = " (using [[User:Jfmantis/WikiPatroller|WikiPatroller]])";
		String summaryFmt = "Reverted edit(s) by [[Special:Contributions/%s|%s]] ([[User_talk:%s|talk]])";
		String summary = String.format(summaryFmt, user, user, user);

		if (reason.length() > 0) {
			summary += ": " + reason;
		} else {
			summary += " to last revision";
		}

		if (summary.length() + myPage.length() < 250) {
			summary += myPage;
		}

		return summary;
	}

	private CharSequence genDiff(String before, String after) {
		SpannableStringBuilder builder = new SpannableStringBuilder();

		LinkedList<diff_match_patch.Diff> diffs = differ.diff_main(before, after);
		differ.diff_cleanupSemantic(diffs);
		differ.diff_cleanupSemantic(diffs);
		
		int pos = 0;
		for (int i = 0; i < diffs.size(); i++) {
			diff_match_patch.Diff d = diffs.get(i);

			if (d.operation == diff_match_patch.Operation.EQUAL) {
				String string = "";
				int len = d.text.length();

				if (len < 100) {
					string = d.text;
				} else {
					if (i > 0) {
						string += d.text.substring(0, 80) + "\n";
					}

					string += " . . . ";

					if (i < diffs.size() - 1) {
						string += "\n" + d.text.substring(len - 80, len);
					}
				}

				builder.append(string);
				pos += string.length();
			} else {
				builder.append(d.text);

				if (d.operation == diff_match_patch.Operation.INSERT) {
					builder.setSpan(new BackgroundColorSpan(Color.rgb(220, 255, 220)), pos, builder.length(), 0);
				} else if (d.operation == diff_match_patch.Operation.DELETE) {
					builder.setSpan(new BackgroundColorSpan(Color.rgb(255, 220, 220)), pos, builder.length(), 0);
				}

				pos += d.text.length();
			}
		}

		return builder;
	}
}

/*
 * Helper class for building GET and POST requests with many parameters. There
 * are a lot of variables that are the same for every request that this
 * application makes, so there are a lot of static variables, but except for all
 * the wiki-specific stuff, it's actually pretty flexible.
 */

class RequestBuilder {
	// 30 seconds is enough
	private static final int CONNECT_TIMEOUT = 30000;
	private static final int READ_TIMEOUT = 30000;

	// all requests are based off the same base url
	private static String domain, baseUrl, format;

	// cookies are shared between all requests
	private static HashMap<String, String> cookies = new HashMap<String, String>();

	public static void setDomain(String domainString) {
		domain = domainString;
		baseUrl = "https://" + domain + "/w/api.php?";
	}

	public static void setFormat(String formatString) {
		format = formatString;
	}

	public static String getBaseUrl() {
		return baseUrl;
	}

	public static String getDomain() {
		return domain;
	}

	public static String getFormat() {
		return format;
	}

	public static void clearCookies() {
		cookies.clear();
	}

	// instance variables and methods

	public RequestBuilder() throws UnsupportedEncodingException {
		addParam("format", format);
	}

	public String toString() {
		return baseUrl + " : " + builder.toString();
	}

	private StringBuilder builder = new StringBuilder();

	public void addParam(String key, int value) throws UnsupportedEncodingException {
		addParam(key, Integer.toString(value));
	}

	public void addParam(String key, long value) throws UnsupportedEncodingException {
		addParam(key, Long.toString(value));
	}

	public void addParam(String key, boolean value) throws UnsupportedEncodingException {
		addParam(key, value ? "true" : "false");
	}

	public void addParam(String key, String value) throws UnsupportedEncodingException {
		key = URLEncoder.encode(key, "UTF-8");
		value = URLEncoder.encode(value, "UTF-8");
		builder.append("&").append(key);
		builder.append("=").append(value);
	}

	public String get() throws IOException {
		URLConnection connection = new URL(baseUrl + builder.toString()).openConnection();
		connection.setConnectTimeout(CONNECT_TIMEOUT);
		connection.setReadTimeout(READ_TIMEOUT);
		setCookies(connection);
		connection.connect();
		getCookies(connection);

		return slurpStream(connection.getInputStream());
	}

	public String post() throws IOException {
		URLConnection connection = new URL(baseUrl).openConnection();
		connection.setDoOutput(true);
		setCookies(connection);
		connection.connect();

		OutputStreamWriter outwriter = new OutputStreamWriter(connection.getOutputStream(), "UTF-8");
		outwriter.write(builder.toString());
		outwriter.close();

		getCookies(connection);

		return slurpStream(connection.getInputStream());
	}

	private String slurpStream(InputStream in) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

		StringBuilder builder = new StringBuilder();
		char[] buf = new char[4096];
		for (int n; (n = reader.read(buf)) != -1;) {
			builder.append(new String(buf, 0, n));
		}
		reader.close();

		return builder.toString();
	}

	private void setCookies(URLConnection connection) {
		StringBuilder cookie = new StringBuilder();
		for (Map.Entry<String, String> entry : cookies.entrySet()) {
			cookie.append(entry.getKey() + "=" + entry.getValue() + ";");
		}
		connection.setRequestProperty("Cookie", cookie.toString());
	}

	private void getCookies(URLConnection connection) {
		Map<String, List<String>> header = connection.getHeaderFields();
		if (header.containsKey("Set-Cookie")) {
			for (String cookie : header.get("Set-Cookie")) {
				cookie = cookie.substring(0, cookie.indexOf(';'));
				String name = cookie.substring(0, cookie.indexOf('='));
				String value = cookie.substring(cookie.indexOf('=') + 1, cookie.length());
				cookies.put(name, value);
			}
		}
	}
}
