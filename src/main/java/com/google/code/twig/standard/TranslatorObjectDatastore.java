package com.google.code.twig.standard;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.QueryResultIterator;
import com.google.code.twig.Path;
import com.google.code.twig.Property;
import com.google.code.twig.PropertyTranslator;
import com.google.code.twig.configuration.Configuration;
import com.google.code.twig.conversion.CombinedConverter;
import com.google.code.twig.conversion.TypeConverter;
import com.google.code.twig.translator.ChainedTranslator;
import com.google.code.twig.translator.ListTranslator;
import com.google.code.twig.translator.MapTranslator;
import com.google.code.twig.translator.FieldTranslator;
import com.google.code.twig.translator.PolymorphicTranslator;
import com.google.code.twig.util.EntityToKeyFunction;
import com.google.code.twig.util.Reflection;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;

/**
 * Stateful layer responsible for caching key-object references and creating a
 * PropertyTranslator that can be configured using Strategy instances.
 * 
 * @author John Patterson <john@vercer.com>
 */
public abstract class TranslatorObjectDatastore extends BaseObjectDatastore
{
	// keeps track of which instances are associated with which keys
	protected final InstanceKeyCache keyCache;
	
	// ensure only used be a single thread
	protected Thread thread;
	
	// are properties indexed by default
	protected boolean indexed;

	// key details are updated as the current instance is encoded
	protected KeySpecification encodeKeySpec;

	// the key of the currently decoding entity
	protected Key decodeKey;

	// current activation depth
	int activationDepth = Integer.MAX_VALUE;
	
	// translators are selected for particular fields by the configuration
	private final PropertyTranslator configurationFieldTranslator;
	private final PropertyTranslator embedTranslator;
	private final PropertyTranslator polymorphicComponentTranslator;
	private final PropertyTranslator parentTranslator;
	private final PropertyTranslator independantTranslator;
	private final PropertyTranslator idFieldTranslator;
	private final PropertyTranslator childTranslator;
	private final ChainedTranslator valueTranslatorChain;
	private final PropertyTranslator keyFieldTranslator;
	private final PropertyTranslator defaultTranslator;

	private static final Map<Class<?>, Field> idFields = new ConcurrentHashMap<Class<?>, Field>();
	private static final Map<Class<?>, Field> keyFields = new ConcurrentHashMap<Class<?>, Field>();

	// indicates we are only associating instances so do not store them
	boolean associating;

	// set when all entities should be collected and stored in one call
	Map<Object, Entity> batched;

	Map<String, Iterator<Key>> allocatedIdRanges;
	
	// set when an instance is to be refreshed rather than instantiated
	private Object refresh;

	private final CombinedConverter converter;

	private final Configuration configuration;


	public TranslatorObjectDatastore(Configuration configuration)
	{
		// retry non-transactional puts 3 times
		super(5);
		
		this.configuration = configuration;
		this.converter = createTypeConverter();
		this.thread = Thread.currentThread();
		
		// top level translator which examines object field values
		configurationFieldTranslator = new ConfigurationFieldTranslator(converter);

		// simple values encoded as a single property
		valueTranslatorChain = createValueTranslatorChain();

		// referenced instances stored as separate entities
		parentTranslator = new ParentRelationTranslator(this);
		childTranslator = new ChildRelationTranslator(this);
		independantTranslator = new RelationTranslator(this);

		// @Id and @GaeKey fields
		keyFieldTranslator = new KeyTranslator(this);
		idFieldTranslator = new IdFieldTranslator(this, valueTranslatorChain, converter);

		// embed a field value in the current entity
		embedTranslator = new ListTranslator(new MapTranslator(configurationFieldTranslator, converter));

		// similar to embedding but stores a class discriminator
		polymorphicComponentTranslator = new ListTranslator(
				new MapTranslator(
						new PolymorphicTranslator(
								new ChainedTranslator(valueTranslatorChain, configurationFieldTranslator), 
								configuration), 
						converter));

		// the translator to use with unconfigured field values
		defaultTranslator = new ListTranslator(
				new MapTranslator(
						new ChainedTranslator(valueTranslatorChain, getFallbackTranslator()),
						converter));

		keyCache = createKeyCache();
	}

