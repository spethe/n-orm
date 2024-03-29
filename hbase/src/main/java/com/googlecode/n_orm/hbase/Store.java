package com.googlecode.n_orm.hbase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.InvalidFamilyOperationException;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.TableNotDisabledException;
import org.apache.hadoop.hbase.TableNotEnabledException;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.UnknownScannerException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.RetriesExhaustedWithDetailsException;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.ScannerTimeoutException;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.filter.KeyOnlyFilter;
import org.apache.hadoop.hbase.filter.QualifierFilter;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.io.hfile.Compression.Algorithm;
import org.apache.hadoop.hbase.regionserver.NoSuchColumnFamilyException;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.RecoverableZooKeeper;
import org.apache.hadoop.mapreduce.Job;
import org.apache.zookeeper.ZooKeeper;
import org.codehaus.plexus.util.DirectoryScanner;

import com.googlecode.n_orm.Callback;
import com.googlecode.n_orm.DatabaseNotReachedException;
import com.googlecode.n_orm.EmptyCloseableIterator;
import com.googlecode.n_orm.PersistingElement;
import com.googlecode.n_orm.Process;
import com.googlecode.n_orm.PropertyManagement;
import com.googlecode.n_orm.cache.perthread.Cache;
import com.googlecode.n_orm.hbase.RecursiveFileAction.Report;
import com.googlecode.n_orm.hbase.actions.Action;
import com.googlecode.n_orm.hbase.actions.BatchAction;
import com.googlecode.n_orm.hbase.actions.CountAction;
import com.googlecode.n_orm.hbase.actions.DeleteAction;
import com.googlecode.n_orm.hbase.actions.ExistsAction;
import com.googlecode.n_orm.hbase.actions.GetAction;
import com.googlecode.n_orm.hbase.actions.IncrementAction;
import com.googlecode.n_orm.hbase.actions.ScanAction;
import com.googlecode.n_orm.hbase.actions.TruncateAction;
import com.googlecode.n_orm.hbase.mapreduce.ActionJob;
import com.googlecode.n_orm.hbase.properties.HColumnFamilyProperty;
import com.googlecode.n_orm.hbase.properties.HTableProperty;
import com.googlecode.n_orm.hbase.properties.PropertyUtils;
import com.googlecode.n_orm.query.SearchableClassConstraintBuilder;
import com.googlecode.n_orm.storeapi.ActionnableStore;
import com.googlecode.n_orm.storeapi.Constraint;
import com.googlecode.n_orm.storeapi.DefaultColumnFamilyData;
import com.googlecode.n_orm.storeapi.GenericStore;
import com.googlecode.n_orm.storeapi.MetaInformation;
import com.googlecode.n_orm.storeapi.Row.ColumnFamilyData;

/**
 * The HBase store found according to its configuration folder.
 * An example store.properties file is:<br><code>
 * class=com.googlecode.n_orm.hbase.Store<br>
 * static-accessor=getStore<br>
 * 1=/usr/lib/hbase/conf/
 * </code><br>
 * Given files adn directories are explored recursively ignoring files given with a ! prefix. You can also define  (positive or negative with a ! prefix) filters using wilcards such as * (any character set), ? (any character), and ** (any sub-directory) can be used both in included and excluded patterns (see {@link DirectoryScanner}), but at least one directory to look in must be defined without wildcard.
 * Two attempts are performed during search: first explicitly looking for ./*-site.xml and ./conf/*-site.xml, and then all possible ** /*-site.xml. hbase-site.xml MUST be found for the operation to succeed.
 * Compared to {@link HBase}, no jar found in those is added to classpath.
 * For test purpose, you can also directly reach an HBase instance thanks to one of its zookeeper host and client port:<br><code>
 * class=com.googlecode.n_orm.hbase.Store<br>
 * static-accessor=getStore<br>
 * 1=localhost<br>
 * 2=2181<br>
 * compression=gz &#35;can be 'none', 'gz', 'lzo', or 'snappy' (default is 'none') ; in the latter two cases, take great care that those compressors are available for all nodes of your hbase cluster
 * <br></code>
 * One important property to configure is {@link #setScanCaching(Integer)}.<br>
 * Most properties can be overloaded at class or column-family level by using the annotation {@link HBaseSchema}. 
 * This store supports remote processes (see {@link com.googlecode.n_orm.operations.Process#processElementsRemotely(Class, Constraint, Process, Callback, int, String[], int, long)} and {@link SearchableClassConstraintBuilder#remoteForEach(Process, Callback, int, long)}) as it implements {@link ActionnableStore} by using HBase/Hadoop Map-only jobs. However, be careful when configuring your hadoop: all jars containing your process and n-orm (with dependencies) should be available.
 * By default, all known jars are sent (which might become a problem is same jars are sent over and over).
 * You can change this using e.g. {@link #setMapRedSendJars(boolean)}.
 */
public class Store implements com.googlecode.n_orm.storeapi.Store, ActionnableStore, GenericStore {
	private static final String CONF_MAXRETRIES_KEY = "hbase.client.retries.number";

	private static final String CONF_PORT_KEY = "hbase.zookeeper.property.clientPort";

	private static final String CONF_HOST_KEY = HConstants.ZOOKEEPER_QUORUM;

	private static class ReportConf extends Report {
		private final Configuration conf;
		public boolean foundPropertyFile = false;
		public boolean foundHBasePropertyFile = false;

		public ReportConf(Configuration conf) {
			super();
			this.conf = conf;
		}

		Configuration getConf() {
			return conf;
		}

		@Override
		public void fileHandled(File f) {
			this.foundPropertyFile = true;
			if (!this.foundHBasePropertyFile && f.getName().equals("hbase-site.xml"))
				this.foundHBasePropertyFile = true;
		}
		
	}

	private static RecursiveFileAction addConfAction = new RecursiveFileAction() {
		
		@Override
		public void fileFound(File f, Report r) {
			try {
				((ReportConf)r).getConf().addResource(new FileInputStream(f));
				System.out.println("Getting HBase store: found configuration file " + f.getAbsolutePath());
			} catch (FileNotFoundException e) {
				System.err.println("Could not load configuration file " + f.getName());
				e.printStackTrace();
			}
		}

		@Override
		public boolean acceptFile(File file) {
			return file.getName().endsWith("-site.xml");
		}
	};

	public static final Logger logger = HBase.logger;
	public static final Logger errorLogger = HBase.errorLogger;
	public static final String localHostName;
	private static List<String> unavailableCompressors = new ArrayList<String>();
	
	protected static Map<Properties, Store> knownStores = new HashMap<Properties, Store>();
	
	static {
		String lhn;
		try {
			lhn = java.net.InetAddress.getLocalHost().getHostName();
		} catch (Exception x) {
			lhn = "localhost";
			errorLogger.log(Level.WARNING, "Cannot get local host name", x);
		}
		localHostName = lhn;
	}

	/**
	 * For test purpose ; avoid using this.
	 */
	public static Store getStore(String host, int port) {
		return getStore(host, port, null);
	}

	/**
	 * For test purpose ; avoid using this.
	 */
	public static Store getStore(String host, int port, Integer maxRetries) {
		synchronized (Store.class) {
			Properties p = new Properties();
			p.setProperty("host", host);
			p.setProperty("port", Integer.toString(port));
			if (maxRetries != null)
				p.setProperty("maxRetries", maxRetries.toString());
			Store ret = knownStores.get(p);
			if (ret == null) {
				logger.info("Creating store for " + host + ':' + port);
				ret = new Store(p);
				ret.setHost(host);
				ret.setPort(port);
				if (maxRetries != null)
					ret.setMaxRetries(maxRetries);
				knownStores.put(p, ret);
				logger.info("Created store " + ret.hashCode() + " for " + host + ':' + port);
			}
			return ret;
		}
	}
	
	/**
	 * Get an HBase store according to a set of comma-separated configuration folders.
	 * Those folders are supposed to have configuration files following the pattern *-site.xml. 
	 */
	public static Store getStore(String commaSeparatedConfigurationFolders) throws IOException {
		synchronized(Store.class) {
			Properties p = new Properties();
			p.setProperty("commaSeparatedConfigurationFolders", commaSeparatedConfigurationFolders);
			Store ret = knownStores.get(p);
			
			if (ret == null) {
				logger.info("Creating store for " + commaSeparatedConfigurationFolders);
				Configuration conf = new Configuration();
				ReportConf r = new ReportConf(conf);
				
				//First attempt using usual configuration
				String cscf = commaSeparatedConfigurationFolders + ",conf/*-site.xml,*-site.xml,!**/*example*/**,!**/*src*/**";
				addConfAction.clear();
				addConfAction.addFiles(cscf);
				try {
					addConfAction.explore(r);
				} catch (IllegalArgumentException x) {
					throw new DatabaseNotReachedException("Invalid configuration folders specification " + commaSeparatedConfigurationFolders + ": " + x.getMessage());
				}
				if (!r.foundPropertyFile || !r.foundHBasePropertyFile) {
					//Second attempt exploring all possibilities
					cscf = commaSeparatedConfigurationFolders;
					addConfAction.clear();
					addConfAction.addFiles(cscf);
					addConfAction.explore(r);
				}
			
				if (!r.foundPropertyFile)
					throw new IOException("No configuration file found in the following folders " + commaSeparatedConfigurationFolders + " ; expecting some *-site.xml files");
				if (!r.foundHBasePropertyFile)
					throw new IOException("Could not find hbase-site.xml from folders " + commaSeparatedConfigurationFolders);
				
				ret = new Store(p);
				ret.setConf(HBaseConfiguration.create(conf));
				
				knownStores.put(p, ret);
				logger.info("Created store " + ret.hashCode() + " for " + commaSeparatedConfigurationFolders);
			}
			
			return ret;
		}
	}
	
	public static Store getKnownStore(Properties properties) {
		return knownStores.get(properties);
	}
	
	public static Store getStore(Configuration conf, Properties props) throws IOException {
		if (knownStores.containsKey(props))
			throw new IllegalStateException("Store already exists with " + props);
		Store s = new Store(props);
		s.setConf(conf);
		return s;
	}
	
	private final Properties launchProps;
	
	//For inter-processes synchronization:
	//avoids different processes to alter schema concurrently
	private ConcurrentMap<MangledTableName , TableLocker /*lock*/> locks = new ConcurrentHashMap<MangledTableName, TableLocker>();
	
