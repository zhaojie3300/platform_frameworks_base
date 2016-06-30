/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.retaildemo;

import android.app.ActivityManagerInternal;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RetailDemoModeServiceInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import com.android.internal.os.BackgroundThread;
import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.am.ActivityManagerService;
import com.android.server.retaildemo.UserInactivityCountdownDialog.OnCountDownExpiredListener;

import java.io.File;
import java.util.ArrayList;

public class RetailDemoModeService extends SystemService {
    private static final boolean DEBUG = false;

    private static final String TAG = RetailDemoModeService.class.getSimpleName();
    private static final String DEMO_USER_NAME = "Demo";
    private static final String ACTION_RESET_DEMO = "com.android.server.am.ACTION_RESET_DEMO";

    private static final int MSG_TURN_SCREEN_ON = 0;
    private static final int MSG_INACTIVITY_TIME_OUT = 1;
    private static final int MSG_START_NEW_SESSION = 2;

    private static final long SCREEN_WAKEUP_DELAY = 2500;
    private static final long USER_INACTIVITY_TIMEOUT = 30000;
    private static final long WARNING_DIALOG_TIMEOUT = 6000;
    private static final long MILLIS_PER_SECOND = 1000;

    private static final int[] VOLUME_STREAMS_TO_MUTE = {
            AudioSystem.STREAM_RING,
            AudioSystem.STREAM_MUSIC
    };

    // Tron Vars
    private static final String DEMO_SESSION_COUNT = "retail_demo_session_count";
    private static final String DEMO_SESSION_DURATION = "retail_demo_session_duration";

    boolean mDeviceInDemoMode = false;
    int mCurrentUserId = UserHandle.USER_SYSTEM;
    private ActivityManagerService mAms;
    private ActivityManagerInternal mAmi;
    private AudioManager mAudioManager;
    private NotificationManager mNm;
    private UserManager mUm;
    private PowerManager mPm;
    private PowerManager.WakeLock mWakeLock;
    Handler mHandler;
    private ServiceThread mHandlerThread;
    private PendingIntent mResetDemoPendingIntent;
    private CameraManager mCameraManager;
    private String[] mCameraIdsWithFlash;
    private Configuration mSystemUserConfiguration;

