/*
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1
 * 
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 ("License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.zimbra.com/license
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 * 
 * The Original Code is: Zimbra Collaboration Suite Server.
 * 
 * The Initial Developer of the Original Code is Zimbra, Inc.
 * Portions created by Zimbra are Copyright (C) 2005 Zimbra, Inc.
 * All Rights Reserved.
 * 
 * Contributor(s): 
 * 
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.imap;

import java.io.*;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AccountServiceException;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.imap.ImapSession.EnabledHack;
import com.zimbra.cs.imap.ImapSession.ImapFlag;
import com.zimbra.cs.index.ZimbraHit;
import com.zimbra.cs.index.ZimbraQueryResults;
import com.zimbra.cs.index.MailboxIndex;
import com.zimbra.cs.index.queryparser.ParseException;
import com.zimbra.cs.localconfig.LC;
import com.zimbra.cs.mailbox.*;
import com.zimbra.cs.mailbox.Mailbox.OperationContext;
import com.zimbra.cs.mime.Mime;
import com.zimbra.cs.mime.ParsedMessage;
import com.zimbra.cs.ozserver.OzByteArrayMatcher;
import com.zimbra.cs.ozserver.OzByteBufferGatherer;
import com.zimbra.cs.ozserver.OzConnection;
import com.zimbra.cs.ozserver.OzConnectionHandler;
import com.zimbra.cs.ozserver.OzCountingMatcher;
import com.zimbra.cs.ozserver.OzMatcher;
import com.zimbra.cs.ozserver.OzTLSFilter;
import com.zimbra.cs.service.ServiceException;
import com.zimbra.cs.session.SessionCache;
import com.zimbra.cs.util.AccountUtil;
import com.zimbra.cs.util.BuildInfo;
import com.zimbra.cs.util.Config;
import com.zimbra.cs.util.Constants;
import com.zimbra.cs.util.JMSession;
import com.zimbra.cs.util.ZimbraLog;

/**
 * NB: Copied from ImapHandler.java on October 20, 2005 - while there
 * are two server impls we have to merge all changes to made to
 * ImapHandler.java since that date, to this file.
 *
 * @author dkarp
 */
public class OzImapConnectionHandler implements OzConnectionHandler, ImapSessionHandler {

    public static final String PROPERTY_ALLOW_CLEARTEXT_LOGINS = "imap.allows.cleartext.logins"; 
    public static final String PROPERTY_SECURE_SERVER = "imap.secure.server";

    private static final long MAXIMUM_IDLE_PROCESSING_MILLIS = 15 * Constants.MILLIS_PER_SECOND;

    static final char[] LINE_SEPARATOR       = { '\r', '\n' };
    static final byte[] LINE_SEPARATOR_BYTES = { '\r', '\n' };

