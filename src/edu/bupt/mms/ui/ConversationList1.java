/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
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
 * limitations under the License.
 */

package edu.bupt.mms.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

import edu.bupt.mms.LogTag;
import edu.bupt.mms.R;
import edu.bupt.mms.data.Contact;
import edu.bupt.mms.data.ContactList;
import edu.bupt.mms.data.Conversation;
import edu.bupt.mms.data.Conversation.ConversationQueryHandler;
import edu.bupt.mms.transaction.MessagingNotification;
import edu.bupt.mms.transaction.SmsRejectedReceiver;
//import edu.bupt.mms.ui.ConversationList.ModeCallback;
import edu.bupt.mms.util.DraftCache;
import edu.bupt.mms.util.Recycler;
import com.google.android.mms.pdu.PduHeaders;

import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.database.sqlite.SqliteWrapper;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.AsyncQueryHandler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.DialogInterface.OnClickListener;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Contacts;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Threads;
import android.util.Log;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnCreateContextMenuListener;
import android.view.View.OnKeyListener;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;

/**
 * This activity provides a list view of existing conversations.
 */
//public class ConversationList1 extends ListActivity implements DraftCache.OnDraftChangedListener {
public class ConversationList1 extends ListActivity{
    private static final String TAG = "ConversationList";
    private static final boolean DEBUG = false;
    private static final boolean LOCAL_LOGV = DEBUG;

    private static final int THREAD_LIST_QUERY_TOKEN       = 1701;
    private static final int UNREAD_THREADS_QUERY_TOKEN    = 1702;
    public static final int DELETE_CONVERSATION_TOKEN      = 1801;
    public static final int HAVE_LOCKED_MESSAGES_TOKEN     = 1802;
    private static final int DELETE_OBSOLETE_THREADS_TOKEN = 1803;

    // IDs of the context menu items for the list of conversations.
    public static final int MENU_DELETE               = 0;
    public static final int MENU_VIEW                 = 1;
    public static final int MENU_VIEW_CONTACT         = 2;
    public static final int MENU_ADD_TO_CONTACTS      = 3;

    private ThreadListQueryHandler mQueryHandler;
    private ConversationListAdapter1 mListAdapter;
    
    private ConversationListAdapter mbacListAdapter;
    private SharedPreferences mPrefs;
    private Handler mHandler;
    private boolean mNeedToMarkAsSeen;
    private TextView mUnreadConvCount;
    private MenuItem mSearchItem;
    private SearchView mSearchView;
    
    private Bundle s;
    
    static private final String CHECKED_MESSAGE_LIMITS = "checked_message_limits";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.conversation_list_screen);

        //setContentView(R.layout.delete_thread_dialog_view);
        mQueryHandler = new ThreadListQueryHandler(getContentResolver());
        