	private String host = localHostName;
	private int port = HConstants.DEFAULT_ZOOKEPER_CLIENT_PORT;
	private Integer maxRetries = null;
	private boolean wasStarted = false;
	private volatile boolean restarting = false;
	private final Object restartMutex = new Object();
	private Configuration config;
	private HBaseAdmin admin;
	private long cacheTTLMs = 10*60*1000; //10 min
	public ConcurrentMap<MangledTableName, HTableDescriptor> tablesD = new ConcurrentHashMap<MangledTableName, HTableDescriptor>();
	public ConcurrentHashMap<MangledTableName, Object> notExistingTables = new ConcurrentHashMap<MangledTableName, Object>();
	public HTablePool tablesC;
	
	private Integer clientTimeout = null;
	
	private Integer scanCaching = null;
	
	private Boolean deferredLogFlush = null;
	private boolean forceDeferredLogFlush = false;
	
	private Algorithm compression;
	private boolean forceCompression = false;
	
	private Boolean inMemory = null;
	private boolean forceInMemory = false;
	
	private Integer timeToLiveSeconds = null;
	private boolean forceTimeToLive = false;
	
	private Integer maxVersions = null;
	private boolean forceMaxVersions = false;
	
	private StoreFile.BloomType bloomFilterType = null;
	private boolean forceBloomFilterType = false;

	private Boolean blockCacheEnabled = null;
	private boolean forceBlockCacheEnabled = false;
	private Integer blockSize = null;
	private boolean forceBlockSize = false;
	
	private Integer replicationScope = null;
	private boolean forceReplicationScope = false;
	
	private boolean countMapRed = false;
	private boolean truncateMapRed = false;
	
	private int mapRedScanCaching = 500;
	private boolean mapRedSendHBaseJars = true;
	private boolean mapRedSendNOrmJars = true;
	private boolean mapRedSendJobJars = true;

	protected Store(Properties properties) {
		this.launchProps = properties;
		try {
			host = java.net.InetAddress.getLocalHost().getHostName();
		} catch (Exception x) {
			errorLogger.log(Level.WARNING, "Cannot get local host name", x);
		}
		
		Thread cacheReloader = new Thread("n-orm hbase cache reloader") {

			@Override
			public void run() {
				while(true) {
					try {
						Thread.sleep(getCacheTTLMs());
					} catch (InterruptedException e) {
						return;
					}
					loadCache();
				}
			}
			
		};
		cacheReloader.setDaemon(true);
		cacheReloader.start();
	}

