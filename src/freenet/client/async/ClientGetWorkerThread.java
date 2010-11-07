/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package freenet.client.async;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.net.URI;
import java.net.URISyntaxException;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.filter.ContentFilter;
import freenet.client.filter.FoundURICallback;
import freenet.client.filter.TagReplacerCallback;
import freenet.client.filter.ContentFilter.FilterStatus;
import freenet.crypt.HashResult;
import freenet.crypt.MultiHashInputStream;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**A thread which does postprocessing of decompressed data, in particular,
 * writing it to its final destination. This thread also handles hashing and
 * filtering. If these are not required, <code>null</code> may be passed through
 * the relevant constructor arguments.*/
public class ClientGetWorkerThread extends Thread {

	private InputStream input;
	final private URI uri;
	final private HashResult[] hashes;
	final private boolean filterData;
	final private String charset;
	final private FoundURICallback prefetchHook;
	final private TagReplacerCallback tagReplacer;
	final private String mimeType;
	private OutputStream output;
	private boolean finished = false;
	private Throwable error = null;
	private ClientMetadata clientMetadata = null;

	private static volatile boolean logMINOR;

	static {
		Logger.registerClass(ClientGetWorkerThread.class);
	}


	 /**
	 * @param input The stream to read the data from
	 * @param output The final destination to which the data will be written
	 * @param uri The URI of the fetched data. Needed for the ContentFilter. Optional.
	 * @param mimeType MIME of the fetched data. The best guess is needed for the
	 * ContentFilter. Optional.
	 * @param hashes Hashes of the fetched data, to be compared against. Optional.
	 * @param filterData If true, the ContentFilter will be invoked
	 * @param charset Charset to be passed to the ContentFilter.
	 * Only needed if filterData is true.
	 * @param prefetchHook Only needed if filterData is true.
	 * @param tagReplacer Used for web-pushing. Only needed if filterData is true.
	 * @throws URISyntaxException 
	 */
	public ClientGetWorkerThread(PipedInputStream input, OutputStream output, FreenetURI uri,
			String mimeType, HashResult[] hashes, boolean filterData, String charset,
			FoundURICallback prefetchHook, TagReplacerCallback tagReplacer) throws URISyntaxException {
		super("ClientGetWorkerThread");
		this.input = input;
		if(uri != null) this.uri = uri.toURI("/");
		else this.uri = null;
		if(mimeType != null && mimeType.compareTo("application/xhtml+xml") == 0) mimeType = "text/html";
		this.mimeType = mimeType;
		this.hashes = hashes;
		this.output = output;
		this.filterData = filterData;
		this.charset = charset;
		this.prefetchHook = prefetchHook;
		this.tagReplacer = tagReplacer;
	}

	@Override
	public void run() {
		try {
			//Validate the hash of the now decompressed data
			input = new BufferedInputStream(input);
			MultiHashInputStream hashStream = null;
			if(hashes != null) {
				hashStream = new MultiHashInputStream(input, HashResult.makeBitmask(hashes));
				input = hashStream;
			}
			//Filter the data, if we are supposed to
			if(filterData){
				if(logMINOR) Logger.minor(this, "Running content filter... Prefetch hook: "+prefetchHook+" tagReplacer: "+tagReplacer);
				if(mimeType == null || uri == null || input == null || output == null) throw new IOException("Insufficient arguements to worker thread");
				// Send XHTML as HTML because we can't use web-pushing on XHTML.
				FilterStatus filterStatus = ContentFilter.filter(input, output, mimeType, uri, prefetchHook, tagReplacer, charset);

				String detectedMIMEType = filterStatus.mimeType.concat(filterStatus.charset == null ? "" : "; charset="+filterStatus.charset);
				synchronized(this) {
					clientMetadata = new ClientMetadata(detectedMIMEType);
				}
			}
			else {
				if(logMINOR) Logger.minor(this, "Ignoring content filter. The final result has not been written. Writing now.");
				FileUtil.copy(input, output, -1);
			}
			input.close();
			output.close();
			if(hashes != null) {
				HashResult[] results = hashStream.getResults();
				if(!HashResult.strictEquals(results, hashes)) {
					throw new FetchException(FetchException.CONTENT_HASH_FAILED);
				}
			}

			onFinish();
		} catch(Throwable t) {
			Logger.error(this, "Exception caught while processing fetch: "+t,t);
			setError(t);
		} finally {
			Closer.close(input);
			Closer.close(output);
		}
	}

	/**
	 * @return a ClientMetadata created by the ContentFilter
	 */
	public synchronized ClientMetadata getClientMetadata() {
		return clientMetadata;
	}

	/** Stores the exception and awakens blocked threads. */
	public synchronized void setError(Throwable t) {
		if(error != null) return;
		error = t;
		onFinish();
	}

	public synchronized void getError() throws Throwable {
		if(error != null) throw error;
	}
	/** Marks that all work has finished, and wakes blocked threads.*/
	public synchronized void onFinish() {
		finished = true;
		notifyAll();
	}

	/** Blocks until all threads have finished executing and cleaning up. This method
	 * also passes an exception which occurred back to the parent thread.
	 * @throws Throwable Any errors that arose during execution*/
	public synchronized void waitFinished() throws Throwable {
		while(!finished) {
			try {
				wait();
			} catch(InterruptedException e) {
				//Do nothing
			}
		}
		getError();
	}
}