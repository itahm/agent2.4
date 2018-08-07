package com.itahm;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.snmp4j.PDU;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;

import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.json.RollingFile;
import com.itahm.snmp.Node;
import com.itahm.snmp.RequestOID;
import com.itahm.util.TopTable;
import com.itahm.util.Util;

public class SNMPNode extends Node {
	
	private final static OID OID_TRAP = new OID(new int [] {1,3,6,1,6,3,1,1,5});
	private final static OID OID_LINKDOWN = new OID(new int [] {1,3,6,1,6,3,1,1,5,3});
	private final static OID OID_LINKUP = new OID(new int [] {1,3,6,1,6,3,1,1,5,4});
	
	public enum Rolling {
		HRPROCESSORLOAD("hrProcessorLoad"),
		IFINOCTETS("ifInOctets"),
		IFOUTOCTETS("ifOutOctets"),
		IFINERRORS("ifInErrors"),
		IFOUTERRORS("ifOutErrors"),
		HRSTORAGEUSED("hrStorageUsed"),
		RESPONSETIME("responseTime");
		
		private String database;
		
		private Rolling(String database) {
			this.database = database;
		}
		
		public String toString() {
			return this.database;
		}
	}
	
	private File nodeRoot;
	private final Map<Rolling, HashMap<String, RollingFile>> rollingMap = new HashMap<Rolling, HashMap<String, RollingFile>>();
	private final String ip;
	private final SNMPAgent agent;
	private final Critical critical;
	private JSONObject ifSpeed = new JSONObject();
	private int rollingInterval = 1; // 단위: 분 (s, ms 아님!)
	
	public SNMPNode(SNMPAgent agent, String ip, int udp, int version, String name, int level) throws IOException {
		super(agent, ip, udp);
		
		if (version == SnmpConstants.version3) {
			super.setUser(new OctetString(name), level);
		}
		else {
			super.setCommunity(new OctetString(name), version);
		}
		
		this.agent = agent;
		this.ip = ip;
		this.nodeRoot = new File(agent.nodeRoot, this.ip);
		this.nodeRoot.mkdirs();
		
		for (Rolling database : Rolling.values()) {
			rollingMap.put(database, new HashMap<String, RollingFile>());
			
			new File(nodeRoot, database.toString()).mkdir();
		}
		
		this.critical = new Critical() {
			@Override
			public void onCritical(boolean isCritical, String resource, String index, long rate, String description) {
				agent.onCritical(ip, resource, index, isCritical, rate, description);
			}};
		
		setRequestOID(super.pdu);
	}
	
	public void setCritical(JSONObject critical) {
		this.critical.set(critical);
	}
	
	public void setInterface(JSONObject ifSpeed) {
		this.ifSpeed = ifSpeed;
	}
	
	public void setRollingInterval(int interval) {
		this.rollingInterval = interval;
	}
	
	private void putData(Rolling database, String index, long value) throws IOException {
		Map<String, RollingFile> rollingMap = this.rollingMap.get(database);
		RollingFile rollingFile = rollingMap.get(index);
		
		if (rollingFile == null) {
			rollingMap.put(index, rollingFile = new RollingFile(new File(this.nodeRoot, database.toString()), index));
		}
		
		rollingFile.roll(value, this.rollingInterval);
	}
	
	private void parseResponseTime() throws IOException {
		this.putData(Rolling.RESPONSETIME, "0", super.responseTime);
		
		this.agent.onSubmitTop(this.ip, SNMPAgent.Resource.RESPONSETIME, new TopTable.Value(responseTime, -1, "0"));
	}
	
	private void parseProcessor() throws IOException {
		TopTable.Value max = null;
		long value;
		
		for(String index: super.hrProcessorEntry.keySet()) {
			value = super.hrProcessorEntry.get(index);
			
			this.putData(Rolling.HRPROCESSORLOAD, index, value);
			
			if (this.critical != null) {
				this.critical.analyze(Critical.Resource.PROCESSOR, index, 100, value);
			}
			
			if (max == null || max.getValue() < value) {
				max = new TopTable.Value(value, value, index);
			}
		}
		
		if (max != null) {
			this.agent.onSubmitTop(this.ip, SNMPAgent.Resource.PROCESSOR, max);
		}
	}
	
