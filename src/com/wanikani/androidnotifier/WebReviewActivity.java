package com.wanikani.androidnotifier;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/* 
 *  Copyright (c) 2013 Alberto Cuda
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * This activity that allows the user to perform its reviews through an integrated
 * browser. The only reason we need this (instead of just giving spawning an external
 * browser) is that we also display a minimal keyboard, that interacts with WK scripts
 * to compose kanas. Ordinarily, in fact, Android keyboards do not behave correctly.
 * <p>
 * The keyboard is displayed only when needed, so we need to check whether the
 * page contains a <code>user_response</code> text box, and it is enabled.
 * In addition, to submit the form, we look up from the page, assuming its id
 * is <code>question-form</code>.
 * <p>
 * To accomplish this, we register a JavascriptObject (<code>wknKeyboard</code>) and inject
 * a javascript to check how the page looks like. If the keyboard needs to be shown,
 * it calls its <code>show</code> (vs. <code>hide</code>) method.
 * The JavascriptObject is implemented by @link WebReviewActivity.WKNKeyboard.
 */
public class WebReviewActivity extends Activity {
	
	/**
	 * This class is barely a container of all the strings that should match with the
	 * WaniKani portal. Hopefully none of these will ever be changed, but in case
	 * it does, here is where to look for.
	 */
	static class WKConfig {
		
		/** All the pages that are shown inside the integrated WebView */
		static final String REVIEW_SPACE =  "http://www.wanikani.com/review";
		
		/** Review start page. Of course must be inside of @link {@link #REVIEW_SPACE} */
		static final String REVIEW_START = "http://www.wanikani.com/review/session/start";

		/** Login page. Needed when authentication has not been performed yet */
		static final String LOGIN_PAGE =  "http://www.wanikani.com/login";

		/** HTML id of the textbox the user types its answer in */
		static final String ANSWER_BOX = "user_response";

		/** HTML id of the review form */
		static final String QUESTION_FORM = "question-form";

	};

	/**
	 * Web view controller. This class is used by @link WebView to tell whether
	 * a link should be opened inside of it, or an external browser needs to be invoked.
	 * Currently, I will let all the pages inside the <code>/review</code> namespace
	 * to be opened here. Theoretically, it could even be stricter, and use
	 * <code>/review/session</code>, but that would be prevent the final summary
	 * from being shown. That page is useful, albeit not as integrated with the app as
	 * the other pages.
	 */
	private class WebViewClientImpl extends WebViewClient {
				
		/**
		 * Called to check whether a link should be opened in the view or not.
		 * We also display the progress bar.
		 * 	@param view the web view
		 *  @url the URL to be opened
		 */
	    @Override
	    public boolean shouldOverrideUrlLoading (WebView view, String url) 
	    {
	        view.loadUrl (url);

	        return true;
	    }
		
	    /**
	     * Called when something bad happens while accessing the resource.
	     * Show the splash screen and give some explanation (based on the <code>description</code>
	     * string).
	     * 	@param view the web view
	     *  @param errorCode HTTP error code
	     *  @param description an error description
	     *  @param failingUrl error
	     */
	    public void onReceivedError (WebView view, int errorCode, String description, String failingUrl)
	    {
	    	String s;
	    	
	    	s = String.format (getResources ().getString (R.string.fmt_web_review_error), description);
	    	splashScreen (s);
	    	bar.setVisibility (View.GONE);
	    }

		@Override  
	    public void onPageStarted (WebView view, String url, Bitmap favicon)  
	    {  
	        bar.setVisibility (View.VISIBLE);
		}
	
		/**
	     * Called when a page finishes to be loaded. We hide the progress bar
	     * and run the initialization javascript that shows the keyboard, if needed.
	     */
		@Override  
	    public void onPageFinished(WebView view, String url)  
	    {  
			contentScreen ();
			bar.setVisibility (View.GONE);
			if (url.startsWith ("http"))
				js (JS_INIT);
	    }
	}
	
	/**
	 * An additional webclient, that receives a few callbacks that a simple 
	 * {@link WebChromeClient} does not intecept. 
	 */
	private class WebChromeClientImpl extends WebChromeClient {
	
		/**
		 * Called as the download progresses. We update the progress bar.
		 * @param view the web view
		 * @param progress progress percentage
		 */
		@Override	
		public void onProgressChanged (WebView view, int progress)
		{
			bar.setProgress (progress);
		}		
	};

	/**
	 * A small job that hides or shows the keyboard. We need to implement this
	 * here because {@link WebReviewActibity.WKNKeyboard} gets called from a
	 * javascript thread, which is not necessarily an UI thread.
	 * The constructor simply calls <code>runOnUIThread</code> to make sure
	 * we hide/show the views from the correct context.
	 */
	private class ShowHideKeyboard implements Runnable {
		
