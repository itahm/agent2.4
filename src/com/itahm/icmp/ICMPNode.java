package com.itahm.icmp;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ICMPNode implements Runnable, Closeable {

	private final ICMPListener listener;
	private final InetAddress target;
	private final Thread thread;
	private final BlockingQueue<Long> bq = new LinkedBlockingQueue<>();
	
	public final String ip;
	
	public ICMPNode(ICMPListener listener, String ip) throws UnknownHostException {
		this.listener = listener;
		this.ip = ip;
		
		target = InetAddress.getByName(ip);
		
		thread = new Thread(this);
		
		thread.setName("ITAhM ICMPNode "+ ip);
		
		thread.start();
	}
	
	@Override
	public void run() {
		long delay, sent;
		int timeout, retry;
		
		init: while (!this.thread.isInterrupted()) {
			try {
				try {
					delay = this.bq.take();
					
					if (delay > 0) {
						Thread.sleep(delay);
					}
					else if (delay < 0) {
						throw new InterruptedException();
					}
					
					sent = System.currentTimeMillis();
					timeout = this.listener.getTimeout();
					retry = this.listener.getRetry();
					
					for (int i=0; i<retry; i++) {
						if (this.thread.isInterrupted()) {
							throw new InterruptedException();
						}
						
						if (this.target.isReachable(timeout)) {
							this.listener.onSuccess(this, System.currentTimeMillis() - sent);
							
							continue init;
						}
					}
					
				} catch (IOException e) {}
				
				this.listener.onFailure(this);
				
			} catch (InterruptedException e) {
				
				break;
			}
		}
	}
	
	public void ping(long delay) {
		try {
			this.bq.put(delay);
		} catch (InterruptedException e) {
		}
	}

	public void _close(boolean gracefully) throws IOException {
		close();
		
		if (gracefully) {
			try {
				this.thread.join();
			} catch (InterruptedException e) {}
		}
	}
	
	@Override
	public void close() throws IOException {
		this.thread.interrupt();
		
		try {
			this.bq.put(-1L);
		} catch (InterruptedException ie) {}
	}
	
}