	private void parseStorageEntry(Set<String> set) {
		if (super.data.has("hrStorageEntry")) {
			for (Object index : super.data.getJSONObject("hrStorageEntry").keySet()) {
				set.add((String)index);
			}
		}
	}
	
	private void parseStorage() throws IOException {
		JSONObject storage;
		TopTable.Value
			max = null,
			maxRate = null;
		long value,
			capacity,
			tmpValue;
		int type;
		Set<String> entry = new HashSet<>();
		
		parseStorageEntry(entry);
		
		for(String index: super.hrStorageEntry.keySet()) {
			if (entry.contains(index)) {
				entry.remove(index);
			}
			else {
				// 추가된 index
			}
			
			storage = super.hrStorageEntry.get(index);
			
			try {
				capacity = storage.getInt("hrStorageSize");
				tmpValue = storage.getInt("hrStorageUsed");
				value = 1L* tmpValue * storage.getInt("hrStorageAllocationUnits");
				type = storage.getInt("hrStorageType");
			} catch (JSONException jsone) {
				Agent.syslog(Util.EToString(jsone));
				
				return;
			}
			
			if (capacity <= 0) {
				continue;
			}
			
			this.putData(Rolling.HRSTORAGEUSED, index, value);
			
			switch(type) {
			case 2:
				// 물리적 memory는하나뿐이므로 한번에 끝나고 
				if (this.critical != null) {
					this.critical.analyze(Critical.Resource.MEMORY, index, capacity, tmpValue);
				}
				
				this.agent.onSubmitTop(this.ip, SNMPAgent.Resource.MEMORY, new TopTable.Value(value, tmpValue *100 / capacity, index));
				this.agent.onSubmitTop(this.ip, SNMPAgent.Resource.MEMORYRATE, new TopTable.Value(value, tmpValue *100 / capacity, index));
				
				break;
			case 4:
				// 스토리지는 여러 볼륨중 가장 높은값을 submit
				if (this.critical != null) {
					this.critical.analyze(Critical.Resource.STORAGE, index, capacity, tmpValue);
				}
				
				if (max == null || max.getValue() < value) {
					max = new TopTable.Value(value, tmpValue *100L / capacity, index);
				}
				
				if (maxRate == null || maxRate.getRate() < (tmpValue *100L / capacity)) {
					maxRate = new TopTable.Value(value, tmpValue *100L / capacity, index);
				}
			}
		}
		
		for (String index : entry) { // TODO 삭제된 index
			System.out.println(index);
		}
		
		if (max != null) {
			this.agent.onSubmitTop(this.ip, SNMPAgent.Resource.STORAGE, max);
		}
		
		if (maxRate != null) {
			this.agent.onSubmitTop(this.ip, SNMPAgent.Resource.STORAGERATE, maxRate);
		}
	}
	
