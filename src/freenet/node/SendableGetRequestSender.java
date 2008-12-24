package freenet.node;

import freenet.client.async.ChosenBlock;
import freenet.client.async.ClientContext;
import freenet.keys.ClientKey;
import freenet.support.Logger;

public class SendableGetRequestSender implements SendableRequestSender {

	/** Do the request, blocking. Called by RequestStarter. 
	 * Also responsible for deleting it.
	 * @return True if a request was executed. False if caller should try to find another request, and remove
	 * this one from the queue. */
	public boolean send(NodeClientCore core, final RequestScheduler sched, ClientContext context, ChosenBlock req) {
		Object keyNum = req.token;
		ClientKey key = req.ckey;
		if(key == null) {
			Logger.error(SendableGet.class, "Key is null in send(): keyNum = "+keyNum+" for "+req);
			return false;
		}
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		if(Logger.shouldLog(Logger.MINOR, SendableGet.class))
			Logger.minor(SendableGet.class, "Sending get for key "+keyNum+" : "+key);
		if(req.isCancelled()) {
			if(logMINOR) Logger.minor(SendableGet.class, "Cancelled: "+req);
			req.onFailure(new LowLevelGetException(LowLevelGetException.CANCELLED), context);
			return false;
		}
		try {
			try {
				core.realGetKey(key, req.localRequestOnly, req.cacheLocalRequests, req.ignoreStore);
			} catch (final LowLevelGetException e) {
				req.onFailure(e, context);
				return true;
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
				req.onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), context);
				return true;
			}
			req.onFetchSuccess(context);
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
			req.onFailure(new LowLevelGetException(LowLevelGetException.INTERNAL_ERROR), context);
			return true;
		}
		return true;
	}

}