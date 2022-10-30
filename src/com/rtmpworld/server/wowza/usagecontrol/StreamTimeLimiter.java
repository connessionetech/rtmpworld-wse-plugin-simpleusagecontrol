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
						logger.info(ModuleSimpleUsageControl.MODULE_NAME + ": RTMP disconnecting client " + client.getClientId() + " FlashVer is " + client.getFlashVer(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

					client.setShutdownClient(true);
				}
			}
			
			
		}

		Iterator<IHTTPStreamerSession> httpSessions = appInstance.getHTTPStreamerSessions().iterator();
		while (httpSessions.hasNext())
		{
			IHTTPStreamerSession httpSession = httpSessions.next();
			WMSProperties props = httpSession.getProperties();
			if (httpSession.getTimeRunningSeconds() > restrictions.ingest.maxPublishTime)
			{
				if (props.containsKey(ModuleSimpleUsageControl.KEY_PUBLISHER))
				{
					if (debug)
						logger.info(ModuleSimpleUsageControl.MODULE_NAME + ": HTTP disconnecting session " + httpSession.getSessionId(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

					httpSession.rejectSession();
				}
			}
		}

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
						logger.info(ModuleSimpleUsageControl.MODULE_NAME + ": RTP disconnecting client " + rtpSession.getSessionId(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

					appInstance.getVHost().getRTPContext().shutdownRTPSession(rtpSession);
				}
			}
		}
	}
}