	private void parseInterface() throws IOException {
		JSONObject
			lastEntry = super.data.has("ifEntry")? super.data.getJSONObject("ifEntry"): null;
		
		if (lastEntry == null) {
			return;
		}
		
		JSONObject
			data, lastData;
		long 
			iValue, oValue,
			rate,
			capacity,
			duration;
		TopTable.Value
			max = null,
			maxRate = null,
			maxErr = null;
		
		for(String index: super.ifEntry.keySet()) {
			// 특정 index가 새로 생성되었다면 보관된 값이 없을수도 있음.
			if (!lastEntry.has(index)) {
				continue;
			}
			
			data = super.ifEntry.get(index);
			capacity = 0;
			
			lastData = lastEntry.getJSONObject(index);
			
			if (!data.has("ifAdminStatus") || data.getInt("ifAdminStatus") != 1
				|| !data.has("ifOperStatus") || data.getInt("ifOperStatus") != 1) {
				continue;
			}
			
			//custom speed가 있는 경우
			if (this.ifSpeed.has(index)) {
				capacity = this.ifSpeed.getLong(index);
			}
			else if (data.has("ifHighSpeed")) {
				capacity = data.getLong("ifHighSpeed");
			}
			else if (capacity == 0 && data.has("ifSpeed")) {
				capacity = data.getLong("ifSpeed");
			}
			
			if (capacity <= 0) {
				continue;
			}
			
			if (data.has("ifInErrors") && lastData.has("ifInErrors")) {
				long value = data.getInt("ifInErrors") - lastData.getInt("ifInErrors");
				
				data.put("ifInErrors", value);
				
				this.putData(Rolling.IFINERRORS, index, value);
				
				if (maxErr == null || maxErr.getValue() < value) {
					maxErr = new TopTable.Value(value, -1, index);
				}
			}
			
			if (data.has("ifOutErrors") && lastData.has("ifOutErrors")) {
				long value = data.getInt("ifOutErrors") - lastData.getInt("ifOutErrors");
				
				data.put("ifOutErrors", value);
				
				this.putData(Rolling.IFOUTERRORS, index, value);
				
				if (maxErr == null || maxErr.getValue() < value) {
					maxErr = new TopTable.Value(value, -1, index);
				}
			}
			
			if (!data.has("timestamp") || !lastData.has("timestamp")) {
				continue;
			}
				
			duration = data.getLong("timestamp") - lastData.getLong("timestamp");
			
			iValue = -1;
			
			if (data.has("ifHCInOctets") && lastData.has("ifHCInOctets")) {
				iValue = data.getLong("ifHCInOctets") - lastData.getLong("ifHCInOctets");
			}
			
			if (data.has("ifInOctets") && lastData.has("ifInOctets")) {
				iValue = Math.max(iValue, data.getLong("ifInOctets") - lastData.getLong("ifInOctets"));
			}
			
			if (iValue  > -1) {
				iValue = iValue *8000 / duration;
				
				data.put("ifInBPS", iValue);
				
				this.putData(Rolling.IFINOCTETS, index, iValue);
				
				rate = iValue*100L / capacity;
				
				if (max == null ||
					max.getValue() < iValue ||
					max.getValue() == iValue && max.getRate() < rate) {
					max = new TopTable.Value(iValue, rate, index);
				}
				
				if (maxRate == null ||
					maxRate.getRate() < rate ||
					maxRate.getRate() == rate && maxRate.getValue() < iValue) {
					maxRate = new TopTable.Value(iValue, rate, index);
				}
			}
			
			oValue = -1;
			
			if (data.has("ifHCOutOctets") && lastData.has("ifHCOutOctets")) {
				oValue = data.getLong("ifHCOutOctets") - lastData.getLong("ifHCOutOctets");
			}
			
			if (data.has("ifOutOctets") && lastData.has("ifOutOctets")) {
				oValue = Math.max(oValue, data.getLong("ifOutOctets") - lastData.getLong("ifOutOctets"));
			}
			
			if (oValue > -1) {
				oValue = oValue *8000 / duration;
				
				data.put("ifOutBPS", oValue);
				
				this.putData(Rolling.IFOUTOCTETS, index, oValue);
				
				rate = oValue*100L / capacity;
				
				if (max == null ||
					max.getValue() < oValue ||
					max.getValue() == oValue && max.getRate() < rate) {
					max = new TopTable.Value(oValue, rate, index);
				}
				
				if (maxRate == null ||
					maxRate.getRate() < rate ||
					maxRate.getRate() == rate && maxRate.getValue() < oValue) {
					maxRate = new TopTable.Value(oValue, rate, index);
				}
			}
			
			if (this.critical != null) {
				long value = Math.max(iValue, oValue);
				
				if (value > -1) {					
					this.critical.analyze(Critical.Resource.THROUGHPUT, index, capacity, value);
				}
			}
		}
		
		if (max != null) {
			this.agent.onSubmitTop(this.ip, SNMPAgent.Resource.THROUGHPUT, max);
		}
		
		if (maxRate != null) {
			this.agent.onSubmitTop(this.ip, SNMPAgent.Resource.THROUGHPUTRATE, maxRate);
		}
		
		if (maxErr != null) {
			this.agent.onSubmitTop(this.ip, SNMPAgent.Resource.THROUGHPUTERR, maxErr);
		}
	}
	
	public void parseTrap(OID trap, Variable variable) {
		if (trap.startsWith(OID_TRAP)) {
			if (trap.startsWith(OID_LINKUP)) {
				
			}
			else if (trap.startsWith(OID_LINKDOWN)) {
				
			}
		}
	}
	
	public JSONObject test() {
		return new JSONObject()
			.put("sysObjectID", super.data.has("sysObjectID")? super.data.getString("sysObjectID"): "")
			.put("hrProcessorEntry", super.hrProcessorEntry.size())
			.put("hrStorageEntry", super.hrStorageEntry.size())
			.put("ifEntry", super.ifEntry.size());
	}
	
