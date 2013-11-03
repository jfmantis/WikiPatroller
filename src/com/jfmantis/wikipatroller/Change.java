package com.jfmantis.wikipatroller;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Represents one edit
 * 
 * Contains all the information returned about an edit by querying the recent
 * changes list. Also stores the previous user and the diff, which are computed
 * by the Wiki class in fetchRecentChanges().
 */
public class Change {
	private String user, title, summary, time, prevUser;
	private long revid, oldrevid, pageid;
	private int oldlen, newlen;

	private CharSequence diff;

	public Change(JSONObject json) {
		try {
			user = json.getString("user");
			title = json.getString("title");
			summary = json.getString("comment");
			if (summary.length() == 0) {
				summary = "(no edit summary)";
			}
			time = json.getString("timestamp");

			revid = json.getLong("revid");
			oldrevid = json.getLong("old_revid");
			pageid = json.getLong("pageid");

			oldlen = json.getInt("oldlen");
			newlen = json.getInt("newlen");
		} catch (JSONException e) {
			e.printStackTrace();
		}
	}

	public void setPrevUser(String s) {
		prevUser = s;
	}

	public String getPrevUser() {
		return prevUser;
	}

	public String getUser() {
		return user;
	}

	public String getTitle() {
		return title;
	}

	public String getSummary() {
		return summary;
	}

	public String getTime() {
		return time.substring(time.length() - 9, time.length() - 1);
	}

	public long getRevid() {
		return revid;
	}

	public long getOldrevid() {
		return oldrevid;
	}

	public long getPageid() {
		return pageid;
	}

	public int getOldlen() {
		return oldlen;
	}

	public int getNewlen() {
		return newlen;
	}

	public int getLenDiff() {
		return newlen - oldlen;
	}

	public void setDiff(CharSequence diff) {
		this.diff = diff;
	}

	public CharSequence getDiff() {
		return diff;
	}
}