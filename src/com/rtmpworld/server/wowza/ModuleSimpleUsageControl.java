package com.rtmpworld.server.wowza;

import com.wowza.wms.application.*;

import java.io.File;
import java.io.FileReader;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.rtmpworld.server.wowza.decorators.StreamingSessionTarget;
import com.rtmpworld.server.wowza.enums.StreamingProtocols;
import com.rtmpworld.server.wowza.usagecontrol.CountryInfo;
import com.rtmpworld.server.wowza.usagecontrol.StreamBitrateMonitor;
import com.rtmpworld.server.wowza.usagecontrol.StreamTimeLimiter;
import com.rtmpworld.server.wowza.usagecontrol.dataprovider.IPWhoIsWebServiceGeoInfoProvider;
import com.rtmpworld.server.wowza.usagecontrol.dataprovider.MaxmindDBGeoInfoProvider;
import com.rtmpworld.server.wowza.usagecontrol.dataprovider.MaxmindWebServiceGeoInfoProvider;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.GeoInfoException;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.UsageRestrictionException;
import com.rtmpworld.server.wowza.usagecontrol.interfaces.IClientSessionManager;
import com.rtmpworld.server.wowza.usagecontrol.interfaces.IGeoInfoProvider;
import com.rtmpworld.server.wowza.usagecontrol.restrictions.UsageRestrictions;
import com.rtmpworld.server.wowza.utils.WowzaUtils;
import com.rtmpworld.server.wowza.webrtc.constants.WebRTCDirections;
import com.rtmpworld.server.wowza.webrtc.model.WebRTCJSONStr;
import com.wowza.util.IOPerformanceCounter;
import com.wowza.wms.amf.*;
import com.wowza.wms.client.*;
import com.wowza.wms.module.*;
import com.wowza.wms.request.*;
import com.wowza.wms.stream.*;
import com.wowza.wms.stream.mediacaster.MediaStreamMediaCasterUtils;
import com.wowza.wms.util.ModuleUtils;
import com.wowza.wms.rtp.model.*;
import com.wowza.wms.rtsp.RTSPRequestMessage;
import com.wowza.wms.rtsp.RTSPResponseMessages;
import com.wowza.wms.server.Server;
import com.wowza.wms.httpstreamer.model.*;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.logging.WMSLoggerIDs;

public class ModuleSimpleUsageControl extends ModuleBase implements IClientSessionManager {
	
	private IApplicationInstance appInstance;
	private UsageRestrictions restrictions;	
	private IGeoInfoProvider geoInfoProvider;
	private StreamListener streamListener = new StreamListener();
	private RTSPListener rtspListener = new RTSPListener();

	
	private Map<Long, String> httpSessionCache = new ConcurrentHashMap<Long, String>();
	private Map<Long, String> rtmpSessionCache = new ConcurrentHashMap<Long, String>();
	private long sessionCacheDuration = 600*1000; // 10 minutes
	
	
	// module name and property name prefix
	private static String PROP_NAME_PREFIX = "usagecontrol";
	public static String MODULE_NAME = "ModuleSimpleUsageControl";
	
	// for logging
	private static String PROP_DEBUG = PROP_NAME_PREFIX + "Debug";
	private static String PROP_RESTRICTIONS_RULE_PATH = PROP_NAME_PREFIX + "RestrictionsRulePath";
	
	private static String PROP_MAXMIND_ACCOUNT_ID = PROP_NAME_PREFIX + "MaxmindAccountId";
	private static String PROP_MAXMIND_DB_PATH = PROP_NAME_PREFIX + "MaxmindDBPath";
	private static String PROP_GEO_API_LICENSE_KEY = PROP_NAME_PREFIX + "GeoApiLicenseKey";
	private static String PROP_ALLOW_ON_GEO_FAIL = PROP_NAME_PREFIX + "AllowOnGeoFail";
	private static String PROP_SESSION_CACHE_DURATION = PROP_NAME_PREFIX + "SessionCacheDuration";
	
	
	public static String KEY_PUBLISHER = "PUBLISHER";
	private static String KEY_PUBLISH_TIME = "PUBLISHTIME";
	private static String KEY_PUBLISH_PROTOCOL = "PUBLISHPROTOCOL";
	public static String KEY_SUBSCRIBER = "KEY_SUBSCRIBER";
	private static String KEY_SUBSCRIBE_TIME = "SUBSCRIBETIME";
	private static String KEY_SUBSCRIBE_PROTOCOL = "SUBSCRIBEPROTOCOL";
	
	
	// for threading
	private static String PROP_THREADPOOL_SIZE = PROP_NAME_PREFIX + "ThreadPoolSize";
	private static String PROP_THREAD_IDLE_TIMEOUT = PROP_NAME_PREFIX + "ThreadIdleTimeout";
	private static String PROP_THREADPOOL_TERMINATION_TIMEOUT = PROP_NAME_PREFIX + "ThreadPoolTerminationTimeout";
	
	
	private static ThreadPoolExecutor httpRequestThreadPool;
	private static int threadPoolSize;
	private static int threadIdleTimeout;	
	private static int threadPoolAwaitTerminationTimeout;
	
	
	private String restrictionsRulePath;
	boolean moduleDebug;
	private boolean logViewerCounts = false;
	private static boolean serverDebug = false;
	private Timer timer = null;
	