	@Override
	public StandardFindCommand find()
	{
		return new StandardFindCommand(this);
	}

	@Override
	public StandardStoreCommand store()
	{
		return new StandardStoreCommand(this);
	}

	@Override
	public StandardLoadCommand load()
	{
		return new StandardLoadCommand(this);
	}

	@Override
	public final Key store(Object instance)
	{
		assert instance != null;
		return store().instance(instance).now();
	}

	@Override
	public final Key store(Object instance, long id)
	{
		assert instance != null;
		assert id > 0;
		return store().instance(instance).id(id).now();
	}

	@Override
	public final Key store(Object instance, String id)
	{
		assert instance != null;
		assert id != null;
		assert id.length() > 0;
		return store().instance(instance).id(id).now();
	}

	@Override
	public final <T> Map<T, Key> storeAll(Collection<? extends T> instances)
	{
		return store().instances(instances).now();
	}

	// TODO hook these up!
//	 protected void onAfterStore(Object instance, Key key)
//	 {
//	 }
//	
//	 protected void onBeforeStore(Object instance)
//	 {
//	 }
//	 
//	 protected void onBeforeLoad(Key key)
//	 {
//	 }
//	 
//	 protected void onAfterLoad(Key key, Object instance)
//	 {
//	 }

	public final <T> QueryResultIterator<T> find(Class<? extends T> type)
	{
		return find().type(type).now();
	}

	public final <T> QueryResultIterator<T> find(Class<? extends T> type, String field, Object value)
	{
		return find().type(type).addFilter(field, FilterOperator.EQUAL, value).now();
	}

	@Override
	public <T> T load(Key key)
	{
		@SuppressWarnings("unchecked")
		T result = (T) load().key(key).now();
		return result;
	}

	@Override
	public final <T> T load(Class<? extends T> type, Object id)
	{
		return load().type(type).id(id).now();
	}

	@Override
	public final <I, T> Map<I, T> loadAll(Class<? extends T> type, Collection<? extends I> ids)
	{
		return load().type(type).ids(ids).now();
	}

	@Override
	public final void update(Object instance)
	{
		assert instance != null;
		
		// store but set the internal update flag so
		store().update(true).instance(instance).now();
	}

	@Override
	public void updateAll(Collection<?> instances)
	{
		assert instances != null;
		
		store().update(true).instances(instances).now();
	}

	@Override
	public final void storeOrUpdate(Object instance)
	{
		assert instance != null;
		if (associatedKey(instance) != null)
		{
			update(instance);
		}
		else
		{
			store(instance);
		}
	}

	@Override
	public final void delete(Object instance)
	{
		assert instance != null;
		Key key = keyCache.getKey(instance);
		if (key == null)
		{
			throw new IllegalArgumentException("Instance " + instance + " is not associated");
		}
		deleteKeys(Collections.singleton(key));
	}

	@Override
	public final void deleteAll(Collection<?> instances)
	{
		assert instances != null;
		deleteKeys(Collections2.transform(instances, cachedInstanceToKeyFunction));
	}

	@Override
	public final void refresh(Object instance)
	{
		assert instance != null;
		Key key = associatedKey(instance);

		if (key == null)
		{
			throw new IllegalStateException("Instance not associated with session");
		}

		// so it is not loaded from the cache
		disassociate(instance);

		// load will use this instance instead of creating new
		refresh = instance;

		// load from datastore into the refresh instance
		Object loaded = load().key(key).now();

		if (loaded == null)
		{
			throw new IllegalStateException("Instance to be refreshed could not be found");
		}
	}

	@Override
	public void refreshAll(Collection<?> instances)
	{
		assert instances != null;
		// TODO optimise! add to a stack then pop instances off the stack
		for (Object instance : instances)
		{
			refresh(instance);
		}
	}

