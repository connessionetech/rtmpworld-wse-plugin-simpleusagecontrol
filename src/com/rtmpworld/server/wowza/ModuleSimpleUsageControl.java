package com.rtmpworld.server.wowza;

import com.wowza.wms.application.*;

import java.io.File;
import java.io.FileReader;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.rtmpworld.server.wowza.usagecontrol.UsageRestrictions;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.UsageRestrictionException;
import com.wowza.util.IOPerformanceCounter;
import com.wowza.wms.amf.*;
import com.wowza.wms.client.*;
import com.wowza.wms.module.*;
import com.wowza.wms.request.*;
import com.wowza.wms.rest.restserver.HTTPVerifierWowzaRemoteHTTP.HTTPSession;
import com.wowza.wms.stream.*;
import com.wowza.wms.rtp.model.*;
import com.wowza.wms.server.Server;
import com.wowza.wms.httpstreamer.model.*;
import com.wowza.wms.httpstreamer.cupertinostreaming.httpstreamer.*;
import com.wowza.wms.httpstreamer.smoothstreaming.httpstreamer.*;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerIDs;

public class ModuleSimpleUsageControl extends ModuleBase {
	
	private IApplicationInstance appInstance;
	private UsageRestrictions restrictions;	
	private StreamListener streamListener = new StreamListener();
	
	
	// module name and property name prefix
	private static String PROP_NAME_PREFIX = "usagecontrol";
	private static String MODULE_NAME = "ModuleSimpleUsageControl";
	
	// for logging
	private static String PROP_DEBUG = PROP_NAME_PREFIX + "Debug";
	private static String PROP_RESTRICTIONS_RULE_PATH = PROP_NAME_PREFIX + "RestrictionsRulePath";
	private static String KEY_PUBLISHER = "PUBLISHER";
	private static String KEY_PUBLISH_TIME = "PUBLISHTIME";
	private static String KEY_PUBLISH_PROTOCOL = "PUBLISHPROTOCOL";
	private static String KEY_SUBSCRIBER = "KEY_SUBSCRIBER";
	private static String KEY_SUBSCRIBE_TIME = "SUBSCRIBETIME";
	private static String KEY_SUBSCRIBE_PROTOCOL = "SUBSCRIBEPROTOCOL";
	
	private String restrictionsRulePath;	
	private boolean moduleDebug;
	private Timer timer = null;

	
	private static WMSProperties serverProps = Server.getInstance().getProperties();
	private WMSLogger logger;	
	
	
	private void validateApplicationBandwidthUsageRestrictions() throws UsageRestrictionException
	{
		IOPerformanceCounter perf = appInstance.getIOPerformanceCounter();
		double bytesIn = perf.getMessagesInBytes();
		double bytesOut = perf.getMessagesOutBytes();
		
		if((restrictions.maxBytesIn>0) && (bytesIn > restrictions.maxBytesIn))
		{
			throw new UsageRestrictionException("Max bytes-In restriction breached!!");
		}
		
		if((restrictions.maxBytesOut>0) && (bytesOut > restrictions.maxBytesOut))
		{
			throw new UsageRestrictionException("Max bytes-Out restriction breached!!");
		}
	}
	
	
	
	private class Disconnecter extends TimerTask
	{

