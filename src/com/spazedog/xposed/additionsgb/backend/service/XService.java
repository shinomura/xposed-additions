/*
 * This file is part of the Xposed Additions Project: https://github.com/spazedog/xposed-additions
 *  
 * Copyright (c) 2014 Daniel Bergløv
 *
 * Xposed Additions is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * Xposed Additions is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with Xposed Additions. If not, see <http://www.gnu.org/licenses/>
 */

package com.spazedog.xposed.additionsgb.backend.service;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Environment;
import android.os.RemoteException;
import android.util.Log;

import com.spazedog.lib.reflecttools.ReflectTools;
import com.spazedog.lib.reflecttools.ReflectTools.ReflectClass;
import com.spazedog.lib.reflecttools.ReflectTools.ReflectMethod;
import com.spazedog.xposed.additionsgb.Common;

import de.robv.android.xposed.XC_MethodHook;

public final class XService extends IXService.Stub {
	public static final String TAG = XService.class.getName();
	
	public static final int TYPE_EMPTY = 0;
	public static final int TYPE_STRING = 1;
	public static final int TYPE_INTEGER = 2;
	public static final int TYPE_BOOLEAN = 3;
	public static final int TYPE_LIST = 4;
	
	private Context mContextSystem;
	private Context mContextModule;
	
	private Map<String, Object> mCachedData = new HashMap<String, Object>();
	private Map<String, Boolean> mCachedPreserve = new HashMap<String, Boolean>();
	private Boolean mCachedUpdated = false;
	
	private Boolean mIsReady = false;
	
	private ExecutorService mThreadExecuter = Executors.newSingleThreadExecutor();
	
	private static class PREFERENCE {
		private static File ROOT = new File(Environment.getDataDirectory(), "data/" + Common.PACKAGE_NAME);
		private static File DIR = new File(ROOT.getPath(), "shared_prefs");
		private static File FILE = new File(DIR.getPath(), Common.PREFERENCE_FILE + ".xml");
		private static int UID = 1000;
		private static int GID = 1000;
	}
	
	private static class PlaceHolder<T> {
		public T value;
	}
	
	public static void init() {
		if(Common.DEBUG) Log.d(TAG, "Adding Service Hooks");
		
		/*
		 * Plug in the service into Android's service manager
		 */
		XService hooks = new XService();
		ReflectClass ams = ReflectTools.getReflectClass("com.android.server.am.ActivityManagerService");
		
		ams.inject("main", hooks.hook_main);
		ams.inject("systemReady", hooks.hook_systemReady);
		ams.inject("shutdown", hooks.hook_shutdown);
		
		/*
		 * This service is all about the module's shared preferences. So we need to make sure that this
		 * service has access to handle the associated file.
		 */
		if (PREFERENCE.ROOT.exists() && (PREFERENCE.DIR.exists() || PREFERENCE.DIR.mkdir())) {
			File packageList = new File(Environment.getDataDirectory(), "system/packages.list");
			
			if (packageList.exists()) {
				try {
					BufferedReader buffer = new BufferedReader(new FileReader(packageList));
					String line;
					
					while ((line = buffer.readLine()) != null) {
						if (line.startsWith(Common.PACKAGE_NAME + " ")) {
							String[] parts = line.trim().split(" ");
							
							PREFERENCE.UID = Integer.parseInt(parts[1]);
							
							break;
						}
					}
					
					buffer.close();
					
				} catch (Throwable e) { e.printStackTrace(); }
			}
			
			if (!PREFERENCE.FILE.exists()) {
				try {
					FileOutputStream stream = new FileOutputStream(PREFERENCE.FILE);
					ReflectClass xmlUtils = ReflectTools.getReflectClass("com.android.internal.util.XmlUtils");
					ReflectClass fileUtils = ReflectTools.getReflectClass("android.os.FileUtils");
					
					xmlUtils.getMethod("writeMapXml", ReflectTools.MEMBER_MATCH_FAST, HashMap.class, stream.getClass()).invoke(false, new HashMap<String, Object>(), stream);
					fileUtils.getMethod("sync", ReflectTools.MEMBER_MATCH_FAST, stream.getClass()).invoke(false, stream);
				
					try {
						stream.close();
						
					} catch (IOException e) {}
					
				} catch (FileNotFoundException e) { e.printStackTrace(); }
			}
			
			/*
			 * Some Android versions fail when trying to change the ownership. As a fallback we leave the ownership unchanged, and just makes
			 * sure that the file is globally readable and writable.
			 */
			
			ReflectMethod setPermissions = ReflectTools.getReflectClass("android.os.FileUtils")
					.getMethod("setPermissions", ReflectTools.MEMBER_MATCH_FAST, String.class, Integer.TYPE, Integer.TYPE, Integer.TYPE);
			
			if((Integer) setPermissions.invoke(false, PREFERENCE.DIR.getPath(), 0771, PREFERENCE.UID, PREFERENCE.GID) != 0) {
				setPermissions.invoke(false, PREFERENCE.DIR.getPath(), 0771, -1, -1);
			}
			
			if((Integer) setPermissions.invoke(false, PREFERENCE.FILE.getPath(), 0660, PREFERENCE.UID, PREFERENCE.GID) != 0) {
				setPermissions.invoke(false, PREFERENCE.FILE.getPath(), 0660, -1, -1);
			}
		}
	}
	