    private DateFormat mTimeFormat   = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.US);
    private DateFormat mDateFormat   = new SimpleDateFormat("dd-MMM-yyyy", Locale.US);
    private DateFormat mZimbraFormat = new SimpleDateFormat("MM/dd/yyyy", Locale.US);

    private ImapSession mSession;
    private Mailbox     mMailbox;
    private OzImapRequest mIncompleteRequest = null;
    private boolean     mStartedTLS;
    private boolean     mGoodbyeSent;

    private final OzConnection mConnection;
    
    public OzImapConnectionHandler(OzConnection connection) {
        mConnection = connection;
    }
    
    public void dumpState(Writer w) {
    	StringBuilder s = new StringBuilder("\n\tOzImapConnectionHandler ").append(this.toString()).append("\n");
    	if (mIncompleteRequest != null) 
    		s.append("\t\tIncompleteRequest: ").append(mIncompleteRequest.toString()).append('\n');
    	
    	if (mConnection == null) {
    		s.append("\t\tCONNECTION IS NULL\n");
    	} else {
    		s.append("\t\t").append(mConnection.toString()).append(" matcher=");
    		OzMatcher matcher = mConnection.getMatcher();
    		s.append(matcher.toString()).append('\n');
    	}
    	
    	s.append("\t\tStartedTLS=").append(mStartedTLS ? "true" : "false").append('\n');
    	s.append("\t\tGoodbyeSent=").append(mGoodbyeSent ? "true" : "false").append('\n');
    	
    	try {
    		w.write(s.toString());
    	} catch(IOException e) {};
    }

    public DateFormat getDateFormat() {
        return mDateFormat;
    }

    public DateFormat getZimbraFormat() {
        return mZimbraFormat;
    }

    private final class ImapCommand {
        static final int CMD_CAPABILITY   = 1;
        static final int CMD_NOOP         = 2;
        static final int CMD_LOGOUT       = 3;
        static final int CMD_STARTTLS     = 4;
        static final int CMD_AUTHENTICATE = 5;
        static final int CMD_LOGIN        = 6;
        static final int CMD_SELECT       = 7;
        static final int CMD_EXAMINE      = 8;
        static final int CMD_CREATE       = 9;
        static final int CMD_DELETE       = 10;
        static final int CMD_RENAME       = 11;
        static final int CMD_SUBSCRIBE    = 12;
        static final int CMD_UNSUBSCRIBE  = 13;
        static final int CMD_LIST         = 14;
        static final int CMD_LSUB         = 15;
        static final int CMD_STATUS       = 16;
        static final int CMD_APPEND       = 17;
        static final int CMD_CHECK        = 18;
        static final int CMD_CLOSE        = 19;
        static final int CMD_EXPUNGE      = 20;
        static final int CMD_SEARCH       = 21;
        static final int CMD_FETCH        = 22;
        static final int CMD_STORE        = 23;
        static final int CMD_COPY         = 24;
        static final int CMD_UNSELECT     = 25;
        static final int CMD_ID           = 26;
        static final int CMD_IDLE         = 27;
        static final int CMD_SETQUOTA     = 28;
        static final int CMD_GETQUOTA     = 29;
        static final int CMD_GETQUOTAROOT = 30;
        static final int CMD_NAMESPACE    = 31;

        boolean mValid;
        String  mTag;
        int     mCommand;
        List<Object> mArguments = new LinkedList<Object>();

        ImapCommand(OzImapRequest req, ImapSession session) throws IOException, ImapException {
            if (session != null && session.isIdle()) {
                mCommand = CMD_IDLE;  mValid = true;
                mArguments.add(IDLE_STOP);
                try {
                    mArguments.add(new Boolean(req.readAtom().equals("DONE") && req.eof()));
                } catch (ImapParseException ipe) {
                    mArguments.add(Boolean.FALSE);
                }
                return;
            }
            mTag = req.readTag();

            Boolean byUID = Boolean.FALSE;
            req.skipSpace();
            String command = req.readAtom();
            do {
                switch (command.charAt(0)) {
                    case 'A':
                        if (command.equals("AUTHENTICATE")) {
                            mCommand = CMD_AUTHENTICATE;
                            req.skipSpace();  mArguments.add(req.readAtom());
                            mValid = req.eof();
                            return;
                        } else if (command.equals("APPEND")) {
                            mCommand = CMD_APPEND;
                            List tags = new ArrayList();  Date date = null;
                            req.skipSpace();  mArguments.add(req.readFolder());
                            req.skipSpace();
                            if (req.peekChar() == '(') {
                                tags = req.readFlags();  req.skipSpace();
                            }
                            if (req.peekChar() == '"') {
                                date = req.readDate(mTimeFormat);  req.skipSpace();
                            }
                            mArguments.add(tags);  mArguments.add(date);
                            mArguments.add(req.readLiteral());
                            mValid = req.eof();
                            return;
                        }
                        break;
                    case 'C':
                        if (command.equals("CAPABILITY")) {
                            mCommand = CMD_CAPABILITY;  mValid = req.eof();
                            return;
                        } else if (command.equals("CLOSE")) {
                            mCommand = CMD_CLOSE;  mValid = req.eof();
                            return;
                        } else if (command.equals("COPY")) {
                            mCommand = CMD_COPY;
                            req.skipSpace();  mArguments.add(req.readSequence());
                            req.skipSpace();  mArguments.add(req.readFolder());
                            mArguments.add(byUID);
                            mValid = req.eof();
                            return;
                        } else if (command.equals("CREATE")) {
                            mCommand = CMD_CREATE;
                            req.skipSpace();  mArguments.add(req.readFolder());
                            mValid = req.eof();
                            return;
                        } else if (command.equals("CHECK")) {
                            mCommand = CMD_CHECK;  mValid = req.eof();
                            return;
                        }
                        break;
                    case 'D':
                        if (command.equals("DONE")) {
                            mCommand = CMD_IDLE;  mArguments.add(Boolean.FALSE);
                            mValid = req.eof();
                            return;
                        } else if (command.equals("DELETE")) {
                            mCommand = CMD_DELETE;
                            req.skipSpace();  mArguments.add(req.readFolder());
                            mValid = req.eof();
                            return;
                        }
                        break;
                    case 'E':
                        if (command.equals("EXPUNGE")) {
                            mCommand = CMD_EXPUNGE;  mArguments.add(byUID);
                            String sequence = null;
                            if (byUID == Boolean.TRUE) {
                                req.skipSpace();  sequence = req.readSequence();
                            }
                            mArguments.add(sequence);
                            mValid = req.eof();
                            return;
                        } else if (command.equals("EXAMINE")) {
                            mCommand = CMD_EXAMINE;
                            req.skipSpace();  mArguments.add(req.readFolder());
                            mValid = req.eof();
                            return;
                        }
                        break;
                    case 'F':
                        if (command.equals("FETCH")) {
                            mCommand = CMD_FETCH;
                            List<ImapPartSpecifier> parts = new ArrayList<ImapPartSpecifier>();
                            req.skipSpace();  mArguments.add(req.readSequence());
                            req.skipSpace();  mArguments.add(new Integer(req.readFetch(parts)));
                            mArguments.add(parts);  mArguments.add(byUID);
                            mValid = req.eof();
                            return;
                        }
                        break;
                    case 'G':
                        if (command.equals("GETQUOTA")) {
                            mCommand = CMD_GETQUOTA;
                            req.skipSpace();  mArguments.add(req.readAstring());
                            mValid = req.eof();
                            return;
                        } else if (command.equals("GETQUOTAROOT")) {
                            mCommand = CMD_GETQUOTAROOT;
                            req.skipSpace();  mArguments.add(req.readFolder());
                            mValid = req.eof();
                            return;
                        }
                        break;
                    case 'I':
                        if (command.equals("IDLE")) {
                            mCommand = CMD_IDLE;  mValid = req.eof();
                            mArguments.add(IDLE_START);  mArguments.add(Boolean.TRUE);
                            return;
                        } else if (command.equals("ID")) {
                            mCommand = CMD_ID;
                            req.skipSpace();  mArguments.add(req.readParameters(true));
                            mValid = req.eof();
                            return;
                        }
                        break;
                    case 'L':
                        if (command.equals("LIST")) {
                            mCommand = CMD_LIST;
                            req.skipSpace();  mArguments.add(req.readEscapedFolder());
                            req.skipSpace();  mArguments.add(req.readFolderPattern());
                            mValid = req.eof();
                            return;
                        } if (command.equals("LSUB")) {
                            mCommand = CMD_LSUB;
                            req.skipSpace();  mArguments.add(req.readEscapedFolder());
                            req.skipSpace();  mArguments.add(req.readFolderPattern());
                            mValid = req.eof();
                            return;
                        } else if (command.equals("LOGIN")) {
                            mCommand = CMD_LOGIN;
                            req.skipSpace();  mArguments.add(req.readAstring());
                            req.skipSpace();  mArguments.add(req.readAstring());
                            mValid = req.eof();
                            return;
                        } else if (command.equals("LOGOUT")) {
                            mCommand = CMD_LOGOUT;  mValid = req.eof();
                            return;
                        }
                        break;
                    case 'N':
                        if (command.equals("NOOP")) {
                            mCommand = CMD_NOOP;  mValid = req.eof();
                            return;
                        } else if (command.equals("NAMESPACE")) {
                            mCommand = CMD_NAMESPACE;  mValid = req.eof();
                            return;
                        }
                        break;
                    case 'R':
                        if (command.equals("RENAME")) {
                            mCommand = CMD_RENAME;
                            req.skipSpace();  mArguments.add(req.readFolder());
                            req.skipSpace();  mArguments.add(req.readFolder());
                            mValid = req.eof();
                            return;
                        }
                        break;
                    case 'S':
                        if (command.equals("STORE")) {
                            mCommand = CMD_STORE;
                            Byte operation = STORE_REPLACE;  Boolean silent = Boolean.FALSE;
                            req.skipSpace();  mArguments.add(req.readSequence());
                            req.skipSpace();
                            switch (req.peekChar()) {
                                case '+':  req.skipChar('+');  operation = STORE_ADD;     break;
                                case '-':  req.skipChar('-');  operation = STORE_REMOVE;  break;
                            }
                            String cmd = req.readAtom();
                            if (cmd.equals("FLAGS.SILENT"))  silent = Boolean.TRUE;
                            else if (!cmd.equals("FLAGS"))   throw new ImapParseException(mTag, "invalid store-att-flags");
                            req.skipSpace();  mArguments.add(req.readFlags());
                            mArguments.add(operation);  mArguments.add(silent);  mArguments.add(byUID);
                            mValid = req.eof();
                            return;
                        } else if (command.equals("SEARCH")) {
                            mCommand = CMD_SEARCH;
                            TreeMap<Integer, Object> insertions = new TreeMap<Integer, Object>();
                            req.skipSpace();  mArguments.add(req.readSearch(insertions));
                            mArguments.add(insertions);  mArguments.add(byUID);
                            mValid = req.eof();
                            return;
                        } else if (command.equals("SELECT")) {
                            mCommand = CMD_SELECT;
                            req.skipSpace();  mArguments.add(req.readFolder());
                            mValid = req.eof();
                            return;
                        } else if (command.equals("STARTTLS")) {
                            mCommand = CMD_STARTTLS;  mValid = req.eof();
                            return;
                        } else if (command.equals("STATUS")) {
                            mCommand = CMD_STATUS;
                            int statusFlags = 0;
                            req.skipSpace();  mArguments.add(req.readFolder());
                            req.skipSpace();  req.skipChar('(');
                            while (req.peekChar() != ')') {
                                String flag = req.readAtom();
                                if (flag.equals("MESSAGES"))          statusFlags |= STATUS_MESSAGES;
                                else if (flag.equals("RECENT"))       statusFlags |= STATUS_RECENT;
                                else if (flag.equals("UIDNEXT"))      statusFlags |= STATUS_UIDNEXT;
                                else if (flag.equals("UIDVALIDITY"))  statusFlags |= STATUS_UIDVALIDITY;
                                else if (flag.equals("UNSEEN"))       statusFlags |= STATUS_UNSEEN;
                                else
                                    throw new ImapParseException(mTag, "unknown STATUS attribute \"" + flag + '"');
                                if (req.peekChar() != ')')
                                    req.skipSpace();
                            }
                            req.skipChar(')');  mArguments.add(new Integer(statusFlags));
                            mValid = req.eof();
                            return;
                        } else if (command.equals("SUBSCRIBE")) {
                            mCommand = CMD_SUBSCRIBE;
                            req.skipSpace();  mArguments.add(req.readFolder());
                            mValid = req.eof();
                            return;
                        } else if (command.equals("SETQUOTA")) {
                            mCommand = CMD_SETQUOTA;
                            HashMap<String, String> limits = new HashMap<String, String>();
                            req.skipSpace();  mArguments.add(req.readAstring());
                            req.skipSpace();  req.skipChar('(');
                            while (req.peekChar() != ')') {
                                String resource = req.readAtom();  req.skipSpace();
                                limits.put(resource, req.readNumber());
                            }
                            req.skipChar(')');  mValid = req.eof();
                            return;
                        }
                        break;
                    case 'U':
                        if (command.equals("UID")) {
                            req.skipSpace();  command = req.readAtom();
                            if (!command.equals("FETCH") && !command.equals("SEARCH") && !command.equals("COPY") && !command.equals("STORE") && !command.equals("EXPUNGE"))
                                throw new ImapParseException(mTag, "command not permitted with UID");
                            byUID = Boolean.TRUE;
                            continue;
                        } else if (command.equals("UNSUBSCRIBE")) {
                            mCommand = CMD_UNSUBSCRIBE;
                            req.skipSpace();  mArguments.add(req.readFolder());
                            mValid = req.eof();
                            return;
                        } else if (command.equals("UNSELECT")) {
                            mCommand = CMD_UNSELECT;  mValid = req.eof();
                            return;
                        }
                        break;
                }
            } while (byUID == Boolean.TRUE);

            throw new ImapParseException(mTag, "command not implemented");
        }

        boolean execute() throws IOException {
            if (!mValid)
                return true;
            switch (mCommand) {
                case CMD_CAPABILITY:   return doCAPABILITY(mTag);
                case CMD_NOOP:         return doNOOP(mTag);
                case CMD_ID:           return doID(mTag, mArguments);
                case CMD_AUTHENTICATE: return doAUTHENTICATE(mTag, mArguments);
                case CMD_STARTTLS:     return doSTARTTLS(mTag);
                case CMD_LOGOUT:       return doLOGOUT(mTag);
                case CMD_LOGIN:        return doLOGIN(mTag, mArguments);
                case CMD_SELECT:       return doSELECT(mTag, mArguments);
                case CMD_EXAMINE:      return doEXAMINE(mTag, mArguments);
                case CMD_CREATE:       return doCREATE(mTag, mArguments);
                case CMD_DELETE:       return doDELETE(mTag, mArguments);
                case CMD_RENAME:       return doRENAME(mTag, mArguments);
                case CMD_SUBSCRIBE:    return doSUBSCRIBE(mTag, mArguments);
                case CMD_UNSUBSCRIBE:  return doUNSUBSCRIBE(mTag, mArguments);
                case CMD_LIST:         return doLIST(mTag, mArguments);
                case CMD_LSUB:         return doLSUB(mTag, mArguments);
                case CMD_STATUS:       return doSTATUS(mTag, mArguments);
                case CMD_APPEND:       return doAPPEND(mTag, mArguments);
                case CMD_IDLE:         return doIDLE(mTag, mArguments);
                case CMD_SETQUOTA:     return doSETQUOTA(mTag, mArguments);
                case CMD_GETQUOTA:     return doGETQUOTA(mTag, mArguments);
                case CMD_GETQUOTAROOT: return doGETQUOTAROOT(mTag, mArguments);
                case CMD_NAMESPACE:    return doNAMESPACE(mTag, mArguments);
                case CMD_CHECK:        return doCHECK(mTag);
                case CMD_CLOSE:        return doCLOSE(mTag);
                case CMD_UNSELECT:     return doUNSELECT(mTag);
                case CMD_EXPUNGE:      return doEXPUNGE(mTag, mArguments);
                case CMD_SEARCH:       return doSEARCH(mTag, mArguments);
                case CMD_FETCH:        return doFETCH(mTag, mArguments);
                case CMD_STORE:        return doSTORE(mTag, mArguments);
                case CMD_COPY:         return doCOPY(mTag, mArguments);
                default:
                    sendBAD(mTag, "command not implemented");
                    return true;
            }
        }
    }

    private static final boolean STOP_PROCESSING = false;
    private static final boolean CONTINUE_PROCESSING = true;

    private boolean processCommand() throws IOException {
        OzImapRequest req = new OzImapRequest(mCurrentRequestTag, mCurrentRequestData, mSession);
        boolean keepGoing = CONTINUE_PROCESSING;
        try {
            if (mSession != null)
                ZimbraLog.addAccountNameToContext(mSession.getUsername());

            ImapCommand cmd = new ImapCommand(req, mSession);
            mIncompleteRequest = req = null;
            if (!cmd.mValid)
                throw new ImapParseException(cmd.mTag, "excess characters at end of command");

            // check account status before executing command
            if (mMailbox != null)
                try {
                    Account account = mMailbox.getAccount();
                    if (account == null || !account.getAccountStatus().equals(Provisioning.ACCOUNT_STATUS_ACTIVE)) {
                        ZimbraLog.imap.warn("account missing or not active; dropping connection");
                        return STOP_PROCESSING;
                    }
                } catch (ServiceException e) {
                    ZimbraLog.imap.warn("error checking account status; dropping connection", e);
                    return STOP_PROCESSING;
                }

            // FIXME: need to enqueue and process off queue, I think...
            keepGoing = cmd.execute();
        } catch (ImapContinuationException ice) {
            mIncompleteRequest = req.rewind();
            if (ice.sendContinuation)
                sendContinuation();
        } catch (ImapParseException ipe) {
            mIncompleteRequest = null;
            if (ipe.mTag == null)
                sendUntagged("BAD " + ipe.getMessage(), true);
            else if (ipe.mCode != null)
                sendNO(ipe.mTag, '[' + ipe.mCode + "] " + ipe.getMessage());
            else
                sendBAD(ipe.mTag, ipe.getMessage());
        } catch (ImapTerminatedException ite) {
            mIncompleteRequest = null;
            keepGoing = STOP_PROCESSING;
        } catch (ImapException ie) {
            ZimbraLog.imap.error("unexpected (and uncaught) IMAP exception type", ie);
            mIncompleteRequest = null;
            keepGoing = STOP_PROCESSING;
        }

        return keepGoing;
    }

    boolean checkState(String tag, byte required) throws IOException {
        byte state = ImapSession.getState(mSession);
        if (required == ImapSession.STATE_NOT_AUTHENTICATED && state != ImapSession.STATE_NOT_AUTHENTICATED) {
            sendNO(tag, "must be in NOT AUTHENTICATED state");
            return false;
        } else if (required == ImapSession.STATE_AUTHENTICATED && (state == ImapSession.STATE_NOT_AUTHENTICATED || state == ImapSession.STATE_LOGOUT)) {
            sendNO(tag, "must be in AUTHENTICATED or SELECTED state");
            return false;
        } else if (required == ImapSession.STATE_SELECTED && state != ImapSession.STATE_SELECTED) {
            sendNO(tag, "must be in SELECTED state");
            return false;
        } else
            return true;
    }

    boolean canContinue(ServiceException e) {
        return e.getCode() == MailServiceException.MAINTENANCE ? STOP_PROCESSING : CONTINUE_PROCESSING;
    }

    private OperationContext getContext() throws ServiceException {
        if (mSession == null)
            throw ServiceException.AUTH_REQUIRED();
        return mSession.getContext();
    }


    boolean doCAPABILITY(String tag) throws IOException {
        sendCapability();
        sendOK(tag, "CAPABILITY completed");
        return CONTINUE_PROCESSING;
    }

    private boolean allowCleartextLogin() {
        return Boolean.valueOf(mConnection.getProperty(PROPERTY_ALLOW_CLEARTEXT_LOGINS, Boolean.FALSE.toString())).booleanValue();
    }
    
    private void sendCapability() throws IOException {
        // [IMAP4rev1]        RFC 3501: Internet Message Access Protocol - Version 4rev1
        // [LOGINDISABLED]    RFC 3501: Internet Message Access Protocol - Version 4rev1
        // [STARTTLS]         RFC 3501: Internet Message Access Protocol - Version 4rev1
        // [AUTH=PLAIN]       RFC 2595: Using TLS with IMAP, POP3 and ACAP
        // [CHILDREN]         RFC 3348: IMAP4 Child Mailbox Extension
        // [ID]               RFC 2971: IMAP4 ID Extension
        // [IDLE]             RFC 2177: IMAP4 IDLE command
        // [LITERAL+]         RFC 2088: IMAP4 non-synchronizing literals
        // [LOGIN-REFERRALS]  RFC 2221: IMAP4 Login Referrals
        // [NAMESPACE]        RFC 2342: IMAP4 Namespace
        // [QUOTA]            RFC 2087: IMAP4 QUOTA extension
        // [UIDPLUS]          RFC 2359: IMAP4 UIDPLUS extension
        // [UNSELECT]         RFC 3691: IMAP UNSELECT command
        boolean authenticated = mSession != null;
        String nologin =  allowCleartextLogin() || mStartedTLS || authenticated ? "" : "LOGINDISABLED ";
        String starttls = mStartedTLS || authenticated ? "" : "STARTTLS ";
//        String plain = !mStartedTLS || authenticated ? "" : "AUTH=PLAIN "; 
        sendUntagged("CAPABILITY IMAP4rev1 " + nologin + starttls + "CHILDREN ID IDLE LITERAL+ LOGIN-REFERRALS NAMESPACE QUOTA UIDPLUS UNSELECT");
    }

    boolean doNOOP(String tag) throws IOException {
        if (mMailbox != null)
            sendNotifications(true, false);
        sendOK(tag, "NOOP completed");
        return CONTINUE_PROCESSING;
    }

    // RFC 2971 3: "The sole purpose of the ID extension is to enable clients and servers
    //              to exchange information on their implementations for the purposes of
    //              statistical analysis and problem determination.
    boolean doID(String tag, List args) throws IOException {
        Map attrs = (Map) args.remove(0);
        if (attrs != null)
            ZimbraLog.imap.info("IMAP client identified as: " + attrs);

        sendUntagged("ID (\"NAME\" \"Zimbra\" \"VERSION\" \"" + BuildInfo.VERSION + "\" \"RELEASE\" \"" + BuildInfo.RELEASE + "\")");
        sendOK(tag, "ID completed");
        return CONTINUE_PROCESSING;
    }

    boolean doSTARTTLS(String tag) throws IOException {
        if (!checkState(tag, ImapSession.STATE_NOT_AUTHENTICATED))
            return CONTINUE_PROCESSING;
        else if (mStartedTLS) {
            sendNO(tag, "TLS already started");
            return CONTINUE_PROCESSING;
        }
        sendOK(tag, "Begin TLS negotiation now");

        boolean debugLogging = LC.nio_imap_debug_logging.booleanValue();
        mConnection.addFilter(new OzTLSFilter(mConnection, debugLogging, ZimbraLog.imap));

        mStartedTLS = true;

        return CONTINUE_PROCESSING;
    }


    boolean doAUTHENTICATE(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_NOT_AUTHENTICATED))
            return CONTINUE_PROCESSING;