		public synchronized void run()
		{			
			Iterator<IClient> clients = appInstance.getClients().iterator();
			while (clients.hasNext())
			{
				IClient client = clients.next();
				WMSProperties props = client.getProperties();
				if (client.getTimeRunningSeconds() > restrictions.ingest.maxPublishTime)
				{
					if (props.containsKey(KEY_PUBLISHER))
					{
						if (moduleDebug)
							logger.info(MODULE_NAME + ": RTMP disconnecting client " + client.getClientId() + " FlashVer is " + client.getFlashVer(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

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
					if (props.containsKey(KEY_PUBLISHER))
					{
						if (moduleDebug)
							logger.info(MODULE_NAME + ": HTTP disconnecting session " + httpSession.getSessionId(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

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
					if (props.containsKey(KEY_PUBLISHER))
					{
						if (moduleDebug)
							logger.info(MODULE_NAME + ": RTSP disconnecting client " + rtpSession.getSessionId(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

						appInstance.getVHost().getRTPContext().shutdownRTPSession(rtpSession);
					}
				}
			}
		}
	}
	
	
	
	class StreamListener extends MediaStreamActionNotifyBase
	{
		@Override
		public void onPause(IMediaStream stream, boolean isPause, double location)
		{
			
		}
		
		@Override
		public void onStop(IMediaStream stream)
		{
			
		}

		@Override
		public void onPlay(IMediaStream stream, String streamName, double playStart, double playLen, int playReset) 
		{

			if(moduleDebug) {
				logger.info(MODULE_NAME+".onPlay = > " + streamName);
			}
			
			try
			{
				// marking publishers
				StreamingProtocols protocol = WowzaUtils.getClientProtocol(stream);
				WMSProperties props = null;
				
				switch(protocol)
				{
					case RTMP:
						IClient client = stream.getClient();
						props = client.getProperties();
					break;
					
					case RTSP:							
					case WEBRTC:
						RTPSession rtpSession = stream.getRTPStream().getSession();
						props = rtpSession.getProperties();
					break;
					
					case HTTP:
						IHTTPStreamerSession httpSession = stream.getHTTPStreamerSession();
						props = httpSession.getProperties();
						break;
						
					case UNKNOWN:
						default:
							getLogger().info("Unknown stream type");
						break;
				}
				
				
				// if we have properties object set new properties
				if(props != null)
				{
					synchronized(props)
					{
						if(moduleDebug) {
							logger.info(MODULE_NAME+".onPlay => setting properties `subscriber` &`protocol` => "+protocol+" on session");
						}
						props.setProperty(KEY_SUBSCRIBER, true);
						props.setProperty(KEY_SUBSCRIBE_TIME, System.currentTimeMillis());
						props.setProperty(KEY_SUBSCRIBE_PROTOCOL, protocol);
					}
				}
			}
			catch(Exception e)
			{
				logger.error("Error setting properties on playback session");
			}
		}
		

		@Override
		public void onPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend)
		{
			if(moduleDebug) {
				logger.info(MODULE_NAME+".onPublish = > " + streamName);
			}
			
			try
			{
				// marking publishers
				StreamingProtocols protocol = WowzaUtils.getClientProtocol(stream);
				WMSProperties props = null;
				
				switch(protocol)
				{
					case RTMP:
						IClient client = stream.getClient();
						props = client.getProperties();
					break;
					
					case RTSP:							
					case WEBRTC:
						RTPSession rtpSession = stream.getRTPStream().getSession();
						props = rtpSession.getProperties();
					break;
					
					case HTTP:
						IHTTPStreamerSession httpSession = stream.getHTTPStreamerSession();
						props = httpSession.getProperties();
						break;
						
					case UNKNOWN:
						default:
							getLogger().info("Unknown stream type");
						break;
				}
				
				
				// if we have properties object set new properties
				if(props != null)
				{
					synchronized(props)
					{
						if(moduleDebug) {
							logger.info(MODULE_NAME+".onPublish => setting properties `publisher` &`protocol` => "+protocol+" on session");
						}
						props.setProperty(KEY_PUBLISHER, true);
						props.setProperty(KEY_PUBLISH_TIME, System.currentTimeMillis());
						props.setProperty(KEY_PUBLISH_PROTOCOL, protocol);
					}
				}
			}
			catch(Exception e)
			{
				logger.error("Error setting properties on publish session");
			}
		}



		@Override
		public void onUnPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend)
		{
			if(moduleDebug) {
				logger.info(MODULE_NAME+".onUnPublish = > " + streamName);
			}
			
		}
	}	
	
	
	
	private void readProperties()
	{ 
		logger.info(MODULE_NAME + ".readProperties => reading properties");
		
		try
		{
			moduleDebug = getPropertyValueBoolean(PROP_NAME_PREFIX + "Debug", false);

			//if (logger.isDebugEnabled())
			//	moduleDebug = true;

			if (moduleDebug)
				logger.info(MODULE_NAME + " DEBUG mode is ON");
			else
				logger.info(MODULE_NAME + " DEBUG mode is OFF");
			
			restrictionsRulePath = getPropertyValueStr(PROP_RESTRICTIONS_RULE_PATH, null);
			if(moduleDebug){
				logger.info(MODULE_NAME + " reportingEndPoint : " + String.valueOf(restrictionsRulePath));
			}				
		}
		catch(Exception e)
		{
			logger.error(MODULE_NAME + " Error reading properties {}", e);
		}
	}
	
	
	private void loadRestrictions()
	{
		try
		{
			File file = new File(restrictionsRulePath);
			if(file.exists())
			{
				Gson gson = new Gson();
				JsonReader reader = new JsonReader(new FileReader(file));
				restrictions = gson.fromJson(reader,UsageRestrictions.class); 
				
				if(moduleDebug) {
					logger.error(MODULE_NAME + ".loadRestrictions => restrictions loaded successfully" +  restrictions.toString());
				}
			}
		}
		catch(Exception e)
		{
			restrictions = new UsageRestrictions();
			logger.error(MODULE_NAME + ".loadRestrictions => Error reading restrictions " +  e.getMessage());
		}
	}
	
	
	private String getPropertyValueStr(String key, String defaultValue)
	{
		String value = serverProps.getPropertyStr(key, defaultValue);
		value = appInstance.getProperties().getPropertyStr(key, value);
		return value;
	}
	
	
	private int getPropertyValueInt(String key, int defaultValue)
	{
		int value = serverProps.getPropertyInt(key, defaultValue);
		value = appInstance.getProperties().getPropertyInt(key, value);
		return value;
	}
	
	
	private boolean getPropertyValueBoolean(String key, boolean defaultValue)
	{
		boolean value = serverProps.getPropertyBoolean(key, defaultValue);
		value = appInstance.getProperties().getPropertyBoolean(key, value);
		return value;
	}
	
	
	public String getHTTPProtocol(IHTTPStreamerSession session)
	{
		String connectionProtocol = "HTTP";
		switch (session.getSessionProtocol())
		{
		case IHTTPStreamerSession.SESSIONPROTOCOL_CUPERTINOSTREAMING:
			connectionProtocol = "HTTPCupertino";
			break;
		case IHTTPStreamerSession.SESSIONPROTOCOL_MPEGDASHSTREAMING:
			connectionProtocol = "HTTPMpegDash";
			break;
		case IHTTPStreamerSession.SESSIONPROTOCOL_SMOOTHSTREAMING:
			connectionProtocol = "HTTPSmooth";
			break;
		case IHTTPStreamerSession.SESSIONPROTOCOL_SANJOSESTREAMING:
			connectionProtocol = "HTTPSanjose";
			break;
		}
		return connectionProtocol;
	}
	
	

	public void onAppStart(IApplicationInstance appInstance) {
		this.logger = getLogger();
		
		String fullname = appInstance.getApplication().getName() + "/" + appInstance.getName();
		logger.info(MODULE_NAME+".onAppStart: " + fullname);
		
		this.appInstance = appInstance;
		this.readProperties();
		
		if(this.restrictionsRulePath != null)
		{
			loadRestrictions();
			
			// if restrictions are enabled run timer to scan for connections
			if(this.restrictions.enableRestrictions) {
				this.timer = new Timer(MODULE_NAME + " [" + appInstance.getContextStr() + "]");
				this.timer.schedule(new Disconnecter(), 0, 1000);
			}
		}
	}
	
	
	public void onStreamCreate(IMediaStream stream)
	{
		stream.addClientListener(streamListener);
	}

	
	public void onStreamDestroy(IMediaStream stream)
	{
		stream.removeClientListener(streamListener);
	}
	

	public void onAppStop(IApplicationInstance appInstance) {
		String fullname = appInstance.getApplication().getName() + "/" + appInstance.getName();
		logger.info(MODULE_NAME+".onAppStop: " + fullname);

		if (timer != null)
		{
			timer.cancel();
		}
		timer = null;
	}
	
	
	public void onRTPSessionCreate(RTPSession rtpSession) {
		getLogger().info(MODULE_NAME+".onRTPSessionCreate: " + rtpSession.getSessionId());
		
		
		try 
		{
			this.validateApplicationBandwidthUsageRestrictions();
		} 
		catch (UsageRestrictionException e) 
		{
			if(moduleDebug) {
				logger.info(MODULE_NAME + ".onConnect => rejecting session as usage restrictions were violated(" + e.getMessage() + ").");
			}
			
			
			WowzaUtils.terminateSession(appInstance, rtpSession);
		}
	}
	
	
	public void onHTTPSessionCreate(IHTTPStreamerSession httpSession) {
		getLogger().info(MODULE_NAME+".onHTTPSessionCreate: " + httpSession.getSessionId());
		
		try 
		{
			this.validateApplicationBandwidthUsageRestrictions();
		} 
		catch (UsageRestrictionException e) 
		{
			if(moduleDebug) {
				logger.info(MODULE_NAME + ".onConnect => rejecting session as usage restrictions were violated(" + e.getMessage() + ").");
			}
			
			WowzaUtils.terminateSession(appInstance, httpSession);
		}
	}
	

	public void onConnect(IClient client, RequestFunction function, AMFDataList params) {
		logger.info(MODULE_NAME+".onConnect: " + client.getClientId());
		
		if(WowzaUtils.isRTMPClient(client))
		{
			try 
			{
				this.validateApplicationBandwidthUsageRestrictions();
			} 
			catch (UsageRestrictionException e) 
			{
				if(moduleDebug) {
					logger.info(MODULE_NAME + ".onConnect => rejecting session as usage restrictions were violated(" + e.getMessage() + ").");
				}
				
				WowzaUtils.terminateSession(appInstance, client);
			}
		}

	}
	
	
	

	public void onConnectAccept(IClient client) {
		logger.info(MODULE_NAME+".onConnectAccept: " + client.getClientId());
	}

	public void onConnectReject(IClient client) {
		getLogger().info(MODULE_NAME+".onConnectReject: " + client.getClientId());
	}

	public void onDisconnect(IClient client) {
		getLogger().info(MODULE_NAME+".onDisconnect: " + client.getClientId());
	}
}