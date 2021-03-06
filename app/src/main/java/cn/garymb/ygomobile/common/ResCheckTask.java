package cn.garymb.ygomobile.common;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.List;

import cn.garymb.ygomobile.R;
import cn.garymb.ygomobile.StaticApplication;
import cn.garymb.ygomobile.controller.Controller;
import cn.garymb.ygomobile.core.BaseTask.TaskStatusCallback;
import cn.garymb.ygomobile.core.SimpleDownloadTask;
import cn.garymb.ygomobile.data.wrapper.IBaseJob;
import cn.garymb.ygomobile.data.wrapper.SimpleDownloadJob;
import cn.garymb.ygomobile.model.data.ResourcesConstants;
import cn.garymb.ygomobile.setting.Settings;
import cn.garymb.ygomobile.utils.DatabaseUtils;
import cn.garymb.ygomobile.utils.FileOpsUtils;
import cn.garymb.ygomobile.widget.ProgressUpdateDialog;
import cn.garymb.ygomobile.widget.ProgressUpdateDialogController;
import de.greenrobot.event.EventBus;

public class ResCheckTask extends AsyncTask<Void, Integer, Integer> {

    public static final int RES_ERR_NO_FONT_AVAIL = -1;

    private static final int RES_CHECK_TYPE_MESSAGE_UPDATE = 1;
    private static final int RES_CHECK_TYPE_PROGRESS_UPDATE = 2;
    private static final int RES_CHECK_TYPE_DOWNLOAD_HINT = 3;

    public interface ResCheckListener {
        void onResCheckFinished(int result);
    }

    private static final int CORE_CONFIG_COPY_COUNT = 3;
    private static final int ASSET_EXTRA_VERSION = 2;

    private static final String TAG = "ResCheckTask";
    private StaticApplication mApp;
    private Activity mContext;

    private SharedPreferences mSettingsPref;

    private ProgressDialog mWaitDialog;
    private ProgressUpdateDialog mProgressUpdateDialog;

    private ResCheckListener mListener;

    private Object mLock = new Object();

    public ResCheckTask(Activity context) {
        this(context, null);
    }

    public ResCheckTask(Activity context, ResCheckListener listener) {
        mContext = context;
        mApp = StaticApplication.get();
        mSettingsPref = PreferenceManager.getDefaultSharedPreferences(context);
        View content = mContext.getLayoutInflater().inflate(R.layout.image_dl_dialog, null);
        mProgressUpdateDialog = new ProgressUpdateDialog(mContext, null, content, null);
        mWaitDialog = new ProgressDialog(context);
        mWaitDialog.setMessage(mApp.getResources().getString(R.string.checking_resource));
        mWaitDialog.setCancelable(false);
        mProgressUpdateDialog.setCancelable(false);
        setResCheckListener(listener);
    }

    public void setResCheckListener(ResCheckListener listener) {
        mListener = listener;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        mWaitDialog.show();
    }