	protected XC_MethodHook hook_main = new XC_MethodHook() {
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			mContextSystem = (Context) param.getResult();
			
			/*
			 * The original com.android.server.am.ActivityManagerService.main() method
			 * will return the system context, which XposedBridge will have stored in param.getResult().
			 * This is why we inject this as an After Hook.
			 */
			ReflectTools.getReflectClass("android.os.ServiceManager")
				.getMethod("addService", ReflectTools.MEMBER_MATCH_FAST, String.class, XService.class)
				.invoke(false, Common.XSERVICE_NAME, XService.this);
		}
	};
	
	protected XC_MethodHook hook_systemReady = new XC_MethodHook() {
		@Override
		@SuppressWarnings("unchecked")
		protected final void afterHookedMethod(final MethodHookParam param) {
			if(Common.DEBUG) Log.d(TAG, "Starting the service");
			
			try {
				mContextModule = mContextSystem.createPackageContext(Common.PACKAGE_NAME, Context.CONTEXT_RESTRICTED);
				
				/*
				 * Make sure that we have the correct UID when checking access later on
				 */
				PREFERENCE.UID = mContextModule.getApplicationInfo().uid;
				
			} catch (NameNotFoundException e1) { e1.printStackTrace(); }
			
			if (PREFERENCE.FILE.exists()) {
				try {
					BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(PREFERENCE.FILE), 16*1024);
					
					Map<String, Object> data = (Map<String, Object>) ReflectTools.getReflectClass("com.android.internal.util.XmlUtils")
							.getMethod("readMapXml", ReflectTools.MEMBER_MATCH_FAST, inputStream.getClass())
							.invoke(false, inputStream);
					
					try {
						inputStream.close();
						
					} catch (IOException e) {}
					
					if (data != null) {
						/*
						 * TODO:
						 * 		Find a more elegant way of handling the order of array items.
						 */
						Map<String, Map<String, String>> arrays = new HashMap<String, Map<String, String>>();
						
						for (String key : data.keySet()) {
							String indexKey = key;
							
							if (key.indexOf("#") == 0) {
								indexKey = key.substring(key.indexOf(":")+1);
								String index = key.substring(1, key.indexOf(":"));
								Map<String, String> map = arrays.containsKey(indexKey) ? arrays.get(indexKey) : new HashMap<String, String>();

								map.put(index, "@null".equals(data.get(key)) ? null : (String) data.get(key));
								arrays.put(indexKey, map);
								
							} else {
								if(Common.DEBUG) Log.d(TAG, "Caching preference " + key + " = '" + data.get(key) + "'");
								
								mCachedData.put(key, "@null".equals(data.get(key)) ? null : data.get(key));
							}
							
							/*
							 * Mark these keys as 'preserve' so that they are re-written
							 * to the preference file during shutdown
							 */
							if (!mCachedPreserve.containsKey(indexKey)) {
								mCachedPreserve.put(indexKey, true);
							}
						}
						
						for (String key : arrays.keySet()) {
							Map<String, String> arrayMap = arrays.get(key);
							List<String> arrayList = new ArrayList<String>(arrayMap.size());
							
							for (int i=1; i <= arrayMap.size(); i++) {
								if(Common.DEBUG) Log.d(TAG, "Caching preference " + key + "[" + i + "] = '" + arrayMap.get(""+i) + "'");
								
								arrayList.add(arrayMap.get(""+i));
							}
							
							mCachedData.put(key, arrayList);
						}
					}
					
					if(Common.DEBUG) Log.d(TAG, "Cached " + (mCachedData == null ? "NULL" : mCachedData.size()) + " indexes from preference file");
					
				} catch (FileNotFoundException e) { e.printStackTrace(); }
			}
			
			IntentFilter intentFilter = new IntentFilter();
			intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
			intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
			intentFilter.addDataScheme("package");
			
			mContextSystem.registerReceiver(applicationNotifier, intentFilter);
			
			mIsReady = true;
		}
	};
	
	protected BroadcastReceiver applicationNotifier = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			Intent newIntent = new Intent(Common.XSERVICE_BROADCAST);
			newIntent.putExtra("action", "package_change");
			
			mContextSystem.sendBroadcast(newIntent);
		}
	};
	
	protected XC_MethodHook hook_shutdown = new XC_MethodHook() {
		@Override
		protected final void beforeHookedMethod(final MethodHookParam param) {
			if(Common.DEBUG) Log.d(TAG, "Stopping the service");
			
			apply();
		}
	};
	
	private Boolean accessGranted() {
		/*
		 * By default we allow access to Android and our own module. Others will need to include our permission
		 */
		return Binder.getCallingUid() == PREFERENCE.GID || Binder.getCallingUid() == PREFERENCE.UID || 
				mContextSystem.checkCallingPermission(Common.XSERVICE_PERMISSIONS) == PackageManager.PERMISSION_GRANTED;
	}
	
	private void setCached(String key, Object value, Integer preserve) {
		synchronized (mCachedData) {
			if (accessGranted()) {
				mCachedData.put(key, value);
				mCachedPreserve.put(key, preserve < 0 ? (mCachedPreserve.get(key) != null && mCachedPreserve.get(key) == true) : (preserve == 1));
				
				if (mCachedPreserve.get(key)) {
					mCachedUpdated = true;
				}
				
				broadcastChange(key);
			}
		}
	}
	
	private Object getCached(String key, Object defaultValue) {
		if (mCachedData.containsKey(key)) {
			return mCachedData.get(key);
		}
		
		return defaultValue;
	}
	
	@Override
	public void putString(String key, String value, int preserve) throws RemoteException {
		setCached(key, value, preserve);
	}
	
	@Override
	public void putStringArray(String key, List<String> value, int preserve) throws RemoteException {
		setCached(key, value, preserve);
	}
	
	@Override
	public void putInt(String key, int value, int preserve) throws RemoteException {
		setCached(key, value, preserve);
	}
	
	@Override
	public void putBoolean(String key, boolean value, int preserve) throws RemoteException {
		setCached(key, value, preserve);
	}

	@Override
	public String getString(String key, String defaultValue) throws RemoteException {
		return (String) getCached(key, defaultValue);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<String> getStringArray(String key, List<String> defaultValue) throws RemoteException {
		return (List<String>) getCached(key, defaultValue);
	}

	@Override
	public int getInt(String key, int defaultValue) throws RemoteException {
		return (Integer) getCached(key, defaultValue);
	}

	@Override
	public boolean getBoolean(String key, boolean defaultValue) throws RemoteException {
		return (Boolean) getCached(key, defaultValue);
	}
	
	@Override
	public boolean remove(String key) {
		if (mCachedData.containsKey(key)) {
			Object value = mCachedData.get(key);
			Boolean preserved = mCachedPreserve.get(key);
			
			mCachedData.remove(key);
			mCachedPreserve.remove(key);
			
			broadcastChange(key);
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public int getType(String key) {
		if (mCachedData.containsKey(key)) {
			Object object = mCachedData.get(key);
			
			if (object instanceof Integer) 
				return TYPE_INTEGER;
			else if (object instanceof Boolean) 
				return TYPE_BOOLEAN;
			else if (object instanceof List) 
				return TYPE_LIST;
			else
				return TYPE_STRING;
		}
		
		return TYPE_EMPTY;
	}
	
	@Override
	public List<String> getKeys() {
		ArrayList<String> list = new ArrayList<String>();
		
		for (String key : mCachedData.keySet()) {
			list.add(key);
		}
		
		return list;
	}
	
	@Override 
	public List<String> getPreservedKeys() {
		ArrayList<String> list = new ArrayList<String>();
		
		for (String key : mCachedData.keySet()) {
			Boolean check = mCachedPreserve.get(key);
			
			if (check != null && check == true) {
				list.add(key);
			}
		}
		
		return list;
	}
	
	@Override
	public boolean apply() {
		return write(false);
	}
	
	@Override
	public void commit() {
		write(true);
	}
	
	@SuppressWarnings("unchecked")
	private Boolean write(Boolean async) {
		synchronized (mCachedData) {
			if (mCachedUpdated) {
				if(Common.DEBUG) Log.d(TAG, "Writing preferences to file");
				
				mCachedUpdated = false;
				
				final PlaceHolder<Boolean> status = new PlaceHolder<Boolean>();
				final Map<String, Object> data = new HashMap<String, Object>();
				final Runnable writeRunnable = new Runnable() {
					@Override
					public void run() {
						synchronized (XService.this) {
							try {
								if(Common.DEBUG) Log.d(TAG, "Writing " + data.size() + " settings to the preference file");
								
								FileOutputStream stream = new FileOutputStream(PREFERENCE.FILE);
								ReflectClass xmlUtils = ReflectTools.getReflectClass("com.android.internal.util.XmlUtils");
								ReflectClass fileUtils = ReflectTools.getReflectClass("android.os.FileUtils");
								
								xmlUtils.getMethod("writeMapXml", ReflectTools.MEMBER_MATCH_FAST, HashMap.class, stream.getClass()).invoke(false, data, stream);
								fileUtils.getMethod("sync", ReflectTools.MEMBER_MATCH_FAST, stream.getClass()).invoke(false, stream);
								
								try {
									stream.close();
									
								} catch (IOException e) {}
								
								status.value = true;
								
							} catch (Throwable e) { 
								if(Common.DEBUG) Log.d(TAG, "Failed to write preferences to file");
								
								status.value = false; 
								e.printStackTrace(); 
							}
						}
					}
				};
				
				for (String key : mCachedPreserve.keySet()) {
					if (mCachedPreserve.get(key) == true) {
						Object content = mCachedData.get(key);
						
						if (content instanceof List) {
							int i = 0;
							
							for (String value : ((ArrayList<String>) content)) {
								data.put("#" + (i += 1) + ":" + key, value == null ? "@null" : value);
							}
							
						} else {
							data.put(key.indexOf("#") == 0 ? key.substring(1) : key, content == null ? "@null" : content);
						}
					}
				}
				
				if (async) {
					mThreadExecuter.execute(writeRunnable);
					
				} else {
					writeRunnable.run();
					
					return status.value;
				}
			}
			
			return true;
		}
	}
	
	@Override
	public boolean isUnlocked() {
		return mContextSystem.getPackageManager()
				.checkSignatures(Common.PACKAGE_NAME, Common.PACKAGE_NAME_PRO) == PackageManager.SIGNATURE_MATCH;
	}
	
	@Override
	public boolean isReady() {
		return mIsReady;
	}
	
	@SuppressWarnings("unchecked")
	private void broadcastChange(String key) {
		Intent intent = new Intent(Common.XSERVICE_BROADCAST);
		intent.putExtra("action", "preference_change");
		intent.putExtra("key", key);
		
		mContextSystem.sendBroadcast(intent);
	}
}
