package com.rtmpworld.server.wowza;

import com.wowza.wms.application.*;

import java.io.File;
import java.io.FileReader;
import java.net.URL;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import com.rtmpworld.server.wowza.decorators.StreamingSessionTarget;
import com.rtmpworld.server.wowza.usagecontrol.UsageRestrictions;
import com.rtmpworld.server.wowza.usagecontrol.exceptions.UsageRestrictionException;
import com.rtmpworld.server.wowza.utils.WowzaUtils;
import com.wowza.util.IOPerformanceCounter;
import com.wowza.wms.amf.*;
import com.wowza.wms.client.*;
import com.wowza.wms.module.*;
import com.wowza.wms.request.*;
import com.wowza.wms.stream.*;
import com.wowza.wms.stream.mediacaster.MediaStreamMediaCasterUtils;
import com.wowza.wms.util.ModuleUtils;
import com.wowza.wms.rtp.model.*;
import com.wowza.wms.server.Server;
import com.wowza.wms.httpstreamer.model.*;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.logging.WMSLoggerIDs;

public class ModuleSimpleUsageControl extends ModuleBase {
	
	private IApplicationInstance appInstance;
	private UsageRestrictions restrictions;	
	private GeoInfoProvider geoInfoProvider;
	private StreamListener streamListener = new StreamListener();
	
	
	// module name and property name prefix
	private static String PROP_NAME_PREFIX = "usagecontrol";
	private static String MODULE_NAME = "ModuleSimpleUsageControl";
	
	// for logging
	private static String PROP_DEBUG = PROP_NAME_PREFIX + "Debug";
	private static String PROP_RESTRICTIONS_RULE_PATH = PROP_NAME_PREFIX + "RestrictionsRulePath";
	private static String PROP_GEOINFO_ENDPOINT = PROP_NAME_PREFIX + "GeoInfoEndpoint";
	private static String PROP_GEOINFO_ASYNC_FETCH = PROP_NAME_PREFIX + "GeoInfoAsyncFetch";
	private static String KEY_PUBLISHER = "PUBLISHER";
	private static String KEY_PUBLISH_TIME = "PUBLISHTIME";
	private static String KEY_PUBLISH_PROTOCOL = "PUBLISHPROTOCOL";
	private static String KEY_SUBSCRIBER = "KEY_SUBSCRIBER";
	private static String KEY_SUBSCRIBE_TIME = "SUBSCRIBETIME";
	private static String KEY_SUBSCRIBE_PROTOCOL = "SUBSCRIBEPROTOCOL";
	
	
	// for threading
	private static String PROP_THREADPOOL_SIZE = PROP_NAME_PREFIX + "ThreadPoolSize";
	private static String PROP_DELAY_FOR_FAILED_REQUESTS = PROP_NAME_PREFIX + "DelayForFailedRequests";
	private static String PROP_HTTP_MAX_FAIL_RETRIES = PROP_NAME_PREFIX + "HTTPMaxRetries";
	private static String PROP_THREADPOOL_TERMINATION_TIMEOUT = PROP_NAME_PREFIX + "ThreadPoolTerminationTimeout";
	
	
	private static ThreadPoolExecutor httpRequestThreadPool;
	private static int threadPoolSize;
	private static int threadIdleTimeout;	
	private static int threadPoolAwaitTerminationTimeout;
	private static int httpFailureRetries = 5;
	
	
	private String restrictionsRulePath;	
	private String geoInfoEndpoint;	
	private boolean asyncGeoInfoFetch = false;
	private boolean moduleDebug;
	private boolean logViewerCounts = false;
	private static boolean serverDebug = false;
	private Timer timer = null;

	
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
		threadIdleTimeout = serverProps.getPropertyInt(PROP_DELAY_FOR_FAILED_REQUESTS, 60);
		threadPoolAwaitTerminationTimeout = serverProps.getPropertyInt(PROP_THREADPOOL_TERMINATION_TIMEOUT, 5);
		httpFailureRetries = serverProps.getPropertyInt(PROP_HTTP_MAX_FAIL_RETRIES, httpFailureRetries);
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

			if (logViewerCounts)
				logger.info(MODULE_NAME + ".getViewerCounts streamName: " + streamName + " total:" + count + " rtmp: " + rtmpCount + " http: " + httpCount + " rtp: " + rtpCount, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);

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
	 * GeoInfoProvider class to help lookup country info from remote url
	 * using client IP address.
	 *
	 */
	private class GeoInfoProvider
	{
		private String apiEndPoint;
		
