package freenet.node.updater;

import java.io.File;
import java.util.Properties;
import java.util.Set;

import com.db4o.ObjectContainer;

import freenet.client.FetchContext;
import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.client.async.ClientContext;
import freenet.client.async.ClientGetCallback;
import freenet.client.async.ClientGetter;
import freenet.client.events.ClientEvent;
import freenet.client.events.ClientEventListener;
import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;
import freenet.node.RequestClient;
import freenet.node.RequestStarter;
import freenet.node.updater.MainJarDependenciesChecker.Dependency;
import freenet.node.updater.MainJarDependenciesChecker.Deployer;
import freenet.node.updater.MainJarDependenciesChecker.JarFetcher;
import freenet.node.updater.MainJarDependenciesChecker.JarFetcherCallback;
import freenet.node.updater.MainJarDependenciesChecker.MainJarDependencies;
import freenet.support.Logger;
import freenet.support.io.FileBucket;

public class MainJarUpdater extends NodeUpdater implements Deployer {
	
	static private boolean logMINOR;
	static {
		Logger.registerClass(MainJarUpdater.class);
	}
	
	private final FetchContext dependencyCtx;
	private final ClientContext clientContext;

	MainJarUpdater(NodeUpdateManager manager, FreenetURI URI, int current, int min, int max, String blobFilenamePrefix) {
		super(manager, URI, current, min, max, blobFilenamePrefix);
		dependencyCtx = core.makeClient((short) 0, true, false).getFetchContext();
		dependencyCtx.allowSplitfiles = true;
		dependencyCtx.dontEnterImplicitArchives = false;
		clientContext = core.clientContext;
		dependencies = new MainJarDependenciesChecker(this);
	}

	private final MainJarDependenciesChecker dependencies;
	
	@Override
	public String jarName() {
		return "freenet.jar";
	}
	
	@Override
	protected void maybeParseManifest(FetchResult result, int build) {
		// Do nothing.
	}

	@Override
	protected void processSuccess(int fetched, FetchResult result) {
		manager.onDownloadedNewJar(result.asBucket(), fetched);
		// NodeUpdateManager expects us to dependencies *AFTER* we tell it about the new jar.
		parseDependencies(result, fetched);
	}

	@Override
	protected void onStartFetching() {
		manager.onStartFetching();
	}
	
	// Dependency handling.

	protected void parseDependencies(Properties props, int build) {
		MainJarDependencies deps = dependencies.handle(props, build);
		if(deps != null) {
			manager.onDependenciesReady(deps);
		}
	}

	@Override
	public void deploy(MainJarDependencies deps) {
		manager.onDependenciesReady(deps);
	}

	/** Glue code. */
	private class DependencyJarFetcher implements JarFetcher, ClientGetCallback, RequestClient, ClientEventListener {

		private final ClientGetter getter;
		private SplitfileProgressEvent lastProgress;
		private final JarFetcherCallback cb;
		
		DependencyJarFetcher(File filename, FreenetURI chk, JarFetcherCallback cb) {
			FetchContext myCtx = new FetchContext(dependencyCtx, FetchContext.IDENTICAL_MASK, false, null);
			// FIXME single global binary blob writer for MainJarDependenciesChecker AND MainJarUpdater.
			getter = new ClientGetter(this,  
					chk, myCtx, RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
					this, new FileBucket(filename, false, false, false, false, false), null /* FIXME binary blob writer */, null);
			myCtx.eventProducer.addEventListener(this);
			this.cb = cb;
		}
		
		@Override
		public void cancel() {
			getter.cancel(null, clientContext);
		}
		
		@Override
		public void onMajorProgress(ObjectContainer container) {
			// Ignore.
		}
		
		@Override
		public boolean persistent() {
			return false;
		}
		
		@Override
		public boolean realTimeFlag() {
			return false;
		}
		
		@Override
		public void removeFrom(ObjectContainer container) {
			throw new UnsupportedOperationException();
		}
		
		@Override
		public void onSuccess(FetchResult result, ClientGetter state,
				ObjectContainer container) {
			cb.onSuccess();
		}
		
		@Override
		public void onFailure(FetchException e, ClientGetter state,
				ObjectContainer container) {
			cb.onFailure(e);
		}
		
		@Override
		public void receive(ClientEvent ce, ObjectContainer maybeContainer,
				ClientContext context) {
			if(ce instanceof SplitfileProgressEvent)
				lastProgress = (SplitfileProgressEvent) ce;
		}
		
		@Override
		public void onRemoveEventProducer(ObjectContainer container) {
			// Do nothing.
		}

		private void start() throws FetchException {
			getter.start(null, clientContext);
		}
		
	}
	
	@Override
	public JarFetcher fetch(FreenetURI uri, File downloadTo,
			JarFetcherCallback cb, int build) throws FetchException {
		System.out.println("Fetching "+downloadTo+" needed for new Freenet update "+build);
		if(logMINOR) Logger.minor(this, "Fetching "+uri+" to "+downloadTo+" for next update");
		DependencyJarFetcher fetcher = new DependencyJarFetcher(downloadTo, uri, cb);
		fetcher.start();
		return fetcher;
	}

}