//        ModeCallback mModeCallback = new ModeCallback();
//        mModeCallback.onCreateActionMode(mode, menu);

        
        
        ListView listView = getListView();

        listView.setOnCreateContextMenuListener(mConvListOnCreateContextMenuListener);
   
        listView.setOnKeyListener(mThreadListKeyListener);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        //listView.setChoiceMode(2);

       listView.setMultiChoiceModeListener(new ModeCallback());

     

        // Tell the list view which view to display when the list is empty
        listView.setEmptyView(findViewById(R.id.empty));
        
      
        

        initListAdapter();

        setupActionBar();

        setTitle(R.string.app_label);


        
        mHandler = new Handler();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean checkedMessageLimits = mPrefs.getBoolean(CHECKED_MESSAGE_LIMITS, false);
        if (DEBUG) Log.v(TAG, "checkedMessageLimits: " + checkedMessageLimits);
        if (!checkedMessageLimits || DEBUG) {
            runOneTimeStorageLimitCheckForLegacyMessages();
        }
        
      
    }

    private void setupActionBar() {
        ActionBar actionBar = getActionBar();

//        ViewGroup v = (ViewGroup)LayoutInflater.from(this)
//            .inflate(R.layout.conversation_list_actionbar, null);
        ViewGroup v = (ViewGroup)LayoutInflater.from(this)
                .inflate(R.layout.conversation_list_multi_select_actionbar, null);
        
        actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,
                ActionBar.DISPLAY_SHOW_CUSTOM);
        actionBar.setCustomView(v,
                new ActionBar.LayoutParams(ActionBar.LayoutParams.WRAP_CONTENT,
                        ActionBar.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL | Gravity.RIGHT));

        mUnreadConvCount = (TextView)v.findViewById(R.id.unread_conv_count);
    }

    private final ConversationListAdapter1.OnContentChangedListener mContentChangedListener =
        new ConversationListAdapter1.OnContentChangedListener() {
        @Override
        public void onContentChanged(ConversationListAdapter1 adapter) {
            startAsyncQuery();
        }
    };
    
    private final ConversationListAdapter.OnContentChangedListener mContentChangedListener1 =
            new ConversationListAdapter.OnContentChangedListener() {
            @Override
            public void onContentChanged(ConversationListAdapter adapter) {
                startAsyncQuery();
            }
        };

    private void initListAdapter() {
        mListAdapter = new ConversationListAdapter1(this, null);
        mListAdapter.setOnContentChangedListener(mContentChangedListener);
        setListAdapter(mListAdapter);
        getListView().setRecyclerListener(mListAdapter);
    }
    
    private void initListAdapter1() {
    	mbacListAdapter = new ConversationListAdapter(this, null);
    	mbacListAdapter.setOnContentChangedListener(mContentChangedListener1);
        setListAdapter(mbacListAdapter);
        getListView().setRecyclerListener(mbacListAdapter);
    }
    
    

    /**
     * Checks to see if the number of MMS and SMS messages are under the limits for the
     * recycler. If so, it will automatically turn on the recycler setting. If not, it
     * will prompt the user with a message and point them to the setting to manually
     * turn on the recycler.
     */
    public synchronized void runOneTimeStorageLimitCheckForLegacyMessages() {
        if (Recycler.isAutoDeleteEnabled(this)) {
            if (DEBUG) Log.v(TAG, "recycler is already turned on");
            // The recycler is already turned on. We don't need to check anything or warn
            // the user, just remember that we've made the check.
            markCheckedMessageLimit();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (Recycler.checkForThreadsOverLimit(ConversationList1.this)) {
                    if (DEBUG) Log.v(TAG, "checkForThreadsOverLimit TRUE");
                    // Dang, one or more of the threads are over the limit. Show an activity
                    // that'll encourage the user to manually turn on the setting. Delay showing
                    // this activity until a couple of seconds after the conversation list appears.
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            Intent intent = new Intent(ConversationList1.this,
                                    WarnOfStorageLimitsActivity.class);
                            startActivity(intent);
                        }
                    }, 2000);
                } else {
                    if (DEBUG) Log.v(TAG, "checkForThreadsOverLimit silently turning on recycler");
                    // No threads were over the limit. Turn on the recycler by default.
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            SharedPreferences.Editor editor = mPrefs.edit();
                            editor.putBoolean(MessagingPreferenceActivity.AUTO_DELETE, true);
                            editor.apply();
                        }
                    });
                }
                // Remember that we don't have to do the check anymore when starting MMS.
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        markCheckedMessageLimit();
                    }
                });
            }
        }, "ConversationList.runOneTimeStorageLimitCheckForLegacyMessages").start();
    }

    /**
     * Mark in preferences that we've checked the user's message limits. Once checked, we'll
     * never check them again, unless the user wipe-data or resets the device.
     */
    private void markCheckedMessageLimit() {
        if (DEBUG) Log.v(TAG, "markCheckedMessageLimit");
        SharedPreferences.Editor editor = mPrefs.edit();
        editor.putBoolean(CHECKED_MESSAGE_LIMITS, true);
        editor.apply();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // Handle intents that occur after the activity has already been created.
        startAsyncQuery();
    }

    @Override
    protected void onStart() {
        super.onStart();

        MessagingNotification.cancelNotification(getApplicationContext(),
                SmsRejectedReceiver.SMS_REJECTED_NOTIFICATION_ID);

      //  DraftCache.getInstance().addOnDraftChangedListener(this);

        mNeedToMarkAsSeen = true;

        startAsyncQuery();

        // We used to refresh the DraftCache here, but
        // refreshing the DraftCache each time we go to the ConversationList seems overly
        // aggressive. We already update the DraftCache when leaving CMA in onStop() and
        // onNewIntent(), and when we delete threads or delete all in CMA or this activity.
        // I hope we don't have to do such a heavy operation each time we enter here.

        // we invalidate the contact cache here because we want to get updated presence
        // and any contact changes. We don't invalidate the cache by observing presence and contact
        // changes (since that's too untargeted), so as a tradeoff we do it here.
        // If we're in the middle of the app initialization where we're loading the conversation
        // threads, don't invalidate the cache because we're in the process of building it.
        // TODO: think of a better way to invalidate cache more surgically or based on actual
        // TODO: changes we care about
        /*
        if (!Conversation.loadingThreads()) {
            Contact.invalidateCache();
        }
        */
    }

    @Override
    protected void onStop() {
        super.onStop();

       // DraftCache.getInstance().removeOnDraftChangedListener(this);

        // Simply setting the choice mode causes the previous choice mode to finish and we exit
        // multi-select mode (if we're in it) and remove all the selections.
        getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);

        mListAdapter.changeCursor(null);
    }