	private int maxmindAccountId;
	private String geoApiLicenseKey;
	private String maxmindDbPath;
	private boolean allowOnGeoFail;

	
	private static WMSProperties serverProps = Server.getInstance().getProperties();
	private WMSLogger logger;
	
	
	
	
	/**
	 * Static code-block to initialize threadpool
	 */
	static  
	{
		serverDebug = serverProps.getPropertyBoolean(PROP_DEBUG, false);
		if (WMSLoggerFactory.getLogger(ModuleSimpleUsageControl.class).isDebugEnabled())
			serverDebug = true;
		
		threadPoolSize = serverProps.getPropertyInt(PROP_THREADPOOL_SIZE, 5);
		threadIdleTimeout = serverProps.getPropertyInt(PROP_THREAD_IDLE_TIMEOUT, 20);
		threadPoolAwaitTerminationTimeout = serverProps.getPropertyInt(PROP_THREADPOOL_TERMINATION_TIMEOUT, 5);
		httpRequestThreadPool = new ThreadPoolExecutor(threadPoolSize, threadPoolSize, threadIdleTimeout, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
		
		Runtime.getRuntime().addShutdownHook(new Thread()
		{
			@Override
			public void run()
			{
				try
				{
					if (serverDebug)
						WMSLoggerFactory.getLogger(getClass()).info(MODULE_NAME + " Runtime.getRuntime().addShutdownHook");
					httpRequestThreadPool.shutdown();
					threadPoolAwaitTerminationTimeout = serverProps.getPropertyInt(PROP_NAME_PREFIX + "ThreadPoolTerminationTimeout", 5);
					if (!httpRequestThreadPool.awaitTermination(threadPoolAwaitTerminationTimeout, TimeUnit.SECONDS))
						httpRequestThreadPool.shutdownNow();
				}
				catch (InterruptedException e)
				{
					// problem
					WMSLoggerFactory.getLogger(ModuleSimpleUsageControl.class).error(MODULE_NAME + ".ShutdownHook.run() InterruptedException", e);
				}
			}
		});
	}
	
	
	
	/** Decode & return stream name in case it is not straightforward **/
	private String decodeStreamName(String streamName)
	{
		String streamExt = MediaStream.BASE_STREAM_EXT;
		if (streamName != null)
		{
			String[] streamDecode = ModuleUtils.decodeStreamExtension(streamName, streamExt);
			streamName = streamDecode[0];
			streamExt = streamDecode[1];

			boolean isStreamNameURL = streamName.indexOf("://") >= 0;
			int streamQueryIdx = streamName.indexOf("?");
			if (!isStreamNameURL && streamQueryIdx >= 0)
			{
				streamName = streamName.substring(0, streamQueryIdx);
			}
		}
		return streamName;
	}
	

	
	
	
	/**
	 * Get viewer count
	 * 
	 * @param streamName
	 * @return
	 */
	private int getStreamViewerCounts(String streamName)
	{
		return getStreamViewerCounts(streamName, null);
	}
	
	
	
	/**
	 * Get publisher count
	 * 
	 * @return
	 */
	private synchronized int getPublisherCount()
	{
		return this.appInstance.getPublishStreamNames().size();
	}
	
	
	
	
	/**
	 * 
	 * @return total publishers count
	 */
	private synchronized int getPublisherCountUsingProperties()
	{
		int rtmpCount = 0;
		int rtpCount = 0;
		int httpCount = 0;
		
		WMSProperties props;
		
		//count http
		List<IHTTPStreamerSession> httpsessions = appInstance.getHTTPStreamerSessions();
		for(IHTTPStreamerSession httpsession : httpsessions)
		{
			props = httpsession.getProperties();
			if(props.containsKey(KEY_PUBLISHER))
			{
				httpCount += 1;
			}
		}
		
		
		//count rtp
		List<RTPSession> rtpsessions = appInstance.getRTPSessions();
		for(RTPSession rtpsession : rtpsessions)
		{
			props = rtpsession.getProperties();
			if(props.containsKey(KEY_PUBLISHER))
			{
				rtpCount += 1;
			}
		}
		
		
		//count rtmp
		List<IClient> clients = appInstance.getClients();
		for(IClient client : clients)
		{
			props = client.getProperties();
			if(props.containsKey(KEY_PUBLISHER))
			{
				rtmpCount += 1;
			}
		}
		
		
		return rtmpCount + rtpCount + httpCount;
	}
	
	
	
	
	
	
	/**
	 * Get viewer count
	 * 
	 * @param streamName
	 * @param client
	 * @return
	 */
	private synchronized int getStreamViewerCounts(String streamName, IClient client)
	{
		int count = 0;
		int rtmpCount = 0;
		int httpCount = 0;
		int rtpCount = 0;

		streamName = decodeStreamName(streamName);
		if (streamName != null)
		{
			rtmpCount += appInstance.getPlayStreamCount(streamName);
			httpCount += appInstance.getHTTPStreamerSessionCount(streamName);
			rtpCount += appInstance.getRTPSessionCount(streamName);

			// Test for mediaCaster streams like wowz://[origin-ip]:1935/origin/myStream.
			String mediaCasterName = MediaStreamMediaCasterUtils.mapMediaCasterName(appInstance, client, streamName);
			if (!mediaCasterName.equals(streamName))
			{
				if (logViewerCounts)
					logger.info(MODULE_NAME + ".getViewerCounts matching mediaCaster name: " + mediaCasterName, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				rtmpCount += appInstance.getPlayStreamCount(mediaCasterName);
				httpCount += appInstance.getHTTPStreamerSessionCount(mediaCasterName);
				rtpCount += appInstance.getRTPSessionCount(mediaCasterName);
			}
			count = rtmpCount + httpCount + rtpCount;

			if (logViewerCounts) {
				logger.info(MODULE_NAME + ".getViewerCounts streamName: " + streamName + " total:" + count + " rtmp: " + rtmpCount + " http: " + httpCount + " rtp: " + rtpCount, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			}

		}
		return count;
	}
	
	
	
	
	/**
	 * Returns total viewer count of the application instance.This is the net sum of
	 * all subscribers of all streams.  
	 * 
	 * @param client
	 * @return
	 */
	private synchronized int getTotalViewerCounts(IClient client)
	{
		int totalCount = 0;		
		List<String> streamNames = appInstance.getPublishStreamNames();
		
		for(String streamName : streamNames)
		{
			int count = 0;
			int rtmpCount = 0;
			int httpCount = 0;
			int rtpCount = 0;
			
			streamName = decodeStreamName(streamName);
			if (streamName != null)
			{
				rtmpCount += appInstance.getPlayStreamCount(streamName);
				httpCount += appInstance.getHTTPStreamerSessionCount(streamName);
				rtpCount += appInstance.getRTPSessionCount(streamName);
	
				// Test for mediaCaster streams like wowz://[origin-ip]:1935/origin/myStream.
				String mediaCasterName = MediaStreamMediaCasterUtils.mapMediaCasterName(appInstance, client, streamName);
				if (!mediaCasterName.equals(streamName))
				{
					if (logViewerCounts)
						logger.info(MODULE_NAME + ".getViewerCounts matching mediaCaster name: " + mediaCasterName, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
					rtmpCount += appInstance.getPlayStreamCount(mediaCasterName);
					httpCount += appInstance.getHTTPStreamerSessionCount(mediaCasterName);
					rtpCount += appInstance.getRTPSessionCount(mediaCasterName);
				}
	
				if (logViewerCounts)
					logger.info(MODULE_NAME + ".getViewerCounts streamName: " + streamName + " total:" + count + " rtmp: " + rtmpCount + " http: " + httpCount + " rtp: " + rtpCount, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
			}
			
			count = (rtmpCount + httpCount + rtpCount);
			totalCount += count;
		}
		
		return totalCount;
	}
	
	
	
	
	
	/**
	 * GeoRestriction class to be used for encapsulating GeoInfo data
	 * for use inside Future scopes.
	 */
	private class GeoRestriction{
		
		private boolean checkByAllowed = false;
		private boolean checkByRestricted = false;
		

		public boolean isCheckByAllowed() {
			return checkByAllowed;
		}

		public void setCheckByAllowed(boolean checkByAllowed) {
			this.checkByAllowed = checkByAllowed;
		}

		public boolean isCheckByRestricted() {
			return checkByRestricted;
		}

		public void setCheckByRestricted(boolean checkByRestricted) {
			this.checkByRestricted = checkByRestricted;
		}
	}
	
	
	
	
	
	/**
	 * Validate total bandwidth usage of application (bytes-in/bytes-out) against max allowed
	 * values. Exception is thrown when the usage exceeds the max allowed limits.
	 * 
	 * @throws UsageRestrictionException
	 */
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
	
	
	
	/**
	 * Validates current publisher count against max allowed publishers.
	 * Exception is thrown when the count will exceeds the max allowed publishers.
	 * 
	 * @throws UsageRestrictionException
	 */
	private void validateMaxPublisherRestrictions() throws UsageRestrictionException
	{
		getLogger().info(MODULE_NAME + ".validateMaxPublisherRestrictions");
		
		int publisherCount = getPublisherCount();
		
		if(moduleDebug) {
			getLogger().info(MODULE_NAME + ".publisherCount = " + publisherCount);
		}
		
		if((restrictions.ingest.maxPublishersCount>0) && (publisherCount > restrictions.ingest.maxPublishersCount))
		{
			throw new UsageRestrictionException("Max publishers restriction reached!!");
		}
	}
	
	
	
	/**
	 * Validates current viewer count against max allowed viewers.
	 * Exception is thrown when the count will exceeds the max allowed viewers.
	 * 
	 * @param streamName
	 * @throws UsageRestrictionException
	 */
	private void validateMaxViewerRestrictionsPerStream(String streamName) throws UsageRestrictionException
	{
		int viewerCount = getStreamViewerCounts(streamName);
		if((restrictions.egress.maxSubscribersPerStream>0) && (viewerCount > restrictions.egress.maxSubscribersPerStream))
		{
			throw new UsageRestrictionException("Max viewer per stream restriction reached!!");
		}
	}
	
	
	
	/**
	 * Validates total viewer count (sum of all viewers for all streams) against max allowed limit.
	 * Exception is thrown when the count will exceeds the max allowed viewers.
	 * 
	 * @throws UsageRestrictionException
	 */
	private void validateMaxViewerRestrictions() throws UsageRestrictionException
	{
		int viewerCount = getTotalViewerCounts(null);
		
		if(moduleDebug) {
			logger.info("Total viewer count = " + viewerCount);
		}
		
		if((restrictions.egress.maxSubscribers>0) && (viewerCount > restrictions.egress.maxSubscribers))
		{
			throw new UsageRestrictionException("Max total viewer restriction reached!!");
		}
	}
	
	
	
	
	/**
	 * Validates a target (IMediaStream, RTPSession, IHTTPStreamerSession, IClient)'s country
	 * against supplied list of allowed/restricted country codes.
	 * 
	 * @param target
	 * @param allowedFrom
	 * @param restrictedFrom
	 * @throws UsageRestrictionException
	 */
	private void validateGeoRestrictions(StreamingSessionTarget target, List<String> allowedFrom, List<String> restrictedFrom) throws UsageRestrictionException
	{
		final GeoRestriction georestriction = new GeoRestriction();
		final String ip = target.getIPAddress(); // "115.240.90.163"
		
		
		// allow unconditionally
		if(ip.equalsIgnoreCase("127.0.0.1") || ip.equalsIgnoreCase("localhost") || allowedFrom.contains("*")) {
			if (moduleDebug) {
				logger.info(MODULE_NAME + ".validateGeoRestrictions => Allowing unconditionally");
			}
			return;
		}
		
		
		// terminate unconditionally
		if(restrictedFrom.contains("*")) {
			if (moduleDebug) {
				logger.info(MODULE_NAME + ".validateGeoRestrictions => Disallowing unconditionally");
			}
			
			target.terminateSession();
			addSession(target);
		}
		
		
		if(allowedFrom != null && allowedFrom.size() > 0)
		{
			if (moduleDebug)
				logger.info(MODULE_NAME + ".validateGeoRestrictions => allowed check mode");

			
			georestriction.setCheckByAllowed(true);
		}
		else if(restrictedFrom != null && restrictedFrom.size() > 0)
		{
			if (moduleDebug)
				logger.info(MODULE_NAME + ".validateGeoRestrictions => restricted check mode");

			
			georestriction.setCheckByRestricted(true);
		}
		
		if(georestriction.isCheckByAllowed() || georestriction.isCheckByRestricted())
		{
			if (moduleDebug) {
				logger.info(MODULE_NAME + ".validateGeoRestrictions => async fetch");
			}
			
			CompletableFuture<CountryInfo> future = getGeoInfo(ip);
			future.thenAccept(value -> {
				
				// What to do if we are unable to fetch geo info
				if(value == null) {
					if(allowOnGeoFail)
					{
						return;
					}
					else
					{
						target.terminateSession();
						addSession(target);
					}
				}
				
				
				String cc = value.getCountryCode();
				
				if (moduleDebug) {
					logger.info(MODULE_NAME + ".validateGeoRestrictions => Checking country code " + cc);
				}
				
				if(georestriction.isCheckByAllowed())
				{
					if(!allowedFrom.contains(cc.toUpperCase()))
					{
						if (moduleDebug)
							logger.info(MODULE_NAME + ".validateGeoRestrictions => country code not in list of allowed");
						
						
						target.terminateSession();
						addSession(target);
					}
					else
					{
						if (moduleDebug)
							logger.info(MODULE_NAME + ".validateGeoRestrictions => Country allowed");
					}
				}
				else if(georestriction.isCheckByRestricted())
				{
					if(restrictedFrom.contains(cc.toUpperCase()))
					{
						if (moduleDebug)
							logger.info(MODULE_NAME + ".validateGeoRestrictions => country code is in list of restricted");
						
						
						target.terminateSession();
						addSession(target);
					}
					else
					{
						if (moduleDebug)
							logger.info(MODULE_NAME + ".validateGeoRestrictions => Country allowed");
					}
				}
			});
		}
	}
	
	
	
	
	/**
	 * Fetches geo info via IGeoInfoProvider
	 * @param ip
	 * @return
	 */
	private CompletableFuture<CountryInfo> getGeoInfo(String ip)
	{
		return CompletableFuture.supplyAsync(()->{
			
			CountryInfo info = null;
			
			try 
			{
				if(moduleDebug) {
					logger.info("Looking up geo info for " + ip + " through " + geoInfoProvider.getClass().getCanonicalName());
				}
				
				info = geoInfoProvider.getCountryInfo(ip);
			} 
			catch (GeoInfoException e) 
			{
				logger.error("Unable to fetch geo information for client ip {}. Cause {}", ip, e);
			}
			
			return info;
			
		});
	}
	
	
	
	
	class RTSPListener extends RTSPActionNotifyBase
	{

		@Override
		public void onAnnounce(RTPSession arg0, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onAnnounce");
			super.onAnnounce(arg0, arg1, arg2);
		}

		@Override
		public void onDescribe(RTPSession arg0, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onDescribe");
			super.onDescribe(arg0, arg1, arg2);
		}

		@Override
		public void onGetParameter(RTPSession arg0, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onGetParameter");
			super.onGetParameter(arg0, arg1, arg2);
		}

		@Override
		public void onOptions(RTPSession arg0, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onOptions");
			super.onOptions(arg0, arg1, arg2);
		}

		@Override
		public void onPause(RTPSession arg0, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onPause");
			super.onPause(arg0, arg1, arg2);
		}

		@Override
		public void onPlay(RTPSession rtpSession, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onPlay");
			super.onPlay(rtpSession, arg1, arg2);
			
			WMSProperties props = rtpSession.getProperties();
			
			// if we have properties object set new properties
			if(props != null)
			{
				synchronized(props)
				{
					if(moduleDebug) {
						logger.info(MODULE_NAME+".onPlay (RTP) => setting properties `subscriber` &`protocol` => "+protocol+" on session");
					}					
				
					props.setProperty(KEY_SUBSCRIBER, true);
					props.setProperty(KEY_SUBSCRIBE_TIME, System.currentTimeMillis());
					props.setProperty(KEY_SUBSCRIBE_PROTOCOL, StreamingProtocols.RTSP);
				}
			}
		}

		@Override
		public void onRecord(RTPSession arg0, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onRecord");
			super.onRecord(arg0, arg1, arg2);
		}

		@Override
		public void onRedirect(RTPSession arg0, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onRedirect");
			super.onRedirect(arg0, arg1, arg2);
		}

		@Override
		public void onSetParameter(RTPSession arg0, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onSetParameter");
			super.onSetParameter(arg0, arg1, arg2);
		}

		@Override
		public void onSetup(RTPSession arg0, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onSetup");
			super.onSetup(arg0, arg1, arg2);
		}

		@Override
		public void onTeardown(RTPSession arg0, RTSPRequestMessage arg1, RTSPResponseMessages arg2) {
			//logger.info(MODULE_NAME + " RTSPListener.onTeardown");
			super.onTeardown(arg0, arg1, arg2);
		}
				
	}
	
	
	
		
	
	/**
	 * Class for listening to stream events 
	 */
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
			
			
			
			
			if(restrictions.enableRestrictions)
			{
				
				/** Max total viewers restriction check**/
				try
				{
					validateMaxViewerRestrictions();
				}
				catch (UsageRestrictionException e) 
				{
					if(moduleDebug) {
						logger.info(MODULE_NAME + ".onPlay => rejecting session on max total viewer restriction violation.(" + e.getMessage() + ").");
					}
					
					WowzaUtils.terminateSession(appInstance, stream);
					addSession(new StreamingSessionTarget(appInstance, stream));
				}
				
				
				
				/** Max viewers per stream restriction check**/
				try
				{
					String truStreamName = ((ApplicationInstance)appInstance).internalResolvePlayAlias(streamName);
					validateMaxViewerRestrictionsPerStream(truStreamName);
				}
				catch (UsageRestrictionException e) 
				{
					if(moduleDebug) {
						logger.info(MODULE_NAME + ".onPlay => rejecting session on max viewer restriction violation for stream "+ streamName +"(" + e.getMessage() + ").");
					}
					
					WowzaUtils.terminateSession(appInstance, stream);
					addSession(new StreamingSessionTarget(appInstance, stream));
				}
				
				
				
				
				/** GEO Restriction check**/
				try 
				{
					validateGeoRestrictions(new StreamingSessionTarget(appInstance, stream), restrictions.egress.allowedFromGeo, restrictions.egress.restrictFromGeo);
				} 
				catch (UsageRestrictionException e) 
				{
					if(moduleDebug) {
						logger.info(MODULE_NAME + ".onPlay => rejecting session on geo restrictions were violated(" + e.getMessage() + ").");
					}
					
					WowzaUtils.terminateSession(appInstance, stream);
					addSession(new StreamingSessionTarget(appInstance, stream));
				}
				
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
			
			
			if(restrictions.enableRestrictions)
			{
			
				/** Max publishers check **/
				try 
				{
					validateMaxPublisherRestrictions();
				} 
				catch (UsageRestrictionException e) 
				{
					if(moduleDebug) {
						logger.info(MODULE_NAME + ".onPublish => rejecting session on max publishers restriction violation(" + e.getMessage() + ").");
					}
					
					WowzaUtils.terminateSession(appInstance, stream);
					addSession(new StreamingSessionTarget(appInstance, stream));
				}
				
				
				/** GEO Restriction check**/
				try 
				{
					validateGeoRestrictions(new StreamingSessionTarget(appInstance, stream), restrictions.ingest.allowedFromGeo, restrictions.ingest.restrictFromGeo);
				} 
				catch (UsageRestrictionException e) 
				{
					if(moduleDebug) {
						logger.info(MODULE_NAME + ".onPublish => rejecting session on geo restrictions were violated(" + e.getMessage() + ").");
					}
					
					WowzaUtils.terminateSession(appInstance, stream);
					addSession(new StreamingSessionTarget(appInstance, stream));
				}
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
			
			
			if(restrictions.enableRestrictions)
			{
				StreamBitrateMonitor monitor = new StreamBitrateMonitor(stream, restrictions.ingest.maxPublishBitrate, appInstance, logger, moduleDebug);
				WMSProperties props = stream.getProperties();
				synchronized(props)
				{
					props.setProperty("monitor", monitor);
				}
				monitor.start();
			}
		}



		@Override
		public void onUnPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend)
		{
			if(moduleDebug) {
				logger.info(MODULE_NAME+".onUnPublish = > " + streamName);
			}
			
			
			if(restrictions.enableRestrictions)
			{
				WMSProperties props = stream.getProperties();
				StreamBitrateMonitor monitor;
	
				synchronized(props)
				{
					monitor = (StreamBitrateMonitor)props.get("monitor");
				}
				if (monitor != null)
					monitor.stop();
			}
			
		}
	}	
	
	
	
	
	/**
	 * Read application level properties
	 */
	private void readProperties()
	{ 
		logger.info(MODULE_NAME + ".readProperties => reading properties");
		
		try
		{
			moduleDebug = WowzaUtils.getPropertyValueBoolean(serverProps, appInstance,PROP_NAME_PREFIX + "Debug", false);

			if (moduleDebug)
				logger.info(MODULE_NAME + " DEBUG mode is ON");
			else
				logger.info(MODULE_NAME + " DEBUG mode is OFF");
			
			
			restrictionsRulePath = WowzaUtils.getPropertyValueStr(serverProps, appInstance, PROP_RESTRICTIONS_RULE_PATH, null);
			if(moduleDebug){
				logger.info(MODULE_NAME + " restrictionsRulePath : " + String.valueOf(restrictionsRulePath));
			}		
			
						
			allowOnGeoFail = WowzaUtils.getPropertyValueBoolean(serverProps, appInstance, PROP_ALLOW_ON_GEO_FAIL, false);
			if(moduleDebug){
				logger.info(MODULE_NAME + " allowOnGeoFail : " + String.valueOf(allowOnGeoFail));
			}
			
			
			sessionCacheDuration = WowzaUtils.getPropertyValueInt(serverProps, appInstance, PROP_SESSION_CACHE_DURATION, (600 * 1000));
			if(moduleDebug){
				logger.info(MODULE_NAME + " sessionCacheDuration : " + String.valueOf(sessionCacheDuration));
			}
			
			
			if(sessionCacheDuration < 60) {
				// minimum will be 60 seconds
				sessionCacheDuration = 60; 
			}
			
			
			// check and initialize appropriate geoinfo provider			
			try
			{
				this.maxmindDbPath = WowzaUtils.getPropertyValueStr(serverProps, appInstance, PROP_MAXMIND_DB_PATH, null);
				if(this.maxmindDbPath == null || String.valueOf(this.maxmindDbPath).equalsIgnoreCase("null"))
				{
					throw new IOException("Invalid database path");
				}
				else
				{
					if(moduleDebug) {
						logger.info("maxmindDbPath = " + this.maxmindDbPath);
					}
					
					File database = new File(this.maxmindDbPath);
					if(!database.exists()) {
						throw new IOException("Database does not exist in specified path");
					}
					
					geoInfoProvider = new MaxmindDBGeoInfoProvider(this.maxmindDbPath);
				}				
			}
			catch(IOException ie)
			{
				logger.info(MODULE_NAME + " Maxmind binary database not available. looking for WebService capabilities...");
				
				this.geoApiLicenseKey = WowzaUtils.getPropertyValueStr(serverProps, appInstance, PROP_GEO_API_LICENSE_KEY, null);
				if(this.geoApiLicenseKey == null || String.valueOf(this.geoApiLicenseKey).equalsIgnoreCase("null")){
					throw new IOException("No valid license key specified for geoapi services");
					// exit with exception
				}
				
				if(moduleDebug) {
					logger.info("geoApiLicenseKey = " + this.geoApiLicenseKey);
				}
				
				// if license specified try to look for maxmind account id
				try
				{			
					this.maxmindAccountId = WowzaUtils.getPropertyValueInt(serverProps, appInstance, PROP_MAXMIND_ACCOUNT_ID, 0);
					if(this.maxmindAccountId == 0) throw new Exception("Invalid Maxmind account Id");
					
					if(moduleDebug){
						logger.info(MODULE_NAME + " maxmindAccountId : " + String.valueOf(maxmindAccountId));
					}				
					
					geoInfoProvider = new MaxmindWebServiceGeoInfoProvider(this.maxmindAccountId, this.geoApiLicenseKey);
				}
				catch(Exception ex)
				{
					logger.info(MODULE_NAME + " Maxmind account ID not set. Assuming webservice is for IPWHOIS");
					
					geoInfoProvider = new IPWhoIsWebServiceGeoInfoProvider(this.geoApiLicenseKey);
				}
			}
			
			if(moduleDebug){
				logger.info(MODULE_NAME + " geoInfoProvider : " + String.valueOf(geoInfoProvider));
			}
			
			// initialize geoInfoProvider
			if(geoInfoProvider != null){
				geoInfoProvider.initialize();
				geoInfoProvider.setLogger(getLogger());
			}			
			
		}
		catch(Exception e)
		{
			logger.error(MODULE_NAME + " Error reading module properties {}", e);
		}
	}
	
	
	
	/**
	 * Loads restrictions definition from restriction file 
	 */
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
					logger.info(MODULE_NAME + ".loadRestrictions => restrictions loaded successfully" +  restrictions.toString());
					
					if(restrictions.enableRestrictions) {
						logger.info(MODULE_NAME+".loadRestrictions => Restrictions are enabled");
					}
				}
			}
		}
		catch(Exception e)
		{
			restrictions = new UsageRestrictions();
			logger.error(MODULE_NAME + ".loadRestrictions => Error reading restrictions " +  e.getMessage());
		}
	}	
	
	

	
	/**
	 * onAppStart
	 * 
	 * @param appInstance
	 */
	public void onAppStart(IApplicationInstance appInstance) {		
		
		String fullname = appInstance.getApplication().getName() + "/" + appInstance.getName();
		
		this.logger = getLogger();
		logger.info(MODULE_NAME+".onAppStart: " + fullname);
		
		this.appInstance = appInstance;
		this.readProperties();
		
		if(this.restrictionsRulePath != null)
		{
			loadRestrictions();
			
			// if restrictions are enabled run timer to scan for connections
			if(this.restrictions.enableRestrictions) {
				this.timer = new Timer(MODULE_NAME + " [" + appInstance.getContextStr() + "]");
				this.timer.schedule(new StreamTimeLimiter(appInstance, restrictions, this, logger, moduleDebug), 0, 1000);
			}
		}
		else
		{
			if(moduleDebug) {
				logger.info(MODULE_NAME + " => restrictions not specified");
			}
		}
	}
	

	
	
