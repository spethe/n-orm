package com.mt.storage;

import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;

import static org.junit.Assert.*;

import com.mt.storage.memory.Memory;

public class StoreTestLauncher {
	private static final Collection<Object[]> testedStores;
	
	static {
		testedStores = new ArrayList<Object[]>();
		Properties p;
		
		//Memory
		p = new Properties();
		p.setProperty(StoreSelector.STORE_DRIVERCLASS_PROPERTY, Memory.class.getName());
		p.setProperty(StoreSelector.STORE_DRIVERCLASS_SINGLETON_PROPERTY, "INSTANCE");
		testedStores.add(new Object[]{p});
		
		//HBase
		p = new Properties();
		p.setProperty(StoreSelector.STORE_DRIVERCLASS_PROPERTY, com.mt.storage.hbase.Store.class.getName());
		p.setProperty(StoreSelector.STORE_DRIVERCLASS_STATIC_ACCESSOR, "getStore");
		String hbaseHost = "localhost";
		int hbasePort = 9000;
		int hbaseMaxRetries = 2;
		p.setProperty("1", hbaseHost);
		p.setProperty("2", Integer.toString(hbasePort));
		p.setProperty("3", Integer.toString(hbaseMaxRetries));
		testedStores.add(new Object[]{p});
		
		//Starting HBase server
		try {
			HBaseTestingUtility hBaseTestingUtility = new HBaseTestingUtility();
			hBaseTestingUtility.startMiniCluster(1);
			
			URI phost = null;
			String pshost = hBaseTestingUtility.getConfiguration().get("hbase.host");
			if (pshost != null) {
				try {
					phost = new URI(pshost);
					p.setProperty("1", phost.getHost());
					p.setProperty("2", Integer.toString(phost.getPort()));
				} catch (URISyntaxException e) {
				}
			}
		} catch (Exception e) {
		}
	}
	
	public static Collection<Object[]> getTestedStores() {
		return new ArrayList<Object[]>(testedStores);
	}
	
	@SuppressWarnings("unchecked")
	public static void registerStorePropertiesForInnerClasses(Class<?> clazz, Properties props) {
		for (Class<?> c : clazz.getDeclaredClasses()) {
			if (PersistingElement.class.isAssignableFrom(c))
				StoreSelector.aspectOf().setPropertiesFor((Class<? extends PersistingElement>) c, props);
		}
	}

	private boolean isMemory;
	
	protected StoreTestLauncher(Properties props) {
		StoreTestLauncher.registerStorePropertiesForInnerClasses(this.getClass(), props);
		this.isMemory = props.get(StoreSelector.STORE_DRIVERCLASS_PROPERTY).equals(Memory.class.getName());
	}
	
	public void assertHadAQuery() {
		if (this.isMemory)
			assertTrue(Memory.INSTANCE.hadAQuery());
	}
	
	public void assertHadNoQuery() {
		if (this.isMemory)
			assertTrue(Memory.INSTANCE.hadNoQuery());
	}
	
	public void resetQueryCount() {
		if (this.isMemory)
			Memory.INSTANCE.resetQueries();
	}
}
