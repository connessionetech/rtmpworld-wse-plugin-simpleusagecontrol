package com.rtmpworld.server.wowza.usagecontrol;

import java.util.Iterator;
import java.util.TimerTask;

import com.rtmpworld.server.wowza.ModuleSimpleUsageControl;
import com.rtmpworld.server.wowza.usagecontrol.restrictions.UsageRestrictions;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.client.IClient;
import com.wowza.wms.httpstreamer.model.IHTTPStreamerSession;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerIDs;
import com.wowza.wms.rtp.model.RTPSession;

public class StreamTimeLimiter  extends TimerTask {
	
	IApplicationInstance appInstance;
	UsageRestrictions restrictions;
	boolean debug;
	
	WMSLogger logger;
	
	

	public StreamTimeLimiter(IApplicationInstance appInstance, UsageRestrictions restrictions, WMSLogger logger, boolean debug) {
		this.appInstance = appInstance;
		this.restrictions = restrictions;
		this.logger = logger;
		this.debug = debug;
	}

	
	
	@Override
	public synchronized void run()
	{			
		
		/**
		 * Checking RTMP clients 
		 */
		Iterator<IClient> clients = appInstance.getClients().iterator();
		while (clients.hasNext())
		{
			IClient client = clients.next();
			WMSProperties props = client.getProperties();

			
			if (restrictions.ingest.maxPublishTime > 0 && client.getTimeRunningSeconds() > restrictions.ingest.maxPublishTime)
			{				
				if (props.containsKey(ModuleSimpleUsageControl.KEY_PUBLISHER))
				{
					if (debug)
						logger.info(ModuleSimpleUsageControl.MODULE_NAME + ": RTMP disconnecting publisher client " + client.getClientId() + " FlashVer is " + client.getFlashVer(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

					client.setShutdownClient(true);
				}
			}
			
			
			if (restrictions.egress.maxPlaybackTime > 0 && client.getTimeRunningSeconds() > restrictions.egress.maxPlaybackTime)
			{				
				if (props.containsKey(ModuleSimpleUsageControl.KEY_SUBSCRIBER))
				{
					if (debug)
						logger.info(ModuleSimpleUsageControl.MODULE_NAME + ": RTMP disconnecting subscriber client " + client.getClientId() + " FlashVer is " + client.getFlashVer(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

					client.setShutdownClient(true);
				}				
			}
			
		}

		
		
		/**
		 * Testing HLS clients
		 */
		Iterator<IHTTPStreamerSession> httpSessions = appInstance.getHTTPStreamerSessions().iterator();
		while (httpSessions.hasNext())
		{
			IHTTPStreamerSession httpSession = httpSessions.next();
			WMSProperties props = httpSession.getProperties();
			logger.info("session id " + httpSession.getSessionId() + " : maxPlaybackTime: " + restrictions.egress.maxPlaybackTime + " getTimeRunningSeconds() = " + httpSession.getTimeRunningSeconds());
			if (restrictions.egress.maxPlaybackTime > 0 && httpSession.getTimeRunningSeconds() > restrictions.egress.maxPlaybackTime)
			{
				if (debug)
					logger.info(ModuleSimpleUsageControl.MODULE_NAME + ": HTTP disconnecting subscriber session " + httpSession.getSessionId(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

				
				httpSession.rejectSession();
				httpSession.shutdown();
			}		
			
		}

		
		
		/**
		 * Checking RTP clients (RTSP & WebRTC)
		 */
		Iterator<RTPSession> rtpSessions = appInstance.getRTPSessions().iterator();
		while (rtpSessions.hasNext())
		{
			RTPSession rtpSession = rtpSessions.next();
			WMSProperties props = rtpSession.getProperties();
			if (rtpSession.getTimeRunningSeconds() > restrictions.ingest.maxPublishTime)
			{
				if (props.containsKey(ModuleSimpleUsageControl.KEY_PUBLISHER))
				{
					if (debug)
						logger.info(ModuleSimpleUsageControl.MODULE_NAME + ": RTP disconnecting publisher client " + rtpSession.getSessionId(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

					appInstance.getVHost().getRTPContext().shutdownRTPSession(rtpSession);
				}
			}
			
						
			if (restrictions.egress.maxPlaybackTime > 0 && rtpSession.getTimeRunningSeconds() > restrictions.egress.maxPlaybackTime)
			{
				if (props.containsKey(ModuleSimpleUsageControl.KEY_SUBSCRIBER))
				{
					if (debug)
						logger.info(ModuleSimpleUsageControl.MODULE_NAME + ": RTP disconnecting subscriber client " + rtpSession.getSessionId(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
					
					appInstance.getVHost().getRTPContext().shutdownRTPSession(rtpSession);
				}
			}
		}
	}
}
