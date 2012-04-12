package com.google.code.twig.standard;

import java.util.Collection;
import java.util.Map;

import com.google.appengine.api.datastore.Key;

public class StandardUntypedMultipleLoadCommand extends StandardDecodeCommand<StandardUntypedMultipleLoadCommand>
{
	private final Collection<Key> keys;

	StandardUntypedMultipleLoadCommand(TranslatorObjectDatastore datastore, Collection<Key> keys, int depth)
	{
		super(datastore, depth);
		this.keys = keys;
	}

	public Map<Key, Object> now()
	{
		return keysToInstances(keys, null);
	}

}