	protected InstanceKeyCache createKeyCache()
	{
		return new InstanceKeyCache();
	}

	protected PropertyTranslator decoder(Entity entity)
	{
		return configurationFieldTranslator;
	}

	protected PropertyTranslator encoder(Object instance)
	{
		return configurationFieldTranslator;
	}

	protected PropertyTranslator decoder(Field field, Set<Property> properties)
	{
		return translator(field);
	}

	protected PropertyTranslator encoder(Field field, Object instance)
	{
		return translator(field);
	}

	protected PropertyTranslator translator(Field field)
	{
		if (configuration.entity(field))
		{
			PropertyTranslator translator;
			if (configuration.parent(field))
			{
				translator = parentTranslator;
			}
			else if (configuration.child(field))
			{
				translator = childTranslator;
			}
			else
			{
				translator = independantTranslator;
			}
			return translator;
		}
		else if (configuration.id(field))
		{
			return idFieldTranslator;
		}
		else if (configuration.embed(field))
		{
			if (configuration.polymorphic(field))
			{
				return polymorphicComponentTranslator;
			}
			else
			{
				return embedTranslator;
			}
		}
		else if (configuration.key(field)) {
			return keyFieldTranslator;
		}
		else
		{
			return defaultTranslator;
		}
	}

	/**
	 * @return The translator which is used if no others handle the instance
	 */
	protected abstract PropertyTranslator getFallbackTranslator();

	protected abstract CombinedConverter createTypeConverter();

	/**
	 * @return The translator that is used for single items by default
	 */
	protected abstract ChainedTranslator createValueTranslatorChain();

	protected boolean propertiesIndexedByDefault()
	{
		return true;
	}

	@Override
	public final void disassociate(Object reference)
	{
		keyCache.evictInstance(reference);
	}

	@Override
	public final void disassociateAll()
	{
		keyCache.clear();
	}

	@Override
	public final void associate(Object instance, Key key)
	{
		keyCache.cache(key, instance);
	}

	@Override
	public final void associate(Object instance)
	{
		// encode the instance so we can get its id and parent to make a key
		associating = true;
		store(instance);
		associating = false;
	}
	
	@Override
	public final void associate(Object instance, Object parent)
	{
		// encode the instance so we can get its id and parent to make a key
		associating = true;
		store().instance(instance).parent(parent).now();
		associating = false;
	}

	@Override
	public final void associateAll(Collection<?> instances)
	{
		// encode the instance so we can get its id and parent to make a key
		associating = true;
		storeAll(instances);
		associating = false;
	}

	@Override
	public final Key associatedKey(Object instance)
	{
		return keyCache.getKey(instance);
	}
	
	@Override
	public boolean isAssociated(Object instance)
	{
		return associatedKey(instance) != null;
	}

	@Override
	public final int getActivationDepth()
	{
		return activationDepth;
	}

	@Override
	public final void setActivationDepth(int depth)
	{
		this.activationDepth = depth;
	}

	public CombinedConverter getConverter()
	{
		return converter;
	}
	
	protected final PropertyTranslator getIndependantTranslator()
	{
		return independantTranslator;
	}
	
	protected ChainedTranslator getValueTranslatorChain()
	{
		return this.valueTranslatorChain;
	}

	protected final PropertyTranslator getChildTranslator()
	{
		return childTranslator;
	}

	protected final PropertyTranslator getParentTranslator()
	{
		return parentTranslator;
	}

	protected final PropertyTranslator getPolymorphicTranslator()
	{
		return polymorphicComponentTranslator;
	}
	
	protected final PropertyTranslator getFieldTranslator()
	{
		return configurationFieldTranslator;
	}

	protected final PropertyTranslator getEmbedTranslator()
	{
		return embedTranslator;
	}

	protected final PropertyTranslator getIdFieldTranslator()
	{
		return idFieldTranslator;
	}

