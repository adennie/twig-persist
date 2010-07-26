package com.vercer.engine.persist;

import java.util.Arrays;
import java.util.Map;

import junit.framework.Assert;

import org.junit.Test;

import com.vercer.engine.persist.annotation.AnnotationObjectDatastore;
import com.vercer.engine.persist.annotation.Id;

public class LoadMultiple extends LocalDatastoreTestCase
{
	static class Item
	{
		@Id long id;
		String name;
		@SuppressWarnings("unused")
		private Item () {};
		public Item(String name)
		{
			this.name = name;
		}
	}
	
	@Test
	public void test()
	{
		AnnotationObjectDatastore datastore = new AnnotationObjectDatastore();
		Item hello = new Item("hello");
		Item world = new Item("world");
		
		datastore.storeAll(Arrays.asList(hello, world));
		
		datastore.disassociateAll();
		
		Map<Long, Item> results = datastore.load().type(Item.class).ids(hello.id, world.id).returnResultsNow();
		
		// show that we loaded both
		Assert.assertEquals(2, results.size());
		
		// show that new instance was created
		Assert.assertNotSame(hello, results.get(hello.id));
	}
}