	public long getLoad() {
		Map<String, RollingFile> map;
		long sum = 0;
		long count = 0;
		
		for (Rolling resource : this.rollingMap.keySet()) {
			map = this.rollingMap.get(resource);
			
			for (String index : map.keySet()) {
				sum += map.get(index).getLoad();
				count++;
			}
		}
		
		return count > 0? (sum / count): 0;
	}
	
	public long getResourceCount() {
		long count = 0;
		
		for (Rolling resource : this.rollingMap.keySet()) {
			count += this.rollingMap.get(resource).size();
		}
		
		return count;
	}
	
	public JSONObject getData(String database, String index, long start, long end, boolean summary) {
		try {
			RollingFile rollingFile = this.rollingMap.get(Rolling.valueOf(database.toUpperCase())).get(index);
			
			if (rollingFile == null) {
				rollingFile = new RollingFile(new File(this.nodeRoot, database), index);
			}
			
			if (rollingFile != null) {
				return rollingFile.getData(start, end, summary);
			}
		}
		catch (IllegalArgumentException | IOException e) {
			Agent.syslog(Util.EToString(e));
		}
		
		return null;
	}
	
	public static PDU setRequestOID(PDU pdu) {
		pdu.add(new VariableBinding(RequestOID.sysDescr));
		pdu.add(new VariableBinding(RequestOID.sysObjectID));
		pdu.add(new VariableBinding(RequestOID.sysName));
		pdu.add(new VariableBinding(RequestOID.sysServices));
		pdu.add(new VariableBinding(RequestOID.ifDescr));
		pdu.add(new VariableBinding(RequestOID.ifType));
		pdu.add(new VariableBinding(RequestOID.ifSpeed));
		pdu.add(new VariableBinding(RequestOID.ifPhysAddress));
		pdu.add(new VariableBinding(RequestOID.ifAdminStatus));
		pdu.add(new VariableBinding(RequestOID.ifOperStatus));
		pdu.add(new VariableBinding(RequestOID.ifName));
		pdu.add(new VariableBinding(RequestOID.ifInOctets));
		pdu.add(new VariableBinding(RequestOID.ifInErrors));
		pdu.add(new VariableBinding(RequestOID.ifOutOctets));
		pdu.add(new VariableBinding(RequestOID.ifOutErrors));
		pdu.add(new VariableBinding(RequestOID.ifHCInOctets));
		pdu.add(new VariableBinding(RequestOID.ifHCOutOctets));
		pdu.add(new VariableBinding(RequestOID.ifHighSpeed));
		pdu.add(new VariableBinding(RequestOID.ifAlias));
		pdu.add(new VariableBinding(RequestOID.hrSystemUptime));
		pdu.add(new VariableBinding(RequestOID.hrProcessorLoad));
		pdu.add(new VariableBinding(RequestOID.hrSWRunName));
		pdu.add(new VariableBinding(RequestOID.hrStorageType));
		pdu.add(new VariableBinding(RequestOID.hrStorageDescr));
		pdu.add(new VariableBinding(RequestOID.hrStorageAllocationUnits));
		pdu.add(new VariableBinding(RequestOID.hrStorageSize));
		pdu.add(new VariableBinding(RequestOID.hrStorageUsed));
		
		return pdu;
	}
	
	@Override
	protected void onResponse(boolean success) {
		if (success) {
			try {
				parseResponseTime();
				
				parseProcessor();
				
				parseStorage();
				
				parseInterface();
			} catch (IOException ioe) {
				Agent.syslog(Util.EToString(ioe));
			}
		}
		
		this.agent.onResponse(this.ip, success);
		
		this.agent.onSubmitTop(this.ip, SNMPAgent.Resource.FAILURERATE, new TopTable.Value(this.getFailureRate(), this.getFailureRate(), "-1"));
	}

	@Override
	public void onException(Exception e) {
		if (e != null) {
			Agent.syslog(Util.EToString(e));
		}
		
		this.agent.onException(this.ip);
	}

	@Override
	protected void onTimeout(boolean timeout) {
		this.agent.onTimeout(this.ip, timeout);
	}
	
}