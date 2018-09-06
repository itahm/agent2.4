package com.itahm;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

import com.itahm.json.JSONObject;
import com.itahm.util.Util;

public class Log {

	public enum Type {
		SYSTEM, SHUTDOWN, CRITICAL;
	};

	public final static String SHUTDOWN = "shutdown";
	public final static String CRITICAL = "critical";
	
	private LogFile dailyFile;
	private SysLogFile sysLog;
	
	public Log(File root) throws IOException {
		File logRoot = new File(root, "log");
		File systemRoot = new File(logRoot, "system");
		
		logRoot.mkdir();
		systemRoot.mkdir();
		
		dailyFile = new LogFile(logRoot);
		sysLog = new SysLogFile(systemRoot);
	}
	
	public String getSysLog(long mills) throws IOException {
		byte [] sysLog = this.sysLog.read(mills);
		
		if (sysLog == null) {
			sysLog = new byte [0];
		}
		
		return new String(sysLog, StandardCharsets.UTF_8.name());
	}
	
	public void write(JSONObject log) {
		try {
			this.dailyFile.write(log.put("date", Calendar.getInstance().getTimeInMillis()));
		} catch (IOException ioe) {
			sysLog(Util.EToString(ioe));
		}
	}
	
	public void sysLog(String log) {
		try {
			this.sysLog.write((log + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public String read(long mills) throws IOException {
		byte [] bytes = this.dailyFile.read(mills);
		
		if (bytes != null) {
			return new String(bytes, StandardCharsets.UTF_8.name());
		}
		
		return new JSONObject().toString();
	}
	
	public String read(long start, long end) throws IOException {
		JSONObject jsono = new JSONObject();
		Calendar c = Calendar.getInstance();
		
		c.setTimeInMillis(start);
		
		for (; start <= end; c.set(Calendar.DATE, c.get(Calendar.DATE) +1), start = c.getTimeInMillis()) {
			byte [] bytes = this.dailyFile.read(start);
		
			if (bytes == null) {
				continue;
			}
			
			jsono.put(Long.toString(start), new JSONObject(new String(bytes, StandardCharsets.UTF_8.name())));
		}
		
		return jsono.toString();
	}
	
}