	protected final PropertyTranslator getDefaultTranslator()
	{
		return defaultTranslator;
	}

	protected final InstanceKeyCache getKeyCache()
	{
		return keyCache;
	}

	private final Function<Object, Key> cachedInstanceToKeyFunction = new Function<Object, Key>()
	{
		@Override
		public Key apply(Object instance)
		{
			return keyCache.getKey(instance);
		}
	};

	@Override
	public void activate(Object... instances)
	{
		activateAll(Arrays.asList(instances));
	}

	@Override
	public void activateAll(Collection<?> instances)
	{
		// TODO optimise this
		for (Object instance : instances)
		{
			refresh(instance);
		}
	}

	protected final void setIndexed(boolean indexed)
	{
		this.indexed = indexed;
	}

	final Query createQuery(Class<?> type)
	{
		return new Query(configuration.typeToKind(type));
	}

	public final void deleteAll(Class<?> type)
	{
		Query query = createQuery(type);
		query.setKeysOnly();
		FetchOptions options = FetchOptions.Builder.withChunkSize(100);
		Iterator<Entity> entities = servicePrepare(query).asIterator(options);
		Iterator<Key> keys = Iterators.transform(entities, entityToKeyFunction);
		Iterator<List<Key>> partitioned = Iterators.partition(keys, 100);
		while (partitioned.hasNext())
		{
			deleteKeys(partitioned.next());
		}
	}
	
	protected void deleteKeys(Collection<Key> keys)
	{
		serviceDelete(keys);
		for (Key key : keys)
		{
			if (keyCache.containsKey(key))
			{
				keyCache.evictKey(key);
			}
		}
	}
	
	// permanent cache of class fields to reduce reflection
	private static Map<Class<?>, Collection<Field>> classFields = Maps.newConcurrentMap();
	private static Map<Class<?>, Constructor<?>> constructors = Maps.newConcurrentMap();

	// top level translator that uses the Configuration to decide which translator
	// to use for each Field value.
	private final class ConfigurationFieldTranslator extends FieldTranslator
	{
		private ConfigurationFieldTranslator(TypeConverter converters)
		{
			super(converters);
		}

		private Comparator<Field> fieldComparator = new Comparator<Field>()
		{
			@Override
			public int compare(Field o1, Field o2)
			{
				return configuration.name(o1).compareTo(configuration.name(o2));
			}
		};
		
		// TODO put this in a dedicated meta-data holder
		@Override
		protected Collection<Field> getSortedAccessibleFields(Class<?> clazz)
		{
			// fields are cached and stored as a map because reading more common than writing
			Collection<Field> fields = classFields.get(clazz);
			if (fields == null)
			{
				List<Field> ordered = Reflection.getAccessibleFields(clazz);

				fields = new TreeSet<Field>(fieldComparator);

				for (Field field : ordered)
				{
					fields.add(field);
				}
				
				// cache because reflection is costly
				classFields.put(clazz, fields);
			}
			return fields;
		}

		@Override
		protected Constructor<?> getNoArgsConstructor(Class<?> clazz) throws NoSuchMethodException
		{
			Constructor<?> constructor = constructors.get(clazz);
			if (constructor == null)
			{
				// use no-args constructor
				constructor = clazz.getDeclaredConstructor();
		
				// allow access to private constructor
				if (!constructor.isAccessible())
				{
					constructor.setAccessible(true);
				}
				
				constructors.put(clazz, constructor);
			}
			return constructor;
		}
		

		@Override
		protected boolean indexed(Field field)
		{
			return TranslatorObjectDatastore.this.configuration.index(field);
		}
		
		@Override
		protected boolean stored(Field field)
		{
			if (associating)
			{
				// if associating only decode fields required for the key
				return configuration.id(field) || configuration.parent(field);
			}
			else
			{
				return TranslatorObjectDatastore.this.configuration.store(field);
			}
		}

		@Override
		protected Type type(Field field)
		{
			return TranslatorObjectDatastore.this.configuration.typeOf(field);
		}