		public GeoInfoProvider(String apiEndPoint)
		{
			this.apiEndPoint = apiEndPoint;
		}
				
		
		private CompletableFuture<CountryInfo> performIPLookUp(String ip)
		{
			return CompletableFuture.supplyAsync(()->{
				CloseableHttpClient httpClient = HttpClients.createDefault();
				CountryInfo info = null;
				
				try
				{
					String endpoint = apiEndPoint.replace("IPHERE", ip);
					if(moduleDebug){
	                	getLogger().info(MODULE_NAME + ".performIPLookUp => endpoint : " + endpoint);
	                }
					
				    HttpGet httpget = new HttpGet(endpoint);
				    CloseableHttpResponse response = httpClient.execute(httpget);
				    
				    HttpEntity entity = response.getEntity();
		            if (entity != null) {
		            	Gson gson = new Gson();
		                String result = EntityUtils.toString(entity);
		                if(moduleDebug){
		                	getLogger().info(MODULE_NAME + ".performIPLookUp => Response : " + result);
		                }
		                
		                if(!result.contains("country")) {
		                	info = new CountryInfo("Unknown", "NOP");
		                }else {
		                	info = gson.fromJson(result, CountryInfo.class);
		                }
		            }	            
		            httpClient.close();
				}
				catch(Exception e)
				{
					getLogger().error(MODULE_NAME + ".performIPLookUp for => " + ip + ".Cause : " + e.getMessage());
				}
			    
			    return info;		
				
			}, httpRequestThreadPool);
		}
		
		
		public CountryInfo getCountryInfoSync(CompletableFuture<CountryInfo> future)
		{
			CountryInfo result;
			
			try 
			{
				result = future.get(5000, TimeUnit.MILLISECONDS);
			} 
			catch (InterruptedException | ExecutionException | TimeoutException e) 
			{
				result = null;
				getLogger().error("Error getting country info." + e.getMessage());
				
			}
			
			return result;
		}
	}
	
	
	
	
	
	
	/**
	 * Class to monitors IMediaStream instance
	 * for bitrate and terminate session if bitrate 
	 * exceeds max allowed bitrate.
	 */
	private class MonitorStream
	{
		int monitorInterval = 5000;
		Timer mTimer;
		TimerTask mTask;
		IMediaStream target;
		double maxBitrate = 0;
		
		public MonitorStream(IMediaStream stream, double maxBitrate)
		{
			this.maxBitrate = maxBitrate;
			this.target = stream;
			this.init();
			
		}
		