	/**
	 * onAppStop
	 * 
	 * @param appInstance
	 */
	public void onAppStop(IApplicationInstance appInstance) {
		String fullname = appInstance.getApplication().getName() + "/" + appInstance.getName();
		logger.info(MODULE_NAME+".onAppStop: " + fullname);

		if (timer != null)
		{
			timer.cancel();
		}
		timer = null;
	}
	

	
	/**
	 * Stream create handler	 * 
	 * @param stream
	 */
	public void onStreamCreate(IMediaStream stream)
	{
		stream.addClientListener(streamListener);
	}

	
	
	
	/**
	 * Stream destroy handler
	 * @param stream
	 */
	public void onStreamDestroy(IMediaStream stream)
	{
		stream.removeClientListener(streamListener);
	}
	
	
	
	
	/**
	 * RTP session handler
	 * @param rtpSession
	 */
	public void onRTPSessionCreate(RTPSession rtpSession) {	
		
		String uri = rtpSession.getUri();
		RTPUrl url = new RTPUrl(uri);
		String streamName = url.getStreamName();				
		
		streamName = ((ApplicationInstance)appInstance).internalResolvePlayAlias(streamName, rtpSession);
		//int viewcount = getStreamViewerCounts(streamName);
		/*
		if(restrictions.enableRestrictions)
		{
			try 
			{
				this.validateApplicationBandwidthUsageRestrictions();
			} 
			catch (UsageRestrictionException e) 
			{
				if(moduleDebug) {
					logger.info(MODULE_NAME + ".onRTPSessionCreate => rejecting session as usage restrictions were violated(" + e.getMessage() + ").");
				}
				
				
				WowzaUtils.terminateSession(appInstance, rtpSession);
			}
		}		
		*/
		
		
		StreamingProtocols protocol = WowzaUtils.getClientProtocol(rtpSession);
		
		switch(protocol)
		{
		case RTSP:
			rtpSession.addActionListener(rtspListener);
			break;
			
		case WEBRTC:
			WebRTCJSONStr data = new Gson().fromJson(rtpSession.getWebRTCSession().getCommandRequest().getJSONStr(), WebRTCJSONStr.class);
			WMSProperties props = rtpSession.getProperties();
			
			// if we have properties object set new properties
			if(props != null)
			{
				synchronized(props)
				{
					if(moduleDebug) {
						logger.info(MODULE_NAME+".onPlay => setting properties `subscriber` &`protocol` => "+protocol+" on session");
					}					
				
					if(data.direction.toLowerCase().equals(WebRTCDirections.PUBLISH))
					{
						props.setProperty(KEY_PUBLISHER, true);
						props.setProperty(KEY_PUBLISH_TIME, System.currentTimeMillis());
						props.setProperty(KEY_PUBLISH_PROTOCOL, protocol);
					}
					
					if(data.direction.toLowerCase().equals(WebRTCDirections.PLAY))
					{
						props.setProperty(KEY_SUBSCRIBER, true);
						props.setProperty(KEY_SUBSCRIBE_TIME, System.currentTimeMillis());
						props.setProperty(KEY_SUBSCRIBE_PROTOCOL, protocol);
					}
				}
			}			
			break;
			
		default:
			logger.info("Unexpected protocol " + String.valueOf(protocol));
			break;
		}
	}
	
	
	
	
	
	
	/**
	 * HTTPSession handler
	 * @param httpSession
	 */
	public void onHTTPSessionCreate(IHTTPStreamerSession httpSession) {
		getLogger().info(MODULE_NAME+".onHTTPSessionCreate: " + httpSession.getSessionId());
				
		String streamName = httpSession.getStreamName();
		
		if(restrictions.enableRestrictions)
		{	
			/** Max total viewers restriction check**/
			try
			{
				validateMaxViewerRestrictions();
			}
			catch (UsageRestrictionException e) 
			{
				if(moduleDebug) {
					logger.info(MODULE_NAME + ".onHTTPSessionCreate => rejecting session on max total viewer restriction violation.(" + e.getMessage() + ").");
				}
				
				WowzaUtils.terminateSession(appInstance, httpSession);
				addSession(new StreamingSessionTarget(appInstance, httpSession));
			}
			
			
			
			/** Max viewers per stream restriction check**/
			try
			{
				String truStreamName = ((ApplicationInstance)appInstance).internalResolvePlayAlias(streamName);
				validateMaxViewerRestrictionsPerStream(truStreamName);
			}
			catch (UsageRestrictionException e) 
			{
				if(moduleDebug) {
					logger.info(MODULE_NAME + ".onHTTPSessionCreate => rejecting session on max viewer per stream restriction violation for stream "+ streamName +"(" + e.getMessage() + ").");
				}
				
				WowzaUtils.terminateSession(appInstance, httpSession);
				addSession(new StreamingSessionTarget(appInstance, httpSession));
			}
						
		}		
		
		
	}
	

	
	