		@Override
		protected String fieldToPartName(Field field)
		{
			return TranslatorObjectDatastore.this.configuration.name(field);
		}

		@Override
		protected PropertyTranslator encoder(Field field, Object instance)
		{
			return TranslatorObjectDatastore.this.encoder(field, instance);
		}

		@Override
		protected PropertyTranslator decoder(Field field, Set<Property> properties)
		{
			return TranslatorObjectDatastore.this.decoder(field, properties);
		}

		@Override
		protected Object createInstance(Class<?> clazz)
		{
			// if we are refreshing an instance do not create a new one
			Object instance = refresh;
			if (instance == null)
			{
				instance = TranslatorObjectDatastore.this.createInstance(clazz);
				if (instance == null)
				{
					instance = super.createInstance(clazz);
				}
			}
			refresh = null;

			// cache the instance immediately so related instances can reference it
			if (keyCache.getInstance(decodeKey) == null)
			{
				// only cache first time - not for embedded components
				keyCache.cache(decodeKey, instance);
			}

			return instance;
		}

		@Override
		protected void decode(Object instance, Field field, Path path, Set<Property> properties)
		{
			int existingActivationDepth = activationDepth;
			try
			{
				activationDepth = configuration.activationDepth(field, activationDepth);
				super.decode(instance, field, path, properties);
			}
			finally
			{
				activationDepth = existingActivationDepth;
			}
		}

		@Override
		protected void onBeforeEncode(Path path, Object value)
		{
			if (!path.getParts().isEmpty())
			{
				// check that this embedded value is not a persistent instance
				if (keyCache.getKey(value) != null)
				{
					throw new IllegalStateException("Cannot embed persistent instance " + value + " at " + path );
				}
			}
		}

		@Override
		protected boolean isNullStored()
		{
			return TranslatorObjectDatastore.this.isNullStored();
		}
		
	}

	// TODO roll this meta-data into a single class that is looked up once only
	Field idField(Class<?> type)
	{
		Field result = null;
		if (idFields.containsKey(type))
		{
			result = idFields.get(type);
		}
		else
		{
			List<Field> fields = Reflection.getAccessibleFields(type);
			for (Field field : fields)
			{
				if (configuration.id(field))
				{
					result = field;
					break;
				}
			}

			// null cannot be stored in a concurrent hash map
			if (result == null)
			{
				result = NO_ID_FIELD;
			}
			idFields.put(type, result);
		}

		if (result == NO_ID_FIELD)
		{
			return null;
		}
		else
		{
			return result;
		}
	}

	Field keyField(Class<?> type)
	{
		Field result = null;
		if (keyFields.containsKey(type))
		{
			result = keyFields.get(type);
		}
		else
		{
			List<Field> fields = Reflection.getAccessibleFields(type);
			for (Field field : fields)
			{
				if (configuration.key(field))
				{
					result = field;
					break;
				}
			}

			// null cannot be stored in a concurrent hash map
			if (result == null)
			{
				result = NO_ID_FIELD;
			}
			keyFields.put(type, result);
		}

		if (result == NO_ID_FIELD)
		{
			return null;
		}
		else
		{
			return result;
		}
	}

	/**
	 * Create a new instance which will have its fields populated from stored properties.
	 * 
	 * @param clazz The type to create
	 * @return A new instance or null to use the default behaviour
	 */
	protected Object createInstance(Class<?> clazz)
	{
		return null;
	}

	public Configuration getConfiguration()
	{
		return configuration;
	}

	protected abstract boolean isNullStored();

	// null values are not permitted in a concurrent hash map so 
	// need a special value to represent a missing field
	private static final Field NO_ID_FIELD;
	static
	{
		try
		{
			NO_ID_FIELD = TranslatorObjectDatastore.class.getDeclaredField("NO_ID_FIELD");
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static final Function<Entity, Key> entityToKeyFunction = new EntityToKeyFunction();
}