//        String mechanism = (String) args.remove(0);
//
//        if (mechanism.equals("PLAIN")) {
//            if (!mStartedTLS) {
//                sendNO(tag, "cleartext logins disabled");
//                return CONTINUE_PROCESSING;
//            }
//        }

        // no AUTHENTICATE mechanisms are supported yet
        sendNO(tag, "mechanism not supported");
        return CONTINUE_PROCESSING;
    }

    boolean doLOGOUT(String tag) throws IOException {
        sendUntagged(ImapServer.getGoodbye());
        if (mSession != null)
            mSession.loggedOut();
        mGoodbyeSent = true;
        sendOK(tag, "LOGOUT completed");
        return STOP_PROCESSING;
    }

    boolean doLOGIN(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_NOT_AUTHENTICATED))
            return CONTINUE_PROCESSING;
        else if (!mStartedTLS && !allowCleartextLogin()) {
            sendNO(tag, "cleartext logins disabled");
            return CONTINUE_PROCESSING;
        }

        String username = (String) args.remove(0);
        String password = (String) args.remove(0);

        // the Windows Mobile 5 hacks are enabled by appending "/wm" to the username
        EnabledHack hack = EnabledHack.NONE;
        if (username.endsWith("/wm")) {
            username = username.substring(0, username.length() - 3);
            hack = EnabledHack.WM5;
        }

        Mailbox mailbox = null;
        ImapSession session = null;
        try {
            Provisioning prov = Provisioning.getInstance();
            Account account = prov.getAccountByName(username);
            if (account == null) {
                sendNO(tag, "LOGIN failed");
                return CONTINUE_PROCESSING;
            }
            prov.authAccount(account, password);
            if (!account.getBooleanAttr(Provisioning.A_zimbraImapEnabled, false)) {
                sendNO(tag, "account does not have IMAP access enabled");
                return CONTINUE_PROCESSING;
            } else if (!account.isCorrectHost()) {
                String correctHost = account.getAttr(Provisioning.A_zimbraMailHost);
                ZimbraLog.imap.info("LOGIN failed; should be on host " + correctHost);
                if (correctHost == null || correctHost.trim().equals(""))
                    sendNO(tag, "LOGIN failed [wrong host]");
                else
                    sendNO(tag, "[REFERRAL imap://" + URLEncoder.encode(account.getName(), "utf-8") + '@' + correctHost + "/] LOGIN failed");
                return CONTINUE_PROCESSING;
            }
            session = (ImapSession) SessionCache.getNewSession(account.getId(), SessionCache.SESSION_IMAP);
            if (session == null) {
                sendNO(tag, "LOGIN failed");
                return CONTINUE_PROCESSING;
            }
            session.enableHack(hack);
            mailbox = session.getMailbox();
            synchronized (mailbox) {
                session.setUsername(account.getName());
                session.cacheFlags(mailbox);
                for (Tag ltag : mailbox.getTagList(session.getContext()))
                    session.cacheTag(ltag);
            }

            // Session timeout will take care of closing an IMAP connection with
            // no activity.
            if (ZimbraLog.imap.isDebugEnabled()) ZimbraLog.imap.debug("disabling unauth connection alarm");
            mConnection.cancelAlarm();
        } catch (ServiceException e) {
            if (mSession != null)
            	mSession.clearTagCache();
            ZimbraLog.imap.warn("LOGIN failed", e);
            if (e.getCode() == AccountServiceException.CHANGE_PASSWORD)
                sendNO(tag, "[ALERT] password must be changed before IMAP login permitted");
            else if (e.getCode() == AccountServiceException.MAINTENANCE_MODE)
                sendNO(tag, "[ALERT] account undergoing maintenance; please try again later");
            else
                sendNO(tag, "LOGIN failed");
            return canContinue(e);
        }

        // XXX: could use mSession.getMailbox() instead of saving a copy...
        mMailbox = mailbox;
        mSession = session;
        mSession.setHandler(this);

        sendCapability();
        sendOK(tag, "LOGIN completed");
        return CONTINUE_PROCESSING;
    }

    boolean doSELECT(String tag, List args) throws IOException {
        return selectFolder(tag, args, "SELECT");
    }

    boolean doEXAMINE(String tag, List args) throws IOException {
        return selectFolder(tag, args, "EXAMINE");
    }

    private boolean selectFolder(String tag, List args, String command) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        // 6.3.1: "The SELECT command automatically deselects any currently selected mailbox 
        //         before attempting the new selection.  Consequently, if a mailbox is selected
        //         and a SELECT command that fails is attempted, no mailbox is selected."
        if (mSession.isSelected())
            mSession.deselectFolder();

        String folderName = (String) args.remove(0);

        boolean writable = command.equals("SELECT");
        ImapFolder i4folder = null;
        try {
            synchronized (mMailbox) {
                i4folder = new ImapFolder(folderName, writable, mMailbox, getContext());
                writable = i4folder.isWritable();
            }
        } catch (ServiceException e) {
            if (e.getCode() == MailServiceException.NO_SUCH_FOLDER)
                ZimbraLog.imap.info(command + " failed: no such folder: " + folderName);
            else
                ZimbraLog.imap.warn(command + " failed", e);
            sendNO(tag, command + " failed");
            return canContinue(e);
        }

        mSession.selectFolder(i4folder);

        // note: not sending back a "* OK [UIDNEXT ....]" response
        //    6.3.1: "If this is missing, the client can not make any assumptions about the
        //            next unique identifier value."
        // note: \Deleted is a session flag for us and is not listed in PERMANENTFLAGS
        //    7.1: "If the client attempts to STORE a flag that is not in the PERMANENTFLAGS
        //          list, the server will either ignore the change or store the state change
        //          for the remainder of the current session only."
        // FIXME: hardcoding "* 0 RECENT"
        sendUntagged(i4folder.getSize() + " EXISTS");
        sendUntagged(0 + " RECENT");
        if (i4folder.getFirstUnread() > 0)
        	sendUntagged("OK [UNSEEN " + i4folder.getFirstUnread() + ']');
        sendUntagged("OK [UIDVALIDITY " + i4folder.getUIDValidity() + ']');
        sendUntagged("FLAGS (" + mSession.getFlagList(false) + ')');
        sendUntagged("OK [PERMANENTFLAGS (" + (writable ? mSession.getFlagList(true) + " \\*" : "") + ")]");
        sendOK(tag, (writable ? "[READ-WRITE] " : "[READ-ONLY] ") + command + " completed");
        return CONTINUE_PROCESSING;
    }

    boolean doCREATE(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String folderName = (String) args.remove(0);
        if (!folderName.startsWith("/"))
            folderName = '/' + folderName;
        if (!ImapFolder.isPathCreatable(folderName)) {
            ZimbraLog.imap.info("CREATE failed: hidden folder or parent: " + folderName, null);
            sendNO(tag, "CREATE failed");
            return CONTINUE_PROCESSING;
        }

        try {
            mMailbox.createFolder(getContext(), folderName, (byte) 0, MailItem.TYPE_MESSAGE);
        } catch (ServiceException e) {
            String cause = "CREATE failed";
            if (e.getCode() == MailServiceException.CANNOT_CONTAIN)
                cause += ": superior mailbox has \\Noinferiors set";
            else if (e.getCode() == MailServiceException.ALREADY_EXISTS)
                cause += ": mailbox already exists";
            else if (e.getCode() == MailServiceException.INVALID_NAME)
                cause += ": invalid mailbox name";
            ZimbraLog.imap.warn(cause, e);
            sendNO(tag, cause);
            return canContinue(e);
        }

        sendNotifications(true, false);
        sendOK(tag, "CREATE completed");
        return CONTINUE_PROCESSING;
    }

    boolean doDELETE(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String folderName = (String) args.remove(0);

        int folderId = 0;
        try {
            synchronized (mMailbox) {
                Folder folder = mMailbox.getFolderByPath(getContext(), folderName);
                if (!ImapFolder.isFolderVisible(folder)) {
                    ZimbraLog.imap.info("cannot delete IMAP-invisible folder: " + folder.getPath());
                    sendNO(tag, "DELETE failed");
                    return CONTINUE_PROCESSING;
                } else if (!folder.isMutable()) {
                    ZimbraLog.imap.info("cannot delete system folder: " + folder.getPath());
                    sendNO(tag, "DELETE failed");
                    return CONTINUE_PROCESSING;
                }

                folderId = folder.getId();
                if (!folder.hasSubfolders())
                    mMailbox.delete(getContext(), folderId, MailItem.TYPE_FOLDER);
                else {
                    // 6.3.4: "It is permitted to delete a name that has inferior hierarchical
                    //         names and does not have the \Noselect mailbox name attribute.
                    //         In this case, all messages in that mailbox are removed, and the
                    //         name will acquire the \Noselect mailbox name attribute."
                    mMailbox.emptyFolder(getContext(), folderId, false);
                    // FIXME: add \Deleted flag to folder
                }
            }
        } catch (ServiceException e) {
            if (e.getCode() == MailServiceException.NO_SUCH_FOLDER)
                ZimbraLog.imap.info("DELETE failed: no such folder: " + folderName);
            else
                ZimbraLog.imap.warn("DELETE failed", e);
            sendNO(tag, "DELETE failed");
            return canContinue(e);
        }

        if (mSession.isSelected() && folderId == mSession.getFolder().getId())
            mSession.deselectFolder();

        sendNotifications(true, false);
        sendOK(tag, "DELETE completed");
        return CONTINUE_PROCESSING;
    }

    boolean doRENAME(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String oldName = (String) args.remove(0);
        String newName = (String) args.remove(0);
        if (!newName.startsWith("/"))
            newName = '/' + newName;

        try {
            synchronized (mMailbox) {
                int folderId = mMailbox.getFolderByPath(getContext(), oldName).getId();
                if (folderId != Mailbox.ID_FOLDER_INBOX)
                    mMailbox.renameFolder(getContext(), folderId, newName);
                else {
                    // 6.3.5: "Renaming INBOX is permitted, and has special behavior.  It moves all
                    //         messages in INBOX to a new mailbox with the given name, leaving INBOX
                    //         empty.  If the server implementation supports inferior hierarchical
                    //         names of INBOX, these are unaffected by a rename of INBOX."
                    // FIXME: move the contents (but not the subfolders) of INBOX to the new folder
                    ZimbraLog.imap.info("RENAME failed: RENAME of INBOX not supported");
                    sendNO(tag, "RENAME failed: RENAME of INBOX not supported");
                    return CONTINUE_PROCESSING;
                }
            }
        } catch (ServiceException e) {
            if (e.getCode() == MailServiceException.NO_SUCH_FOLDER)
                ZimbraLog.imap.info("RENAME failed: no such folder: " + oldName);
            else
                ZimbraLog.imap.warn("RENAME failed", e);
            sendNO(tag, "RENAME failed");
            return canContinue(e);
        }

        // note: if ImapFolder contains a pathname, we may need to update mSelectedFolder
        sendNotifications(true, false);
        sendOK(tag, "RENAME completed");
        return CONTINUE_PROCESSING;
    }

    boolean doSUBSCRIBE(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String folderName = (String) args.remove(0);
        if (folderName.startsWith("/"))
            folderName = folderName.substring(1);

        try {
            synchronized (mMailbox) {
                Folder folder = mMailbox.getFolderByPath(getContext(), folderName);
                if (!ImapFolder.isFolderVisible(folder)) {
                    ZimbraLog.imap.info("SUBSCRIBE failed: folder not visible: " + folderName);
                    sendNO(tag, "SUBSCRIBE failed");
                    return CONTINUE_PROCESSING;
                }
                mSession.subscribe(folder);
            }
        } catch (ServiceException e) {
            if (e.getCode() == MailServiceException.NO_SUCH_FOLDER)
                ZimbraLog.imap.info("SUBSCRIBE failed: no such folder: " + folderName);
            else
                ZimbraLog.imap.warn("SUBSCRIBE failed", e);
            sendNO(tag, "SUBSCRIBE failed");
            return canContinue(e);
        }

        sendNotifications(true, false);
        sendOK(tag, "SUBSCRIBE completed");
        return CONTINUE_PROCESSING;
    }

    boolean doUNSUBSCRIBE(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String folderName = (String) args.remove(0);
        if (folderName.startsWith("/"))
            folderName = folderName.substring(1);

        try {
            synchronized (mMailbox) {
                mSession.unsubscribe(mMailbox.getFolderByPath(getContext(), folderName));
            }
        } catch (MailServiceException.NoSuchItemException nsie) {
            ZimbraLog.imap.info("UNSUBSCRIBE failure skipped: no such folder: " + folderName);
        } catch (ServiceException e) {
            ZimbraLog.imap.warn("UNSUBSCRIBE failed", e);
            sendNO(tag, "UNSUBSCRIBE failed");
            return canContinue(e);
        }

        sendNotifications(true, false);
        sendOK(tag, "UNSUBSCRIBE completed");
        return CONTINUE_PROCESSING;
    }

    boolean doLIST(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String referenceName = (String) args.remove(0);
        String mailboxName   = (String) args.remove(0);

        if (mailboxName.equals("")) {
            // 6.3.8: "An empty ("" string) mailbox name argument is a special request to return
            //         the hierarchy delimiter and the root name of the name given in the reference."
            sendUntagged("LIST (\\Noselect) \"/\" \"\"");
            sendOK(tag, "LIST completed");
            return CONTINUE_PROCESSING;
        }

        String pattern = mailboxName;
        if (!mailboxName.startsWith("/")) {
            if (referenceName.endsWith("/"))  pattern = referenceName + mailboxName;
            else                              pattern = referenceName + '/' + mailboxName;
        }
        if (pattern.startsWith("/"))
            pattern = pattern.substring(1);
        ArrayList<String> matches = new ArrayList<String>();
        try {
            synchronized (mMailbox) {
                Folder root = mMailbox.getFolderById(getContext(), Mailbox.ID_FOLDER_USER_ROOT);
                for (Folder folder : root.getSubfolderHierarchy()) {
                    if (!ImapFolder.isFolderVisible(folder))
                        continue;
                    String path = folder.getPath().substring(1);
                    // FIXME: need to determine "name attributes" for mailbox (\Marked, \Unmarked, \Noinferiors, \Noselect)
                    if (path.toUpperCase().matches(pattern))
                        matches.add("LIST (" + getFolderAttributes(folder) + ") \"/\" " + ImapFolder.encodeFolder(path));
                }
            }
        } catch (ServiceException e) {
            ZimbraLog.imap.warn("LIST failed", e);
            sendNO(tag, "LIST failed");
            return canContinue(e);
        }

        for (String match : matches)
            sendUntagged(match);
        sendNotifications(true, false);
        sendOK(tag, "LIST completed");
        return CONTINUE_PROCESSING;
    }

    private static final String[] FOLDER_ATTRIBUTES = {
        "\\HasNoChildren",            "\\HasChildren",
        "\\HasNoChildren \\Noselect", "\\HasChildren \\Noselect",
        "\\HasNoChildren \\Noinferiors"
    };

    private String getFolderAttributes(Folder folder) {
        int attributes = (folder.hasSubfolders() ? 0x01 : 0x00);
        attributes    |= (!ImapFolder.isFolderSelectable(folder) ? 0x02 : 0x00);
        attributes    |= (folder.getId() == Mailbox.ID_FOLDER_SPAM ? 0x04 : 0x00);
        return FOLDER_ATTRIBUTES[attributes];
    }

    boolean doLSUB(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String referenceName = (String) args.remove(0);
        String mailboxName   = (String) args.remove(0);

        String pattern = mailboxName;
        if (!mailboxName.startsWith("/")) {
            if (referenceName.endsWith("/"))  pattern = referenceName + mailboxName;
            else                              pattern = referenceName + '/' + mailboxName;
        }
        if (pattern.startsWith("/"))
            pattern = pattern.substring(1);

        try {
            Map<String, String> subs;
            synchronized (mMailbox) {
                subs = mSession.getMatchingSubscriptions(mMailbox, pattern);
                for (Map.Entry<String, String> hit : subs.entrySet()) {
                    Folder folder = null;
                    try {
                        folder = mMailbox.getFolderByPath(getContext(), hit.getKey());
                    } catch (MailServiceException.NoSuchItemException nsie) { }
                    // FIXME: need to determine "name attributes" for mailbox (\Marked, \Unmarked, \Noinferiors, \Noselect)
                    boolean visible = hit.getValue() != null && ImapFolder.isFolderVisible(folder);
                    String attributes = visible ? getFolderAttributes(folder) : "\\Noselect";
                    hit.setValue("LSUB (" + attributes + ") \"/\" " + ImapFolder.encodeFolder(hit.getKey()));
                }
            }

            for (String sub : subs.values())
                sendUntagged(sub);
        } catch (ServiceException e) {
            ZimbraLog.imap.warn("LSUB failed", e);
            sendNO(tag, "LSUB failed");
            return canContinue(e);
        }

        sendNotifications(true, false);
        sendOK(tag, "LSUB completed");
        return CONTINUE_PROCESSING;
    }

    private static final int STATUS_MESSAGES    = 0x01;
    private static final int STATUS_RECENT      = 0x02;
    private static final int STATUS_UIDNEXT     = 0x04;
    private static final int STATUS_UIDVALIDITY = 0x08;
    private static final int STATUS_UNSEEN      = 0x10;

    boolean doSTATUS(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String folderName = (String) args.remove(0);
        int status        = ((Integer) args.remove(0)).intValue();

        StringBuffer data = new StringBuffer();
        try {
            Folder folder = mMailbox.getFolderByPath(getContext(), folderName);
            if (!ImapFolder.isFolderVisible(folder)) {
                ZimbraLog.imap.info("STATUS failed: folder not visible: " + folderName);
                sendNO(tag, "STATUS failed");
                return CONTINUE_PROCESSING;
            }
            // note: we're not supporting UIDNEXT; see the comments in selectFolder()
            if ((status & STATUS_MESSAGES) != 0)
                data.append(data.length() > 0 ? " " : "").append("MESSAGES ").append(folder.getMessageCount());
            // FIXME: hardcoded "RECENT 0"
            if ((status & STATUS_RECENT) != 0)
                data.append(data.length() > 0 ? " " : "").append("RECENT ").append(0);
            if ((status & STATUS_UIDVALIDITY) != 0)
                data.append(data.length() > 0 ? " " : "").append("UIDVALIDITY ").append(ImapFolder.getUIDValidity(folder));
            if ((status & STATUS_UNSEEN) != 0)
                data.append(data.length() > 0 ? " " : "").append("UNSEEN ").append(folder.getUnreadCount());
        } catch (ServiceException e) {
            if (e.getCode() == MailServiceException.NO_SUCH_FOLDER)
                ZimbraLog.imap.info("STATUS failed: no such folder: " + folderName);
            else
                ZimbraLog.imap.warn("STATUS failed", e);
            sendNO(tag, "STATUS failed");
            return canContinue(e);
        }

        sendUntagged("STATUS " + ImapFolder.encodeFolder(folderName) + " (" + data + ')');
        sendNotifications(true, false);
        sendOK(tag, "STATUS completed");
        return CONTINUE_PROCESSING;
    }

    boolean doAPPEND(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String folderName = (String) args.remove(0);
        List   tagNames   = (List) args.remove(0);
        Date   date       = (Date) args.remove(0);
        byte[] content    = (byte[]) args.remove(0);

        // server uses UNIX time, so range-check specified date (is there a better place for this?)
        if (date != null && date.getTime() > Integer.MAX_VALUE * 1000L) {
            ZimbraLog.imap.info("APPEND failed: date out of range");
            sendNO(tag, "APPEND failed: date out of range");
            return CONTINUE_PROCESSING;
        }

        ArrayList<Tag> newTags = new ArrayList<Tag>();
        StringBuffer appendHint = new StringBuffer();
        try {
            synchronized (mMailbox) {
                Folder folder = mMailbox.getFolderByPath(getContext(), folderName);
                if (!ImapFolder.isFolderVisible(folder)) {
                    ZimbraLog.imap.info("APPEND failed: cannot APPEND to folder: " + folderName);
                    sendNO(tag, "APPEND failed");
                    return CONTINUE_PROCESSING;
                } else if (!ImapFolder.isFolderWritable(folder)) {
                    ZimbraLog.imap.info("APPEND failed: folder is READ-ONLY: " + folderName);
                    sendNO(tag, "APPEND failed: mailbox is READ-ONLY");
                    return CONTINUE_PROCESSING;
                }

                byte sflags = 0;
                int flagMask = Flag.FLAG_UNREAD;
                StringBuffer tagStr = new StringBuffer();
                if (tagNames != null) {
                    for (ImapFlag i4flag : findOrCreateTags(tagNames, newTags)) {
                        if (!i4flag.mPermanent)
                            sflags |= i4flag.mBitmask;
                        else if (Tag.validateId(i4flag.mId))
                            tagStr.append(tagStr.length() == 0 ? "" : ",").append(i4flag.mId);
                        else if (i4flag.mPositive)
                            flagMask |= i4flag.mBitmask;
                        else
                            flagMask &= ~i4flag.mBitmask;
                    }
                }

                boolean idxAttach = mMailbox.attachmentsIndexingEnabled();
                ParsedMessage pm = date != null ? new ParsedMessage(content, date.getTime(), idxAttach) :
                                                  new ParsedMessage(content, idxAttach);
                try {
                    if (!pm.getSender().equals("")) {
                        InternetAddress ia = new InternetAddress(pm.getSender());
                        if (AccountUtil.addressMatchesAccount(mMailbox.getAccount(), ia.getAddress()))
                            flagMask |= Flag.FLAG_FROM_ME;
                    }
                } catch (Exception e) { }

                Message msg = mMailbox.addMessage(getContext(), pm, folder.getId(), true, flagMask, tagStr.toString());
                if (msg != null) {
                    appendHint.append("[APPENDUID ").append(ImapFolder.getUIDValidity(folder))
                              .append(' ').append(msg.getImapUID()).append("] ");
                    if (sflags != 0 && mSession.isSelected()) {
                        ImapMessage i4msg = mSession.getFolder().getById(msg.getId());
                        if (i4msg != null)
                            i4msg.setSessionFlags(sflags);
                    }
                }
            }
        } catch (ServiceException e) {
            deleteTags(newTags);
            String msg = "APPEND failed";
            if (e.getCode() == MailServiceException.NO_SUCH_FOLDER) {
                ZimbraLog.imap.info("APPEND failed: no such folder: " + folderName);
                // 6.3.11: "Unless it is certain that the destination mailbox can not be created,
                //          the server MUST send the response code "[TRYCREATE]" as the prefix
                //          of the text of the tagged NO response."
                if (ImapFolder.isPathCreatable('/' + folderName))
                    msg = "[TRYCREATE] APPEND failed: no such mailbox";
            } else
                ZimbraLog.imap.warn("APPEND failed", e);
            sendNO(tag, msg);
            return canContinue(e);
        } catch (IOException e) {
            deleteTags(newTags);
            ZimbraLog.imap.warn("APPEND failed", e);
            sendNO(tag, "APPEND failed");
            return CONTINUE_PROCESSING;
        } catch (MessagingException e) {
            deleteTags(newTags);
            // FIXME: shouldn't fail if message parse fails; treat as a blob instead
            ZimbraLog.imap.warn("APPEND failed", e);
            sendNO(tag, "APPEND failed");
            return CONTINUE_PROCESSING;
        }

        sendNotifications(true, false);
        sendOK(tag, appendHint.append("APPEND completed").toString());
        return CONTINUE_PROCESSING;
    }

    private Tag createTag(String name) throws ServiceException {
        // notification will update mTags hash
        return mMailbox.createTag(getContext(), name, MailItem.DEFAULT_COLOR);
    }
    private List<ImapFlag> findOrCreateTags(List tagNames, List<Tag> newTags) throws ServiceException {
        if (tagNames == null || tagNames.size() == 0)
            return Collections.emptyList();
        List<ImapFlag> result = new ArrayList<ImapFlag>();
        for (int i = 0; i < tagNames.size(); i++) {
            String name = (String) tagNames.get(i);
            ImapFlag i4flag = mSession.getFlagByName(name);
            if (i4flag == null) {
                if (name.startsWith("\\"))
                    throw MailServiceException.INVALID_NAME(name);
                try {
                    i4flag = mSession.cacheTag(mMailbox.getTagByName(name));
                } catch (MailServiceException.NoSuchItemException nsie) {
                    if (newTags == null)
                        continue;
                    Tag ltag = createTag(name);  // mTags is updated via notification...
                    newTags.add(ltag);
                    i4flag = mSession.getFlagByName(name);
                }
            }
            result.add(i4flag);
        }
        return result;
    }
    private void deleteTags(List<Tag> ltags) {
        if (mMailbox != null && ltags != null)
            for (Tag ltag : ltags)
                try {
                    // notification will update mTags hash
                    mMailbox.delete(getContext(), ltag.getId(), MailItem.TYPE_TAG);
                } catch (ServiceException e) {
                    ZimbraLog.imap.warn("failed to delete tag: " + ltag.getName(), e);
                }
    }

    static final Boolean IDLE_START = Boolean.TRUE;
    static final Boolean IDLE_STOP  = Boolean.FALSE;

    // RFC 2177 3: "The IDLE command is sent from the client to the server when the client is
    //              ready to accept unsolicited mailbox update messages.  The server requests
    //              a response to the IDLE command using the continuation ("+") response.  The
    //              IDLE command remains active until the client responds to the continuation,
    //              and as long as an IDLE command is active, the server is now free to send
    //              untagged EXISTS, EXPUNGE, and other messages at any time."
    boolean doIDLE(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        Object begin    = args.remove(0);
        boolean success = ((Boolean) args.remove(0)).booleanValue();

        if (begin == IDLE_START) {
            mSession.beginIdle(tag);
            sendNotifications(true, false);
            sendContinuation();
        } else {
            tag = mSession.endIdle();
            if (success)  sendOK(tag, "IDLE completed");
            else          sendBAD(tag, "IDLE stopped without DONE");
        }
        return CONTINUE_PROCESSING;
    }

    boolean doSETQUOTA(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        // cannot set quota from IMAP at present
        sendNO(tag, "SETQUOTA failed");
        return CONTINUE_PROCESSING;
    }

    boolean doGETQUOTA(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String qroot = (String) args.remove(0);
        try {
            long quota = mMailbox.getAccount().getIntAttr(Provisioning.A_zimbraMailQuota, 0);
            if (qroot == null || !qroot.equals("") || quota <= 0) {
                ZimbraLog.imap.info("GETQUOTA failed: unknown quota root: " + qroot, null);
                sendNO(tag, "GETQUOTA failed: unknown quota root");
                return CONTINUE_PROCESSING;
            }
            // RFC 2087 3: "STORAGE  Sum of messages' RFC822.SIZE, in units of 1024 octets"
            sendUntagged("QUOTA \"\" (STORAGE " + (mMailbox.getSize() / 1024) + ' ' + (quota / 1024) + ')');
        } catch (ServiceException e) {
            ZimbraLog.imap.warn("GETQUOTA failed", e);
            sendNO(tag, "GETQUOTA failed");
            return canContinue(e);
        }

        sendNotifications(true, false);
        sendOK(tag, "GETQUOTA completed");
        return CONTINUE_PROCESSING;
    }

    boolean doGETQUOTAROOT(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        String path = (String) args.remove(0);

        try {
            // make sure the folder exists and is visible
            Folder folder = mMailbox.getFolderByPath(getContext(), path);
            if (!ImapFolder.isFolderVisible(folder)) {
                ZimbraLog.imap.info("GETQUOTAROOT failed: folder not visible: " + path);
                sendNO(tag, "GETQUOTAROOT failed");
                return CONTINUE_PROCESSING;
            }

            // see if there's any quota on the account
            long quota = mMailbox.getAccount().getIntAttr(Provisioning.A_zimbraMailQuota, 0);
            sendUntagged("QUOTAROOT " + ImapFolder.encodeFolder(path) + (quota > 0 ? " \"\"" : ""));
            if (quota > 0)
                sendUntagged("QUOTA \"\" (STORAGE " + (mMailbox.getSize() / 1024) + ' ' + (quota / 1024) + ')');
        } catch (ServiceException e) {
            if (e.getCode() == MailServiceException.NO_SUCH_FOLDER)
                ZimbraLog.imap.info("GETQUOTAROOT failed: no such folder: " + path);
            else
                ZimbraLog.imap.warn("GETQUOTAROOT failed", e);
            sendNO(tag, "GETQUOTAROOT failed");
            return canContinue(e);
        }

        sendNotifications(true, false);
        sendOK(tag, "GETQUOTAROOT completed");
        return CONTINUE_PROCESSING;
    }

    boolean doNAMESPACE(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_AUTHENTICATED))
            return CONTINUE_PROCESSING;

        sendUntagged("NAMESPACE ((\"\" \"/\")) NIL NIL");
        sendNotifications(true, false);
        sendOK(tag, "NAMESPACE completed");
        return CONTINUE_PROCESSING;
    }

    boolean doCHECK(String tag) throws IOException {
        if (!checkState(tag, ImapSession.STATE_SELECTED))
            return CONTINUE_PROCESSING;

        sendNotifications(true, false);
        sendOK(tag, "CHECK completed");
        return CONTINUE_PROCESSING;
    }

    boolean doCLOSE(String tag) throws IOException {
        if (!checkState(tag, ImapSession.STATE_SELECTED))
            return CONTINUE_PROCESSING;

        try {
            // 6.4.2: "The CLOSE command permanently removes all messages that have the \Deleted
            //         flag set from the currently selected mailbox, and returns to the authenticated
            //         state from the selected state.  No untagged EXPUNGE responses are sent.
            //
            //         No messages are removed, and no error is given, if the mailbox is
            //         selected by an EXAMINE command or is otherwise selected read-only."
            ImapFolder i4folder = mSession.getFolder();
            if (i4folder.isWritable())
                expungeMessages(i4folder, null);
        } catch (ServiceException e) {
            ZimbraLog.imap.warn("EXPUNGE failed", e);
            sendNO(tag, "EXPUNGE failed");
            return canContinue(e);
        }

        mSession.deselectFolder();

        sendOK(tag, "CLOSE completed");
        return CONTINUE_PROCESSING;
    }

    // RFC 3691 2: "The UNSELECT command frees server's resources associated with the selected
    //              mailbox and returns the server to the authenticated state.  This command
    //              performs the same actions as CLOSE, except that no messages are permanently
    //              removed from the currently selected mailbox."
    boolean doUNSELECT(String tag) throws IOException {
        if (!checkState(tag, ImapSession.STATE_SELECTED))
            return CONTINUE_PROCESSING;

        mSession.deselectFolder();

        sendOK(tag, "UNSELECT completed");
        return CONTINUE_PROCESSING;
    }

    boolean doEXPUNGE(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_SELECTED))
            return CONTINUE_PROCESSING;
        else if (!mSession.getFolder().isWritable()) {
            sendNO(tag, "mailbox selected READ-ONLY");
            return CONTINUE_PROCESSING;
        }

        boolean byUID      = ((Boolean) args.remove(0)).booleanValue();
        String sequenceSet = (String) args.remove(0);
        
        String command = (byUID ? "UID EXPUNGE" : "EXPUNGE");
        try {
            expungeMessages(mSession.getFolder(), sequenceSet);
        } catch (ServiceException e) {
            ZimbraLog.imap.warn(command + " failed", e);
            sendNO(tag, command + " failed");
            return canContinue(e);
        }

        sendNotifications(true, false);
        sendOK(tag, command + " completed");
        return CONTINUE_PROCESSING;
    }

    void expungeMessages(ImapFolder i4folder, String sequenceSet) throws ServiceException, IOException {
        Set i4set = (sequenceSet == null ? null : i4folder.getSubsequence(sequenceSet, true));
        long checkpoint = System.currentTimeMillis();
        synchronized (mMailbox) {
            for (ImapMessage i4msg : i4folder)
                if (i4msg != null && !i4msg.expunged && (i4msg.flags & Flag.FLAG_DELETED) > 0)
                    if (i4set == null || i4set.contains(i4msg)) {
                        // message tagged for deletion -- delete now
                        // FIXME: should handle moves separately
                        // FIXME: it'd be nice to have a bulk-delete Mailbox operation
                        try {
                            ZimbraLog.imap.debug("  ** deleting: " + i4msg.id);
                            mMailbox.delete(null, i4msg.id, MailItem.TYPE_MESSAGE);
                        } catch (ServiceException e) {
                            if (!(e instanceof MailServiceException.NoSuchItemException))
                                throw e;
                        }
                        // send a gratuitous untagged response to keep pissy clients from closing the socket from inactivity
                        long now = System.currentTimeMillis();
                        if (now - checkpoint > MAXIMUM_IDLE_PROCESSING_MILLIS) {
                            sendIdleUntagged();  checkpoint = now;
                        }
                    }
        }
    }

    private static final int LARGEST_FOLDER_BATCH = 600;
    static final byte[] MESSAGE_TYPES = new byte[] { MailItem.TYPE_MESSAGE };

    boolean doSEARCH(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_SELECTED))
            return CONTINUE_PROCESSING;

        String search  = (String) args.remove(0);
        TreeMap insertions = (TreeMap) args.remove(0);
        boolean byUID  = ((Boolean) args.remove(0)).booleanValue();

        List<Integer> hits = new ArrayList<Integer>();
        try {
            synchronized (mMailbox) {
                ImapFolder i4folder = mSession.getFolder();
                if (!insertions.isEmpty()) {
                    int count = insertions.size();
                    Map.Entry[] pieces = new Map.Entry[count];
                    for (Iterator it = insertions.entrySet().iterator(); it.hasNext(); )
                        pieces[--count] = (Map.Entry) it.next();
                    for (int i = 0; i < pieces.length; i++) {
                        int point = ((Integer) pieces[i].getKey()).intValue();
                        Set<ImapMessage> i4set;
                        if (pieces[i].getValue() instanceof String) {
                            String key = (String) pieces[i].getValue();
                            if (key.startsWith("-"))
                                i4set = i4folder.getSubsequence(key.substring(1), true);
                            else
                                i4set = i4folder.getSubsequence(key, false);
                        } else {
                            ImapFlag i4flag = (ImapFlag) pieces[i].getValue();
                            i4set = i4folder.getFlaggedMessages(i4flag);
                        }
                        search = search.substring(0, point) + encodeSequence(i4set, true) + search.substring(point);
                    }
                }

                if (!i4folder.isVirtual())
                    search = "in:" + i4folder.getQuotedPath() + " (" + search + ')';
                else if (i4folder.getSize() <= LARGEST_FOLDER_BATCH)
                    search = encodeSequence(i4folder.getSubsequence("1:*", false), false) + " (" + search + ')';
                else
                    search = '(' + i4folder.getQuery() + ") (" + search + ')';
                ZimbraLog.imap.info("[ search is: " + search + " ]");

                ZimbraQueryResults zqr = mMailbox.search(getContext(), search, MESSAGE_TYPES, MailboxIndex.SortBy.DATE_ASCENDING, 1000);
                try {
                    for (ZimbraHit hit = zqr.getFirstHit(); hit != null; hit = zqr.getNext()) {
                        ImapMessage i4msg = mSession.getFolder().getById(hit.getItemId());
                        if (i4msg != null)
                        	hits.add(byUID ? i4msg.uid : i4msg.seq);
                    }
                } finally {
                    zqr.doneWithSearchResults();
                }
            }
		} catch (ParseException e) {
            ZimbraLog.imap.warn("SEARCH failed (bad query)", e);
            sendNO(tag, "SEARCH failed");
            return CONTINUE_PROCESSING;
        } catch (Exception e) {
            ZimbraLog.imap.warn("SEARCH failed", e);
            sendNO(tag, "SEARCH failed");
            return CONTINUE_PROCESSING;
		}

        Collections.sort(hits);
        StringBuffer result = new StringBuffer("SEARCH");
        for (int hit : hits)
            result.append(' ').append(hit);

        sendUntagged(result.toString());
        sendNotifications(false, false);
        sendOK(tag, "SEARCH completed");
        return CONTINUE_PROCESSING;
    }

    private boolean isAllMessages(Set<ImapMessage> i4set) {
        if (mSession == null || mSession.getFolder() == null)
            return false;
        int size = i4set.size() - (i4set.contains(null) ? 1 : 0);
        return size == mSession.getFolder().getSize();
    }
    private String encodeSequence(Set<ImapMessage> i4set, boolean abbreviateAll) {
        i4set.remove(null);
        if (i4set.isEmpty())
            return "item:none";
        else if (abbreviateAll && isAllMessages(i4set))
            return "item:all";
        StringBuffer sb = new StringBuffer("item:{");
        for (ImapMessage i4msg : i4set)
            sb.append(sb.length() == 6 ? "" : ",").append(i4msg.id);
        return sb.append('}').toString();
    }

    static final int FETCH_BODY          = 0x0001;
    static final int FETCH_BODYSTRUCTURE = 0x0002;
    static final int FETCH_ENVELOPE      = 0x0004;
    static final int FETCH_FLAGS         = 0x0008;
    static final int FETCH_INTERNALDATE  = 0x0010;
    static final int FETCH_RFC822_SIZE   = 0x0020;
    static final int FETCH_UID           = 0x0040;
    static final int FETCH_MARK_READ     = 0x1000;
    private static final int FETCH_FROM_CACHE = FETCH_FLAGS | FETCH_INTERNALDATE | FETCH_RFC822_SIZE | FETCH_UID;

    static final int FETCH_FAST = FETCH_FLAGS | FETCH_INTERNALDATE | FETCH_RFC822_SIZE;
    static final int FETCH_ALL  = FETCH_FAST  | FETCH_ENVELOPE;
    static final int FETCH_FULL = FETCH_ALL   | FETCH_BODY;
    

    boolean doFETCH(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_SELECTED))
            return CONTINUE_PROCESSING;

        String sequenceSet = (String) args.remove(0);
        int attributes     = ((Integer) args.remove(0)).intValue();
        List parts         = (List) args.remove(0);
        boolean byUID      = ((Boolean) args.remove(0)).booleanValue();

        // 6.4.8: "However, server implementations MUST implicitly include the UID message
        //         data item as part of any FETCH response caused by a UID command, regardless
        //         of whether a UID was specified as a message data item to the FETCH."
        if (byUID)
            attributes |= FETCH_UID;
        boolean markRead = mSession.getFolder().isWritable() && (attributes & FETCH_MARK_READ) != 0;

        List<ImapPartSpecifier> fullMessage = new ArrayList<ImapPartSpecifier>();
        if (parts != null && !parts.isEmpty())
            for (Iterator it = parts.iterator(); it.hasNext(); ) {
                ImapPartSpecifier pspec = (ImapPartSpecifier) it.next();
                if (pspec.mPart.equals("") && pspec.mModifier.equals("")) {
                    it.remove();  fullMessage.add(pspec);
                }
            }

        String command = (byUID ? "UID FETCH" : "FETCH");
        boolean allPresent = true;

        synchronized (mMailbox) {
            Set<ImapMessage> i4set = mSession.getFolder().getSubsequence(sequenceSet, byUID);
            allPresent = byUID || !i4set.contains(null);
            for (ImapMessage i4msg : i4set) {
                if (i4msg == null)
                    continue;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
		        PrintStream result = new PrintStream(baos, false, "utf-8");
	        	try {
                    boolean markMessage = markRead && (i4msg.flags & Flag.FLAG_UNREAD) != 0;
                    boolean empty = true;
                    byte[] raw = null;
                    result.print('*');
                    result.print(' ');
                    result.print(i4msg.seq);
                    result.print(" FETCH (");
                    if ((attributes & FETCH_UID) != 0) {
                        result.print(empty ? "" : " ");  result.print("UID "); result.print(i4msg.uid);  empty = false;
                    }
                    if ((attributes & FETCH_INTERNALDATE) != 0) {
			        	result.print(empty ? "" : " ");  result.print(i4msg.getDate(mTimeFormat));  empty = false;
                    }
			        if ((attributes & FETCH_RFC822_SIZE) != 0) {
			        	result.print(empty ? "" : " ");  result.print("RFC822.SIZE ");  result.print(i4msg.getSize());  empty = false;
                    }
                    if (!fullMessage.isEmpty()) {
                        raw = mMailbox.getMessageById(getContext(), i4msg.id).getMessageContent();
                        for (ImapPartSpecifier pspec : fullMessage) {
                            result.print(empty ? "" : " ");  pspec.write(result, raw);  empty = false;
                        }
                    }
			        if (!parts.isEmpty() || (attributes & ~FETCH_FROM_CACHE) != 0) {
                        MimeMessage mm;
                        try {
                            // don't use msg.getMimeMessage() because it implicitly expands TNEF attachments
                            InputStream is = raw != null ? new ByteArrayInputStream(raw) : mMailbox.getMessageById(getContext(), i4msg.id).getRawMessage();
                            mm = new Mime.FixedMimeMessage(JMSession.getSession(), is);
                            if (mSession.isHackEnabled(EnabledHack.WM5))
                                if (new ImapMessage.WindowsMobile5Converter().accept(mm)) {
                                    // FIXME: terrible hack to work around JavaMail repulsiveness
                                    ByteArrayOutputStream baosCopy = new ByteArrayOutputStream();
                                    mm.writeTo(baosCopy);
                                    mm = new Mime.FixedMimeMessage(JMSession.getSession(), new ByteArrayInputStream(baosCopy.toByteArray()));
                                }
                            is.close();
                        } catch (IOException e) {
                            throw ServiceException.FAILURE("error fetching raw content for message " + i4msg.id, e);
                        }
                        if ((attributes & FETCH_BODY) != 0) {
                            result.print(empty ? "" : " ");  result.print("BODY ");
                            i4msg.serializeStructure(result, mm, false);  empty = false;
                        }
                        if ((attributes & FETCH_BODYSTRUCTURE) != 0) {
                            result.print(empty ? "" : " ");  result.print("BODYSTRUCTURE ");
                            i4msg.serializeStructure(result, mm, true);  empty = false;
                        }
                        if ((attributes & FETCH_ENVELOPE) != 0) {
                            result.print(empty ? "" : " ");  result.print("ENVELOPE ");
                            i4msg.serializeEnvelope(result, mm);  empty = false;
                        }
                        for (int i = 0; i < parts.size(); i++) {
                            ImapPartSpecifier pspec = (ImapPartSpecifier) parts.get(i);
                            byte[] content = i4msg.getPart(mm, pspec);
                            result.print(empty ? "" : " ");  pspec.write(result, content);  empty = false;
                        }
			        }
                    // FIXME: optimize by doing a single mark-read op on multiple messages
                    if (markMessage)
                        mMailbox.alterTag(getContext(), i4msg.id, MailItem.TYPE_MESSAGE, Flag.ID_FLAG_UNREAD, false);
                    // 6.4.5: "The \Seen flag is implicitly set; if this causes the flags to
                    //         change, they SHOULD be included as part of the FETCH responses."
                    if ((attributes & FETCH_FLAGS) != 0 || markMessage) {
                        mSession.getFolder().undirtyMessage(i4msg);
                        result.print(empty ? "" : " ");  result.print(i4msg.getFlags(mSession));  empty = false;
                    }
                } catch (ServiceException e) {
                    ZimbraLog.imap.warn("ignoring error during " + command + ": ", e);
                    continue;
                } catch (MessagingException e) {
                    ZimbraLog.imap.warn("ignoring error during " + command + ": ", e);
                    continue;
                } finally {
                    result.write(')');
                    result.write(LINE_SEPARATOR_BYTES, 0, LINE_SEPARATOR_BYTES.length);
                    if (baos != null) {
                        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());
                        mConnection.write(bb);
                        ZimbraLog.imap.debug("  S: " + baos);
                    }
                }
            }
            sendNotifications(false, false);
        }

        if (allPresent)
        	sendOK(tag, command + " completed");
        else {
        	// RFC 2180 4.1.2: "The server MAY allow the EXPUNGE of a multi-accessed mailbox,
            //                  and on subsequent FETCH commands return FETCH responses only
		    //                  for non-expunged messages and a tagged NO."
        	sendNO(tag, "some of the requested messages no longer exist");
        }
        return CONTINUE_PROCESSING;
    }

    static final Byte STORE_REPLACE = new Byte((byte) 0x00);
    static final Byte STORE_ADD     = new Byte((byte) 0x01);
    static final Byte STORE_REMOVE  = new Byte((byte) 0x02);

    boolean doSTORE(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_SELECTED))
            return CONTINUE_PROCESSING;
        else if (!mSession.getFolder().isWritable()) {
            sendNO(tag, "mailbox selected READ-ONLY");
            return CONTINUE_PROCESSING;
        }

        String sequenceSet = (String) args.remove(0);
        List flagList      = (List) args.remove(0);
        Byte operation     = (Byte) args.remove(0);
        boolean silent     = ((Boolean) args.remove(0)).booleanValue();
        boolean byUID      = ((Boolean) args.remove(0)).booleanValue();

        String command = (byUID ? "UID STORE" : "STORE");
        List<Tag> newTags = (operation != STORE_REMOVE ? new ArrayList<Tag>() : null);

        Set<ImapMessage> i4set;
        synchronized (mMailbox) {
            i4set = mSession.getFolder().getSubsequence(sequenceSet, byUID);
        }
        boolean allPresent = byUID || !i4set.contains(null);

        try {
            // get set of relevant tags
            List<ImapFlag> i4flags;
            synchronized (mMailbox) {
                i4flags = findOrCreateTags(flagList, newTags);
            }

            long checkpoint = System.currentTimeMillis();
            for (ImapMessage i4msg : i4set) {
                if (i4msg == null)
                    continue;

                if (!i4flags.isEmpty() || operation == STORE_REPLACE) {
                    // FIXME: for replace, changed tag/flag mask could be precomputed outside the i4set loop
                    byte sflags = (operation != STORE_REPLACE ? i4msg.sflags : 0);
                    int  flags  = (operation != STORE_REPLACE ? i4msg.flags : Flag.FLAG_UNREAD | (i4msg.flags & ~ImapMessage.IMAP_FLAGS));
                    long tags   = (operation != STORE_REPLACE ? i4msg.tags : 0);
                    for (ImapFlag i4flag : i4flags) {
                        if (Tag.validateId(i4flag.mId))
                            tags = (operation == STORE_REMOVE ^ !i4flag.mPositive ? tags & ~i4flag.mBitmask : tags | i4flag.mBitmask);
                        else if (!i4flag.mPermanent)
                            sflags = (byte) (operation == STORE_REMOVE ^ !i4flag.mPositive ? sflags & ~i4flag.mBitmask : sflags | i4flag.mBitmask);
                        else
                            flags = (int) (operation == STORE_REMOVE ^ !i4flag.mPositive ? flags & ~i4flag.mBitmask : flags | i4flag.mBitmask);
                    }

                    synchronized (mMailbox) {
                        if (tags != i4msg.tags || flags != i4msg.flags)
                            try {
                                // if it was a STORE [+-]?FLAGS.SILENT, temporarily disable notifications
                                if (silent)
                                    mSession.getFolder().disableNotifications();
                                // actually alter the item's flags
                                mMailbox.setTags(getContext(), i4msg.id, MailItem.TYPE_MESSAGE, flags, tags);
                                // i4msg's permanent flags/tags will be updated via notification
                                i4msg.setSessionFlags(sflags);
                            } catch (MailServiceException.NoSuchItemException nsie) {
                                i4msg.expunged = true;
                            } finally {
                                // if it was a STORE [+-]?FLAGS.SILENT, reenable notifications
                                mSession.getFolder().enableNotifications();
                            }
                    }
                }

                if (!silent) {
                    mSession.getFolder().undirtyMessage(i4msg);
                    StringBuffer ntfn = new StringBuffer();
                    ntfn.append(i4msg.seq).append(" FETCH (").append(i4msg.getFlags(mSession));
                    // 6.4.8: "However, server implementations MUST implicitly include
                    //         the UID message data item as part of any FETCH response
                    //         caused by a UID command..."
                    if (byUID)
                        ntfn.append(" UID ").append(i4msg.uid);
                    sendUntagged(ntfn.append(')').toString());
                } else {
                    // send a gratuitous untagged response to keep pissy clients from closing the socket from inactivity
                    long now = System.currentTimeMillis();
                    if (now - checkpoint > MAXIMUM_IDLE_PROCESSING_MILLIS) {
                        sendIdleUntagged();  checkpoint = now;
                    }
                }
            }
        } catch (ServiceException e) {
            deleteTags(newTags);
            ZimbraLog.imap.warn(command + " failed", e);
            sendNO(tag, command + " failed");
            return canContinue(e);
        }

        // RFC 2180 4.2.1: "If the ".SILENT" suffix is used, and the STORE completed successfully for
        //                  all the non-expunged messages, the server SHOULD return a tagged OK."
        // RFC 2180 4.2.3: "If the ".SILENT" suffix is not used, and a mixture of expunged and non-
        //                  expunged messages are referenced, the server MAY set the flags and return
        //                  a FETCH response for the non-expunged messages along with a tagged NO."
        if (silent || allPresent)
            sendOK(tag, command + " completed");
        else
            sendNO(tag, command + " completed");
        return CONTINUE_PROCESSING;
    }

    boolean doCOPY(String tag, List args) throws IOException {
        if (!checkState(tag, ImapSession.STATE_SELECTED))
            return CONTINUE_PROCESSING;

        String sequenceSet = (String) args.remove(0);
        String folderName  = (String) args.remove(0);
        boolean byUID      = ((Boolean) args.remove(0)).booleanValue();

        String command = (byUID ? "UID COPY" : "COPY");
        String copyuid = "";
        List<MailItem> newMessages = new ArrayList<MailItem>();
        try {
            Folder folder = mMailbox.getFolderByPath(getContext(), folderName);
            if (!ImapFolder.isFolderVisible(folder)) {
                ZimbraLog.imap.info(command + " failed: folder is hidden: " + folderName);
                sendNO(tag, command + " failed");
                return CONTINUE_PROCESSING;
            } else if (!ImapFolder.isFolderWritable(folder)) {
                ZimbraLog.imap.info(command + " failed: folder is READ-ONLY: " + folderName);
                sendNO(tag, command + " failed: target mailbox is READ-ONLY");
                return CONTINUE_PROCESSING;
            }

            Set<ImapMessage> i4set;
            synchronized (mMailbox) {
                i4set = mSession.getFolder().getSubsequence(sequenceSet, byUID);
            }
            // RFC 2180 4.4.1: "The server MAY disallow the COPY of messages in a multi-
            //                  accessed mailbox that contains expunged messages."
            if (i4set.contains(null) && !byUID) {
                sendNO(tag, "COPY rejected because some of the requested messages were expunged");
                return CONTINUE_PROCESSING;
            }

            List<Integer> srcUIDs = new ArrayList<Integer>(), copyUIDs = new ArrayList<Integer>();
            long checkpoint = System.currentTimeMillis();
            for (ImapMessage i4msg : i4set) {
                if (i4msg == null)
                    continue;
                // FIXME: should optimize to a move, as 95% of IMAP COPY ops are really moves...
                MailItem copy = mMailbox.copy(getContext(), i4msg.id, MailItem.TYPE_MESSAGE, folder.getId());
                if (copy == null)
                    continue;
                newMessages.add(copy);
                srcUIDs.add(i4msg.uid);
                copyUIDs.add(copy.getId());
                // send a gratuitous untagged response to keep pissy clients from closing the socket from inactivity
                long now = System.currentTimeMillis();
                if (now - checkpoint > MAXIMUM_IDLE_PROCESSING_MILLIS) {
                    sendIdleUntagged();  checkpoint = now;
                }
            }

            if (srcUIDs.size() > 0)
                copyuid = "[COPYUID " + ImapFolder.getUIDValidity(folder) + ' ' +
                          ImapFolder.encodeSubsequence(srcUIDs) + ' ' +
                          ImapFolder.encodeSubsequence(copyUIDs) + "] ";
        } catch (IOException e) {
            // 6.4.7: "If the COPY command is unsuccessful for any reason, server implementations
            //         MUST restore the destination mailbox to its state before the COPY attempt."
            deleteMessages(newMessages);

            ZimbraLog.imap.warn(command + " failed", e);
            sendNO(tag, command + " failed");
            return CONTINUE_PROCESSING;
        } catch (ServiceException e) {
            // 6.4.7: "If the COPY command is unsuccessful for any reason, server implementations
            //         MUST restore the destination mailbox to its state before the COPY attempt."
            deleteMessages(newMessages);

            String rcode = "";
            if (e.getCode() == MailServiceException.NO_SUCH_FOLDER) {
                ZimbraLog.imap.info(command + " failed: no such folder: " + folderName);
                if (ImapFolder.isPathCreatable('/' + folderName))
                    rcode = "[TRYCREATE] ";
            } else
                ZimbraLog.imap.warn(command + " failed", e);
            sendNO(tag, rcode + command + " failed");
            return canContinue(e);
        }

        // RFC 2180 4.4: "COPY is the only IMAP4 sequence number command that is safe to allow
        //                an EXPUNGE response on.  This is because a client is not permitted
        //                to cascade several COPY commands together."
        sendNotifications(true, false);
    	sendOK(tag, copyuid + command + " completed");
        return CONTINUE_PROCESSING;
    }

    private void deleteMessages(List<MailItem> messages) {
        if (messages != null && !messages.isEmpty())
            for (MailItem item : messages)
                try {
                    mMailbox.delete(getContext(), item.getId(), MailItem.TYPE_MESSAGE);
                } catch (ServiceException e) {
                    ZimbraLog.imap.warn("could not roll back creation of message", e);
                }
    }


    public void sendNotifications(boolean notifyExpunges, boolean flush) throws IOException {
        if (mSession == null || mSession.getFolder() == null || mMailbox == null)
            return;

        // is this the right thing to synchronize on?
        synchronized (mMailbox) {
            // FIXME: notify untagged NO if close to quota limit

            ImapFolder i4folder = mSession.getFolder();
            boolean removed = false, received = i4folder.checkpointSize();
            if (notifyExpunges)
                for (Integer index : i4folder.collapseExpunged()) {
                    sendUntagged(index + " EXPUNGE");  removed = true;
                }
            i4folder.checkpointSize();

            // notify of any message flag changes
            for (Iterator<ImapMessage> it = i4folder.dirtyIterator(); it.hasNext(); ) {
                ImapMessage i4msg = it.next();
                if (i4msg.added)
                    i4msg.added = false;
                else
                	sendUntagged(i4msg.seq + " FETCH (" + i4msg.getFlags(mSession) + ')');
            }
            i4folder.clearDirty();

            // FIXME: not handling RECENT

            if (received || removed)
                sendUntagged(i4folder.getSize() + " EXISTS");
        }
    }

    void sendIdleUntagged() throws IOException                   { sendUntagged("NOOP", true); }

    void sendOK(String tag, String response) throws IOException  { sendResponse(tag, response.equals("") ? "OK" : "OK " + response, true); }
    void sendNO(String tag, String response) throws IOException  { sendResponse(tag, response.equals("") ? "NO" : "NO " + response, true); }
    void sendBAD(String tag, String response) throws IOException { sendResponse(tag, response.equals("") ? "BAD" : "BAD " + response, true); }
    void sendUntagged(String response) throws IOException        { sendResponse("*", response, false); }
    void sendUntagged(String response, boolean flush) throws IOException { sendResponse("*", response, flush); }
    void sendContinuation() throws IOException                   { sendResponse("+", null, true); }
    
    private void sendResponse(String status, String msg, boolean flush) throws IOException {
        String response = status + ' ' + (msg == null ? "" : msg);
        if (ZimbraLog.imap.isDebugEnabled())
            ZimbraLog.imap.debug("  S: " + response);
        else if (status.startsWith("BAD"))
            ZimbraLog.imap.info("  S: " + response);
        sendLine(response, flush);
    }

    private void sendLine(String line, boolean flush) throws IOException {
        mConnection.writeAsciiWithCRLF(line);
    }

    private static final int MAX_COMMAND_LENGTH = 2048;
    
    private OzByteArrayMatcher mCommandMatcher = new OzByteArrayMatcher(OzByteArrayMatcher.CRLF, null);

    private OzCountingMatcher mLiteralMatcher = new OzCountingMatcher();
    
    private enum ConnectionState { READLITERAL, READLINE, CLOSED, UNKNOWN };

    private ConnectionState mState = ConnectionState.UNKNOWN;
    
    private void gotoReadLineState(boolean newRequest) {
        mState = ConnectionState.READLINE;
        mCommandMatcher.reset();
        mConnection.setMatcher(mCommandMatcher);
        mConnection.enableReadInterest();
        mCurrentData = new OzByteBufferGatherer(256, MAX_COMMAND_LENGTH);
        if (newRequest) {
            mCurrentRequestData = new ArrayList();
            mCurrentRequestTag = null;
        }
        if (ZimbraLog.imap.isDebugEnabled()) ZimbraLog.imap.debug("entered command read state");
    }

    private void gotoReadLiteralState(int target) {
        mState = ConnectionState.READLITERAL;
        mLiteralMatcher.target(target);
        mLiteralMatcher.reset();
        mConnection.setMatcher(mLiteralMatcher);
        mConnection.enableReadInterest();
        mCurrentData = new OzByteBufferGatherer(target, target);
        if (ZimbraLog.imap.isDebugEnabled()) ZimbraLog.imap.debug("entered literal read state target=" + target);
    }
    
    private Object mCloseLock = new Object();
    
    private void gotoClosedStateInternal(boolean sendBanner) throws IOException {
        synchronized (mCloseLock) {
            // Close only once
            if (mState == ConnectionState.CLOSED) {
                throw new IllegalStateException("connection already closed");
            }
            
            if (mSession != null) {
                mSession.setHandler(null);
                SessionCache.clearSession(mSession.getSessionId(), mSession.getAccountId());
                mSession = null;
            }
            
            if (sendBanner) {
                if (!mGoodbyeSent) {
                    sendUntagged(ImapServer.getGoodbye(), true);
                }
                mGoodbyeSent = true;
            }
        }
    }
    
    private void gotoClosedState(boolean sendBanner) {
        try {
            gotoClosedStateInternal(sendBanner);
        } catch (IOException ioe) {
            ZimbraLog.imap.info("exception occurred when closing IMAP connection", ioe);
        } finally {
            mConnection.close();
            mState = ConnectionState.CLOSED;
            if (ZimbraLog.imap.isDebugEnabled()) ZimbraLog.imap.debug("entered closed state: banner=" + sendBanner);
        }
    }

    public void handleDisconnect() {
        assert(mState != ConnectionState.UNKNOWN);
        ZimbraLog.imap.info("connection closed by client");
        try {
            gotoClosedStateInternal(false);
        } catch (IOException ioe) {
            // Can't happen because send banner is false
            ZimbraLog.imap.info("exception occurred when disconnecting IMAP connection", ioe);
        }
    }
    
    public void dropConnection(boolean sendBanner) {
        gotoClosedState(sendBanner);
    }

    public void handleConnect() throws IOException {
        assert(mState == ConnectionState.UNKNOWN);
        if (!Config.userServicesEnabled()) {
            gotoClosedState(true);
            ZimbraLog.imap.debug("services disabled");
            return;
        }
        if (mConnection.getProperty(PROPERTY_SECURE_SERVER, null) != null) {
            mStartedTLS = true;
        }
        sendUntagged(ImapServer.getBanner(), true);
        gotoReadLineState(true);
    }

    private OzByteBufferGatherer mCurrentData;
    
    private ArrayList mCurrentRequestData;

    private String mCurrentRequestTag;
    
    public void handleAlarm() throws IOException {
        ZimbraLog.imap.info("closing unauthenticated idle connection");
        gotoClosedState(true);
    }

    public void handleInput(ByteBuffer buffer, boolean matched) throws IOException {
        
        mCurrentData.add(buffer);

        if (!matched) {
            return;
        }

        mCurrentData.trim(mConnection.getMatcher().trailingTrimLength());

        if (mState == ConnectionState.CLOSED) {
            ZimbraLog.imap.info("input arrived on closed connection");
            mCurrentData.clear();
            return;
        }
                
        if (mState == ConnectionState.READLITERAL) {
            mCurrentRequestData.add(mCurrentData.array());
            /*
			 * at the end of a literal there is more of the command or there is
			 * just CRLF (which is the empty part of the command)
			 */
            gotoReadLineState(false);
            return;
        }
        
        if (mState == ConnectionState.READLINE) {
            if (mCurrentData.overflowed()) {
                sendUntagged("BAD request too long", true);
                gotoReadLineState(true);
                return;
            }
            String line = mCurrentData.toAsciiString();
            /*
             * Is this the first line of this request? If so, then let's try
             * to parse the tag from it so we can report tagged BADs if
             * needed. TODO: should we also strip tag here - so we don't
             * parse it twice?
             */
            if (mCurrentRequestData.size() == 0) {
                logCommand(line);
                try {
                    mCurrentRequestTag = OzImapRequest.readTag(line);
                } catch (ImapParseException ipe) {
                    sendUntagged("BAD " + ipe.getMessage(), true);
                    gotoReadLineState(true);
                    return;
                }
            }
            
            /* See if there is a literal at the end of this line. */
            ImapLiteral literal;
            try {
                literal = ImapLiteral.parse(mCurrentRequestTag, line);
            } catch (ImapParseException ipe) {
                sendBAD(mCurrentRequestTag, ipe.getMessage());
                gotoReadLineState(true);
                return;
            }
            
            /* Add either the literal removed line or a no-literal present at all
             * line (ie, it's line in either case) to the request in flight.
             */ 
            mCurrentRequestData.add(line);
            
            if (literal.octets() > 0) {
                if (literal.blocking()) {
                    sendContinuation();
                }
                gotoReadLiteralState(literal.octets());
                return;
            } else {
                if (processCommand()) {
                    gotoReadLineState(true);
                } else {
                    gotoClosedState(true);
                }
                return;
            }
        }

        throw new RuntimeException("internal error in IMAP server: bad state " + mState);
    }

    private void logCommand(String command) {
        if (!ZimbraLog.imap.isDebugEnabled()) {
            return;
        }
        int space = command.indexOf(' ');
        if ((space + "LOGIN ".length()) < command.length()) {
            String possiblyLogin = command.substring(space + 1, space + 1 + "LOGIN ".length());
            if (possiblyLogin.equalsIgnoreCase("LOGIN ")) {
                int endLogin = space + 1 + "LOGIN ".length();
                int endUser = command.indexOf(' ', endLogin);
                if (endUser > 0) {
                    command = command.substring(0, endUser) + "...";
                }
            }
        }
        ZimbraLog.imap.debug("  C: " + command);
    }
}
