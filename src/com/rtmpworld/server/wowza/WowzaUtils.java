package com.rtmpworld.server.wowza;

import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.client.IClient;
import com.wowza.wms.httpstreamer.model.IHTTPStreamerSession;
import com.wowza.wms.rtp.model.RTPSession;
import com.wowza.wms.rtp.model.RTPStream;
import com.wowza.wms.stream.IMediaStream;

public class WowzaUtils {
	
	
	public static void terminateSession(IApplicationInstance instance, IClient client)
	{
		client.rejectConnection();
		client.setShutdownClient(true);
	}
	
	
	public static void terminateSession(IApplicationInstance instance, RTPSession session)
	{
		instance.getVHost().getRTPContext().shutdownRTPSession(session);
	}
	
	
	public static void terminateSession(IApplicationInstance instance, IHTTPStreamerSession session)
	{
		session.rejectSession();
		session.shutdown();
	}
	
	
	public static StreamingProtocols getClientProtocol(IMediaStream stream)
	{
		IClient client = stream.getClient();
		
		if(client != null)
		{
			if(client.getUri().contains("rtmp") || (client.getFlashVer() != null && client.getFlashVer().length() > 0) || (client.getProtocol() == 1 || client.getProtocol() == 3))
			{
				return StreamingProtocols.RTMP;
			}
			else
			{
				return StreamingProtocols.UNKNOWN;
			}
		}
		
		RTPStream rtpStream = stream.getRTPStream();
		if (rtpStream != null)
		{				
			RTPSession rtpSession = rtpStream.getSession();
			if (rtpSession != null)
			{
				if(rtpSession.isWebRTC())
				{
					return StreamingProtocols.WEBRTC;
				}
				else
				{
					return StreamingProtocols.RTSP;
				}
			}
		}
		
		IHTTPStreamerSession httpStreamerSession = stream.getHTTPStreamerSession();
		if (httpStreamerSession != null)
		{
			return StreamingProtocols.HTTP;
		}
		
		return StreamingProtocols.UNKNOWN;
	}
	
	
	
	public static StreamingProtocols getClientProtocol(RTPSession rtpSession)
	{
		if (rtpSession != null)
		{
			if(rtpSession.isWebRTC())
			{
				return StreamingProtocols.WEBRTC;
			}
			else
			{
				return StreamingProtocols.RTSP;
			}
		}
		
		return StreamingProtocols.UNKNOWN;
	}
	
	
	

	public static boolean isRTMPClient(IClient client)
	{
		if(client != null)
		{
			if((client.getFlashVer() != null && client.getFlashVer().length() > 0) || (client.getProtocol() == 1 || client.getProtocol() == 3))
			{
				return true;
			}
		}
		
		return false;
	}
	
	
	public static boolean isRTPStream(IMediaStream stream)
	{
		RTPStream rtpStream = stream.getRTPStream();
		if (rtpStream != null)
		{				
			RTPSession rtpSession = rtpStream.getSession();
			if (rtpSession != null)
			{
				return true;
			}
		}
		
		return false;
	}
	
	
	
	public static boolean supportsDigestAuthentication(IClient client)
	{
		if(client.getQueryStr() == "" || client.getQueryStr().length()<1)
		{
			if(client.getFlashVer().contains("FMLE") || client.getFlashVer().contains("FMS") || client.getFlashVer().contains("compatible"))
			{
				return true;
			}
		}
				
		return false;
	}
}