	public synchronized void start() throws DatabaseNotReachedException {
		if (this.wasStarted)
			return;
		
		logger.info("Starting store " + this.hashCode());

		if (this.config == null) {
			Configuration properties = HBaseConfiguration.create();
			properties.clear();

			properties.set(CONF_HOST_KEY, this.getHost());
			properties.setInt(CONF_PORT_KEY, this.getPort());

			this.config = properties;
		}

		if (this.maxRetries != null)
			this.config.set(CONF_MAXRETRIES_KEY, this.maxRetries.toString());
		
		if (this.clientTimeout != null)
			this.config.set(HConstants.HBASE_RPC_TIMEOUT_KEY, this.clientTimeout.toString());

		if (this.admin == null)
			try {
				logger.fine("Connecting HBase admin for store " + this.hashCode());
				this.setAdmin(new HBaseAdmin(this.config));
				logger.fine("Connected HBase admin for store " + this.hashCode());
				if (!this.admin.isMasterRunning()) {
					errorLogger.severe("No HBase master running for store " + this.hashCode());
					throw new DatabaseNotReachedException(new MasterNotRunningException());
				}
			} catch (Exception e) {
				errorLogger.severe("Could not connect HBase for store " + this.hashCode() + " (" +e.getMessage() +')');
				throw new DatabaseNotReachedException(e);
			}
		
		this.tablesC = new HTablePool(this.getConf(), Integer.MAX_VALUE);
		
		//Wait for Zookeeper availability
		int maxRetries = 100;
		RecoverableZooKeeper zk = null;
		do {
			try {
				zk = this.admin.getConnection().getZooKeeperWatcher().getRecoverableZooKeeper();
			} catch (Exception x) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
				}
			}
		} while (zk == null && maxRetries-- > 0);
		if (zk == null) {
			logger.log(Level.SEVERE, "Cannot reach Zookeeper");
		}
		
		try {
			String[] host = this.admin.getConnection().getZooKeeperWatcher().getQuorum().split(",")[0].split(":");
			this.host = host[0].trim();
			if (host.length > 1)
				this.port = Integer.parseInt(host[1].trim());
			else
				this.port = HConstants.DEFAULT_ZOOKEPER_CLIENT_PORT;
		} catch (Exception e) {
			errorLogger.log(Level.WARNING, "Cannot read zookeeper info... Might be a bug.", e);
		}

		this.wasStarted = true;
		// Loading schema in cache AFTER marked as started
		this.loadCache();
		
		logger.info("Started store " + this.hashCode());
	}

	/**
	 * The zookeeper host to be used.
	 * You can only trust this method is this store was explicitly set the host before or started.
	 */
	public String getHost() {
		return host;
	}

	/**
	 * The zookeeper quorum host to be used.
	 * You shouldn't use this method as it should be set by {@link #getStore(String)}, {@link #getStore(String, int)}, or {@link #getStore(String, int, Integer)}.
	 */
	@Override
	public void setHost(String url) {
		this.host = url;
	}

	/**
	 * The zookeeper quorum port to be used.
	 * You can only trust this method is this store was explicitly set the port before or started.
	 */
	public int getPort() {
		return port;
	}

	/**
	 * The zookeeper quorum port to be used.
	 * You shouldn't use this method as it should be set by {@link #getStore(String)}, {@link #getStore(String, int)}, or {@link #getStore(String, int, Integer)}.
	 */
	public void setPort(int port) {
		this.port = port;
	}

	/**
	 * Schema for table are cached ; indicates time (in ms) when cache is reloaded (default is 10min)
	 */
	public long getCacheTTLMs() {
		return cacheTTLMs;
	}


	/**
	 * Schema for table are cached ; indicates time (in ms) when cache is reloaded (default is 10min).
	 * New value will be considered after next reload.
	 */
	public void setCacheTTLMs(long cacheTTLMs) {
		if (cacheTTLMs <= 0)
			throw new IllegalArgumentException("Cannot reload cache each " + cacheTTLMs + "ms");
		this.cacheTTLMs = cacheTTLMs;
	}

	/**
	 * The number of times this store can retry connecting the cluster.
	 * Default value is HBase default value (10).
	 * @throws IllegalArgumentException in case the sent value is less that 1
	 */
	public void setMaxRetries(int maxRetries) {
		if (maxRetries <= 0)
			throw new IllegalArgumentException("Cannot retry less than once");
		PropertyUtils.clearCachedValues();
		this.maxRetries = Integer.valueOf(maxRetries);
	}

	/**
	 * Maximum duration in milliseconds a request can last.
	 */
	public Integer getClientTimeout() {
		return clientTimeout;
	}

	/**
	 * Maximum duration in milliseconds a request can last.
	 * <code>null</code> means default value (see {@link HConstants#DEFAULT_HBASE_RPC_TIMEOUT}).
	 */
	public void setClientTimeout(Integer clientTimeout) {
		PropertyUtils.clearCachedValues();
		this.clientTimeout = clientTimeout;
	}

	/**
	 * The number of elements that this store scans at once during a search.
	 * @return the expected value, or null if not set (equivalent to the HBase default value - 1 - see <a href="http://hbase.apache.org/book/perf.reading.html#perf.hbase.client.caching">the HBase documentation</a>)
	 */
	public Integer getScanCaching() {
		return scanCaching;
	}

	/**
	 * The number of elements that this store scans at once during a search.
	 * Default value is HBase default value (1).
	 * Use this carefully ; read <a href="http://hbase.apache.org/book/perf.reading.html#perf.hbase.client.caching">the HBase documentation</a>.
	 */
	public void setScanCaching(Integer scanCaching) {
		PropertyUtils.clearCachedValues();
		this.scanCaching = scanCaching;
	}

	/**
	 * Whether {@link HTableDescriptor#setDeferredLogFlush(boolean)} should be set for tables.
	 * Improves write throughput by making writes log asynchronous on the server while improving risk of data loss in case of server crash.
	 * Default value is HBase default value (false).
	 * Use this carefully ; read <a href="http://hbase.apache.org/book/perf.writing.html#def.log.flush">the HBase documentation</a>.
	 */
	public Boolean getDeferredLogFlush() {
		return deferredLogFlush;
	}

	/**
	 * Whether {@link HTableDescriptor#setDeferredLogFlush(boolean)} should be set for tables.
	 * Improves write throughput by making writes log asynchronous on the server while improving risk of data loss in case of server crash.
	 * Default value is HBase default value (false).
	 * Use this carefully ; read <a href="http://hbase.apache.org/book/perf.writing.html#def.log.flush">the HBase documentation</a>.
	 */
	public void setDeferredLogFlush(Boolean deferredLogFlush) {
		this.deferredLogFlush = deferredLogFlush;
	}

	/**
	 * Whether existing tables have to be altered if they don't have the proper deferredLogFlush property.
	 * @see #getDeferredLogFlush()
	 */
	public boolean isForceDeferredLogFlush() {
		return forceDeferredLogFlush;
	}

	/**
	 * Whether existing tables have to be altered if they don't use the correct deferredLogFlush property.
	 * Default value is false.
	 * Be careful with this parameter as if two process have a store on the same cluster each with {@link #isForceDeferredLogFlush()} to true and different values for {@link Store#getDeferredLogFlush()} : tables might be altered in an endless loop !
	 * Note that altering a table takes some time as it must be disabled and enabled again, so use this with care.
	 * @see #getDeferredLogFlush()
	 */
	public void setForceDeferredLogFlush(boolean forceDeferredLogFlush) {
		this.forceDeferredLogFlush = forceDeferredLogFlush;
	}

	/**
	 * The number of elements that this store scans at once during a Map/Reduce task (see <a href="http://hbase.apache.org/book/perf.reading.html#perf.hbase.client.caching">the HBase documentation</a>).
	 * @see com.googlecode.n_orm.operations.Process#processElementsRemotely(Class, Constraint, Process, Callback, int, String[], int, long)
	 * @see SearchableClassConstraintBuilder#remoteForEach(Process, Callback, int, long)
	 */
	public int getMapRedScanCaching() {
		return mapRedScanCaching;
	}

	public void setMapRedScanCaching(int mapRedScanCaching) {
		PropertyUtils.clearCachedValues();
		this.mapRedScanCaching = mapRedScanCaching;
	}

	/**
	 * The used compression for this store.
	 * Compression is used when creating a column family in the HBase cluster.
	 * In case you set {@link #setForceCompression(boolean)} to true, existing column families are also checked and altered if necessary.
	 * @return the used compression, or null if not set (equivalent to the HBase default value - none)
	 */
	public String getCompression() {
		if (getCompressionAlgorithm() == null)
			return null;
		return getCompressionAlgorithm().getName();
	}

	/**
	 * The used compression for this store.
	 * Default value is the HBase default (none).
	 * Compression is used when creating a column family in the HBase cluster.
	 * In case you set {@link #setForceCompression(boolean)} to true, existing column families are also checked and altered if necessary.
	 * You can test for LOCALLY available compressors using tested_ prefix (e.g. tested_lzo-or-gz).
	 */
	public void setCompression(String compression) {
		if (compression == null) {
			PropertyUtils.clearCachedValues();
			this.compression = null;
		} else {
			compression = compression.toLowerCase();
			for (String cmp : compression.split("-or-")) {
				cmp = cmp.trim();
				boolean tested = cmp.startsWith("tested_");
				if (tested) {
					cmp = cmp.substring("tested_".length()).trim();
				}
				Algorithm newCompression = getCompressionByName(cmp);
				if (newCompression != null) {
					if (tested) {
						try {
							org.apache.hadoop.hbase.util.CompressionTest.testCompression(newCompression);
						} catch (Throwable t) {
							logger.log(Level.WARNING, "Cannot use compressor: " + newCompression, t);
							continue;
						}
					}
					PropertyUtils.clearCachedValues();
					this.compression = newCompression;
					return;
				} else {
					logger.log(Level.WARNING, "Unknown compressor: " + cmp);
				}
			}
			throw new DatabaseNotReachedException("Failed to load compression " + compression);
		}
	}
	
	public Algorithm getCompressionAlgorithm() {
		return compression;
	}

	public static Algorithm getCompressionByName(String requestedCompression) {
		if (requestedCompression.length() > 0) {
			if (unavailableCompressors.contains(requestedCompression))
				return null;
			try {
				return Compression.getCompressionAlgorithmByName(requestedCompression);
			} catch (Exception x) {
				unavailableCompressors.add(requestedCompression);
				logger.log(Level.WARNING, "Cannot not use compression " + requestedCompression, x);
				return null;
			}
		} else
			return null;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct compressor.
	 * @see #getCompression()
	 */
	public boolean isForceCompression() {
		return forceCompression;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct compressor.
	 * Default value is false.
	 * Be careful with this parameter as if two process have a store on the same cluster each with {@link #isForceCompression()} to true and different values for {@link Store#getCompression()} : column families might be altered in an endless loop !
	 * Note that altering a column family takes some time as tables must be disabled and enabled again, so use this with care.
	 * @see #getCompression()
	 */
	public void setForceCompression(boolean forceCompression) {
		PropertyUtils.clearCachedValues();
		this.forceCompression = forceCompression;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setInMemory(boolean)} set. 
	 */
	public Boolean isInMemory() {
		return inMemory;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setInMemory(boolean)} set.
	 * Default value is false.
	 * null is considered as unset (i.e. the default value)
	 */
	public void setInMemory(Boolean inMemory) {
		PropertyUtils.clearCachedValues();
		this.inMemory = inMemory;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setInMemory(boolean)} setting.
	 * @see #isInMemory()
	 */
	public boolean isForceInMemory() {
		return forceInMemory;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setInMemory(boolean)} setting.
	 * Be careful with this parameter as if two process have a store on the same cluster each with {@link #isForceInMemory()} to true and different values for {@link Store#getCompression()} : column families might be altered in an endless loop !
	 * Note that altering a column family takes some time as tables must be disabled and enabled again, so use this with care.
	 * @see #isInMemory()
	 */
	public void setForceInMemory(boolean forceInMemory) {
		PropertyUtils.clearCachedValues();
		this.forceInMemory = forceInMemory;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setTimeToLive(int)} set. 
	 */
	public Integer getTimeToLiveSeconds() {
		return timeToLiveSeconds;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setTimeToLive(int)} set.
	 * Default value is {@link HColumnDescriptor#DEFAULT_TTL}
	 * null or value &lt= 0 is considered as unset (i.e. the default value).
	 */
	public void setTimeToLiveSeconds(Integer timeToLiveSeconds) {
		PropertyUtils.clearCachedValues();
		this.timeToLiveSeconds = timeToLiveSeconds;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setTimeToLive(int)} setting.
	 * @see #getTimeToLiveSeconds()
	 */
	public boolean isForceTimeToLive() {
		return forceTimeToLive;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setTimeToLive(int)} setting.
	 * Be careful with this parameter as if two process have a store on the same cluster each with {@link #isForceTimeToLive()} to true and different values for {@link Store#getTimeToLiveSeconds()} : column families might be altered in an endless loop !
	 * Note that altering a column family takes some time as tables must be disabled and enabled again, so use this with care.
	 * @see #getTimeToLiveSeconds()
	 */
	public void setForceTimeToLive(boolean forceTimeToLive) {
		PropertyUtils.clearCachedValues();
		this.forceTimeToLive = forceTimeToLive;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setMaxVersions(int)} set. 
	 */
	public Integer getMaxVersions() {
		return maxVersions;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setMaxVersions(int)} set.
	 * Default value is {@link HColumnDescriptor#DEFAULT_VERSIONS}
	 * null or value &lt= 0 is considered as unset (i.e. the default value).
	 */
	public void setMaxVersions(Integer maxVersions) {
		PropertyUtils.clearCachedValues();
		this.maxVersions = maxVersions;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setMaxVersions(int)} setting.
	 * @see #getMaxVersions()
	 */
	public boolean isForceMaxVersions() {
		return forceMaxVersions;
	}
	
	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setMaxVersions(int)} setting.
	 * Be careful with this parameter as if two process have a store on the same cluster each with {@link #isForceMaxVersions()} to true and different values for {@link Store#getMaxVersions()} : column families might be altered in an endless loop !
	 * Note that altering a column family takes some time as tables must be disabled and enabled again, so use this with care.
	 * @see #getMaxVersions()
	 */
	public void setForceMaxVersions(boolean forceMaxVersions) {
		PropertyUtils.clearCachedValues();
		this.forceMaxVersions = forceMaxVersions;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setBloomFilterType(org.apache.hadoop.hbase.regionserver.StoreFile.BloomType)} set. 
	 */
	public StoreFile.BloomType getBloomFilterType() {
		return bloomFilterType;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setBloomFilterType(org.apache.hadoop.hbase.regionserver.StoreFile.BloomType)} set.
	 * Default value is equivalent to {@link HColumnDescriptor#DEFAULT_BLOOMFILTER}
	 * null is considered as unset (i.e. the default value).
	 */
	public void setBloomFilterType(StoreFile.BloomType bloomFilterType) {
		PropertyUtils.clearCachedValues();
		this.bloomFilterType = bloomFilterType;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setBloomFilterType(org.apache.hadoop.hbase.regionserver.StoreFile.BloomType)} setting.
	 * @see #getBloomFilterType()
	 */
	public boolean isForceBloomFilterType() {
		return forceBloomFilterType;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setBloomFilterType(org.apache.hadoop.hbase.regionserver.StoreFile.BloomType)} setting.
	 * Be careful with this parameter as if two process have a store on the same cluster each with {@link #isForceBloomFilterType()} to true and different values for {@link Store#getBloomFilterType()} : column families might be altered in an endless loop !
	 * Note that altering a column family takes some time as tables must be disabled and enabled again, so use this with care.
	 * @see #getBloomFilterType()
	 */
	public void setForceBloomFilterType(boolean forceBloomFilterType) {
		PropertyUtils.clearCachedValues();
		this.forceBloomFilterType = forceBloomFilterType;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setBlockCacheEnabled(boolean)} set. 
	 */
	public Boolean getBlockCacheEnabled() {
		return blockCacheEnabled;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setBlockCacheEnabled(boolean)} set.
	 * Default value is equivalent to {@link HColumnDescriptor#DEFAULT_BLOCKCACHE}
	 * null is considered as unset (i.e. the default value).
	 */
	public void setBlockCacheEnabled(Boolean blockCacheEnabled) {
		PropertyUtils.clearCachedValues();
		this.blockCacheEnabled = blockCacheEnabled;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setBlockCacheEnabled(boolean)} setting.
	 * @see #getBlockCacheEnabled()
	 */
	public boolean isForceBlockCacheEnabled() {
		return forceBlockCacheEnabled;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setBlockCacheEnabled(boolean)} setting.
	 * Be careful with this parameter as if two process have a store on the same cluster each with {@link #isForceBlockCacheEnabled()} to true and different values for {@link Store#getBlockCacheEnabled()} : column families might be altered in an endless loop !
	 * Note that altering a column family takes some time as tables must be disabled and enabled again, so use this with care.
	 * @see #getBlockCacheEnabled()
	 */
	public void setForceBlockCacheEnabled(boolean forceBlockCacheEnabled) {
		PropertyUtils.clearCachedValues();
		this.forceBlockCacheEnabled = forceBlockCacheEnabled;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setBlocksize(int)} set. 
	 */
	public Integer getBlockSize() {
		return blockSize;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setBlocksize(int)} set.
	 * Default value is equivalent to {@link HColumnDescriptor#DEFAULT_BLOCKSIZE}
	 * null is considered as unset (i.e. the default value).
	 */
	public void setBlockSize(Integer blockSize) {
		PropertyUtils.clearCachedValues();
		this.blockSize = blockSize;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setBlocksize(int)} setting.
	 * @see #getBlockCacheEnabled()
	 */
	public boolean isForceBlockSize() {
		return forceBlockSize;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setBlocksize(int)} setting.
	 * Be careful with this parameter as if two process have a store on the same cluster each with {@link #isForceBlockSize()} to true and different values for {@link Store#getBlockSize()} : column families might be altered in an endless loop !
	 * Note that altering a column family takes some time as tables must be disabled and enabled again, so use this with care.
	 * @see #getBlockSize()
	 */
	public void setForceBlockSize(boolean forceBlockSize) {
		PropertyUtils.clearCachedValues();
		this.forceBlockSize = forceBlockSize;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setScope(int)} set. 
	 */
	public Integer getReplicationScope() {
		return replicationScope;
	}

	/**
	 * Whether created tables should have {@link HColumnDescriptor#setScope(int)} set.
	 * Default value is equivalent to {@link HColumnDescriptor#DEFAULT_REPLICATION_SCOPE}
	 * null or &lt= 0 or &gt=2 is considered as unset (i.e. the default value).
	 */
	public void setReplicationScope(Integer replicationScope) {
		PropertyUtils.clearCachedValues();
		this.replicationScope = replicationScope;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setScope(int)} setting.
	 * @see #getReplicationScope()
	 */
	public boolean isForceReplicationScope() {
		return forceReplicationScope;
	}

	/**
	 * Whether existing columns have to be altered if they don't use the correct {@link HColumnDescriptor#setScope(int)} setting.
	 * Be careful with this parameter as if two process have a store on the same cluster each with {@link #isForceReplicationScope()} to true and different values for {@link Store#getReplicationScope()} : column families might be altered in an endless loop !
	 * Note that altering a column family takes some time as tables must be disabled and enabled again, so use this with care.
	 * @see #getReplicationScope()
	 */
	public void setForceReplicationScope(boolean forceReplicationScope) {
		PropertyUtils.clearCachedValues();
		this.forceReplicationScope = forceReplicationScope;
	}

	/**
	 * Whether counts (e.g. {@link #count(MetaInformation, String, Constraint)}) should use a map/reduce job.
	 */
	public boolean isCountMapRed() {
		return countMapRed;
	}

	/**
	 * Whether counts (e.g. {@link Store#count(MetaInformation, String, Constraint)}) should use a map/reduce job.
	 * Default value is false.
	 * Map/reduce jobs are usually hard to run, so if this method is faster in case of large data on large cluster, it should be avoided on small clusters.
	 */
	public void setCountMapRed(boolean countMapRed) {
		PropertyUtils.clearCachedValues();
		this.countMapRed = countMapRed;
	}

	/**
	 * Whether truncates (e.g. {@link #truncate(MetaInformation, String, Constraint)}) should use a map/reduce job.
	 */
	public boolean isTruncateMapRed() {
		return truncateMapRed;
	}

	/**
	 * Whether truncates (e.g. {@link #truncate(MetaInformation, String, Constraint)}) should use a map/reduce job.
	 * Default value is false.
	 * Map/reduce jobs are usually hard to run, so if this method is faster in case of large data on large cluster, it should be avoided on small clusters.
	 */
	public void setTruncateMapRed(boolean truncateMapRed) {
		PropertyUtils.clearCachedValues();
		this.truncateMapRed = truncateMapRed;
	}

	/**
	 * Whether jar files containing sent jobs should be sent to the Hadoop Map/Reduce cluster while performing a Map/Reduce job.
	 */
	public boolean isMapRedSendJobJars() {
		return mapRedSendJobJars;
	}

	/**
	 * Whether jar files containing sent jobs should be sent to the Hadoop Map/Reduce cluster while performing a Map/Reduce job.
	 * Default value is true.
	 * Setting this parameter to false will improve map/reduce tasks setup, but you might face {@link ClassNotFoundException} on task tracker nodes if their CLASSPATH is not configured properly.
	 * To be used with care !
	 */
	public void setMapRedSendJobJars(boolean mapRedSendJars) {
		PropertyUtils.clearCachedValues();
		this.mapRedSendJobJars = mapRedSendJars;
	}

	/**
	 * Whether jar files containing n-orm and the n-orm HBase driver should be sent to the Hadoop Map/Reduce cluster while performing a Map/Reduce job.
	 */
	public boolean isMapRedSendNOrmJars() {
		return mapRedSendNOrmJars;
	}

	/**
	 * Whether jar files containing n-orm and the n-orm HBase driver should be sent to the Hadoop Map/Reduce cluster while performing a Map/Reduce job.
	 * Default value is true.
	 * Setting this parameter to false will improve map/reduce tasks setup, but you might face {@link ClassNotFoundException} on task tracker nodes if their CLASSPATH is not configured properly.
	 * To be used with care !
	 */
	public void setMapRedSendNOrmJars(boolean mapRedSendJars) {
		PropertyUtils.clearCachedValues();
		this.mapRedSendNOrmJars = mapRedSendJars;
	}

	/**
	 * Whether jar files containing the HBase client should be sent to the Hadoop Map/Reduce cluster while performing a Map/Reduce job.
	 */
	public boolean isMapRedSendHBaseJars() {
		return mapRedSendHBaseJars;
	}

	/**
	 * Whether jar files containing the HBase client should be sent to the Hadoop Map/Reduce cluster while performing a Map/Reduce job.
	 * Default value is true.
	 * Setting this parameter to false will improve map/reduce tasks setup, but you might face {@link ClassNotFoundException} on task tracker nodes if their CLASSPATH is not configured properly.
	 * To be used with care !
	 */
	public void setMapRedSendHBaseJars(boolean mapRedSendJars) {
		PropertyUtils.clearCachedValues();
		this.mapRedSendHBaseJars = mapRedSendJars;
	}

	/**
	 * Whether jar files containing HBase, n-orm, the n-orm HBase driver and sent job should be sent to the Hadoop Map/Reduce cluster while performing a Map/Reduce job.
	 * Default value is true.
	 * Setting this parameter to false will improve map/reduce tasks setup, but you might face {@link ClassNotFoundException} on task tracker nodes if their CLASSPATH is not configured properly.
	 * To be used with care !
	 */
	public void setMapRedSendJars(boolean mapRedSendJars) {
		this.setMapRedSendJobJars(mapRedSendJars);
		this.setMapRedSendNOrmJars(mapRedSendJars);
		this.setMapRedSendHBaseJars(mapRedSendJars);
	}

	/**
	 * The configuration used by this store.
	 * You can only trust this method is this store was explicitly set the host before or started.
	 * This method provides a mean to have greater control over HBase and Hadoop.
	 */
	public Configuration getConf() {
		return this.config;
	}

	public Properties getLaunchProps() {
		return launchProps;
	}


	/**
	 * The configuration to be used by this store for its {@link #start()} or {@link #restart()}.
	 * Only valid when store is not started yet.
	 * Overloads any other configuration setting already set by {@link #getStore(String)}, {@link #getStore(String, int)}, {@link #getStore(String, int, Integer)}, or {@link #getAdmin()}.
	 * Ignored in case of a subsequent {@link #setAdmin(HBaseAdmin)}.
	 * Changed when invoked {@link #start()} or {@link #restart()}.
	 * @throws IllegalStateException if this store was started already
	 */
	public void setConf(Configuration configuration) {
		if (this.wasStarted)
			synchronized(this) {
				if (this.wasStarted)
					throw new IllegalStateException("Cannot set new admin object on already started store " + this);
			}
		
		this.config = configuration;
		String prop = this.config.get(CONF_HOST_KEY);
		if (prop != null)
			this.host = prop;
		else
			try {
				this.host = java.net.InetAddress.getLocalHost().getHostName();
			} catch (UnknownHostException e) {
				logger.log(Level.WARNING, "Cannot determine local host", e);
			}
		prop = this.config.get(CONF_PORT_KEY);
		if (prop != null)
			this.port = Integer.parseInt(prop);
		else
			this.port = HConstants.DEFAULT_ZOOKEPER_CLIENT_PORT;
		prop = this.config.get(CONF_MAXRETRIES_KEY);
		if (prop != null)
			this.maxRetries = Integer.parseInt(prop);
		else
			this.maxRetries = null;
	}
	
	public HBaseAdmin getAdmin() {
		return this.admin;
	}

	/**
	 * Sets the HBase admin object.
	 * Only valid before the store is started.
	 * @throws IllegalStateException in case this store is already started
	 */
	public void setAdmin(HBaseAdmin admin) {
		if (this.wasStarted)
			synchronized(this) {
				if (this.wasStarted)
					throw new IllegalStateException("Cannot set new admin object on already started store " + this);
			}
		this.admin = admin;
		this.setConf(admin.getConfiguration());
	}

	private void loadCache() {
		if (! this.wasStarted)
			return;
		this.notExistingTables.clear();
		try {
			
			HTableDescriptor[] tbls = this.admin.listTables();
			List<HTableDescriptor> tables = tbls == null || tbls.length == 0 ? new LinkedList<HTableDescriptor>() : Arrays.asList(tbls);
			for(HTableDescriptor td : tables) {
				this.tablesD.put(new MangledTableName(td.getNameAsString()), td);
			}
			Iterator<Entry<MangledTableName, HTableDescriptor>> it = this.tablesD.entrySet().iterator();
			while (it.hasNext()) {
				Entry<MangledTableName, HTableDescriptor> cur = it.next();
				if (! tables.contains(cur.getValue()))
					it.remove();
			}
		} catch (IOException e) {
			errorLogger.log(Level.SEVERE, "Cannot load existing tables in cache: " + e.getMessage(), e);
			throw new DatabaseNotReachedException(e);
		}
	}
	
	// For test purpose
	void clearCache() {
		this.tablesD.clear();
	}
	
	private void cache(MangledTableName tableName, HTableDescriptor descr) {
		HTableDescriptor knownDescr = this.tablesD.get(tableName);
		if (knownDescr == null || !knownDescr.equals(descr)) {
			this.uncache(tableName);
			this.tablesD.put(tableName, descr);
			this.notExistingTables.remove(tableName);
		}
	}

	private void uncache(MangledTableName tableName) {
		this.tablesD.remove(tableName);
		this.notExistingTables.remove(tableName);
	}

	/**
	 * Resets the connection to HBase.
	 * Also vacuum any cached value about the store (but not Cached elements from {@link Cache}).
	 * This method is thread-safe ; if a restart is already ongoing, the methods block until restart is done.
	 */
	public void restart() {
		synchronized(this.restartMutex) {
			if (this.restarting)
				try {
					this.restartMutex.wait(10000);
					assert !this.restarting;
					return;
				} catch (InterruptedException e) {
					errorLogger.log(Level.WARNING, "Problem while waiting for store restart: " + e.getMessage(), e);
				}
			this.restarting = true;
		}
		try {
				try {
					this.admin.close();
				} catch (Exception x) {
					logger.log(Level.WARNING, "Exception while restarting store " + this + ": error while stopping admin", x);
				}
			this.tablesD.clear();
			try {
				HConnectionManager.deleteConnection(this.config);
			} catch (Exception x) {
				logger.log(Level.WARNING, "Problem while closing connection during store restart: " + x.getMessage(), x);
			}
			this.config = HBaseConfiguration.create(this.config);
			this.admin = null;
			this.wasStarted = false;
			this.tablesD.clear();
			this.start();
		} finally {
			synchronized (this.restartMutex) {
				this.restartMutex.notifyAll();
				this.restarting = false;
			}
		}
	}
	
	private boolean hasProblem(Throwable t, Class<? extends Throwable> problem) {
		Set<Throwable> explored = new HashSet<Throwable>(); 
		while (t != null && explored.add(t)) {
			if (problem.isInstance(t)) {
				return true;
			}
			if (t.getMessage()  != null && t.getMessage().contains(problem.getName())) {
				return true;
			}
			if (t instanceof RetriesExhaustedWithDetailsException) {
				for (Throwable e : ((RetriesExhaustedWithDetailsException)t).getCauses()) {
					if (explored.add(e) && hasProblem(e, problem))
						return true;
				}
			}
			t = t.getCause();
		}
		return false;
	}
	
	protected void handleProblem(Throwable e, Class<? extends PersistingElement> clazz, MangledTableName table, String tablePostfix, Map<String, Field> expectedFamilies) throws DatabaseNotReachedException {		
		while (((e instanceof UndeclaredThrowableException) || (e instanceof RuntimeException)) && e.getCause() != null) {
			e = e.getCause();
		}
		
		if (e instanceof DatabaseNotReachedException)
			throw (DatabaseNotReachedException)e;
		
		String msg = e.getMessage();
		if (msg == null) msg = "";
		
		//System.err.println("====================== handling " + e + " with message " + msg);
		
		if (this.hasProblem(e, TableNotFoundException.class)) {
			errorLogger.log(Level.INFO, "Trying to recover from exception for store " + this.hashCode() + " it seems that a table was dropped ; recreating", e);
			this.uncache(table);
			try {
				this.getTableDescriptor(clazz, table, tablePostfix, expectedFamilies);
			} catch (Exception x) {
				throw new DatabaseNotReachedException(x);
			}
		} else if (this.hasProblem(e, NotServingRegionException.class)
				|| this.hasProblem(e, TableNotEnabledException.class)
				|| (e.getMessage() != null && e.getMessage().contains("disabled"))) {
			try {
				TableLocker lock = this.getLock(table);
				lock.sharedLockTable();
				try {
					if (this.hasTableInt(table) && this.admin.isTableDisabled(table.getNameAsBytes())) { //First detect the error
						errorLogger.log(Level.INFO, "It seems that table " + table + " was disabled ; enabling", e);
						this.enableTable(clazz, table, tablePostfix);
					} else // Error was auto-repaired (another thread ?)
						return;
				} finally {
					lock.sharedUnlockTable();
				}
			} catch (IOException f) {
				throw new DatabaseNotReachedException(f);
			}
		} else if (this.hasProblem(e, NoSuchColumnFamilyException.class)) {
			errorLogger.log(Level.INFO, "Trying to recover from exception for store " + this.hashCode() + " it seems that table " + table + " was dropped a column family ; recreating", e);
			this.uncache(table);
			try {
				this.getTableDescriptor(clazz, table, tablePostfix, expectedFamilies);
			} catch (Exception x) {
				throw new DatabaseNotReachedException(x);
			}
		} else if (this.hasProblem(e, ConnectException.class) || msg.contains("closed")) {
			errorLogger.log(Level.INFO, "Trying to recover from exception for store " + this.hashCode() + " it seems that connection was lost ; restarting store", e);
			restart();
		} else if (this.hasProblem(e, SocketTimeoutException.class) || this.hasProblem(e, ScannerTimeoutException.class) || msg.contains("timeout") || this.hasProblem(e, UnknownScannerException.class) || this.hasProblem(e, TimeoutException.class)) {
			errorLogger.log(Level.INFO, "Timeout while requesting " + table + " (max duration is set to " + this.config.get(HConstants.HBASE_RPC_TIMEOUT_KEY, Integer.toString(HConstants.DEFAULT_HBASE_RPC_TIMEOUT)) + "ms)", e);
		} else if (this.hasProblem(e, IOException.class)) {
			errorLogger.log(Level.INFO, "Trying to recover from IO exception for store " + this.hashCode() + " ; restarting store", e);
			restart();
		} else {
			throw new DatabaseNotReachedException(e);
		}
	}
	
	protected <R> R tryPerform(Action<R> action, Class<? extends PersistingElement> clazz,  MangledTableName tableName, String tablePostfix, Map<String, Field> expectedFamilies) throws DatabaseNotReachedException {
		HTableInterface table = this.getTable(clazz, tableName, tablePostfix, expectedFamilies);
		try {
			return this.tryPerform(action, table, clazz, tablePostfix, expectedFamilies);
		} finally {
			try {
				this.returnTable(action.getTable());
			} catch (IOException x) {
				throw new DatabaseNotReachedException(x);
			}
		}
		
	}
	
	/**
	 * Performs an action. Table should be replaced by action.getTable() as it can change in case of problem handling (like a connection lost).
	 */
	protected <R> R tryPerform(final Action<R> action, HTableInterface table, Class<? extends PersistingElement> clazz, String tablePostfix, Map<String, Field> expectedFamilies) throws DatabaseNotReachedException {	
		assert tablePostfix == null || Bytes.toString(table.getTableName()).endsWith(tablePostfix);
		action.setTable(table);
		try {
			return action.perform();
		} catch (Throwable e) {
			errorLogger.log(Level.INFO, "Got an error while performing a " + action.getClass().getName() + " on table " + Bytes.toString(action.getTable().getTableName()) + " for store " + this.hashCode(), e);
			
			HTablePool tp = this.tablesC;
			MangledTableName tableName = new MangledTableName(table);
			this.handleProblem(e, clazz, tableName, tablePostfix, expectedFamilies);
			if (tp != this.tablesC) { //Store was restarted ; we should get a new table client
				table = this.getTable(clazz, tableName, tablePostfix, expectedFamilies);
				action.setTable(table);
			}
			try {
				errorLogger.log(Level.INFO, "Retrying to perform again erroneous " + action.getClass().getName() + " on table " + Bytes.toString(action.getTable().getTableName()) + " for store " + this.hashCode(), e);
				return action.perform();
			} catch (Exception f) {
				errorLogger.log(Level.SEVERE, "Cannot recover from error while performing a" + action.getClass().getName() + " on table " + Bytes.toString(action.getTable().getTableName()) + " for store " + this.hashCode(), e);
				throw new DatabaseNotReachedException(f);
			}
		}
	}
	
	ZooKeeper getZooKeeper() throws DatabaseNotReachedException {
		ZooKeeper zk;
		try {
			try {
				zk = this.admin.getConnection().getZooKeeperWatcher().getRecoverableZooKeeper().getZooKeeper();
			} catch (ZooKeeperConnectionException x) { //Lost zookeeper ?
				this.restart();
				zk = this.admin.getConnection().getZooKeeperWatcher().getRecoverableZooKeeper().getZooKeeper();
			} catch (NullPointerException x) { //Lost zookeeper ?
				this.restart();
				zk = this.admin.getConnection().getZooKeeperWatcher().getRecoverableZooKeeper().getZooKeeper();
			}
			if (!zk.getState().isAlive()) {
				errorLogger.log(Level.WARNING, "Zookeeper connection lost ; restarting...");
				this.restart();
				zk = this.admin.getConnection().getZooKeeperWatcher().getRecoverableZooKeeper().getZooKeeper();
			}
			return zk;
		} catch (Exception e1) {
			throw new DatabaseNotReachedException(e1);
		}
		
	}
	
	protected TableLocker getLock(MangledTableName table) throws DatabaseNotReachedException {
		
		TableLocker ret = new TableLocker(this, table);
		
		TableLocker actualRet = this.locks.putIfAbsent(table, ret);
		if(actualRet != null) {
			ret = actualRet;
		}

		return ret;
	}

	@Override
	public boolean hasTable(String name) throws DatabaseNotReachedException {
		return this.hasTable(new MangledTableName(name));
	}

	public boolean hasTable(MangledTableName name) throws DatabaseNotReachedException {
		if (this.tablesD.containsKey(name))
			return true;
		
		if (this.notExistingTables.containsKey(name))
			return false;

		return hasTableNoCache(name);
	}

	private boolean hasTableNoCache(MangledTableName name) {
		try {
			TableLocker lock = this.getLock(name);
			lock.sharedLockTable();
			try {
				return this.hasTableInt(name);
			} finally {
				lock.sharedUnlockTable();
			}
		} catch (Exception e) {
			throw new DatabaseNotReachedException(e);
		}
	}
	
	private boolean hasTableInt(MangledTableName name) throws IOException {
		boolean ret;
		try {
			ret = this.admin.tableExists(name.getNameAsBytes());
		} catch (IOException x) {
			String msg = x.getMessage();
			if (msg != null && msg.contains("closed")) {
				this.restart();
				ret = this.admin.tableExists(name.getNameAsBytes());
			} else {
				throw x;
			}
		}
		if (ret) {
			this.notExistingTables.remove(name);
		} else {
			this.uncache(name);
			this.notExistingTables.put(name, name);
		}
		return ret;
	}
	
	private void disableTable(
			Class<? extends PersistingElement> clazz,
			MangledTableName tableName, String tablePostfix)
					throws IOException {
		TableLocker lock = this.getLock(tableName);
		lock.exclusiveLockTable();
		try {
			this.admin.disableTable(tableName.getNameAsBytes());
		} catch (TableNotEnabledException x) {
			logger.info("Table " + tableName + " already disabled");
		} catch (TableNotFoundException x) {
			this.handleProblem(x, clazz, tableName, tablePostfix, null);
			this.admin.disableTable(tableName.getNameAsBytes());
		} finally {
			lock.exclusiveUnlockTable();
		}
		if (! this.admin.isTableDisabled(tableName.getNameAsBytes()))
			throw new IOException("Not able to disable table " + tableName);
		logger.info("Table " + tableName + " disabled");
	}
	
	private void enableTable(
			Class<? extends PersistingElement> clazz,
			MangledTableName tableName, String tablePostfix)
					throws IOException {
		TableLocker lock = this.getLock(tableName);
		lock.exclusiveLockTable();
		try {
			this.admin.enableTable(tableName.getNameAsBytes());
			if (!this.admin.isTableEnabled(tableName.getNameAsBytes()))
				throw new IOException();
		} catch (TableNotDisabledException x) {
			logger.info("Table " + tableName + " already enabled");
		} catch (Exception x) {
			this.handleProblem(x, clazz, tableName, tablePostfix, null);
			this.admin.enableTable(tableName.getNameAsBytes());
			if (!this.admin.isTableEnabled(tableName.getNameAsBytes()))
				throw new IOException("SEVERE: cannot enable table " + tableName);
		} finally {
			lock.exclusiveUnlockTable();
		}
		logger.info("Table " + tableName + " enabled");
	}
	
	protected HTableDescriptor getTableDescriptor(Class<? extends PersistingElement> clazz, MangledTableName name, String tablePostfix, Map<String, Field> expectedFamilies) throws Exception {
		HTableDescriptor td;
		boolean created = false;
		boolean freshDescriptor = false;
		td = this.tablesD.get(name);
		if (td == null) {

			TableLocker lock = this.getLock(name);
			lock.sharedLockTable();
			try {
				logger.fine("Unknown table " + name + " for store " + this.hashCode());
				if (!this.hasTable(name)) {
					lock.exclusiveLockTable();
					try {
						if (!this.hasTableInt(name)) {
							logger.info("Table " + name + " not found ; creating" + (expectedFamilies == null ? "" : " with column families " + expectedFamilies.keySet().toString()));
							td = new HTableDescriptor(name.getNameAsBytes());
							PropertyUtils.setValues(this, td, clazz, tablePostfix);
							if (expectedFamilies != null) {
								for (Entry<String, Field> fam : expectedFamilies.entrySet()) {
									byte [] famB = Bytes.toBytes(fam.getKey());
									if (!td.hasFamily(famB)) {
										HColumnDescriptor famD = new HColumnDescriptor(famB);
										PropertyUtils.setValues(this, famD, clazz, fam.getValue(), tablePostfix);
										td.addFamily(famD);
									}
								}
							}
							this.admin.createTable(td);
							logger.info("Table " + name + " created");
							created = true;
							freshDescriptor = true;
						}
					} catch (TableExistsException x) {
						//Already done by another process...
						td = this.admin.getTableDescriptor(name.getNameAsBytes());
						freshDescriptor = true;
						logger.fine("Got descriptor for table " + name);
					} finally {
						lock.exclusiveUnlockTable();
						assert this.getLock(name).isShareLocked();
					}
				}
				if (td == null) {
					td = this.admin.getTableDescriptor(name.getNameAsBytes());
					freshDescriptor = true;
					logger.fine("Got descriptor for table " + name);
				}
				this.cache(name, td);
			} finally {
				lock.sharedUnlockTable();
			}
		}
		
		// Checking whether table is well formed in case it were just retrieved
		if (freshDescriptor && !PropertyUtils.asExpected(this, td, clazz, tablePostfix)) {
			logger.info("Table " + td.getNameAsString() + " has wrong properties : altering");
			PropertyUtils.setValues(this, td, clazz, tablePostfix);
			TableLocker lock = this.getLock(name);
			lock.exclusiveLockTable();
			try {
				this.disableTable(clazz, name, tablePostfix);
				try {
					this.admin.modifyTable(td.getName(), td);
				} catch (TableNotFoundException x) {
					this.handleProblem(x, clazz, name, tablePostfix, null);
					this.admin.modifyTable(td.getName(), td);
				}
				do {
					Thread.sleep(10);
					td = this.admin.getTableDescriptor(td.getName());
				} while (!PropertyUtils.asExpected(this, td, clazz, tablePostfix));
				this.enableTable(clazz, name, tablePostfix);
				HTableProperty<?>[] problems = PropertyUtils.checkIsAsExpected(this, td, clazz, tablePostfix);
				if (problems.length > 0)
					throw new IOException("Table " + td.getName() + " still has wrong properties " + Arrays.toString(problems));
			} finally {
				lock.exclusiveUnlockTable();
			}
		}
		
		if (freshDescriptor) {
			this.cache(name, td);
		}
		
		if (!created && expectedFamilies != null && expectedFamilies.size()>0) {
			this.enforceColumnFamiliesExists(td, freshDescriptor, clazz, expectedFamilies, name, tablePostfix);
		}
		
		return td;
	}

	protected HTableInterface getTable(Class<? extends PersistingElement> clazz, MangledTableName name, String tablePostfix, Map<String, Field> expectedFamilies)
			throws DatabaseNotReachedException {
		assert tablePostfix == null || name.getName().endsWith(tablePostfix);
		
		try {
			//Checking that this table actually exists with the expected column families
			this.getTableDescriptor(clazz, name, tablePostfix, expectedFamilies);
			return this.tablesC.getTable(name.getNameAsBytes());
		} catch (Throwable x) {
			this.handleProblem(x, clazz, name, tablePostfix, expectedFamilies);
			try {
				this.getTableDescriptor(clazz, name, tablePostfix, expectedFamilies);
			} catch (Exception y) {
				throw new DatabaseNotReachedException(x);
			}
			return (HTableInterface)this.tablesC.getTable(name.getNameAsBytes());
		}
	}
	
	protected void returnTable(HTableInterface table) throws IOException {
		table.close();
		//this.tablesC.putTable(table);
	}

	protected boolean hasColumnFamily(MangledTableName table, String family)
			throws DatabaseNotReachedException {
		if (!this.hasTable(table))
			return false;

		HTableDescriptor td = this.tablesD.get(table);
		if (td != null && td.hasFamily(Bytes.toBytes(family)))
			return true;
		
		TableLocker lock = this.getLock(table);
		lock.sharedLockTable();
		try {
			td = this.admin.getTableDescriptor(table.getNameAsBytes());
			this.cache(table, td);
		} catch (Exception e) {
			throw new DatabaseNotReachedException(e);
		} finally {
			lock.sharedUnlockTable();
		}
		return td.hasFamily(Bytes.toBytes(family));
	}
	
	private void enforceColumnFamiliesExists(HTableDescriptor tableD, boolean freshDescriptor, Class<? extends PersistingElement> clazz, 
			Map<String, Field> columnFamilies, MangledTableName tableName, String tablePostfix) throws DatabaseNotReachedException {
		assert tableD != null;
		List<HColumnDescriptor> toBeAdded = new ArrayList<HColumnDescriptor>(columnFamilies.size());
		List<HColumnDescriptor> toBeAltered = new ArrayList<HColumnDescriptor>(columnFamilies.size());
		TableLocker lock = null;
		boolean recreated = freshDescriptor; //Whether table descriptor was just retrieved from HBase admin
		for (Entry<String, Field> cf : columnFamilies.entrySet()) {
			byte[] cfname = Bytes.toBytes(cf.getKey());
			HColumnDescriptor family = tableD.hasFamily(cfname) ? tableD.getFamily(cfname) : null;
			boolean asExpected = PropertyUtils.asExpected(this, family, clazz, cf.getValue(), tablePostfix);
			if (!recreated && !asExpected) {
				logger.fine("Table " + tableName + " is not known to have family " + cf.getKey() + " properly configured: checking from HBase");
				lock = this.getLock(tableName);
				lock.sharedLockTable();
				try {
					tableD = this.admin.getTableDescriptor(tableD.getName());
				} catch (Exception e) {
					errorLogger.log(Level.INFO, " Problem while getting descriptor for " + tableName + "; retrying", e);
					this.handleProblem(e, clazz, tableName, tablePostfix, null);
					try {
						this.getTableDescriptor(clazz, tableName, tablePostfix, columnFamilies);
					} catch (Exception x) {
						throw new DatabaseNotReachedException(x);
					}
					return;
				} finally {
					lock.sharedUnlockTable();
				}
				family = tableD.hasFamily(cfname) ? tableD.getFamily(cfname) : null;
				asExpected = PropertyUtils.asExpected(this, family, clazz, cf.getValue(), tablePostfix);
				this.cache(tableName, tableD);
				recreated = true;
			}
			if (family == null) {
				HColumnDescriptor newFamily = new HColumnDescriptor(cfname);
				PropertyUtils.setValues(this, newFamily, clazz, cf.getValue(), tablePostfix);
				toBeAdded.add(newFamily);
			} else if (!asExpected) {
				PropertyUtils.setValues(this, family, clazz, cf.getValue(), tablePostfix);
				toBeAltered.add(family);
			}
		}
		if (!toBeAdded.isEmpty() || !toBeAltered.isEmpty()) {
			try {
				if (!toBeAdded.isEmpty())
					logger.info("Table " + tableName + " is missing families " + toBeAdded.toString() + ": altering");
				if (!toBeAltered.isEmpty())
					logger.info("Table " + tableName + " has wrong properties for families " + toBeAltered.toString() + ": altering");
				if (lock == null)
					lock = getLock(tableName);
				lock.exclusiveLockTable();
				try {
					this.disableTable(clazz, tableName, tablePostfix);
					for (HColumnDescriptor hColumnDescriptor : toBeAdded) {
						try {
							this.admin.addColumn(tableD.getName(),hColumnDescriptor);
						} catch (TableNotFoundException x) {
							this.handleProblem(x, clazz, tableName, tablePostfix, null);
							this.admin.addColumn(tableD.getName(),hColumnDescriptor);
						} catch (InvalidFamilyOperationException x) {
							String msg = x.getMessage();
							if (msg != null && msg.contains("already exists")) {
								toBeAltered.add(hColumnDescriptor);
							} else
								throw x;
						}
					}
					for (HColumnDescriptor hColumnDescriptor : toBeAltered) {
						try {
							this.admin.modifyColumn(tableD.getName(), hColumnDescriptor);
						} catch (TableNotFoundException x) {
							this.handleProblem(x, clazz, tableName, tablePostfix, null);
							this.admin.modifyColumn(tableD.getName(), hColumnDescriptor);
						}
					}
					boolean done = true;
					do {
						Thread.sleep(10);
						tableD = this.admin.getTableDescriptor(tableD.getName());
						for (int i = 0; done && i < toBeAdded.size(); i++) {
							done = done && tableD.hasFamily(toBeAdded.get(i).getName());
						}
						for (int i = 0; done && i < toBeAltered.size(); ++i) {
							HColumnDescriptor expectedFamily = toBeAltered.get(i);
							HColumnDescriptor actualFamily = tableD.getFamily(expectedFamily.getName());
							done = done && actualFamily != null;
							if (done) {
								for (HColumnFamilyProperty<?> hprop : PropertyUtils.columnProperties) {
									done = done && hprop.hasValue(actualFamily, this, clazz, columnFamilies.get(expectedFamily.getNameAsString()), tablePostfix);
								}
							}
						}
					} while (!done);
					this.enableTable(clazz, tableName, tablePostfix);
					
					//Checking post-condition
					for (int i = 0; done && i < toBeAdded.size(); i++) {
						if (!tableD.hasFamily(toBeAdded.get(i).getName()))
							throw new IOException("Table " + tableName + " is still lacking familiy " + toBeAdded.get(i).getNameAsString());
					}
					for (int i = 0; done && i < toBeAltered.size(); ++i) {
						HColumnDescriptor expectedFamily = toBeAltered.get(i);
						HColumnDescriptor actualFamily = tableD.getFamily(expectedFamily.getName());
						if (actualFamily == null)
							throw new IOException("Table " + tableName + " is now lacking familiy " + expectedFamily.getNameAsString());
						HColumnFamilyProperty<?>[] problems = PropertyUtils.checkIsAsExpected(this, actualFamily, clazz, columnFamilies.get(expectedFamily.getNameAsString()), tablePostfix);
						if (problems.length > 0)
							throw new IOException("Table " + tableD.getName() + " still has wrong properties for column family " + expectedFamily.getNameAsString() + ": " + Arrays.toString(problems));
					}
					this.cache(tableName, tableD);
					logger.info("Table " + tableD.getNameAsString() + " altered");
				} finally {
					lock.exclusiveUnlockTable();
				}
			} catch (Exception e) {
				errorLogger.log(Level.SEVERE, "Could not create on table " + tableD.getNameAsString() + " families " + toBeAdded.toString(), e);
				throw new DatabaseNotReachedException(e);
			}

		}
	}
	
	protected Map<String, Field> toMap(String family, Field field) {
		if (family == null)
			return null;
		
		Map<String, Field> ret = new TreeMap<String, Field>();
		ret.put(family, field == null ? null : (family.equals(field.getName()) ? field : null));
		return ret;
	}
	
	protected Map<String, Field> toMap(Set<String> families, MetaInformation meta) {
		return this.toMap(families, meta == null ? null : meta.getFamilies());
	}
	
	protected Map<String, Field> toMap(Set<String> families, Map<String, Field> fields) {
		if (families == null)
			return null;
		
		if (fields != null && families.equals(fields.keySet()))
			return fields;
		
		Map<String, Field> ret = new TreeMap<String, Field>();
		
		for (String family : families) {
			ret.put(family, null);
		}
		
		if (fields != null) {
			for (Entry<String, Field> field : fields.entrySet()) {
				if (ret.containsKey(field.getKey())) {
					ret.put(field.getKey(), field.getValue());
				}
			}
		}
		
		return ret;
	}
	
	////////////////////////////////////////////////////////////////////
	// Actual implementation methods
	////////////////////////////////////////////////////////////////////

	private Filter addFilter(Filter f1, Filter f2) {
		if (f2 == null) {
			return f1;
		} else if (f1 == null) {
			return f2;
		} else if (f1 instanceof FilterList) {
			((FilterList) f1).addFilter(f2);
			return f1;
		} else {
			FilterList list = new FilterList();
			list.addFilter(f1);
			list.addFilter(f2);
			return list;
		}
	}

	protected Filter createFamilyConstraint(Constraint c) {
		Filter f = null;
		if (c.getStartKey() != null)
			f = new QualifierFilter(CompareOp.GREATER_OR_EQUAL,
					new BinaryComparator(Bytes.toBytes(c.getStartKey())));
		if (c.getEndKey() != null)
			f = this.addFilter(f, new QualifierFilter(CompareOp.LESS_OR_EQUAL,
					new BinaryComparator(Bytes.toBytes(c.getEndKey()))));
		return f;
	}

	protected Scan getScan(Constraint c, Class<? extends PersistingElement> clazz, Map<String, Field> families) throws DatabaseNotReachedException {
		Scan s = new Scan();
		
		//Getting scan caching:
		Integer caching = null;
		//Grabbing the lowest values for all column families
		if (families != null)
			for (Field fam : families.values()) {
				if (fam != null) {
					HBaseSchema ann = fam.getAnnotation(HBaseSchema.class);
					if (ann != null) {
						int famCaching = ann.scanCaching();
						if (famCaching > 0 && (caching == null || famCaching < caching))
							caching = famCaching;
					}
				}
			}
		//If not found, looking for the class value
		if (caching == null && clazz != null) {
			HBaseSchema ann = clazz.getAnnotation(HBaseSchema.class);
			if (ann != null && ann.scanCaching() > 0)
				caching = ann.scanCaching();
		}
		//If not found, looking for this store value
		if (caching == null)
			caching = this.getScanCaching();
		//If there exists one, setting it
		if (caching != null)
			s.setCaching(caching);
		
		if (c != null && c.getStartKey() != null)
			s.setStartRow(Bytes.toBytes(c.getStartKey()));
		if (c != null && c.getEndKey() != null) {
			byte[] endb = Bytes.toBytes(c.getEndKey());
			endb = Bytes.add(endb, new byte[] { 0 });
			s.setStopRow(endb);
		}
		
		if (families != null) {
			for (String fam : families.keySet()) {
				s.addFamily(Bytes.toBytes(fam));
			}
		} else {
			//No family to load ; avoid getting all information in the row (that may be big)
			s.setFilter(this.addFilter(new FirstKeyOnlyFilter(), new KeyOnlyFilter()));
		}
		
		return s;
	}

	@Override
	public ColumnFamilyData get(MetaInformation meta, String tableName, String id,
			Set<String> families) throws DatabaseNotReachedException {
		MangledTableName table = new MangledTableName(tableName);
		if (!this.hasTable(table))
			return null;
		
		Map<String, Field> cf = toMap(families, meta);
		
		Get g = new Get(Bytes.toBytes(id));
		for (String family : families) {
			g.addFamily(Bytes.toBytes(family));
		}

		Result r = this.tryPerform(new GetAction(g), meta == null ? null : meta.getClazz(), table, meta == null ? null : meta.getTablePostfix(), cf);
		if (r.isEmpty())
			return null;
		
		ColumnFamilyData ret = new DefaultColumnFamilyData();
		for (KeyValue kv : r.list()) {
			String familyName = Bytes.toString(kv.getFamily());
			Map<String, byte[]> fam = ret.get(familyName);
			if (fam == null) {
				fam = new TreeMap<String, byte[]>();
				ret.put(familyName, fam);
			}
			fam.put(Bytes.toString(kv.getQualifier()),
					kv.getValue());
		}
		return ret;
	}
	
	/**
	 * Waits until an element can be updated (stored/deleted) again.
	 * Objects should be marked using {@link #tagUpdate(MetaInformation, long)}
	 * each time they are updated.
	 * @return timestamp to be used within next {@link #tagUpdate(MetaInformation, long)}
	 */
	private void waitForNewUpdate(MetaInformation meta, MangledTableName table, String row) {
		if (meta == null)
			return;
		PersistingElement pe = meta.getElement();
		assert pe != null;
		ThreadLocal<Long> npu = (ThreadLocal<Long>)pe.getAdditionalProperty("HBaseNextPossibleUpdateInTable"+table.getName());
		if (npu == null || npu.get() == null)
			return;
		long now;
		while ((now = System.currentTimeMillis()) < npu.get()) {
			try {
				Thread.sleep(npu.get()-now);
			} catch (InterruptedException e) {
			}
		}
	}
	
	/**
	 * Marks necessary additional information
	 * to use {@link #waitForNewUpdate(MetaInformation)}.
	 */
	private void tagUpdate(MetaInformation meta, MangledTableName table, String row) {
		if (meta == null)
			return;
		PersistingElement pe = meta.getElement();
		assert pe != null;
		// HBase uses timestamps ; next possible update for this element is next timestamp...
		// We rely here on meta as it is important to preserve sequentiality for a given
		// persisting element, but not for different one (even if targeting the same row)
		// as they are certainly not in the same threads
		// Corrects com.googlecode.n_orm.ImportExportTest
		String key = "HBaseNextPossibleUpdateInTable"+table.getName();
		ThreadLocal<Long> npu = (ThreadLocal<Long>)pe.getAdditionalProperty(key);
		if (npu == null) {
			npu = (ThreadLocal<Long>)pe.addAdditionalProperty(key, new ThreadLocal<Long>(), true);
		}
		if (npu == null) // Ugh, looks like we are inside a test and pe was mocked
			return;
		assert pe.getAdditionalProperty(key) == npu;
		npu.set(System.currentTimeMillis()+1);
	}

	@Override
	public void storeChanges(MetaInformation meta, String tableName, String id,
			ColumnFamilyData changed,
			Map<String, Set<String>> removed,
			Map<String, Map<String, Number>> increments)
			throws DatabaseNotReachedException {
		//Grabbing all involved families
		Set<String> families = new HashSet<String>();
		if (changed !=null) families.addAll(changed.keySet());
		if (removed != null) families.addAll(removed.keySet());
		if (increments != null) families.addAll(increments.keySet());
		
		//In HBase, an element only exists when there is a value for its column family
		//Storing (see later) an empty value with an empty qualifier within the properties family
		//Thus the properties family is always involved
		families.add(PropertyManagement.PROPERTY_COLUMNFAMILY_NAME);
		Map<String, Field> fams = this.toMap(families, meta);

		MangledTableName table = new MangledTableName(tableName);
		HTableInterface t = this.getTable(meta == null ? null : meta.getClazz(), table, meta == null ? null : meta.getTablePostfix(), fams);

		try {
			byte[] row = Bytes.toBytes(id);
			
			List<org.apache.hadoop.hbase.client.Row> actions = new ArrayList<org.apache.hadoop.hbase.client.Row>(2); //At most one put and one delete
			
			this.waitForNewUpdate(meta, table, id);
	
			//Transforming changes into a big Put (if necessary)
			//and registering it as an action to be performed
			Put rowPut = null;
			if (changed != null && !changed.isEmpty()) {
				rowPut = new Put(row);
				for (Entry<String, Map<String, byte[]>> family : changed.entrySet()) {
					byte[] cf = Bytes.toBytes(family.getKey());
					for (Entry<String, byte[]> col : family.getValue().entrySet()) {
						rowPut.add(cf, Bytes.toBytes(col.getKey()), col.getValue());
					}
				}
				if (rowPut.getFamilyMap().isEmpty())
					rowPut = null;
				else
					actions.add(rowPut);
			}

			//Transforming deletes into a big Delete (if necessary)
			//and registering it as an action to be performed
			Delete rowDel = null;
			if (removed != null && !removed.isEmpty()) {
				rowDel = new Delete(row);
				for (Entry<String, Set<String>> family : removed.entrySet()) {
					byte[] cf = Bytes.toBytes(family.getKey());
					for (String key : family.getValue()) {
						rowDel.deleteColumns(cf, Bytes.toBytes(key));
					}
	
				}
				if (rowDel.getFamilyMap().isEmpty())
					rowDel = null;
				else
					actions.add(rowDel);
			}

			//Transforming changes into a big Put (if necessary)
			//but can't register it as an action to be performed (according to HBase API)
			Increment rowInc = null;
			if (increments != null && !increments.isEmpty()) {
				rowInc = new Increment(row);
				for (Entry<String, Map<String, Number>> incrs : increments.entrySet()) {
					byte[] cf = Bytes.toBytes(incrs.getKey());
					for (Entry<String, Number> inc : incrs.getValue().entrySet()) {
						rowInc.addColumn(cf, Bytes.toBytes(inc.getKey()), inc.getValue().longValue());
					}
				}
				if (rowInc.getFamilyMap().isEmpty())
					rowInc = null;
				//Can't add that to actions :(
			}
	
			//An empty object is to be stored...
			//Adding a dummy value into properties family
			if (rowPut == null && rowInc == null) { //NOT rowDel == null; deleting an element that becomes empty actually deletes the element !
				rowPut = new Put(row);
				rowPut.add(Bytes.toBytes(PropertyManagement.PROPERTY_COLUMNFAMILY_NAME), null, new byte[]{});
				actions.add(rowPut);
			}
			
			// Checking for WAL policy
			if (rowPut != null) { // Only Puts can override WAL policy in 0.90
				HBaseSchema.WALWritePolicy useWal = null;
				HBaseSchema clazzAnnotation = meta == null || meta.getClazz() == null ? null : meta.getClazz().getAnnotation(HBaseSchema.class);
				HBaseSchema.WALWritePolicy clazzWAL = clazzAnnotation == null ? HBaseSchema.WALWritePolicy.UNSET : clazzAnnotation.writeToWAL();
				for(byte[] famB : rowPut.getFamilyMap().keySet()) {
					String famS = Bytes.toString(famB);
					HBaseSchema.WALWritePolicy wtw;
					if (PropertyManagement.PROPERTY_COLUMNFAMILY_NAME.equals(famS)) {
						// Properties use schema for classes
						wtw = clazzWAL;
					} else {
						HBaseSchema schema;
						Field fam = fams.get(famS);
						if (fam != null) {
							schema = fam.getAnnotation(HBaseSchema.class);
						} else {
							schema = null;
						}
						// No explicit schema mean schema for class
						if (schema == null) {
							wtw = clazzWAL;
						} else {
							wtw = schema.writeToWAL();
							if (HBaseSchema.WALWritePolicy.UNSET.equals(wtw)) {
								wtw = clazzWAL;
							}
						}
					}
					// Grabbing strongest policy for the store
					if (useWal == null || wtw.strongerThan(useWal)) {
						useWal = wtw;
					}
				}
				
				if (useWal == null) {
					useWal = HBaseSchema.WALWritePolicy.UNSET;
				}
				
				switch(useWal) {
				case UNSET:
					break;
				case SKIP:
					rowPut.setWriteToWAL(false);
					break;
				default:
					rowPut.setWriteToWAL(true);
					break;
				}
			}
			
			Action<?> act;
			//Running puts and deletes
			if (! actions.isEmpty()) {
				act = new BatchAction(actions);
				this.tryPerform(act, t, meta == null ? null : meta.getClazz(), meta == null ? null : meta.getTablePostfix(), fams);
				t = act.getTable();
			}
			//Running increments
			if (rowInc != null) {
				act = new IncrementAction(rowInc);
				this.tryPerform(act, t, meta == null ? null : meta.getClazz(), meta == null ? null : meta.getTablePostfix(), fams);
				t = act.getTable();
			}
			
			this.tagUpdate(meta, table, id);
		} finally {
			if (t != null)
				try {
					this.returnTable(t);
				} catch (IOException e) {
					throw new DatabaseNotReachedException(e);
				}
		}
	}

	@Override
	public void delete(MetaInformation meta, String tableName, String id)
			throws DatabaseNotReachedException {
		MangledTableName table = new MangledTableName(tableName);
		if (!this.hasTable(table))
			return;
		this.waitForNewUpdate(meta, table, id);
		Delete d = new Delete(Bytes.toBytes(id));
		this.tryPerform(new DeleteAction(d), meta == null ? null : meta.getClazz(), table, meta == null ? null : meta.getTablePostfix(), null);
		this.tagUpdate(meta, table, id);
	}

	@Override
	public boolean exists(MetaInformation meta, String tableName, String row, String family)
			throws DatabaseNotReachedException {
		MangledTableName table = new MangledTableName(tableName);
		if (!this.hasColumnFamily(table, family))
			return false;

		Get g = new Get(Bytes.toBytes(row)).addFamily(Bytes.toBytes(family));
		g.setFilter(this.addFilter(new FirstKeyOnlyFilter(), new KeyOnlyFilter()));
		return this.tryPerform(new ExistsAction(g), meta == null ? null : meta.getClazz(), table, meta == null ? null : meta.getTablePostfix(), null);
	}

	@Override
	public boolean exists(MetaInformation meta, String tableName, String row)
			throws DatabaseNotReachedException {
		MangledTableName table = new MangledTableName(tableName);
		if (!this.hasTable(table))
			return false;

		Get g = new Get(Bytes.toBytes(row));
		g.setFilter(this.addFilter(new FirstKeyOnlyFilter(), new KeyOnlyFilter()));
		return this.tryPerform(new ExistsAction(g), meta == null ? null : meta.getClazz(), table, meta == null ? null : meta.getTablePostfix(), null);
	}

	@Override
	public byte[] get(MetaInformation meta, String tableName, String row, String family, String key)
			throws DatabaseNotReachedException {
		MangledTableName table = new MangledTableName(tableName);
		if (! this.hasColumnFamily(table, family))
			return null;

		Get g = new Get(Bytes.toBytes(row)).addColumn(Bytes.toBytes(family),
				Bytes.toBytes(key));

		Result result = this.tryPerform(new GetAction(g), meta == null ? null : meta.getClazz(), table, meta == null ? null : meta.getTablePostfix(), this.toMap(family, meta == null ? null : meta.getProperty()));
		
		if (result.isEmpty())
			return null;
		return result.value();
	}

	@Override
	public Map<String, byte[]> get(MetaInformation meta,
			String table, String id, String family)
			throws DatabaseNotReachedException {
		return this.get(meta, table, id, family, (Constraint) null);
	}

	@Override
	public Map<String, byte[]> get(MetaInformation meta,
			String tableName, String id, String family,
			Constraint c) throws DatabaseNotReachedException {
		MangledTableName table = new MangledTableName(tableName);
		if (!this.hasTable(table))
			return null;

		Get g = new Get(Bytes.toBytes(id)).addFamily(Bytes.toBytes(family));

		if (c != null) {
			g.setFilter(createFamilyConstraint(c));
		}

		Result r = this.tryPerform(new GetAction(g), meta == null ? null : meta.getClazz(), table, meta == null ? null : meta.getTablePostfix(), toMap(family, meta == null ? null : meta.getProperty()));
		if (r.isEmpty())
			return null;
		
		Map<String, byte[]> ret = new HashMap<String, byte[]>();
		if (!r.isEmpty()) {
			for (KeyValue kv : r.raw()) {
				ret.put(Bytes.toString(kv.getQualifier()), kv.getValue());
			}
		}
		return ret;
	}

	@Override
	public long count(MetaInformation meta, String tableName, Constraint c) throws DatabaseNotReachedException {
		MangledTableName table = new MangledTableName(tableName);
		if (! this.hasTable(table))
			return 0;
		
		Class<? extends PersistingElement> type = meta == null ? null : meta.getClazz();
		return this.tryPerform(new CountAction(this, this.getScan(c, type, null)), type, table, meta == null ? null : meta.getTablePostfix(), null);
	}

	@Override
	public com.googlecode.n_orm.storeapi.CloseableKeyIterator get(MetaInformation meta, String tableName, Constraint c,
			 int limit, Set<String> families) throws DatabaseNotReachedException {
		return this.get(meta, new MangledTableName(tableName), c, limit, families);
	}


	public com.googlecode.n_orm.storeapi.CloseableKeyIterator get(MetaInformation meta, MangledTableName table, Constraint c,
			 int limit, Set<String> families) throws DatabaseNotReachedException {
		if (!this.hasTable(table))
			return new EmptyCloseableIterator();
		
		Map<String, Field> cf = toMap(families, meta);
		
		Class<? extends PersistingElement> clazz = meta == null ? null : meta.getClazz();
		Scan s = this.getScan(c, clazz, cf);
		int cacheSize = s.getCaching();
		if (cacheSize > limit)
			s.setCaching(limit);
		
		String tablePostfix = meta == null ? null : meta.getTablePostfix();
		ResultScanner r = this.tryPerform(new ScanAction(s), clazz, table, tablePostfix, cf);
		return new CloseableIterator(this, clazz, table, tablePostfix, c, limit, cf, r, cf != null);
	}

	public void truncate(MetaInformation meta, String tableName, Constraint c) throws DatabaseNotReachedException {
		MangledTableName table = new MangledTableName(tableName);
		if (!this.hasTable(table))
			return;
		
		logger.info("Truncating table " + table);
		
		TruncateAction action = new TruncateAction(this, this.getScan(c, meta == null ? null : meta.getClazz(), null));
		this.tryPerform(action, meta == null ? null : meta.getClazz(), table, meta == null ? null : meta.getTablePostfix(), null);
		
		logger.info("Truncated table " + table);
	}

	@Override
	public <AE extends PersistingElement, E extends AE> void process(MetaInformation meta,
			final String tableName, Constraint c, Set<String> families, Class<E> elementClass,
			Process<AE> action, final Callback callback)
			throws DatabaseNotReachedException {
		final MangledTableName table = new MangledTableName(tableName);
		if (! this.hasTable(table)) {
			if (callback != null)
				callback.processCompleted();
			return;
		}
		Map<String, Field> cf = toMap(families, meta);
		final Class<?> actionClass = action.getClass();
		try {
			//Checking that cf are all there so that process will work
			this.getTableDescriptor(elementClass, table, meta == null ? null : meta.getTablePostfix(), cf);
			final Job job = ActionJob.createSubmittableJob(this, table, this.getScan(c, elementClass, cf), action, elementClass, families.toArray(new String[families.size()]));
			logger.log(Level.FINE, "Runing server-side process " + actionClass.getName() + " on table " + table + " with id " + job.hashCode());
			if (callback != null) {
				new Thread() {

					@Override
					public void run() {
						try {
							if (job.waitForCompletion(false)) {
								callback.processCompleted();
							} else {
								throw new RuntimeException("Unknown reason");
							}
						} catch (Throwable x) {
							errorLogger.log(Level.WARNING, "Could not perform server-side process " + actionClass.getName() + " on table " + table, x);
							if (callback != null) {
								callback.processCompletedInError(x);
							}
						}
					}
					
				}.start();
			} else
				job.submit();

			logger.log(Level.FINE, "Server-side process " + actionClass.getName() + " on table " + table + " with id " + job.hashCode() + " done !");
		} catch (Throwable x) {
			errorLogger.log(Level.WARNING, "Could not perform server-side process " + actionClass.getName() + " on table " + table, x);
			if (callback != null) {
				callback.processCompletedInError(x);
			}
		}
	}

}
