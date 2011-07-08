package com.googlecode.n_orm.hbase;

 import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.BeforeClass;
import org.junit.Test;

import com.googlecode.n_orm.hbase.HBaseLauncher;
import com.googlecode.n_orm.hbase.Store;
import com.googlecode.n_orm.storeapi.Constraint;


public class ConcurrentTest {
	private static Store store1, store2;

	@BeforeClass
	public static void gettingSimilarStores() {
		HBaseLauncher.prepareHBase();
		store1 = HBaseLauncher.hbaseStore;
		HBaseLauncher.hbaseStore = null;
		HBaseLauncher.hBaseServer = null;
		HBaseLauncher.hbaseMaxRetries++;
		HBaseLauncher.prepareHBase();
		store2 = HBaseLauncher.hbaseStore;
	}
	
	@Test
	public void storesAreDifferent() {
		//Just to know if the test setup is well written...
		assertNotNull(store1);
		assertNotNull(store2);
		assertNotSame(store1, store2);
	}
	
	private void deleteTable(String table) throws IOException {

		if (store1.getAdmin().tableExists(table)) {
			store1.getAdmin().disableTable(table);
			store1.getAdmin().deleteTable(table);
		}
	}
	
	private void disableTable(String table) throws IOException {

		if (store1.getAdmin().tableExists(table)) {
			store1.getAdmin().disableTable(table);
		}
	}
	
	private void truncateTable(String table) throws IOException {

		if (store1.getAdmin().tableExists(table)) {
			store1.truncate(table, (Constraint)null);
			assertEquals(0, store1.count(table, (Constraint)null));
		}
	}
	
	@Test
	public void gettingEmptyObjectAndGetItFromBothStores() throws IOException {
		this.truncateTable("t1");
		
		store1.storeChanges("t1", "idt1", null, null, null);
		assertTrue(store2.exists("t1", "idt1"));
		assertTrue(store1.exists("t1", "idt1"));
	}
	
	@Test
	public void acceptingOutsideTableRemoval() throws IOException {
		this.deleteTable("t1");
		
		Map<String, Map<String, byte[]>> change1 = new TreeMap<String, Map<String,byte[]>>();
		TreeMap<String, byte[]> ch1 = new TreeMap<String, byte[]>();
		change1.put("cf1", ch1);
		ch1.put("k1", new byte[]{1, 2});
		store1.storeChanges("t1", "idt1", change1 , null, null); //Table should be created
		store2.storeChanges("t1", "idt1", change1 , null, null); //Table should be discovered
		assertTrue(store2.exists("t1", "idt1", "cf1"));
		
		this.deleteTable("t1");
		
		store1.storeChanges("t1", "idt1", change1 , null, null); //Table should be re-discovered
		store2.storeChanges("t1", "idt1", change1 , null, null); //Table should be re-discovered
		assertTrue(store2.exists("t1", "idt1", "cf1")); 
	}
	
	@Test
	public void acceptingOutsideTableDisable() throws IOException {
		this.deleteTable("t1");
		
		Map<String, Map<String, byte[]>> change1 = new TreeMap<String, Map<String,byte[]>>();
		TreeMap<String, byte[]> ch1 = new TreeMap<String, byte[]>();
		change1.put("cf1", ch1);
		ch1.put("k1", new byte[]{1, 2});
		store1.storeChanges("t1", "idt1", change1 , null, null); //Table should be created
		store2.storeChanges("t1", "idt1", change1 , null, null); //Table should be discovered
		assertTrue(store2.exists("t1", "idt1", "cf1"));
		
		this.disableTable("t1");
		
		store1.storeChanges("t1", "idt1", change1 , null, null); //Table should be re-discovered
		store2.storeChanges("t1", "idt1", change1 , null, null); //Table should be re-discovered
		assertTrue(store2.exists("t1", "idt1", "cf1")); 
	}
	
	@Test(expected=Test.None.class)
	public void acceptingConnectionTimeout() throws IOException, SecurityException, NoSuchMethodException, IllegalArgumentException, IllegalAccessException, InvocationTargetException {
		
		Map<String, Map<String, byte[]>> change1 = new TreeMap<String, Map<String,byte[]>>();
		TreeMap<String, byte[]> ch1 = new TreeMap<String, byte[]>();
		change1.put("cf1", ch1);
		ch1.put("k1", new byte[]{1, 2});
		
		HConnection cm = store1.getAdmin().getConnection();
		Method closeM = cm.getClass().getDeclaredMethod("close", boolean.class);
		closeM.setAccessible(true);
		closeM.invoke(cm, true);
		
		store1.storeChanges("t1", "idt1", change1 , null, null);
	}
	
	@Test
	public void acceptingOutsideColumnFamilyAddition() throws IOException {

		this.deleteTable("t1");
		
		Map<String, Map<String, byte[]>> change1 = new TreeMap<String, Map<String,byte[]>>();
		TreeMap<String, byte[]> ch1 = new TreeMap<String, byte[]>();
		change1.put("cf1", ch1);
		ch1.put("k1", new byte[]{1, 2});
		store1.storeChanges("t1", "idt1", change1 , null, null);
		store2.storeChanges("t1", "idt1", change1 , null, null);
		
		Map<String, Map<String, byte[]>> change2 = new TreeMap<String, Map<String,byte[]>>();
		TreeMap<String, byte[]> ch2 = new TreeMap<String, byte[]>();
		change2.put("cf2", ch2);
		ch2.put("k1", new byte[]{1, 2});
		
		store1.storeChanges("t1", "idt1", change2 , null, null); //CF cf2 should be added to table
		store2.storeChanges("t1", "idt1", change2 , null, null); //CF cf2 should be discovered as added to table
		assertTrue(store2.exists("t1", "idt1", "cf2"));
	}
	
	@Test(timeout=60000)
	public void acceptingOutsideColumnFamilyRemoval() throws IOException, InterruptedException {
		
		Map<String, Map<String, byte[]>> change1 = new TreeMap<String, Map<String,byte[]>>();
		TreeMap<String, byte[]> ch1 = new TreeMap<String, byte[]>();
		change1.put("cf1", ch1);
		ch1.put("k1", new byte[]{1, 2});
		store1.storeChanges("t1", "idt1", change1 , null, null);
		
		byte[] tblNameBytes = Bytes.toBytes("t1");
		HTableDescriptor td = store1.getAdmin().getTableDescriptor(tblNameBytes);
		td.removeFamily(Bytes.toBytes("cf1"));
		store1.getAdmin().disableTable(tblNameBytes);
		store1.getAdmin().modifyTable(tblNameBytes, td);
		store1.getAdmin().enableTable(tblNameBytes);
		synchronized(this) {
			do {
				this.wait(500);
			} while (store1.getAdmin().getTableDescriptor(tblNameBytes).hasFamily(Bytes.toBytes("cf1")));
		}
		
		Map<String, Map<String, byte[]>> change2 = new TreeMap<String, Map<String,byte[]>>();
		TreeMap<String, byte[]> ch2 = new TreeMap<String, byte[]>();
		change2.put("cf1", ch2);
		ch2.put("k1", new byte[]{1, 2, 3});
		
		store1.storeChanges("t1", "idt1", change2 , null, null); //CF cf2 should be added again to table
		assertTrue(store1.exists("t1", "idt1", "cf1"));
	}
}