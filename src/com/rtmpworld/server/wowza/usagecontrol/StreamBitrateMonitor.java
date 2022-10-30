package com.rtmpworld.server.wowza.usagecontrol;

import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import com.rtmpworld.server.wowza.ModuleSimpleUsageControl;
import com.rtmpworld.server.wowza.utils.WowzaUtils;
import com.wowza.util.IOPerformanceCounter;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerIDs;
import com.wowza.wms.stream.IMediaStream;



/**
 * Class to monitors IMediaStream instance
 * for bitrate and terminate session if bitrate 
 * exceeds max allowed bitrate.
 */
public class StreamBitrateMonitor {
	

	int monitorInterval = 5000;
	Timer mTimer;
	TimerTask mTask;
	IMediaStream target;
	double maxBitrate = 0;
	
	ModuleSimpleUsageControl module;
	
	private WMSLogger logger;
	private boolean debug;
	IApplicationInstance appInstance;
	
	public StreamBitrateMonitor(IMediaStream stream, double maxBitrate, IApplicationInstance appInstance, WMSLogger logger, boolean debug)
	{
		this.appInstance = appInstance;
		this.debug = debug;
		this.logger = logger;
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
					
				if (debug) {
					logger.info(ModuleSimpleUsageControl.MODULE_NAME + ".MonitorStream.run '" + target.getName() + "' BitRate: " + Math.round(Math.floor(bitrate)) + "kbs, MaxBitrate:" + maxBitrate, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
					logger.info(ModuleSimpleUsageControl.MODULE_NAME + ".MonitorStream.run getPublishBitrateVideo = '" + target.getPublishBitrateVideo(), WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
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
