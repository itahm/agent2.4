package com.itahm.command;

import java.io.IOException;

import com.itahm.json.JSONObject;

import com.itahm.http.Response;

abstract public class Command {
	abstract public void execute(JSONObject request, Response response) throws IOException;
	
	public static Command valueOf(String command) {
		switch(command.toUpperCase()) {
		case "PULL":
			return new Pull();
			
		case "PUSH":
			return new Push();
			
		case "PUT":
			return new Put();
			
		case "QUERY":
			return new Query();
			
		case "SELECT":
			return new Select();
			
		case "CONFIG":
			return new Config();
			
		case "EXTRA":
			return new Extra();
		}
		/*try {
		switch(command.toUpperCase()) {
			case "PULL":
				return (Command)Class.forName("com.itahm.command.Pull").newInstance();
				
			case "PUSH":
				return (Command)Class.forName("com.itahm.command.Push").newInstance();
				
			case "PUT":
				return (Command)Class.forName("com.itahm.command.Put").newInstance();
				
			case "QUERY":
				return (Command)Class.forName("com.itahm.command.Query").newInstance();
				
			case "SELECT":
				return (Command)Class.forName("com.itahm.command.Select").newInstance();
				
			case "LISTEN":
				return (Command)Class.forName("com.itahm.command.Listen").newInstance();
					
			case "CONFIG":
				return (Command)Class.forName("com.itahm.command.Config").newInstance();
				
			case "EXTRA":
				return (Command)Class.forName("com.itahm.command.Extra").newInstance();
			}
		} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
		}*/
		
		return null;
	}
	
}
