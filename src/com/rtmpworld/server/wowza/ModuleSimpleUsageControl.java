package com.rtmpworld.server.wowza;

import com.wowza.wms.application.*;

import java.io.File;
import java.io.FileReader;

import com.google.gson.Gson;
import com.rtmpworld.server.wowza.usagecontrol.UsageRestrictions;
import com.wowza.wms.amf.*;
import com.wowza.wms.client.*;
import com.wowza.wms.module.*;
import com.wowza.wms.request.*;
import com.wowza.wms.stream.*;
import com.wowza.wms.rtp.model.*;
import com.wowza.wms.server.Server;
import com.wowza.wms.httpstreamer.model.*;
import com.wowza.wms.httpstreamer.cupertinostreaming.httpstreamer.*;
import com.wowza.wms.httpstreamer.smoothstreaming.httpstreamer.*;
import com.wowza.wms.logging.WMSLogger;

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
	
	private String restrictionsRulePath;	
	private boolean moduleDebug;
	
	
	private static WMSProperties serverProps = Server.getInstance().getProperties();
	private WMSLogger logger;
	
	
	
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
			
		}
		

		@Override
		public void onPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend)
		{
			
		}



		@Override
		public void onUnPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend)
		{
			
		}
	}	
	
	
	
	private void readProperties()
	{ 
		logger.info(MODULE_NAME + ".readProperties => reading properties");
		
		try
		{
			moduleDebug = getPropertyValueBoolean(PROP_NAME_PREFIX + "Debug", false);

			if (logger.isDebugEnabled())
				moduleDebug = true;

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
				restrictions = gson.fromJson(new FileReader(file),UsageRestrictions.class); 
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
	}

	public void onConnect(IClient client, RequestFunction function, AMFDataList params) {
		logger.info(MODULE_NAME+".onConnect: " + client.getClientId());
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