		/// Whether the keyboard should be shown or hidden
		boolean show;
		
		/**
		 * Constructor. It also takes care to schedule the invokation
		 * on the UI thread, so all you have to do is just to create an
		 * instance of this object
		 * @param show <code>true</code> iff the keyboard should be shown
		 */
		ShowHideKeyboard (boolean show)
		{
			this.show = show;
			
			runOnUiThread (this);
		}
		
		/**
		 * Hides/shows the keyboard. Invoked by the UI thread.
		 */
		public void run ()
		{
			View view;
			
			view = findViewById (R.id.keyboard);
			view.setVisibility (show ? View.VISIBLE : View.GONE);			
		}
		
	}
	
	/**
	 * This class implements the <code>wknKeyboard</code> javascript object.
	 * It implements the @link {@link #show} and {@link #hide} methods. 
	 */
	private class WKNKeyboard {
		
		/**
		 * Called by javascript when the keyboard should be shown.
		 */
		@JavascriptInterface
		public void show ()
		{
			new ShowHideKeyboard (true);
		}

		/**
		 * Called by javascript when the keyboard should be hidden.
		 */
		@JavascriptInterface
		public void hide ()
		{
			new ShowHideKeyboard (false);
		}
	}
	
	/**
	 * A button listener that handles all the meta keys on the keyboard.
	 * Actually some buttons have nothing really special (like space, or apostrophe): 
	 * the real definition of meta key, here, is a key that does not change
	 * when the meta (123) button is pressed. 
	 */
	private class MetaListener implements View.OnClickListener {
	
		/**
		 * Called when one of the keys is pressed.
		 * Looks up in the @link {@link WebReviewActivity#meta_table} array
		 * and inserts the appropriate key code.
		 * 	@param v the keyboard view
		 */
		@Override
		public void onClick (View v)
		{
			int i, id;
		
			id = v.getId ();
		
			for (i = 0; i < meta_table.length; i++) {
				if (id == meta_table [i]) {
					insert (meta_codes [i]);
					break;
				}
			}
		}
	};

	/**
	 * A listener that handles all the ordinary keys.
	 */
	private class KeyListener implements View.OnClickListener {
		

		/**
		 * Called when one of the keys is pressed.
		 * Looks up in the @link {@link WebReviewActivity#key_table} array
		 * and inserts the appropriate key code. This is done by looking up
		 * the appropriate character in the {@link WebReviewActivity#keyboard}
		 * string, which is an ASCII string. Therefore, an additional translation
		 * (ASCII to Keycodes) is performed, through 
		 * {@link WebReviewActivity#charToKeyCode(char)}.
		 * 	@param v the keyboard view
		 */
		@Override
		public void onClick (View v)
		{
			int i, id;
			
			id = v.getId ();
			
			for (i = 0; i < key_table.length; i++) {
				if (id == key_table [i]) {
					if (i < keyboard.length ())
						insert (charToKeyCode (keyboard.charAt (i)));
					break;
				}
			}
		}
	};

	/** The web view, where the web contents are rendered */
	WebView wv;
	
	/** The view containing a splash screen. Visible when we want to display 
	 * some message to the user */ 
	View splashView;
	
	/**
	 * The view contaning the ordinary content.
	 */
	View contentView;
	
	/** A textview in the splash screen, where we can display some message */
	TextView msgw;
	
	/** The web progress bar */
	ProgressBar bar;
			
	private static final String PREFIX = "com.wanikani.androidnotifier.WebReviewActivity.";
	
	public static final String OPEN_ACTION = PREFIX + "OPEN";
	
	private static final String JS_INIT = 
			"var textbox = document.getElementById (\"" + WKConfig.ANSWER_BOX + "\"); " +
			"if (textbox != null && !textbox.disabled) {" +
			"	wknKeyboard.show ();" +
			"} else {" +
			"	wknKeyboard.hide ();" +
			"}";
	private static final String JS_FOCUS = 
			"var textbox = document.getElementById (\"" + WKConfig.ANSWER_BOX + "\"); " +
			"	textbox.focus ();";

	private static final String JS_ENTER = 
			"var form = document.getElementById (\"" + WKConfig.QUESTION_FORM + "\"); " +
			"form.submit ();";

	private static final String KB_LATIN = "qwertyuiopasdfghjklzxcvbnm";

	private static final String KB_ALT = "1234567890";
	
	private static final int key_table [] = new int [] {
		R.id.kb_0, R.id.kb_1,  R.id.kb_2, R.id.kb_3, R.id.kb_4,
		R.id.kb_5, R.id.kb_6,  R.id.kb_7, R.id.kb_8, R.id.kb_9,
		R.id.kb_10, R.id.kb_11,  R.id.kb_12, R.id.kb_13, R.id.kb_14,
		R.id.kb_15, R.id.kb_16,  R.id.kb_17, R.id.kb_18, R.id.kb_19,
		R.id.kb_20, R.id.kb_21,  R.id.kb_22, R.id.kb_23, R.id.kb_24,
		R.id.kb_25
	};
	