    final Object mActivityLock = new Object();
    // Whether the newly created demo user has interacted with the screen yet
    @GuardedBy("mActivityLock")
    boolean mUserUntouched;
    @GuardedBy("mActivityLock")
    long mFirstUserActivityTime;
    @GuardedBy("mActivityLock")
    long mLastUserActivityTime;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mDeviceInDemoMode) {
                return;
            }
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    mHandler.removeMessages(MSG_TURN_SCREEN_ON);
                    mHandler.sendEmptyMessageDelayed(MSG_TURN_SCREEN_ON, SCREEN_WAKEUP_DELAY);
                    break;
                case ACTION_RESET_DEMO:
                    mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
                    break;
            }
        }
    };

    final class MainHandler extends Handler {

        MainHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TURN_SCREEN_ON:
                    if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                    mWakeLock.acquire();
                    break;
                case MSG_INACTIVITY_TIME_OUT:
                    if (isDemoLauncherDisabled()) {
                        Slog.i(TAG, "User inactivity timeout reached");
                        showInactivityCountdownDialog();
                    }
                    break;
                case MSG_START_NEW_SESSION:
                    if (DEBUG) {
                        Slog.d(TAG, "Switching to a new demo user");
                    }
                    removeMessages(MSG_START_NEW_SESSION);
                    removeMessages(MSG_INACTIVITY_TIME_OUT);
                    if (mCurrentUserId != UserHandle.USER_SYSTEM) {
                        logSessionDuration();
                    }
                    final UserInfo demoUser = getUserManager().createUser(DEMO_USER_NAME,
                            UserInfo.FLAG_DEMO | UserInfo.FLAG_EPHEMERAL);
                    if (demoUser != null) {
                        setupDemoUser(demoUser);
                        getActivityManager().switchUser(demoUser.id);
                    }
                    break;
            }
        }
    }

    private void showInactivityCountdownDialog() {
        UserInactivityCountdownDialog dialog = new UserInactivityCountdownDialog(getContext(),
                WARNING_DIALOG_TIMEOUT, MILLIS_PER_SECOND);
        dialog.setNegativeButtonClickListener(null);
        dialog.setPositiveButtonClickListener(new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
            }
        });
        dialog.setOnCountDownExpiredListener(new OnCountDownExpiredListener() {
            @Override
            public void onCountDownExpired() {
                mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
            }
        });
        dialog.show();
    }

    public RetailDemoModeService(Context context) {
        super(context);
        synchronized (mActivityLock) {
            mFirstUserActivityTime = mLastUserActivityTime = SystemClock.uptimeMillis();
        }
    }

    private Notification createResetNotification() {
        return new Notification.Builder(getContext())
                .setContentTitle(getContext().getString(R.string.reset_retail_demo_mode_title))
                .setContentText(getContext().getString(R.string.reset_retail_demo_mode_text))
                .setOngoing(true)
                .setSmallIcon(R.drawable.platlogo)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(getResetDemoPendingIntent())
                .setColor(getContext().getColor(R.color.system_notification_accent_color))
                .build();
    }

    private PendingIntent getResetDemoPendingIntent() {
        if (mResetDemoPendingIntent == null) {
            Intent intent = new Intent(ACTION_RESET_DEMO);
            mResetDemoPendingIntent = PendingIntent.getBroadcast(getContext(), 0, intent, 0);
        }
        return mResetDemoPendingIntent;
    }

    boolean isDemoLauncherDisabled() {
        IPackageManager pm = AppGlobals.getPackageManager();
        int enabledState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        String demoLauncherComponent = getContext().getResources()
                .getString(R.string.config_demoModeLauncherComponent);
        try {
            enabledState = pm.getComponentEnabledSetting(
                    ComponentName.unflattenFromString(demoLauncherComponent),
                    mCurrentUserId);
        } catch (RemoteException exc) {
            Slog.e(TAG, "Unable to talk to Package Manager", exc);
        }
        return enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setupDemoUser(UserInfo userInfo) {
        UserManager um = getUserManager();
        UserHandle user = UserHandle.of(userInfo.id);
        LockPatternUtils lockPatternUtils = new LockPatternUtils(getContext());
        lockPatternUtils.setLockScreenDisabled(true, userInfo.id);
        um.setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, true, user);
        um.setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true, user);
        um.setUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, true, user);
        um.setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, true, user);
        Settings.Secure.putIntForUser(getContext().getContentResolver(),
                Settings.Secure.SKIP_FIRST_USE_HINTS, 1, userInfo.id);
    }

    void logSessionDuration() {
        final int sessionDuration;
        synchronized (mActivityLock) {
            sessionDuration = (int) ((mLastUserActivityTime - mFirstUserActivityTime) / 1000);
        }
        MetricsLogger.histogram(getContext(), DEMO_SESSION_DURATION, sessionDuration);
    }

    private ActivityManagerService getActivityManager() {
        if (mAms == null) {
            mAms = (ActivityManagerService) ActivityManagerNative.getDefault();
        }
        return mAms;
    }

    private UserManager getUserManager() {
        if (mUm == null) {
            mUm = getContext().getSystemService(UserManager.class);
        }
        return mUm;
    }

    private AudioManager getAudioManager() {
        if (mAudioManager == null) {
            mAudioManager = getContext().getSystemService(AudioManager.class);
        }
        return mAudioManager;
    }

    private void registerSettingsChangeObserver() {
        final Uri deviceDemoModeUri = Settings.Global.getUriFor(Settings.Global.DEVICE_DEMO_MODE);
        final Uri deviceProvisionedUri = Settings.Global.getUriFor(
                Settings.Global.DEVICE_PROVISIONED);
        final ContentResolver cr = getContext().getContentResolver();
        final ContentObserver deviceDemoModeSettingObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                if (deviceDemoModeUri.equals(uri)) {
                    mDeviceInDemoMode = UserManager.isDeviceInDemoMode(getContext());
                    if (mDeviceInDemoMode) {
                        mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
                    } else if (mWakeLock.isHeld()) {
                        mWakeLock.release();
                    }
                }
                // If device is provisioned and left demo mode - run the cleanup in demo folder
                if (!mDeviceInDemoMode && isDeviceProvisioned()) {
                    // Run on the bg thread to not block the fg thread
                    BackgroundThread.getHandler().post(new Runnable() {
                        @Override
                        public void run() {
                            if (!deleteDemoFolderContents()) {
                                Slog.w(TAG, "Failed to delete demo folder contents");
                            }
                        }
                    });
                }
            }
        };
        cr.registerContentObserver(deviceDemoModeUri, false, deviceDemoModeSettingObserver,
                UserHandle.USER_SYSTEM);
        cr.registerContentObserver(deviceProvisionedUri, false, deviceDemoModeSettingObserver,
                UserHandle.USER_SYSTEM);
    }

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(
                getContext().getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    private boolean deleteDemoFolderContents() {
        final File dir = Environment.getDataPreloadsDemoDirectory();
        Slog.i(TAG, "Deleting contents of " + dir);
        return FileUtils.deleteContents(dir);
    }

    private void registerBroadcastReceiver() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(ACTION_RESET_DEMO);
        getContext().registerReceiver(mBroadcastReceiver, filter);
    }

    private String[] getCameraIdsWithFlash() {
        ArrayList<String> cameraIdsList = new ArrayList<String>();
        try {
            for (String cameraId : mCameraManager.getCameraIdList()) {
                CameraCharacteristics c = mCameraManager.getCameraCharacteristics(cameraId);
                if (Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                    cameraIdsList.add(cameraId);
                }
            }
        } catch (CameraAccessException e) {
            Slog.e(TAG, "Unable to access camera while getting camera id list", e);
        }
        return cameraIdsList.toArray(new String[cameraIdsList.size()]);
    }

    private void turnOffAllFlashLights() {
        for (String cameraId : mCameraIdsWithFlash) {
            try {
                mCameraManager.setTorchMode(cameraId, false);
            } catch (CameraAccessException e) {
                Slog.e(TAG, "Unable to access camera " + cameraId + " while turning off flash", e);
            }
        }
    }

    private void muteVolumeStreams() {
        for (int stream : VOLUME_STREAMS_TO_MUTE) {
            getAudioManager().setStreamVolume(stream, getAudioManager().getStreamMinVolume(stream),
                    0);
        }
    }

    private Configuration getSystemUsersConfiguration() {
        if (mSystemUserConfiguration == null) {
            Settings.System.getConfiguration(getContext().getContentResolver(),
                    mSystemUserConfiguration = new Configuration());
        }
        return mSystemUserConfiguration;
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.d(TAG, "Service starting up");
        }
        mHandlerThread = new ServiceThread(TAG, android.os.Process.THREAD_PRIORITY_FOREGROUND,
                false);
        mHandlerThread.start();
        mHandler = new MainHandler(mHandlerThread.getLooper());
        publishLocalService(RetailDemoModeServiceInternal.class, mLocalService);
    }

    @Override
    public void onBootPhase(int bootPhase) {
        if (bootPhase != PHASE_THIRD_PARTY_APPS_CAN_START) {
            return;
        }
        mPm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        mAmi = LocalServices.getService(ActivityManagerInternal.class);
        mWakeLock = mPm
                .newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
        mNm = NotificationManager.from(getContext());
        mCameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
        mCameraIdsWithFlash = getCameraIdsWithFlash();

        if (UserManager.isDeviceInDemoMode(getContext())) {
            mDeviceInDemoMode = true;
            mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
        }
        registerSettingsChangeObserver();
        registerBroadcastReceiver();
    }

    @Override
    public void onSwitchUser(int userId) {
        if (!mDeviceInDemoMode) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "onSwitchUser: " + userId);
        }
        final UserInfo ui = getUserManager().getUserInfo(userId);
        if (!ui.isDemo()) {
            Slog.wtf(TAG, "Should not allow switch to non-demo user in demo mode");
            return;
        }
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
        mCurrentUserId = userId;
        mAmi.updatePersistentConfigurationForUser(getSystemUsersConfiguration(), userId);
        turnOffAllFlashLights();
        muteVolumeStreams();
        mNm.notifyAsUser(TAG, 1, createResetNotification(), UserHandle.of(userId));

        synchronized (mActivityLock) {
            mUserUntouched = true;
        }
        MetricsLogger.count(getContext(), DEMO_SESSION_COUNT, 1);
        mHandler.removeMessages(MSG_INACTIVITY_TIME_OUT);
    }

    private RetailDemoModeServiceInternal mLocalService = new RetailDemoModeServiceInternal() {
        private static final long USER_ACTIVITY_DEBOUNCE_TIME = 2000;

        @Override
        public void onUserActivity() {
            if (!mDeviceInDemoMode) {
                return;
            }
            long timeOfActivity = SystemClock.uptimeMillis();
            synchronized (mActivityLock) {
                if (timeOfActivity < mLastUserActivityTime + USER_ACTIVITY_DEBOUNCE_TIME) {
                    return;
                }
                mLastUserActivityTime = timeOfActivity;
                if (mUserUntouched && isDemoLauncherDisabled()) {
                    Slog.d(TAG, "retail_demo first touch");
                    mUserUntouched = false;
                    mFirstUserActivityTime = timeOfActivity;
                }
            }
            mHandler.removeMessages(MSG_INACTIVITY_TIME_OUT);
            mHandler.sendEmptyMessageDelayed(MSG_INACTIVITY_TIME_OUT, USER_INACTIVITY_TIMEOUT);
        }
    };
}
