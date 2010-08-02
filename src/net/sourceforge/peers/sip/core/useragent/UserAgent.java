/*
    This file is part of Peers, a java SIP softphone.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
    
    Copyright 2007, 2008, 2009, 2010 Yohann Martineau 
*/

package net.sourceforge.peers.sip.core.useragent;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import net.sourceforge.peers.Config;
import net.sourceforge.peers.Logger;
import net.sourceforge.peers.media.CaptureRtpSender;
import net.sourceforge.peers.media.Echo;
import net.sourceforge.peers.media.IncomingRtpReader;
import net.sourceforge.peers.media.MediaMode;
import net.sourceforge.peers.media.SoundManager;
import net.sourceforge.peers.sdp.SDPManager;
import net.sourceforge.peers.sip.RFC3261;
import net.sourceforge.peers.sip.Utils;
import net.sourceforge.peers.sip.core.useragent.handlers.ByeHandler;
import net.sourceforge.peers.sip.core.useragent.handlers.CancelHandler;
import net.sourceforge.peers.sip.core.useragent.handlers.InviteHandler;
import net.sourceforge.peers.sip.core.useragent.handlers.OptionsHandler;
import net.sourceforge.peers.sip.core.useragent.handlers.RegisterHandler;
import net.sourceforge.peers.sip.syntaxencoding.SipURI;
import net.sourceforge.peers.sip.syntaxencoding.SipUriSyntaxException;
import net.sourceforge.peers.sip.transaction.Transaction;
import net.sourceforge.peers.sip.transaction.TransactionManager;
import net.sourceforge.peers.sip.transactionuser.DialogManager;
import net.sourceforge.peers.sip.transport.SipMessage;
import net.sourceforge.peers.sip.transport.SipRequest;
import net.sourceforge.peers.sip.transport.SipResponse;
import net.sourceforge.peers.sip.transport.TransportManager;


public class UserAgent {

    public final static String CONFIG_FILE = "conf" + File.separator + "peers.xml";
    public final static int RTP_DEFAULT_PORT = 8000;
    
    private Config config;
    
    private List<String> peers;
    //private List<Dialog> dialogs;
    
    private CaptureRtpSender captureRtpSender;
    private IncomingRtpReader incomingRtpReader;
    //TODO factorize echo and captureRtpSender
    private Echo echo;
    
    private UAC uac;
    private UAS uas;

    private ChallengeManager challengeManager;
    
    private DialogManager dialogManager;
    private TransactionManager transactionManager;
    private TransportManager transportManager;

    private int cseqCounter;
    private SipListener sipListener;
    
    private SDPManager sdpManager;
    private SoundManager soundManager;

    public UserAgent() {
        config = new Config(Utils.getPeersHome() + CONFIG_FILE);
        
        cseqCounter = 0;
        
        StringBuffer buf = new StringBuffer();
        buf.append("starting user agent [");
        buf.append("myAddress: ");
        buf.append(config.getInetAddress().getHostAddress()).append(", ");
        buf.append("sipPort: ");
        buf.append(config.getSipPort()).append(", ");
        buf.append("userpart: ");
        buf.append(config.getUserPart()).append(", ");
        buf.append("domain: ");
        buf.append(config.getDomain()).append("]");
        Logger.info(buf.toString());

        //transaction user
        
        dialogManager = new DialogManager();
        
        //transaction
        
        transactionManager = new TransactionManager();
        
        //transport
        
        transportManager = new TransportManager(transactionManager,
                config.getInetAddress(),
                config.getSipPort());
        
        transactionManager.setTransportManager(transportManager);
        
        //core
        
        InviteHandler inviteHandler = new InviteHandler(this,
                dialogManager,
                transactionManager,
                transportManager);
        CancelHandler cancelHandler = new CancelHandler(this,
                dialogManager,
                transactionManager,
                transportManager);
        ByeHandler byeHandler = new ByeHandler(this,
                dialogManager,
                transactionManager,
                transportManager);
        OptionsHandler optionsHandler = new OptionsHandler(this,
                transactionManager,
                transportManager);
        RegisterHandler registerHandler = new RegisterHandler(this,
                transactionManager,
                transportManager);
        
        InitialRequestManager initialRequestManager =
            new InitialRequestManager(
                this,
                inviteHandler,
                cancelHandler,
                byeHandler,
                optionsHandler,
                registerHandler,
                dialogManager,
                transactionManager,
                transportManager);
        MidDialogRequestManager midDialogRequestManager =
            new MidDialogRequestManager(
                this,
                inviteHandler,
                cancelHandler,
                byeHandler,
                optionsHandler,
                registerHandler,
                dialogManager,
                transactionManager,
                transportManager);
        
        uas = new UAS(this,
                initialRequestManager,
                midDialogRequestManager,
                dialogManager,
                transactionManager,
                transportManager);
        String profileUri = RFC3261.SIP_SCHEME + RFC3261.SCHEME_SEPARATOR
            + config.getUserPart() + RFC3261.AT + config.getDomain();
        uac = new UAC(this,
                profileUri,
                initialRequestManager,
                midDialogRequestManager,
                dialogManager,
                transactionManager,
                transportManager);

        if (config.getPassword() != null) {
            challengeManager = new ChallengeManager(config.getUserPart(),
                    config.getPassword(),
                    initialRequestManager,
                    profileUri);
            registerHandler.setChallengeManager(challengeManager);
            inviteHandler.setChallengeManager(challengeManager);
        }

        peers = new ArrayList<String>();
        //dialogs = new ArrayList<Dialog>();

        if (config.getPassword() != null) {
            try {
                uac.register();
            } catch (SipUriSyntaxException e) {
                Logger.error("syntax error", e);
            }
        }
        sdpManager = new SDPManager(this);
        inviteHandler.setSdpManager(sdpManager);
        optionsHandler.setSdpManager(sdpManager);
        soundManager = new SoundManager(config.isMediaDebug());
    }