	/**
	 * Connect handler
	 * 
	 * @param client
	 * @param function
	 * @param params
	 */
	public void onConnect(IClient client, RequestFunction function, AMFDataList params) {
		logger.info(MODULE_NAME+".onConnect: " + client.getClientId());
				
		if(restrictions.enableRestrictions)
		{
			if(WowzaUtils.isRTMPClient(client))
			{
				
				
				/*
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
				*/
			}
		}
	}
	


	@Override
	public void addSession(StreamingSessionTarget session) 
	{
		String sessionId = null;
		StreamingProtocols protocol = session.getProtocol();
		switch(protocol)
		{
			case RTMP:
				IClient rclient = (IClient) session.getTarget();
				sessionId = WowzaUtils.getUniqueIdentifier(rclient);
				break;
			
			case HTTP:
				IHTTPStreamerSession hclient  = (IHTTPStreamerSession) session.getTarget();
				sessionId = hclient.getSessionId();
				break;
		}
	}





	@Override
	public boolean hasSession(StreamingSessionTarget session) {
		
		String sessionId = null;
		StreamingProtocols protocol = session.getProtocol();
		switch(protocol)
		{
			case RTMP:
				IClient rclient = (IClient) session.getTarget();
				sessionId = WowzaUtils.getUniqueIdentifier(rclient);
				for(Iterator<Entry<Long, String>> iter = rtmpSessionCache.entrySet().iterator(); iter.hasNext(); ) 
				{
				    if (iter.next().getValue().equalsIgnoreCase(sessionId))
					{
				    	return true;
					}
				}
				break;
			
			case HTTP:
				IHTTPStreamerSession hclient  = (IHTTPStreamerSession) session.getTarget();
				sessionId = hclient.getSessionId();
				for(Iterator<Entry<Long, String>> iter = httpSessionCache.entrySet().iterator(); iter.hasNext(); ) 
				{
				    if (iter.next().getValue().equalsIgnoreCase(sessionId))
					{
				    	return true;
					}
				}
				break;
		}
		
		return false;
	}





	@Override
	public void clearSessions() 
	{
		// clear expired HTTP session values from sessionCache
		long httpSessionlimit = System.currentTimeMillis() - sessionCacheDuration;
		for(Iterator<Long> iter = httpSessionCache.keySet().iterator(); iter.hasNext(); ) 
		{
		    if (iter.next() <= httpSessionlimit)
			{
		    	iter.remove();
			}
			else
			{
				break;
			}
		}	
		
		
		// clear expired RTMP session values from sessionCache
		long rtmpSessionlimit = System.currentTimeMillis() - sessionCacheDuration;
		for(Iterator<Long> iter = rtmpSessionCache.keySet().iterator(); iter.hasNext(); ) 
		{
		    if (iter.next() <= rtmpSessionlimit)
			{
		    	iter.remove();
			}
			else
			{
				break;
			}
		}	
		
	}
	
}