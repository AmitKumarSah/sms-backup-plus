package com.zegoggles.smssync;

import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.content.Context;
import android.util.Log;
import com.fsck.k9.mail.*;
import com.fsck.k9.mail.internet.BinaryTempFileBody;
import com.zegoggles.smssync.CursorToMessage.DataType;
import com.zegoggles.smssync.CursorToMessage.Headers;

import org.apache.commons.io.IOUtils;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.io.File;
import java.io.FilenameFilter;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.NoSuchMethodException;
import java.lang.ClassNotFoundException;

import org.thialfihar.android.apg.utils.ApgCon;

import static com.zegoggles.smssync.CursorToMessage.Headers.*;
import static com.zegoggles.smssync.ServiceBase.SmsSyncState.*;

import static com.zegoggles.smssync.App.*;

public class SmsRestoreService extends ServiceBase {
    private static int sCurrentRestoredItems;
    private static int sItemsToRestoreCount;

    public static int sRestoredCount, sDuplicateCount;

    private static boolean sIsRunning = false;
    private static boolean sCanceled = false;

    private Class telephonyThreads;
    private Method getOrCreateThreadId;
    private boolean threadsAvailable = true;

    private static final int MAX_THREAD_CACHE_SIZE = 500;
    private Map<String, Long> mThreadIdCache =
          new LinkedHashMap<String, Long>(MAX_THREAD_CACHE_SIZE+1, .75F, true) {
          @Override
          public boolean removeEldestEntry(Map.Entry eldest) {
            return size() > MAX_THREAD_CACHE_SIZE;
          }
      };

    private ApgCon mEnc;

    public static void cancel() {
        sCanceled = true;
    }

    public static boolean isWorking() {
        return sIsRunning;
    }

    public static int getCurrentRestoredItems() {
        return sCurrentRestoredItems;
    }

    public static int getItemsToRestoreCount() {
        return sItemsToRestoreCount;
    }

    class RestoreTask extends AsyncTask<Integer, SmsSyncState, Integer> {
        private Set<String> insertedIds = new HashSet<String>();
        private Set<String> uids = new HashSet<String>();
        private int max;
        protected java.lang.Integer doInBackground(Integer... params) {
            this.max = params.length > 0 ? params[0] : -1;
            final boolean starredOnly = PrefStore.isRestoreStarredOnly(SmsRestoreService.this);

            try {
                acquireLocks(false);
                sIsRunning = true;

                SmsRestoreService.this.mThreadIdCache.clear();

                publishProgress(LOGIN);
                ImapStore.BackupFolder folder = getSMSBackupFolder();

                publishProgress(CALC);

                final Message[] msgs = folder.getMessagesSince(null, max, starredOnly);

                sItemsToRestoreCount = max <= 0 ? msgs.length : Math.min(msgs.length, max);

                long lastPublished = System.currentTimeMillis();
                for (int i = 0; i < sItemsToRestoreCount && !sCanceled; i++) {

                    importMessage(msgs[i]);
                    sCurrentRestoredItems = i;

                    // help GC
                    msgs[i] = null;

                    if (System.currentTimeMillis() - lastPublished > 1000) {
                        // don't publish too often or we get ANRs
                        publishProgress(RESTORE);
                        lastPublished = System.currentTimeMillis();
                    }

                    if (i % 50 == 0) {
                      //clear cache periodically otherwise SD card fills up
                      clearCache();
                    }
                }
                publishProgress(UPDATING_THREADS);
                updateAllThreads(false);

                return insertedIds.size();
            } catch (ConnectivityErrorException e) {
                lastError = translateException(e);
                publishProgress(CONNECTIVITY_ERROR);
                return null;
            } catch (AuthenticationFailedException e) {
                publishProgress(AUTH_FAILED);
                return null;
            } catch (MessagingException e) {
                Log.e(TAG, "error", e);
                lastError = translateException(e);
                publishProgress(GENERAL_ERROR);
                return null;
            } catch (IllegalStateException e) {
                // usually memory problems (Couldn't init cursor window)
                lastError = translateException(e);
                publishProgress(GENERAL_ERROR);
                return null;
            } finally {
                releaseLocks();
           }
        }

        @Override
        protected void onPostExecute(Integer result) {
            if (sCanceled) {
                Log.d(TAG, "restore canceled by user");
                publishProgress(CANCELED_RESTORE);
            } else if (result != null) {
                Log.d(TAG, "finished (" + result + "/" + uids.size() + ")");
                sRestoredCount = result;
                sDuplicateCount = uids.size() - result;
                publishProgress(FINISHED_RESTORE);
            }
            sCanceled = false;
            sIsRunning = false;
        }

