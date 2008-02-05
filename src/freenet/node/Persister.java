package freenet.node;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.SimpleFieldSet;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

class Persister implements Runnable {

	Persister(Persistable t, File persistTemp, File persistTarget, PacketSender ps) {
		this.persistable = t;
		this.persistTemp = persistTemp;
		this.persistTarget = persistTarget;
		this.ps = ps;
	}
	
	// Subclass must set the others later
	protected Persister(Persistable t, PacketSender ps) {
		this.persistable = t;
		this.ps = ps;
	}
	
	final Persistable persistable;
	private final PacketSender ps;
	File persistTemp;
	File persistTarget;
	private boolean started;
	
	void interrupt() {
		synchronized(this) {
			notifyAll();
		}
	}

	public void run() {
		freenet.support.Logger.OSThread.logPID(this);
		try {
			persistThrottle();
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
			System.err.println("Will restart ThrottlePersister...");
		} catch (Throwable t) {
			Logger.error(this, "Caught in ThrottlePersister: "+t, t);
			System.err.println("Caught in ThrottlePersister: "+t);
			t.printStackTrace();
			System.err.println("Will restart ThrottlePersister...");
		}
		ps.queueTimedJob(this, 60*1000);
	}
	
	private void persistThrottle() {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(logMINOR) Logger.minor(this, "Trying to persist throttles...");
		SimpleFieldSet fs = persistable.persistThrottlesToFieldSet();
		try {
			FileOutputStream fos = new FileOutputStream(persistTemp);
			try {
				fs.writeTo(fos);
			} catch (IOException e) {
				persistTemp.delete();
			} finally {
				Closer.close(fos);
			}
			FileUtil.renameTo(persistTemp, persistTarget);
		} catch (FileNotFoundException e) {
			Logger.error(this, "Could not store throttle data to disk: "+e, e);
			return;
                }
	}

	public SimpleFieldSet read() {
		SimpleFieldSet throttleFS = null;
		try {
			throttleFS = SimpleFieldSet.readFrom(persistTarget, false, true);
		} catch (IOException e) {
			try {
				throttleFS = SimpleFieldSet.readFrom(persistTemp, false, true);
			} catch (FileNotFoundException e1) {
				// Ignore
			} catch (IOException e1) {
				Logger.error(this, "Could not read "+persistTarget+" ("+e+") and could not read "+persistTemp+" either ("+e1+ ')');
			}
		}
		return throttleFS;
	}

	public void start() {
		synchronized(this) {
			if(started) {
				Logger.error(this, "Already started: "+this, new Exception("debug"));
				return;
			}
			started = true;
		}
		run();
	}

}
