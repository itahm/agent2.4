package com.itahm.util;

import java.io.File;

abstract public class DataCleaner implements Runnable{

	private long minDateMills;
	private File dataRoot;
	private int depth;
	private Thread thread;
	
	public DataCleaner(File dataRoot, long minDateMills) {
		this(dataRoot, minDateMills, 0);
	}
	
	public DataCleaner(File dataRoot, long minDateMills, int depth) {
		this.dataRoot = dataRoot;
		this.depth = depth;
		
		this.minDateMills = minDateMills;
		
		this.thread = new Thread(this);
			
		this.thread.setDaemon(true);
		this.thread.start();
	}

	private long emptyLastData(File directory, int depth) {
		File [] files = directory.listFiles();
		long count = 0;
		
		for (File file: files) {
			if (this.thread.isInterrupted()) {
				break;
			}
			
			if (file.isDirectory()) {
				if (depth > 0) {
					count += emptyLastData(file, depth -1);
				}
				else {
					try {
						if (this.minDateMills > Long.parseLong(file.getName())) {
							if (deleteDirectory(file)) {
								count++;
								
								onDelete(file);
							}
						}
					}
					catch (NumberFormatException nfe) {
					}
				}
			}
		}
		
		return count;
	}
	
	public static boolean deleteDirectory(File directory) {
        if(!directory.exists() || !directory.isDirectory()) {
            return false;
        }
        
        File[] files = directory.listFiles();
        
        for (File file : files) {
            if (file.isDirectory()) {
                deleteDirectory(file);
            } else {
                file.delete();
            }
        }
         
        return directory.delete();
    }
	
	abstract public void onDelete(File file);
	abstract public void onComplete(long count);
	
	@Override
	public void run() {
		long count = -1;
		
		if (this.dataRoot.isDirectory()) {
			count = emptyLastData(this.dataRoot, this.depth);
		}
		
		onComplete(count);
	}
	
	public void cancel() {
		if (this.thread != null) {
			this.thread.interrupt();
		}
	}
	
}