		private void init()
		{
			this.mTask = new TimerTask() {

				@Override
				public void run() {
					
					if (target == null)
						stop();
	
					IOPerformanceCounter perf = target.getMediaIOPerformance();
					Double bitrate = perf.getMessagesInBytesRate() * 8 * .001;
						
					if (moduleDebug) {
						logger.info(MODULE_NAME + ".MonitorStream.run '" + target.getName() + "' BitRate: " + Math.round(Math.floor(bitrate)) + "kbs, MaxBitrate:" + maxBitrate, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
						logger.info(MODULE_NAME + ".MonitorStream.run getPublishBitrateVideo = '" + target.getPublishBitrateVideo(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
					}
	
					if (bitrate > maxBitrate && maxBitrate > 0)
					{	
						WowzaUtils.terminateSession(appInstance, target);
					}
				}
			};
		}
		
		public void start()
		{
			if (mTimer == null)
				mTimer = new Timer();
			mTimer.scheduleAtFixedRate(mTask, new Date(), monitorInterval);
		}
	
		public void stop()
		{
			if (mTimer != null)
			{
				mTimer.cancel();
				mTimer = null;
			}
		}
	}
	
	
	
	
	/**
	 * GeoRestriction class to be used for encapsulating GeoInfo data
	 * for use inside Future scopes.
	 */
	private class GeoRestriction{
		
		private CountryInfo info;
		private boolean checkByAllowed = false;
		private boolean checkByRestricted = false;
		

		public CountryInfo getInfo() {
			return info;
		}

		public void setInfo(CountryInfo info) {
			this.info = info;
		}

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
		int publisherCount = getPublisherCount();
		if((restrictions.ingest.maxPublishersCount>0) && (publisherCount >= restrictions.ingest.maxPublishersCount))
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
	private void validateMaxViewerRestrictions(String streamName) throws UsageRestrictionException
	{
		int viewerCount = getStreamViewerCounts(streamName);
		if((restrictions.egress.maxSubscribersPerStream>0) && (viewerCount >= restrictions.egress.maxSubscribersPerStream))
		{
			throw new UsageRestrictionException("Max viewer restriction reached!!");
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
		CountryInfo info;
		final GeoRestriction georestriction = new GeoRestriction();
		final String ip = target.getIPAddress();
		
		if(ip.equalsIgnoreCase("127.0.0.1") || ip.equalsIgnoreCase("localhost")) {
			return;
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
			CompletableFuture<CountryInfo> future = geoInfoProvider.performIPLookUp(ip);
			if(!this.asyncGeoInfoFetch)
			{
				if (moduleDebug)
					logger.info(MODULE_NAME + ".validateGeoRestrictions => sync fetch");

				
				info = this.geoInfoProvider.getCountryInfoSync(future);
				String cc = (info.country_code != null)?info.country_code:info.countryCode;
				
				if (moduleDebug)
					logger.info(MODULE_NAME + ".validateGeoRestrictions => cc = " + cc);
				
				
				if(georestriction.isCheckByAllowed())
				{
					if(!allowedFrom.contains(cc.toUpperCase()))
					{
						if (moduleDebug)
							logger.info(MODULE_NAME + ".validateGeoRestrictions => country code not in list of allowed");
						
						
						throw new UsageRestrictionException("Disallowed country location!!");
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
						
						
						throw new UsageRestrictionException("Disallowed country location!!");
					}
					else
					{
						if (moduleDebug)
							logger.info(MODULE_NAME + ".validateGeoRestrictions => Country allowed");
						
					}
				}
				
			}
			else
			{
				if (moduleDebug)
					logger.info(MODULE_NAME + ".validateGeoRestrictions => async fetch");
				
				future.thenAccept(value -> {
					String cc = (value.country_code != null)?value.country_code:value.countryCode;
					
					if(georestriction.isCheckByAllowed())
					{
						if(!allowedFrom.contains(cc.toUpperCase()))
						{
							if (moduleDebug)
								logger.info(MODULE_NAME + ".validateGeoRestrictions => country code not in list of allowed");
							
							
							target.terminateSession();							
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
	}
	
	
	
	/**
	 * Class Disconnecter implements times disconnect 
	 * for connected sessions
	 *
	 */
	private class Disconnecter extends TimerTask
	{

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
			
			
			
			
			if(restrictions.enableRestrictions)
			{
				
				/** Max viewers restriction check**/
				try
				{
					String truStreamName = ((ApplicationInstance)appInstance).internalResolvePlayAlias(streamName);
					validateMaxViewerRestrictions(truStreamName);
				}
				catch (UsageRestrictionException e) 
				{
					if(moduleDebug) {
						logger.info(MODULE_NAME + ".onPlay => rejecting session on max viewer restriction violation for stream "+ streamName +"(" + e.getMessage() + ").");
					}
					
					WowzaUtils.terminateSession(appInstance, stream);
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
				MonitorStream monitor = new MonitorStream(stream, restrictions.ingest.maxPublishBitrate);
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
				MonitorStream monitor;
	
				synchronized(props)
				{
					monitor = (MonitorStream)props.get("monitor");
				}
				if (monitor != null)
					monitor.stop();
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
			
			String geoAPIEndPoint = getPropertyValueStr(PROP_GEOINFO_ENDPOINT, null);
			URL u = new URL(geoAPIEndPoint);
			u.toURI(); 
			
			this.geoInfoEndpoint = geoAPIEndPoint;
			if(moduleDebug){
				logger.info(MODULE_NAME + " geoInfoEndpoint : " + String.valueOf(geoInfoEndpoint));
			}	
			
			this.asyncGeoInfoFetch = getPropertyValueBoolean(PROP_GEOINFO_ASYNC_FETCH, false);
			if(moduleDebug){
				logger.info(MODULE_NAME + " asyncGeoInfoFetch : " + String.valueOf(asyncGeoInfoFetch));
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
		
		if(this.geoInfoEndpoint != null)
		{
			geoInfoProvider = new GeoInfoProvider(geoInfoEndpoint);
		}
		
		
		/**
		Timer timer = new Timer();
		timer.schedule(new TimerTask() {
		  @Override
		  public void run() {
		    //what you want to do
			  logger.info("publishers :" + String.valueOf(getPublisherCount()) + " : " + String.valueOf(getPublisherCountUsingProperties()));
		  }
		}, 0, 5000);
		**/
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
		
		String uri = rtpSession.getUri();
		RTPUrl url = new RTPUrl(uri);
		String streamName = url.getStreamName();
		
		streamName = ((ApplicationInstance)appInstance).internalResolvePlayAlias(streamName, rtpSession);
		int viewcount = getStreamViewerCounts(streamName);
		
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

	}
	
	
	public void onHTTPSessionCreate(IHTTPStreamerSession httpSession) {
		getLogger().info(MODULE_NAME+".onHTTPSessionCreate: " + httpSession.getSessionId());
		
		String streamName = httpSession.getStreamName();
		int count = getStreamViewerCounts(streamName);
		
		if(restrictions.enableRestrictions)
		{
			try 
			{
				this.validateApplicationBandwidthUsageRestrictions();
			} 
			catch (UsageRestrictionException e) 
			{
				if(moduleDebug) {
					logger.info(MODULE_NAME + ".onHTTPSessionCreate => rejecting session as usage restrictions were violated(" + e.getMessage() + ").");
				}
				
				WowzaUtils.terminateSession(appInstance, httpSession);
			}
		}
	}
	

	public void onConnect(IClient client, RequestFunction function, AMFDataList params) {
		logger.info(MODULE_NAME+".onConnect: " + client.getClientId());
		
		if(restrictions.enableRestrictions)
		{
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