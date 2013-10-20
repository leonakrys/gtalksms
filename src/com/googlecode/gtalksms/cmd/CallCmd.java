package com.googlecode.gtalksms.cmd;

import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import com.googlecode.gtalksms.MainService;
import com.googlecode.gtalksms.R;
import com.googlecode.gtalksms.data.contacts.ContactsManager;
import com.googlecode.gtalksms.data.contacts.ContactsResolver;
import com.googlecode.gtalksms.data.contacts.ResolvedContact;
import com.googlecode.gtalksms.data.phone.Call;
import com.googlecode.gtalksms.data.phone.PhoneManager;
import com.googlecode.gtalksms.receivers.PhoneCallListener;
import com.googlecode.gtalksms.tools.Tools;
import com.googlecode.gtalksms.xmpp.XmppMsg;

import com.android.internal.telephony.ITelephony;

public class CallCmd extends CommandHandlerBase {
    private static boolean sListenerActive = false;
    private PhoneManager mPhoneMgr = null;
    private PhoneCallListener mPhoneListener = null;
    private TelephonyManager mTelephonyMgr = null;
    private ContactsResolver mContactsResolver = null;
        
    public CallCmd(MainService mainService) {
        super(mainService, CommandHandlerBase.TYPE_CONTACTS, "Call", new Cmd("calls"), new Cmd("dial"), new Cmd("call"), new Cmd("ignore"), new Cmd("reject"));
    }

    @Override
    protected void onCommandActivated() {
        mPhoneMgr = new PhoneManager(sContext);
        mTelephonyMgr = (TelephonyManager) sMainService.getSystemService(Context.TELEPHONY_SERVICE);
        mContactsResolver = ContactsResolver.getInstance(sContext);

        if (sSettingsMgr.notifyIncomingCalls && !sListenerActive) {
            if (mPhoneListener == null) {
                mPhoneListener = new PhoneCallListener(sMainService);
            }
            mTelephonyMgr.listen(mPhoneListener, PhoneStateListener.LISTEN_CALL_STATE);
            sListenerActive = true;
        }
    }

    @Override
    protected void onCommandDeactivated() {
        if (mPhoneListener != null && mTelephonyMgr != null) {
            mTelephonyMgr.listen(mPhoneListener, 0);
            sListenerActive = false;
        }

        mPhoneMgr = null;
        mTelephonyMgr = null;
        mContactsResolver = null;
    }
    
    @Override
    protected void execute(Command cmd) {
        if (isMatchingCmd(cmd, "dial")) {
            dial(cmd.getArg1(), false);
        } else if (isMatchingCmd(cmd, "call")) {
            dial(cmd.getArg1(), true);
        } else if (isMatchingCmd(cmd, "calls")) {
            readCallLogs(cmd.getArg1());
        } else if (isMatchingCmd(cmd, "ignore")) {
            ignoreIncomingCall();
        } else if (isMatchingCmd(cmd, "reject")) {
            rejectIncomingCall();
        }
    }

    /** reads last Call Logs from all contacts */
    private void readCallLogs(String args) {

        ArrayList<Call> arrayList = mPhoneMgr.getPhoneLogs();
        XmppMsg all = new XmppMsg();
        int callLogsNumber = Tools.parseInt(args, 10);
        List<Call> callList = Tools.getLastElements(arrayList, callLogsNumber);
        if (callList.size() > 0) {
            for (Call call : callList) {
                all.appendItalic(DateFormat.getDateTimeInstance().format(call.date));
                all.append(" - ");
                all.appendBold(ContactsManager.getContactName(sContext, call.phoneNumber));
                all.appendLine(" - " + call.type(sContext) + getString(R.string.chat_call_duration) + call.duration());
            }
        } else {
            all.appendLine(getString(R.string.chat_no_call));
        }
        send(all);
    }
    
    /** dial the specified contact */
    private void dial(String contactInformation, boolean makeTheCall) {
        if (contactInformation.equals("")) {
            String lastRecipient = RecipientCmd.getLastRecipientNumber();
            String lastRecipientName = RecipientCmd.getLastRecipientName();
            if (lastRecipient != null) {
                doDial(lastRecipientName, lastRecipient, makeTheCall);
            } else {
                // TODO l18n
                send("error: last recipient not set");
            }
        } else {
            ResolvedContact resolvedContact = mContactsResolver.resolveContact(contactInformation, ContactsResolver.TYPE_ALL);
            if (resolvedContact == null) {
                send(R.string.chat_no_match_for, contactInformation);
            } else if (resolvedContact.isDistinct()) {
                doDial(resolvedContact.getName(), resolvedContact.getNumber(), makeTheCall);
            } else if (!resolvedContact.isDistinct()) {
                askForMoreDetails(resolvedContact.getCandidates());
            }
        }
    }

    private void doDial(String name, String number, boolean makeTheCall) {
        if (number != null) {
            send(R.string.chat_dial, name + " (" + number + ")");
        } else {
            send(R.string.chat_dial, name);
        }

        // check if the dial is successful
        if (!mPhoneMgr.Dial(number, makeTheCall)) {
            send(R.string.chat_error_dial);
        }
    }

    /**
     * Rejects an incoming call
     * 
     * @return true if an call was rejected, otherwise false 
     */
    private boolean rejectIncomingCall() {
        send("Rejecting incoming Call");
        ITelephony ts = getTelephonyService();
        return ts.endCall();
    }
    
    /**
     * Ignores an incoming call
     */
    // TODO does not work atm gives:
    // Exception: Neither user 10081 nor current process has android.permission.MODIFY_PHONE_STATE.
    // Although the permission is in the manifest
    private void ignoreIncomingCall() {
        send("Ignoring incoming call");
        ITelephony ts = getTelephonyService();
        ts.silenceRinger();
    }
    
    private ITelephony getTelephonyService() {
        TelephonyManager tm = (TelephonyManager) sContext.getSystemService(Context.TELEPHONY_SERVICE);
        com.android.internal.telephony.ITelephony telephonyService;
        try {
            Class<?> c = Class.forName(tm.getClass().getName());
            Method m = c.getDeclaredMethod("getITelephony");
            m.setAccessible(true);
            telephonyService = (ITelephony) m.invoke(tm);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return telephonyService;
    }

    @Override
    protected void initializeSubCommands() {
        mCommandMap.get("calls").setHelp(R.string.chat_help_calls, "#count#");
        mCommandMap.get("dial").setHelp(R.string.chat_help_dial, "#contact#");
        mCommandMap.get("call").setHelp(R.string.chat_help_call, "#contact#");
        mCommandMap.get("reject").setHelp(R.string.chat_help_reject, null);
        mCommandMap.get("ignore").setHelp(R.string.chat_help_ignore, null);
    }
}
