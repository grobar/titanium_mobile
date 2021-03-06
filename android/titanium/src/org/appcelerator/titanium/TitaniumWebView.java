package org.appcelerator.titanium;

import java.util.HashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.appcelerator.titanium.api.ITitaniumNativeControl;
import org.appcelerator.titanium.config.TitaniumConfig;
import org.appcelerator.titanium.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.AbsoluteLayout;

@SuppressWarnings("deprecation")
public class TitaniumWebView extends WebView implements Handler.Callback
{
	private static final String LCAT = "TiWebView";
	private static final boolean DBG = TitaniumConfig.LOGD;

	private static final String JAVASCRIPT = "javascript:";
	private static final String TITANIUM_CALLBACK = "Titanium.callbacks"; //Sent from ti.js

	public static final int MSG_RUN_JAVASCRIPT = 300;
	public static final int MSG_LOAD_FROM_SOURCE = 301;
	public static final int MSG_ADD_CONTROL = 302;

	protected static final String MSG_EXTRA_URL = "url";
	protected static final String MSG_EXTRA_SOURCE = "source";

	private Handler handler;
	private Handler evalHandler;

	private MimeTypeMap mtm;

	private HashMap<String, ITitaniumNativeControl> nativeControls;
	private AbsoluteLayout.LayoutParams offScreen;

	private HashMap<String, Semaphore> locks;
	private AtomicInteger uniqueLockId;

