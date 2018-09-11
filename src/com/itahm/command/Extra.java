package com.itahm.command;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Iterator;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.Agent;
import com.itahm.http.Response;
import com.itahm.util.Network;

public class Extra extends Command {
	
	private static final int DEF_TOP_CNT = 10;
	
	@Override
	public void execute(JSONObject request, Response response) throws IOException {
		
		try {
			switch(request.getString("extra")) {
			case "reset":
				Agent.resetResponse(request.getString("ip"));
				
				break;
			case "failure":
				JSONObject data = Agent.getFailureRate(request.getString("ip"));
				
				if (data == null) {
					response.setStatus(Response.Status.BADREQUEST);
				}
				else {
					response.write(data.toString());
				}
				
				break;
				
			case "search":
				Network network = new Network(InetAddress.getByName(request.getString("network")).getAddress(), request.getInt("mask"));
				Iterator<String> it = network.iterator();
				
				while(it.hasNext()) {
					Agent.testSNMPNode(it.next(), null);
				}
				
				break;
				
			case "top":
				response.write(Agent.getTop(request.has("count")? request.getInt("count"): DEF_TOP_CNT).toString());
				
				break;
				
			case "log":
				response.write(Agent.getLog(request.getLong("date")));
				
				break;
				
			case "syslog":
				response.write(new JSONObject().put("log", Agent.getSyslog(request.getLong("date"))).toString());
				
				break;
				
			case "report":
				response.write(Agent.report(request.getLong("start"), request.getLong("end")));
			
				break;
				
			case "backup":
				response.write(Agent.backup().toString());
				
				break;
				
			case "restore":
				Agent.restore(request.getJSONObject("backup"));
				
				break;
				
			case "test":
				response.write(Agent.snmpTest().toString());
				
				break;
				
			case "critical":
				Agent.setCritical(request.has("target")? request.getString("target"): null,
					request.has("resource")? request.getString("resource"): null,
					request.getInt("rate"),
					request.getBoolean("overwrite"));
				
				break;
				
			default:
				throw new JSONException("Extra not found.");
			}
		}
		catch (JSONException jsone) {
			response.setStatus(Response.Status.BADREQUEST);jsone.printStackTrace();
		}
		catch (Exception e) {
			response.setStatus(Response.Status.SERVERERROR);
		}
	}

}