	private static final int meta_table [] = new int [] {
		R.id.kb_quote, R.id.kb_backspace, R.id.kb_meta, 
		R.id.kb_space, R.id.kb_enter		
	};
	
	private static final int meta_codes [] = new int [] {
		KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_NUM,
		KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER		
	};
	
	private String keyboard;
	
	private KeyListener klist;
	private MetaListener mlist;

	@Override
	public void onCreate (Bundle bundle) 
	{
		super.onCreate (bundle);
		
		setContentView (R.layout.web_review);
		
		initKeyboard ();
		
		bar = (ProgressBar) findViewById (R.id.pb_reviews);
		
		/* First of all get references to views we'll need in the near future */
		splashView = findViewById (R.id.wv_splash);
		contentView = findViewById (R.id.wv_content);
		msgw = (TextView) findViewById (R.id.tv_message);
		wv = (WebView) findViewById (R.id.wv_reviews);

		splashScreen (getResources ().getString (R.string.fmt_web_review_connecting));
				
		wv.getSettings ().setJavaScriptEnabled (true);
		wv.getSettings().setJavaScriptCanOpenWindowsAutomatically (true);
		wv.getSettings ().setSupportMultipleWindows (true);
		wv.getSettings ().setUseWideViewPort (true);
		wv.addJavascriptInterface (new WKNKeyboard (), "wknKeyboard");
		wv.setScrollBarStyle (ScrollView.SCROLLBARS_OUTSIDE_OVERLAY);
		wv.setWebViewClient (new WebViewClientImpl ());
		wv.setWebChromeClient (new WebChromeClientImpl ());
		
		//resetCookies (wv);
		
		wv.loadUrl (WKConfig.REVIEW_START);
	}
	
	private void resetCookies (WebView wv)
	{
		CookieSyncManager smgr;
		CookieManager mgr;
		
		smgr = CookieSyncManager.createInstance (wv.getContext ());
		mgr = CookieManager.getInstance ();
		mgr.removeAllCookie ();
		smgr.sync ();
	}
	
	protected void initKeyboard ()
	{
		View key;
		int i;
		
		loadKeyboard (KB_LATIN);		

		klist = new KeyListener ();
		mlist = new MetaListener ();

		for (i = 0; i < key_table.length; i++) {
			key = findViewById (key_table [i]);
			key.setOnClickListener (klist);
		}					

		for (i = 0; i < meta_table.length; i++) {
			key = findViewById (meta_table [i]);
			key.setOnClickListener (mlist);
		}					
	}
	
	protected void loadKeyboard (String kbd)
	{
		Button key;
		int i;
		
		keyboard = kbd;
		for (i = 0; i < kbd.length(); i++) {
			key = (Button) findViewById (key_table [i]);
			key.setText ("" + kbd.charAt (i));
		}
		while (i < key_table.length) {
			key = (Button) findViewById (key_table [i]);
			key.setText ("");
		}
	}
	
	public void insert (int keycode)
	{
		KeyEvent kdown, kup;
	
		if (keycode == KeyEvent.KEYCODE_NUM)
			loadKeyboard (keyboard == KB_ALT ? KB_LATIN : KB_ALT);
		else if (keycode == KeyEvent.KEYCODE_ENTER)
			js (JS_ENTER);
		else {
			kdown = new KeyEvent (KeyEvent.ACTION_DOWN, keycode);
			wv.dispatchKeyEvent (kdown);				
			kup = new KeyEvent (KeyEvent.ACTION_UP, keycode);
			wv.dispatchKeyEvent (kup);
		}
	}
	
	protected int charToKeyCode (char c)
	{
		if (Character.isLetter (c))
			return KeyEvent.KEYCODE_A + (Character.toLowerCase (c) - 'a');
		else if (Character.isDigit (c))
			return KeyEvent.KEYCODE_0 + (c - '0');
		
		switch (c) {
		case ' ':
			return KeyEvent.KEYCODE_SPACE;
		}
		
		return KeyEvent.KEYCODE_SPACE;
	}
	
	private void js (String s)
	{
       wv.loadUrl ("javascript:(function() { " + s + "})()");
	}
	
	private void splashScreen (String msg)
	{
		msgw.setText (msg);
		contentView.setVisibility (View.GONE);
		splashView.setVisibility (View.VISIBLE);
	}
	
	private void contentScreen ()
	{
		splashView.setVisibility (View.GONE);
		contentView.setVisibility (View.VISIBLE);
		msgw.setText ("");
	}
}