    @Override
    protected void onPostExecute(final Integer result) {
        super.onPostExecute(result);
        mWaitDialog.dismiss();
        if (mListener != null) {
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    mListener.onResCheckFinished(result);
                    mListener = null;
                }
            });
        }
    }

    @Override
    protected Integer doInBackground(Void... params) {
        String newConfigVersion = null, currentConfigVersion = null;
        SharedPreferences sp = mApp.getSharedPreferences(Constants.PREF_FILE_COMMON, Context.MODE_PRIVATE);
        currentConfigVersion = sp.getString(Constants.PREF_KEY_DATA_VERSION, "");
        try {
            newConfigVersion = mApp.getAssets().list(Constants.CORE_CONFIG_PATH)[0];
        } catch (IOException e) {
            e.printStackTrace();
        }
        boolean needsUpdate = !currentConfigVersion.equals(newConfigVersion);
        publishProgress(RES_CHECK_TYPE_MESSAGE_UPDATE, R.string.updating_fonts);
        initFontList(mApp.getFontDirPath());
        saveCoreConfigVersion(newConfigVersion);
        publishProgress(RES_CHECK_TYPE_MESSAGE_UPDATE, R.string.updating_decks);
        checkAndCopyNewDeckFiles(needsUpdate);
        publishProgress(RES_CHECK_TYPE_MESSAGE_UPDATE, R.string.updating_config);
        checkAndCopyCoreConfig(needsUpdate);
        publishProgress(RES_CHECK_TYPE_MESSAGE_UPDATE, R.string.updating_skin);
        checkAndCopyGameSkin(mApp.getCoreSkinPath());
        publishProgress(RES_CHECK_TYPE_MESSAGE_UPDATE, R.string.updating_single);
        checkAndrCopyNewSingleFiles(needsUpdate);
        publishProgress(R.string.updating_card_data_base);
        DatabaseUtils.checkAndCopyFromInternalDatabase(mApp, mApp.getDataBasePath(), needsUpdate);
        publishProgress(RES_CHECK_TYPE_MESSAGE_UPDATE, R.string.updating_scripts);
        checkAndCopyScripts(needsUpdate);
        publishProgress(RES_CHECK_TYPE_MESSAGE_UPDATE, R.string.updating_dirs);
        checkDirs();
        return 0;
    }

    private void checkDirs() {
        String[] dirs = {"pics","script", "single", "deck", "replay", "fonts"};
        File dirFile = null;
        for (String dir : dirs) {
            dirFile = new File(mApp.getResourcePath(), dir);
            if (!dirFile.exists()) {
                dirFile.mkdirs();
            }
        }

    }

    private void checkAndCopyScripts(boolean isUpdateNeeded) {
        File scriptDir = new File(mApp.getCompatExternalFilesDir());
        File scriptFile = new File(scriptDir, Constants.CORE_SCRIPTS_ZIP);
        if (!scriptDir.exists()) {
            scriptDir.mkdirs();
        }
        if (isUpdateNeeded || !scriptFile.exists()) {
            int assetcopycount = 0;
            while (assetcopycount++ < CORE_CONFIG_COPY_COUNT) {
                try {
                    FileOpsUtils.assetsCopy(mApp, Constants.CORE_SCRIPTS_ZIP, scriptFile.toString(), false);
                } catch (IOException e) {
                    Log.w(TAG, "copy scripts failed, retry count = " + assetcopycount);
                    continue;
                }
            }
        }
    }

    @Override
    protected void onProgressUpdate(Integer... values) {
        super.onProgressUpdate(values);
        if (values[0] == RES_CHECK_TYPE_MESSAGE_UPDATE) {
            mWaitDialog.setMessage(mApp.getResources().getString(values[1]));
        } else if (values[0] == RES_CHECK_TYPE_PROGRESS_UPDATE) {
            if (values[1] == 1) {
                mWaitDialog.dismiss();
                if (!mProgressUpdateDialog.isShowing()) {
                    mProgressUpdateDialog.show();
                }
            } else {
                if (mProgressUpdateDialog != null) {
                    mProgressUpdateDialog.dismiss();
                }
                mWaitDialog.show();
            }
        } else if (values[0] == RES_CHECK_TYPE_DOWNLOAD_HINT) {
            Toast.makeText(mContext, R.string.font_download_via_mobile_not_allowed, Toast.LENGTH_SHORT).show();
        }
    }


    private void checkAndrCopyNewSingleFiles(boolean isUpdateNeeded) {
        File deckDir = new File(mApp.getResourcePath(), Constants.CORE_SINGLE_PATH);
        if (!deckDir.exists()) {
            deckDir.mkdirs();
        }
        if (isUpdateNeeded) {
            int assetcopycount = 0;
            while (assetcopycount++ < CORE_CONFIG_COPY_COUNT) {
                try {
                    FileOpsUtils.assetsCopy(mApp, Constants.CORE_SINGLE_PATH, deckDir.toString(), false);
                } catch (IOException e) {
                    Log.w(TAG, "copy single files failed, retry count = " + assetcopycount);
                    continue;
                }
            }
        }

    }

    private void checkAndCopyNewDeckFiles(boolean isUpdateNeeded) {
        File deckDir = new File(mApp.getResourcePath(), Constants.CORE_DECK_PATH);
        if (!deckDir.exists()) {
            deckDir.mkdirs();
        }
        if (isUpdateNeeded) {
            int assetcopycount = 0;
            while (assetcopycount++ < CORE_CONFIG_COPY_COUNT) {
                try {
                    FileOpsUtils.assetsCopy(mApp, Constants.CORE_DECK_PATH, deckDir.toString(), false);
                } catch (IOException e) {
                    Log.w(TAG, "copy deck files failed, retry count = " + assetcopycount);
                    continue;
                }
            }
        }

    }

    private boolean initFontList(String dir) {
        File systemFontDir = new File(dir);
        String[] fonts = systemFontDir.list();
        for (String name : fonts) {
            mApp.AddFontPath(new File(systemFontDir, name).toString());
        }
        // load extra font
        File extraDir = new File(mApp.getDefaultResPath() + Constants.FONT_DIRECTORY);
        if (extraDir.exists()) {
            fonts = extraDir.list(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String filename) {
                    return filename.endsWith("ttf") || filename.endsWith("TTF") || filename.endsWith(".otf")
                            || filename.endsWith(".OTF");
                }
            });
            boolean isFontHit = false;
            String currentFont;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                currentFont = mSettingsPref.getString(Settings.KEY_PREF_GAME_FONT_NAME,
                        StaticApplication.get().getFontDefault());
            } else {
                currentFont = mSettingsPref.getString(Settings.KEY_PREF_GAME_FONT_NAME, fonts.length == 0 ? "" : new File(extraDir, fonts[0]).toString());
            }
            for (String name : fonts) {
                mApp.AddFontPath(new File(extraDir, name).toString());
            }
            List<String> fontslist = mApp.getFontList();
            for (String name : fontslist) {
                if (currentFont.equals(name)) {
                    isFontHit = true;
                    break;
                }
            }
            // for update compatability.
            if (isFontHit) {
                mSettingsPref.edit().putString(Settings.KEY_PREF_GAME_FONT_NAME, currentFont).commit();
            } else {
                requestDownloadExtraFont(extraDir);
            }
        } else {
            File defaultFont = new File(StaticApplication.get().getFontDefault());
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && defaultFont.exists()) {
                mSettingsPref.edit().putString(Settings.KEY_PREF_GAME_FONT_NAME, StaticApplication.get().getFontDefault()).commit();
            } else {
                requestDownloadExtraFont(extraDir);
            }
        }
        return true;
    }

    private void requestDownloadExtraFont(File extraDir) {
        if (!Controller.peekInstance().isWifiConnected() && StaticApplication.get().getMobileNetworkPref()) {
            publishProgress(RES_CHECK_TYPE_DOWNLOAD_HINT);
            return;
        }
        final File wqyFont = new File(extraDir, "ygo.ttf");
        SimpleDownloadJob job = new SimpleDownloadJob(FileDownloadHelper.getPrivateDownloadUrl(ResourcesConstants.FONTS_DOWNLOAD_URL), wqyFont.getAbsolutePath());
        SimpleDownloadTask task = new SimpleDownloadTask(StaticApplication.get().getOkHttpClient());
        ProgressUpdateDialogController c = (ProgressUpdateDialogController) mProgressUpdateDialog.getController();
        EventBus.getDefault().register(c);
        c.setCurrentTask(task);
        task.setTaskStatusCallback(new TaskStatusCallback() {
            @Override
            public void onTaskFinish(int type, int result) {
                synchronized (mLock) {
                    mLock.notifyAll();
                    publishProgress(RES_CHECK_TYPE_PROGRESS_UPDATE, 0);
                    EventBus.getDefault().unregister(mProgressUpdateDialog.getController());
                    mSettingsPref.edit().putString(Settings.KEY_PREF_GAME_FONT_NAME, wqyFont.getAbsolutePath())
                            .commit();
                    addToFontList(wqyFont.getAbsolutePath());
                }
                if (result != IBaseJob.STATUS_SUCCESS) {
                    mContext.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(mContext, R.string.font_download_failed, Toast.LENGTH_SHORT);
                        }
                    });
                }
            }
        });
        task.addJob(job);
        task.execute();
        publishProgress(RES_CHECK_TYPE_PROGRESS_UPDATE, 1);
        synchronized (mLock) {
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        publishProgress(RES_CHECK_TYPE_MESSAGE_UPDATE, R.string.no_avail_font_file);
    }

    protected void addToFontList(String absolutePath) {
        List<String> fonts = mApp.getFontList();
        fonts.add(absolutePath);
    }

    private void saveCoreConfigVersion(String version) {
        SharedPreferences sp = mApp.getSharedPreferences(Constants.PREF_FILE_COMMON, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(Constants.PREF_KEY_DATA_VERSION, version);
        editor.commit();
        mApp.setCoreConfigVersion(version);
    }

    private void checkAndCopyCoreConfig(boolean needsUpdate) {
        File internalCacheDir = mApp.getCacheDir();
        if (internalCacheDir != null) {
            File coreConfigDir = new File(internalCacheDir, Constants.CORE_CONFIG_PATH);
            if (coreConfigDir != null && coreConfigDir.exists() && coreConfigDir.isDirectory() && !needsUpdate) {
                return;
            }
            if (needsUpdate || (coreConfigDir != null && coreConfigDir.exists() && !coreConfigDir.isDirectory())) {
                coreConfigDir.delete();
            }
            // we need to copy from configs from assets;
            int assetcopycount = 0;
            while (assetcopycount++ < CORE_CONFIG_COPY_COUNT) {
                try {
                    FileOpsUtils.assetsCopy(mApp, Constants.CORE_CONFIG_PATH, coreConfigDir.getAbsolutePath(), false);
                    break;
                } catch (IOException e) {
                    Log.w(TAG, "copy core config failed, retry count = " + assetcopycount);
                    continue;
                }
            }
        }
    }

    private void checkAndCopyGameSkin(String path) {
        File coreSkinDir = new File(path);
        if (coreSkinDir != null && coreSkinDir.exists() && coreSkinDir.isDirectory()) {
            return;
        }
        if (coreSkinDir != null && coreSkinDir.exists() && !coreSkinDir.isDirectory()) {
            coreSkinDir.delete();
        }
        // we need to copy from configs from assets;
        int assetcopycount = 0;
        while (assetcopycount++ < CORE_CONFIG_COPY_COUNT) {
            try {
                FileOpsUtils.assetsCopy(mApp, Constants.CORE_SKIN_PATH, coreSkinDir.getAbsolutePath(), false);
                break;
            } catch (IOException e) {
                Log.w(TAG, "copy core skin failed, retry count = " + assetcopycount);
                continue;
            }
        }
    }

}