//    @Override
//    public void onDraftChanged(final long threadId, final boolean hasDraft) {
//        // Run notifyDataSetChanged() on the main thread.
//        mQueryHandler.post(new Runnable() {
//            @Override
//            public void run() {
//                if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
//                    log("onDraftChanged: threadId=" + threadId + ", hasDraft=" + hasDraft);
//                }
//                mListAdapter.notifyDataSetChanged();
//            }
//        });
//    }

    private void startAsyncQuery() {
        try {
            ((TextView)(getListView().getEmptyView())).setText(R.string.loading_conversations);

            Conversation.startQueryForAll(mQueryHandler, THREAD_LIST_QUERY_TOKEN);
            Conversation.startQuery(mQueryHandler, UNREAD_THREADS_QUERY_TOKEN, Threads.READ + "=0");
        } catch (SQLiteException e) {
            SqliteWrapper.checkSQLiteException(this, e);
        }
    }

    SearchView.OnQueryTextListener mQueryTextListener = new SearchView.OnQueryTextListener() {
        @Override
        public boolean onQueryTextSubmit(String query) {
            Intent intent = new Intent();
            intent.setClass(ConversationList1.this, SearchActivity.class);
            intent.putExtra(SearchManager.QUERY, query);
            startActivity(intent);
            mSearchItem.collapseActionView();
            return true;
        }

        @Override
        public boolean onQueryTextChange(String newText) {
            return false;
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
       // getMenuInflater().inflate(R.menu.conversation_list_menu, menu);
        getMenuInflater().inflate(R.menu.conversation_multi_select_menu, menu);
        
//        View mMultiSelectActionBarView = null;
//        TextView mSelectedConvCount;
//        HashSet<Long> mSelectedThreadIds;
//        MenuInflater inflater = getMenuInflater();
//        mSelectedThreadIds = new HashSet<Long>();
//        MenuInflater inflater1 = getMenuInflater();
//        mSelectedThreadIds = new HashSet<Long>();
//        inflater1.inflate(R.menu.conversation_multi_select_menu, menu);
//
//        if (mMultiSelectActionBarView == null) {
//            mMultiSelectActionBarView = LayoutInflater.from(ConversationList1.this)
//                .inflate(R.layout.conversation_list_multi_select_actionbar, null);
//
//            mSelectedConvCount =
//                (TextView)mMultiSelectActionBarView.findViewById(R.id.selected_conv_count);
//        }
//       
//        ((TextView)mMultiSelectActionBarView.findViewById(R.id.title))
//            .setText(R.string.select_conversations);
        
        
 //       mSearchItem = menu.findItem(R.id.search);
//        mSearchView = (SearchView) mSearchItem.getActionView();
//
//        mSearchView.setOnQueryTextListener(mQueryTextListener);
//        mSearchView.setQueryHint(getString(R.string.search_hint));
//        mSearchView.setIconifiedByDefault(true);
//        SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
//
//        if (searchManager != null) {
//            SearchableInfo info = searchManager.getSearchableInfo(this.getComponentName());
//            mSearchView.setSearchableInfo(info);
//        }

        MenuItem cellBroadcastItem = menu.findItem(R.id.action_cell_broadcasts);
        if (cellBroadcastItem != null) {
            // Enable link to Cell broadcast activity depending on the value in config.xml.
            boolean isCellBroadcastAppLinkEnabled = this.getResources().getBoolean(
                    com.android.internal.R.bool.config_cellBroadcastAppLinks);
            try {
                if (isCellBroadcastAppLinkEnabled) {
                    PackageManager pm = getPackageManager();
                    if (pm.getApplicationEnabledSetting("com.android.cellbroadcastreceiver")
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                        isCellBroadcastAppLinkEnabled = false;  // CMAS app disabled
                    }
                }
            } catch (IllegalArgumentException ignored) {
                isCellBroadcastAppLinkEnabled = false;  // CMAS app not installed
            }
            if (!isCellBroadcastAppLinkEnabled) {
                cellBroadcastItem.setVisible(false);
            }
        }

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.action_delete_all);
        if (item != null) {
            item.setVisible(mListAdapter.getCount() > 0);
        }
        if (!LogTag.DEBUG_DUMP) {
            item = menu.findItem(R.id.action_debug_dump);
            if (item != null) {
                item.setVisible(false);
            }
        }
        return true;
    }

    @Override
    public boolean onSearchRequested() {
        if (mSearchItem != null) {
            mSearchItem.expandActionView();
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.action_compose_new:
              //  createNewMessage();
                break;
            case R.id.action_delete_all:
                // The invalid threadId of -1 means all threads here.
                confirmDeleteThread(-1L, mQueryHandler);
                break;
            case R.id.action_settings:
                Intent intent = new Intent(this, MessagingPreferenceActivity.class);
                startActivityIfNeeded(intent, -1);
                break;
            case R.id.action_traditional:
                //Intent modelintent = new Intent(this, InboxActivity.class);
                Intent modelintent = new Intent(this, ReadSMS.class);
                startActivityIfNeeded(modelintent, -1);
                break;
            case R.id.action_debug_dump:
                LogTag.dumpInternalTables(this);
                break;
            case R.id.action_cell_broadcasts:
                Intent cellBroadcastIntent = new Intent(Intent.ACTION_MAIN);
                cellBroadcastIntent.setComponent(new ComponentName(
                        "com.android.cellbroadcastreceiver",
                        "com.android.cellbroadcastreceiver.CellBroadcastListActivity"));
                cellBroadcastIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(cellBroadcastIntent);
                } catch (ActivityNotFoundException ignored) {
                    Log.e(TAG, "ActivityNotFoundException for CellBroadcastListActivity");
                }
                return true;
            default:
                return true;
        }
        return false;
    }