        @Override
        protected void onProgressUpdate(SmsSyncState... progress) {
          if (progress == null || progress.length == 0) return;
          if (smsSync != null) smsSync.statusPref.stateChanged(progress[0]);
          sState = progress[0];
        }

        private void updateAllThreads(final boolean async) {
            // thread dates + states might be wrong, we need to force a full update
            // unfortunately there's no direct way to do that in the SDK, but passing a
            // negative conversation id to delete should to the trick

            // execute in background, might take some time
            final Thread t = new Thread() {
                @Override
                public void run() {
                    Log.d(TAG, "updating threads");
                    getContentResolver().delete(Uri.parse("content://sms/conversations/-1"), null, null);
                    Log.d(TAG, "finished");
                }
            };
            t.start();
            try {
              if (!async) t.join();
            } catch (InterruptedException e) { }
        }

        private void importMessage(Message message) {
            uids.add(message.getUid());

            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.BODY);

            try {
                if (LOCAL_LOGV) Log.v(TAG, "fetching message uid " + message.getUid());

                message.getFolder().fetch(new Message[] { message }, fp, null);

                //we have two possible header sets here
                //legacy:  there is no CursorToMessage.Headers.DATATYPE. CursorToMessage.Headers.TYPE
                //         contains either the string "mms" or an integer which is the internal type of the sms
                //current: there IS a Headers.DATATYPE containing a string representation of CursorToMessage.DataType
                //         CursorToMessage.Headers.TYPE then contains the type of the sms, mms or calllog entry
                //The current header set was introduced in version 1.2.00
                final String dataType = getHeader(message, Headers.DATATYPE);

                //if we get a encrypted msg, initialize our encodingService
                final String pgp_header = getHeader(message, Headers.PGP_TYPE);
                Log.d( TAG, "pgp header is: "+pgp_header);

                boolean body_is_encrypted = false;
                if( pgp_header != null && pgp_header != "none" ) {
                    body_is_encrypted = true;
                    if( mEnc == null ) {
                        mEnc = new ApgCon(getBaseContext());
                    }
                }

                //only restore sms for now. first check for current headers
                if (null != dataType && !dataType.equalsIgnoreCase(DataType.SMS.toString())) {
                    if (LOCAL_LOGV) Log.d(TAG, "ignoring entry because no sms: " + dataType);
                    return;
                }

                ContentValues values = messageToContentValues(message);
                Integer type = values.getAsInteger(SmsConsts.TYPE);

                // only restore inbox messages and sent messages - otherwise sms might get sent on restore
                if (type != null && (type == SmsConsts.MESSAGE_TYPE_INBOX ||
                                     type == SmsConsts.MESSAGE_TYPE_SENT) &&
                                     !smsExists(values)) {

                    // decrypt encrypted body before restoring
                    if( body_is_encrypted ) {
                        String body = values.getAsString(SmsConsts.BODY);

                        mEnc.set_arg( "MSG", body );
                        mEnc.set_arg( "SYM_KEY", PrefStore.getPgpSymmetricKey(getBaseContext()) );

                        if( !mEnc.call( "decrypt_with_passphrase" ) ) {
                            Log.d( TAG, "decryption returned error: " );
                            while( mEnc.has_next_error() ) {
                                Log.d( TAG, mEnc.get_next_error() );
                            }
                        }
                        while( mEnc.has_next_warning() ) {
                            Log.d( TAG, "Warning: "+mEnc.get_next_warning() );
                        }

                        values.put(SmsConsts.BODY, mEnc.get_result() );
                    }

                    Uri uri = getContentResolver().insert(SMS_PROVIDER, values);
                    if (uri != null) {
                      insertedIds.add(uri.getLastPathSegment());

                      Long timestamp = values.getAsLong(SmsConsts.DATE);

                      if (timestamp != null &&
                          PrefStore.getMaxSyncedDateSms(SmsRestoreService.this) < timestamp) {
                          updateMaxSyncedDateSms(timestamp);
                      }
                      if (LOCAL_LOGV) Log.v(TAG, "inserted " + uri);
                    }
                } else {
                    if (LOCAL_LOGV) Log.d(TAG, "ignoring sms");
                }

            } catch (IllegalArgumentException e) {
                // http://code.google.com/p/android/issues/detail?id=2916
                Log.e(TAG, "error", e);
            } catch (java.io.IOException e) {
                Log.e(TAG, "error", e);
            } catch (MessagingException e) {
                Log.e(TAG, "error", e);
            }
        }
    }

    @Override public void onCreate() {
       asyncClearCache();
       BinaryTempFileBody.setTempDirectory(getCacheDir());
    }

    @Override protected void handleIntent(final Intent intent) {
        synchronized (ServiceBase.class) {
            if (!sIsRunning) {
                new RestoreTask().execute(PrefStore.getMaxItemsPerRestore(this));
            }
        }
    }

    private synchronized void asyncClearCache() {
       new Thread("clearCache") {
          @Override public void run() { clearCache(); }
       }.start();
    }

    private void clearCache() {
        File tmp = getCacheDir();
        if (tmp == null) return; // not sure why this would return null

        Log.d(TAG, "clearing cache in " + tmp);
        for (File f : tmp.listFiles(new FilenameFilter() {
          public boolean accept(File dir, String name) {
            return name.startsWith("body");
          }
        })) {
          if (LOCAL_LOGV) Log.v(TAG, "deleting " + f);
          f.delete();
        }
    }

    private boolean smsExists(ContentValues values) {
        // just assume equality on date+address+type
        Cursor c = getContentResolver().query(SMS_PROVIDER,
                new String[] { "_id" },
                "date = ? AND address = ? AND type = ?",
                new String[] { values.getAsString(SmsConsts.DATE),
                               values.getAsString(SmsConsts.ADDRESS),
                               values.getAsString(SmsConsts.TYPE)},
                               null
        );

        boolean exists = false;
        if (c != null) {
          exists = c.getCount() > 0;
          c.close();
        }
        return exists;
    }

    private ContentValues messageToContentValues(Message message)
            throws java.io.IOException, MessagingException {

        if (message == null || message.getBody() == null) {
          throw new MessagingException("message/body is null");
        }

        java.io.InputStream is = message.getBody().getInputStream();

        if (is == null) {
          throw new MessagingException("body.getInputStream() is null for " + message.getBody());
        }

        String body = IOUtils.toString(is);
        
        ContentValues values = new ContentValues();
        values.put(SmsConsts.BODY, body);
        values.put(SmsConsts.ADDRESS, getHeader(message, ADDRESS));
        values.put(SmsConsts.TYPE, getHeader(message, TYPE));
        values.put(SmsConsts.PROTOCOL, getHeader(message, PROTOCOL));
        values.put(SmsConsts.SERVICE_CENTER, getHeader(message, SERVICE_CENTER));
        values.put(SmsConsts.DATE, getHeader(message, DATE));
        values.put(SmsConsts.STATUS, getHeader(message, STATUS));
        values.put(SmsConsts.THREAD_ID, getThreadId(getHeader(message, ADDRESS)));
        values.put(SmsConsts.READ, PrefStore.getMarkAsReadOnRestore(this) ? "1" : getHeader(message, READ));
        return values;
    }

    private String lookupNumber(String address) {
        return address;
    }

    private Long getThreadId(final String recipient) {
      if (recipient == null || !threadsAvailable) return null;

      if (mThreadIdCache.containsKey(recipient)) {
        return mThreadIdCache.get(recipient);
      }

      if (getOrCreateThreadId == null) {
        try {
          telephonyThreads = Class.forName("android.provider.Telephony$Threads");
          getOrCreateThreadId = telephonyThreads.getMethod("getOrCreateThreadId",
                                                  new Class[] { Context.class, String.class });
        } catch (NoSuchMethodException e) {
          return noThreadsAvailable(e);
        } catch (ClassNotFoundException e) {
          return noThreadsAvailable(e);
        }
      }

      try {
        final Long id = (Long) getOrCreateThreadId.invoke(telephonyThreads,
                                                    new Object[] { this, lookupNumber(recipient)  });
        if (LOCAL_LOGV) Log.v(TAG, "threadId for " + recipient + ": " + id);
        if (id != null) mThreadIdCache.put(recipient, id);

        return id;
      } catch (InvocationTargetException e) {
        return noThreadsAvailable(e);
      } catch (IllegalAccessException e) {
        return noThreadsAvailable(e);
      }
    }

    private Long noThreadsAvailable(Throwable e) {
        Log.e(TAG, "threadsNotAvailable", e);
        threadsAvailable = false;
        return null;
    }

    private String getHeader(Message msg, String header) {
        try {
            String[] hdrs = msg.getHeader(header);
            if (hdrs != null && hdrs.length > 0) {
                return hdrs[0];
            }
        } catch (MessagingException e) {
        }
        return null;
    }
}