	public TitaniumWebView(TitaniumActivity activity)
	{
		super(activity);
		this.handler = new Handler(this);
		this.mtm = MimeTypeMap.getSingleton();
		this.locks = new HashMap<String,Semaphore>();
		this.uniqueLockId = new AtomicInteger();

		WebSettings settings = getSettings();

		setVerticalScrollbarOverlay(true);

        settings.setJavaScriptEnabled(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportZoom(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setLightTouchEnabled(true);

        offScreen = new AbsoluteLayout.LayoutParams(1, 1, -100, -100);
        final TitaniumWebView me = this;

        HandlerThread ht = new HandlerThread("TiJSEvalThread"){

			@Override
			protected void onLooperPrepared() {
				super.onLooperPrepared();
				evalHandler = new Handler(Looper.myLooper(), new Handler.Callback(){

					public boolean handleMessage(Message msg) {
						if(msg.what == MSG_RUN_JAVASCRIPT) {
							if (DBG) {
								Log.d(LCAT, "Invoking: " + msg.obj);
							}
							String id = msg.getData().getString("syncId");
							loadUrl((String) msg.obj);
							syncOn(id);
							if (DBG) {
								Log.w(LCAT, "AFTER: " + msg.obj);
							}

							return true;
						}
						return false;
					}});
				synchronized(me) {
					me.notify();
				}
			}

        };
        ht.start();
        // wait for eval hander to initialize
        synchronized(me) {
        	try {
        		me.wait();
        	} catch (InterruptedException e) {

        	}
        }
	}

	public String registerLock() {
		String syncId = "S:" + uniqueLockId.incrementAndGet();
		synchronized(locks) {
			Semaphore l = locks.get(syncId);
			if (l != null) {
				throw new IllegalStateException("Attempt to register duplicate lock id: " + syncId);
			}
			l = new Semaphore(0);
			locks.put(syncId, l);
			return syncId;
		}
	}

	public Semaphore getLockFor(String syncId) {
		synchronized(locks) {
			return locks.get(syncId);
		}
	}

	public void unregisterLock(String syncId) {
		synchronized(locks) {
			if (locks.containsKey(syncId)) {
				locks.remove(syncId);
			}
		}
	}

	public void signal(String syncId) {
		if (DBG) {
			Log.d(LCAT, "Signaling " + syncId);
		}
		Semaphore l = null;
		synchronized(locks) {
			l = locks.get(syncId);
		}
			l.release();
	}

	public void evalJS(final String method) {
		evalJS(method, (String) null, (String) null);
	}

	public void evalJS(final String method, final String data) {
		evalJS(method, data, (String) null);
	}

	public void evalJS(final String method, final JSONObject data)
	{
		String dataValue = null;

		if (data != null) {
			dataValue = data.toString();
		}

		evalJS(method, dataValue, (String) null);
	}

	private void syncOn(String syncId) {
		if (syncId != null) {
			Semaphore l = null;
			synchronized(locks) {
				l = locks.get(syncId);
			}
			try {
				l.acquire();
			} catch (InterruptedException e) {

			}
		}
	}

	public void evalJS(final String method, final String data, final String syncId)
	{
		String expr = method;
		if (expr != null && expr.startsWith(TITANIUM_CALLBACK)) {
			if (data != null) {
				if (syncId == null) {
					expr += ".invoke(" + data + ")";
				}  else {
					expr += ".invoke(" + data + ",'" + syncId + "')";
				}
			} else {
				if (syncId == null) {
					expr += ".invoke()";
				} else {
					expr += ".invoke(null,'" + syncId + "')";
				}
			}
			if (DBG) {
				Log.d(LCAT, expr);
			}
		}

		if (handler != null) {
			if (!expr.startsWith(JAVASCRIPT)) {
				expr = JAVASCRIPT + expr;
			}

			if (DBG) {
				Log.w(LCAT, " BEFORE: " + expr);
			}
			final String f = expr;
			// If someone tries to invoke from WebViewCoreThread, use our eval thread
			if ("WebViewCoreThread".equals(Thread.currentThread().getName())) {
				Message m = evalHandler.obtainMessage(MSG_RUN_JAVASCRIPT, expr);
				m.getData().putString("syncId", syncId);
				m.sendToTarget();
			} else {
				loadUrl(f);
				syncOn(syncId);
				if (DBG) {
					Log.w(LCAT, "AFTER: " + f);
				}
			}
		} else {
			Log.w(LCAT, "Handler not available for dispatching event");
		}

	}

	public boolean handleMessage(Message msg)
	{
		boolean handled = false;
		Bundle b = msg.getData();

		switch (msg.what) {
		case MSG_LOAD_FROM_SOURCE:
      		String url = b.getString(MSG_EXTRA_URL);
      		String source = b.getString(MSG_EXTRA_SOURCE);

			String extension = MimeTypeMap.getFileExtensionFromUrl(url);
			String mimetype = "application/octet-stream";
			if (extension != null) {
				String type = mtm.getMimeTypeFromExtension(extension);
				if (type != null) {
					mimetype = type;
				} else {
					mimetype = "text/html";
				}

				if("text/html".equals(mimetype)) {

					if (source != null) {
							loadDataWithBaseURL(url, source, mimetype, "utf-8", "about:blank");
							return true;
					} else {
						loadUrl(url); // For testing, doesn't normally run.
					}
				}
			}
			handled = true;
			break;
		case MSG_ADD_CONTROL :
			if (isFocusable()) {
				setFocusable(false);
			}
			View v = (View) msg.obj;
			addView(v, offScreen);
			handled = true;
			break;
		}

		return handled;
	}

	public void loadFromSource(String url, String source)
	{
		Message m = handler.obtainMessage(MSG_LOAD_FROM_SOURCE);
		Bundle b = m.getData();
		b.putString(MSG_EXTRA_URL, url);
		b.putString(MSG_EXTRA_SOURCE, source);
		m.sendToTarget();
	}

	public synchronized void addListener(ITitaniumNativeControl control) {
		String id = control.getHtmlId();

		if (id == null) {
			throw new IllegalArgumentException("Control must have a non-null id");
		}
		if (nativeControls == null) {
			nativeControls = new HashMap<String, ITitaniumNativeControl>();
		} else if(nativeControls.containsKey(id)) {
			throw new IllegalArgumentException("Control has already been registered id=" + id);
		}

		nativeControls.put(id, control);
		//requestNativeLayout(id);

		if (DBG) {
			Log.d(LCAT, "Native control linked to html id " + id);
		}
	}

	public synchronized void removeListener(ITitaniumNativeControl control) {
		if (nativeControls != null) {
			String id = control.getHtmlId();
			if (nativeControls.containsKey(id)) {
				nativeControls.remove(id);
				if (DBG) {
					Log.d(LCAT, "Native control unlinked from html id " + id);
				}
			} else {
				Log.w(LCAT, "Attempt to unlink a non registered control. html id " + id);
			}
		}
	}

	public synchronized void requestNativeLayout() {
		if (nativeControls != null && nativeControls.size() > 0) {
			JSONArray a = new JSONArray();
			for (String id : nativeControls.keySet()) {
				a.put(id);
			}
			requestNativeLayout(a);
		} else {
			if (DBG) {
				Log.d(LCAT, "No native controls, layout request ignored");
			}
		}
	}

	public synchronized void requestNativeLayout(String id)
	{
		JSONArray a = new JSONArray();
		a.put(id);
		requestNativeLayout(a);
	}

	protected void requestNativeLayout(JSONArray a)
	{
		StringBuilder sb = new StringBuilder(256);
		sb.append("Titanium.sendLayoutToNative(")
			.append(a.toString())
			.append(")");

		evalJS(sb.toString());
		sb.setLength(0);
	}

	public void updateNativeControls(String json) {
		try {
			JSONObject o = new JSONObject(json);
			for (String id : nativeControls.keySet()) {
				if (o.has(id)) {
					JSONObject pos = o.getJSONObject(id);
					Bundle b = new Bundle(4);
					b.putInt("top", pos.getInt("top"));
					b.putInt("left", pos.getInt("left"));
					b.putInt("width", pos.getInt("width"));
					b.putInt("height", pos.getInt("height"));

					nativeControls.get(id).handleLayoutRequest(b);
				} else {
					Log.w(LCAT, "Position data not found for id " + id);
				}
			}
		} catch (JSONException e) {
			Log.e(LCAT, "Malformed location object from Titanium.API: " + json);
		}
	}


	public void addControl(View control) {
		handler.obtainMessage(MSG_ADD_CONTROL, control).sendToTarget();
	}

	@Override
	protected void onSizeChanged(int w, int h, int ow, int oh) {
		// TODO Auto-generated method stub
		super.onSizeChanged(w, h, ow, oh);
		handler.post(
				new Runnable() {
					public void run() {
						requestNativeLayout();
					}
		        });
	}

}