//    @Override
//    protected void onListItemClick(ListView l, View v, int position, long id) {
//        // Note: don't read the thread id data from the ConversationListItem view passed in.
//        // It's unreliable to read the cached data stored in the view because the ListItem
//        // can be recycled, and the same view could be assigned to a different position
//        // if you click the list item fast enough. Instead, get the cursor at the position
//        // clicked and load the data from the cursor.
//        // (ConversationListAdapter extends CursorAdapter, so getItemAtPosition() should
//        // return the cursor object, which is moved to the position passed in)
//        Cursor cursor  = (Cursor) getListView().getItemAtPosition(position);
//        Conversation conv = Conversation.from(this, cursor);
//        long tid = conv.getThreadId();
//
//        if (LogTag.VERBOSE) {
//            Log.d(TAG, "onListItemClick: pos=" + position + ", view=" + v + ", tid=" + tid);
//        }
//
//        openThread(tid);
//    }

//    private void createNewMessage() {
//        startActivity(ComposeMessageActivity.createIntent(this, 0));
//    }

//    private void openThread(long threadId) {
//        startActivity(ComposeMessageActivity.createIntent(this, threadId));
//    }

//    public static Intent createAddContactIntent(String address) {
//        // address must be a single recipient
//        Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
//        intent.setType(Contacts.CONTENT_ITEM_TYPE);
//        if (Mms.isEmailAddress(address)) {
//            intent.putExtra(ContactsContract.Intents.Insert.EMAIL, address);
//        } else {
//            intent.putExtra(ContactsContract.Intents.Insert.PHONE, address);
//            intent.putExtra(ContactsContract.Intents.Insert.PHONE_TYPE,
//                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE);
//        }
//        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
//
//        return intent;
//    }

    private final OnCreateContextMenuListener mConvListOnCreateContextMenuListener =
        new OnCreateContextMenuListener() {
        @Override
        public void onCreateContextMenu(ContextMenu menu, View v,
                ContextMenuInfo menuInfo) {

            Cursor cursor = mListAdapter.getCursor();
            if (cursor == null || cursor.getPosition() < 0) {
                return;
            }
            Conversation conv = Conversation.from(ConversationList1.this, cursor);
            ContactList recipients = conv.getRecipients();
            menu.setHeaderTitle(recipients.formatNames(","));

            AdapterView.AdapterContextMenuInfo info =
                (AdapterView.AdapterContextMenuInfo) menuInfo;
            menu.add(0, MENU_VIEW, 0, R.string.menu_view);

            // Only show if there's a single recipient
            if (recipients.size() == 1) {
                // do we have this recipient in contacts?
                if (recipients.get(0).existsInDatabase()) {
                    menu.add(0, MENU_VIEW_CONTACT, 0, R.string.menu_view_contact);
                } 
//                else {
//                    menu.add(0, MENU_ADD_TO_CONTACTS, 0, R.string.menu_add_to_contacts);
//                }
            }
            menu.add(0, MENU_DELETE, 0, R.string.menu_delete);
        }
    };

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Cursor cursor = mListAdapter.getCursor();
        if (cursor != null && cursor.getPosition() >= 0) {
            Conversation conv = Conversation.from(ConversationList1.this, cursor);
            long threadId = conv.getThreadId();
            switch (item.getItemId()) {
            case MENU_DELETE: {
                confirmDeleteThread(threadId, mQueryHandler);
                break;
            }
            case MENU_VIEW: {
              //  openThread(threadId);
                break;
            }
            case MENU_VIEW_CONTACT: {
                Contact contact = conv.getRecipients().get(0);
                Intent intent = new Intent(Intent.ACTION_VIEW, contact.getUri());
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                startActivity(intent);
                break;
            }
            case MENU_ADD_TO_CONTACTS: {
                String address = conv.getRecipients().get(0).getNumber();
               // startActivity(createAddContactIntent(address));
                break;
            }
            default:
                break;
            }
        }
        return super.onContextItemSelected(item);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // We override this method to avoid restarting the entire
        // activity when the keyboard is opened (declared in
        // AndroidManifest.xml).  Because the only translatable text
        // in this activity is "New Message", which has the full width
        // of phone to work with, localization shouldn't be a problem:
        // no abbreviated alternate words should be needed even in
        // 'wide' languages like German or Russian.

        super.onConfigurationChanged(newConfig);
        if (DEBUG) Log.v(TAG, "onConfigurationChanged: " + newConfig);
    }

    /**
     * Start the process of putting up a dialog to confirm deleting a thread,
     * but first start a background query to see if any of the threads or thread
     * contain locked messages so we'll know how detailed of a UI to display.
     * @param threadId id of the thread to delete or -1 for all threads
     * @param handler query handler to do the background locked query
     */
    public static void confirmDeleteThread(long threadId, AsyncQueryHandler handler) {
        ArrayList<Long> threadIds = null;
        if (threadId != -1) {
            threadIds = new ArrayList<Long>();
            threadIds.add(threadId);
        }
        confirmDeleteThreads(threadIds, handler);
    }

    /**
     * Start the process of putting up a dialog to confirm deleting threads,
     * but first start a background query to see if any of the threads
     * contain locked messages so we'll know how detailed of a UI to display.
     * @param threadIds list of threadIds to delete or null for all threads
     * @param handler query handler to do the background locked query
     */
    public static void confirmDeleteThreads(Collection<Long> threadIds, AsyncQueryHandler handler) {
        Conversation.startQueryHaveLockedMessages(handler, threadIds,
                HAVE_LOCKED_MESSAGES_TOKEN);
    }

    /**
     * Build and show the proper delete thread dialog. The UI is slightly different
     * depending on whether there are locked messages in the thread(s) and whether we're
     * deleting single/multiple threads or all threads.
     * @param listener gets called when the delete button is pressed
     * @param threadIds the thread IDs to be deleted (pass null for all threads)
     * @param hasLockedMessages whether the thread(s) contain locked messages
     * @param context used to load the various UI elements
     */
    public static void confirmDeleteThreadDialog(final DeleteThreadListener listener,
            Collection<Long> threadIds,
            boolean hasLockedMessages,
            Context context) {
        View contents = View.inflate(context, R.layout.delete_thread_dialog_view, null);
        TextView msg = (TextView)contents.findViewById(R.id.message);

        if (threadIds == null) {
            msg.setText(R.string.confirm_delete_all_conversations);
        } else {
            // Show the number of threads getting deleted in the confirmation dialog.
            int cnt = threadIds.size();
            msg.setText(context.getResources().getQuantityString(
                R.plurals.confirm_delete_conversation, cnt, cnt));
        }

        final CheckBox checkbox = (CheckBox)contents.findViewById(R.id.delete_locked);
        if (!hasLockedMessages) {
            checkbox.setVisibility(View.GONE);
        } else {
            listener.setDeleteLockedMessage(checkbox.isChecked());
            checkbox.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.setDeleteLockedMessage(checkbox.isChecked());
                }
            });
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.confirm_dialog_title)
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .setCancelable(true)
            .setPositiveButton(R.string.delete, listener)
            .setNegativeButton(R.string.no, null)
            .setView(contents)
            .show();
    }

    private final OnKeyListener mThreadListKeyListener = new OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DEL: {
                        long id = getListView().getSelectedItemId();
                        if (id > 0) {
                            confirmDeleteThread(id, mQueryHandler);
                        }
                        return true;
                    }
                }
            }
            return false;
        }
    };

    public static class DeleteThreadListener implements OnClickListener {
        private final Collection<Long> mThreadIds;
        private final ConversationQueryHandler mHandler;
        private final Context mContext;
        private boolean mDeleteLockedMessages;

        public DeleteThreadListener(Collection<Long> threadIds, ConversationQueryHandler handler,
                Context context) {
            mThreadIds = threadIds;
            mHandler = handler;
            mContext = context;
        }

        public void setDeleteLockedMessage(boolean deleteLockedMessages) {
            mDeleteLockedMessages = deleteLockedMessages;
        }

        @Override
        public void onClick(DialogInterface dialog, final int whichButton) {
            MessageUtils.handleReadReport(mContext, mThreadIds,
                    PduHeaders.READ_STATUS__DELETED_WITHOUT_BEING_READ, new Runnable() {
                @Override
                public void run() {
                    int token = DELETE_CONVERSATION_TOKEN;
                    if (mThreadIds == null) {
                        Conversation.startDeleteAll(mHandler, token, mDeleteLockedMessages);
                        DraftCache.getInstance().refresh();
                    } else {
                        for (long threadId : mThreadIds) {
                            Conversation.startDelete(mHandler, token, mDeleteLockedMessages,
                                    threadId);
                            DraftCache.getInstance().setDraftState(threadId, false);
                        }
                    }
                }
            });
            dialog.dismiss();
        }
    }

    private final Runnable mDeleteObsoleteThreadsRunnable = new Runnable() {
        @Override
        public void run() {
            if (Log.isLoggable(LogTag.APP, Log.VERBOSE)) {
                LogTag.debug("mDeleteObsoleteThreadsRunnable getSavingDraft(): " +
                        DraftCache.getInstance().getSavingDraft());
            }
            if (DraftCache.getInstance().getSavingDraft()) {
                // We're still saving a draft. Try again in a second. We don't want to delete
                // any threads out from under the draft.
                mHandler.postDelayed(mDeleteObsoleteThreadsRunnable, 1000);
            } else {
                Conversation.asyncDeleteObsoleteThreads(mQueryHandler,
                        DELETE_OBSOLETE_THREADS_TOKEN);
            }
        }
    };

    private final class ThreadListQueryHandler extends ConversationQueryHandler {
        public ThreadListQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        // Test code used for various scenarios where its desirable to insert a delay in
        // responding to query complete. To use, uncomment out the block below and then
        // comment out the @Override and onQueryComplete line.
//        @Override
//        protected void onQueryComplete(final int token, final Object cookie, final Cursor cursor) {
//            mHandler.postDelayed(new Runnable() {
//                public void run() {
//                    myonQueryComplete(token, cookie, cursor);
//                    }
//            }, 2000);
//        }
//
//        protected void myonQueryComplete(int token, Object cookie, Cursor cursor) {

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            switch (token) {
            case THREAD_LIST_QUERY_TOKEN:
                mListAdapter.changeCursor(cursor);

                if (mListAdapter.getCount() == 0) {
                    ((TextView)(getListView().getEmptyView())).setText(R.string.no_conversations);
                }

                if (mNeedToMarkAsSeen) {
                    mNeedToMarkAsSeen = false;
                    Conversation.markAllConversationsAsSeen(getApplicationContext());

                    // Delete any obsolete threads. Obsolete threads are threads that aren't
                    // referenced by at least one message in the pdu or sms tables. We only call
                    // this on the first query (because of mNeedToMarkAsSeen).
                    mHandler.post(mDeleteObsoleteThreadsRunnable);
                }
                break;

            case UNREAD_THREADS_QUERY_TOKEN:
                int count = 0;
                if (cursor != null) {
                    count = cursor.getCount();
                    cursor.close();
                }
               // mUnreadConvCount.setText(count > 0 ? Integer.toString(count) : null);
                break;

            case HAVE_LOCKED_MESSAGES_TOKEN:
                @SuppressWarnings("unchecked")
                Collection<Long> threadIds = (Collection<Long>)cookie;
                confirmDeleteThreadDialog(new DeleteThreadListener(threadIds, mQueryHandler,
                        ConversationList1.this), threadIds,
                        cursor != null && cursor.getCount() > 0,
                        ConversationList1.this);
                if (cursor != null) {
                    cursor.close();
                }
                break;

            default:
                Log.e(TAG, "onQueryComplete called with unknown token " + token);
            }
        }

        @Override
        protected void onDeleteComplete(int token, Object cookie, int result) {
            super.onDeleteComplete(token, cookie, result);
            switch (token) {
            case DELETE_CONVERSATION_TOKEN:
                long threadId = cookie != null ? (Long)cookie : -1;     // default to all threads

                if (threadId == -1) {
                    // Rebuild the contacts cache now that all threads and their associated unique
                    // recipients have been deleted.
                    Contact.init(ConversationList1.this);
                } else {
                    // Remove any recipients referenced by this single thread from the
                    // contacts cache. It's possible for two or more threads to reference
                    // the same contact. That's ok if we remove it. We'll recreate that contact
                    // when we init all Conversations below.
                    Conversation conv = Conversation.get(ConversationList1.this, threadId, false);
                    if (conv != null) {
                        ContactList recipients = conv.getRecipients();
                        for (Contact contact : recipients) {
                            contact.removeFromCache();
                        }
                    }
                }
                // Make sure the conversation cache reflects the threads in the DB.
                Conversation.init(ConversationList1.this);

                // Update the notification for new messages since they
                // may be deleted.
                MessagingNotification.nonBlockingUpdateNewMessageIndicator(ConversationList1.this,
                        MessagingNotification.THREAD_NONE, false);
                // Update the notification for failed messages since they
                // may be deleted.
                MessagingNotification.nonBlockingUpdateSendFailedNotification(ConversationList1.this);

                // Make sure the list reflects the delete
                startAsyncQuery();
                break;

            case DELETE_OBSOLETE_THREADS_TOKEN:
                // Nothing to do here.
                break;
            }
        }
    }

    private class ModeCallback implements ListView.MultiChoiceModeListener {
        private View mMultiSelectActionBarView;
        private TextView mSelectedConvCount;
        private HashSet<Long> mSelectedThreadIds;

       
        
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        
        	Log.v("haha","haha"+mode.toString());
        	Log.v("menu","menu"+menu.toString());
        	
        	ConversationListItem1.mCheckBox.setVisibility(8);
        	 //refresh();
        	// initListAdapter1();
            MenuInflater inflater = getMenuInflater();
            mSelectedThreadIds = new HashSet<Long>();
            inflater.inflate(R.menu.conversation_multi_select_menu, menu);

            if (mMultiSelectActionBarView == null) {
                mMultiSelectActionBarView = LayoutInflater.from(ConversationList1.this)
                    .inflate(R.layout.conversation_list_multi_select_actionbar, null);

                mSelectedConvCount =
                    (TextView)mMultiSelectActionBarView.findViewById(R.id.selected_conv_count);
            }
            mode.setCustomView(mMultiSelectActionBarView);
            ((TextView)mMultiSelectActionBarView.findViewById(R.id.title))
                .setText(R.string.select_conversations);
            
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            if (mMultiSelectActionBarView == null) {
                ViewGroup v = (ViewGroup)LayoutInflater.from(ConversationList1.this)
                    .inflate(R.layout.conversation_list_multi_select_actionbar, null);
                mode.setCustomView(v);

                mSelectedConvCount = (TextView)v.findViewById(R.id.selected_conv_count);
            }
        	
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.delete:
                    if (mSelectedThreadIds.size() > 0) {
                        confirmDeleteThreads(mSelectedThreadIds, mQueryHandler);
                    }
                    mode.finish();
                    break;

                default:
                    break;
            }
            return true;
        }

        
       
        
        @Override
        public void onDestroyActionMode(ActionMode mode) {
            ConversationListAdapter1 adapter = (ConversationListAdapter1)getListView().getAdapter();
            adapter.uncheckAll();
            mSelectedThreadIds = null;
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode mode,
                int position, long id, boolean checked) {
            ListView listView = getListView();
            final int checkedCount = listView.getCheckedItemCount();
            mSelectedConvCount.setText(Integer.toString(checkedCount));

           
            Log.v("onItemCheckedStateChanged","onItemCheckedStateChanged");
            Cursor cursor  = (Cursor)listView.getItemAtPosition(position);
            Conversation conv = Conversation.from(ConversationList1.this, cursor);
            conv.setIsChecked(checked);
            long threadId = conv.getThreadId();

            if (checked) {
                mSelectedThreadIds.add(threadId);
               // ConversationListItem1.mCheckBox.setChecked(true);
               
                Log.v("mCheckBox","同步被选中");
            } else {
                mSelectedThreadIds.remove(threadId);
                //ConversationListItem1.mCheckBox.setChecked(false);
            }
        }

    }

//    private void refresh() {  
//        finish();  
//        Intent intent = new Intent(ConversationList1.this, ConversationList1.class);  
//        startActivity(intent);  
//    }  
    
    private void log(String format, Object... args) {
        String s = String.format(format, args);
        Log.d(TAG, "[" + Thread.currentThread().getId() + "] " + s);
    }
}