    /**
     * Gives the sipMessage if sipMessage is a SipRequest or 
     * the SipRequest corresponding to the SipResponse
     * if sipMessage is a SipResponse
     * @param sipMessage
     * @return null if sipMessage is neither a SipRequest neither a SipResponse
     */
    public SipRequest getSipRequest(SipMessage sipMessage) {
        if (sipMessage instanceof SipRequest) {
            return (SipRequest) sipMessage;
        } else if (sipMessage instanceof SipResponse) {
            SipResponse sipResponse = (SipResponse) sipMessage;
            Transaction transaction = (Transaction)transactionManager
                .getClientTransaction(sipResponse);
            if (transaction == null) {
                transaction = (Transaction)transactionManager
                    .getServerTransaction(sipResponse);
            }
            if (transaction == null) {
                return null;
            }
            return transaction.getRequest();
        } else {
            return null;
        }
    }
    
//    public List<Dialog> getDialogs() {
//        return dialogs;
//    }

    public List<String> getPeers() {
        return peers;
    }

//    public Dialog getDialog(String peer) {
//        for (Dialog dialog : dialogs) {
//            String remoteUri = dialog.getRemoteUri();
//            if (remoteUri != null) {
//                if (remoteUri.contains(peer)) {
//                    return dialog;
//                }
//            }
//        }
//        return null;
//    }

    public String generateCSeq(String method) {
        StringBuffer buf = new StringBuffer();
        buf.append(cseqCounter++);
        buf.append(' ');
        buf.append(method);
        return buf.toString();
    }
    
    public CaptureRtpSender getCaptureRtpSender() {
        return captureRtpSender;
    }

    public void setCaptureRtpSender(CaptureRtpSender captureRtpSender) {
        this.captureRtpSender = captureRtpSender;
    }

    public IncomingRtpReader getIncomingRtpReader() {
        return incomingRtpReader;
    }

    public void setIncomingRtpReader(IncomingRtpReader incomingRtpReader) {
        this.incomingRtpReader = incomingRtpReader;
    }

    public UAS getUas() {
        return uas;
    }

    public UAC getUac() {
        return uac;
    }

    public DialogManager getDialogManager() {
        return dialogManager;
    }
    
    public InetAddress getMyAddress() {
        return config.getInetAddress();
    }
    
    public int getSipPort() {
        return config.getSipPort();
    }

    public int getRtpPort() {
        return config.getRtpPort();
    }

    public String getDomain() {
        return config.getDomain();
    }

    public String getUserpart() {
        return config.getUserPart();
    }

    public MediaMode getMediaMode() {
        return config.getMediaMode();
    }

    public boolean isMediaDebug() {
        return config.isMediaDebug();
    }

    public SipURI getOutboundProxy() {
        return config.getOutboundProxy();
    }

    public Echo getEcho() {
        return echo;
    }

    public void setEcho(Echo echo) {
        this.echo = echo;
    }

    public SipListener getSipListener() {
        return sipListener;
    }

    public void setSipListener(SipListener sipListener) {
        this.sipListener = sipListener;
    }

    public SoundManager getSoundManager() {
        return soundManager;
    }

}
