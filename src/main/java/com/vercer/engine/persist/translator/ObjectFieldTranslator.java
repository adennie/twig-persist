package com.vercer.engine.persist.translator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.google.appengine.repackaged.com.google.common.collect.Iterators;
import com.google.appengine.repackaged.com.google.common.collect.PeekingIterator;
import com.vercer.engine.persist.Path;
import com.vercer.engine.persist.Property;
import com.vercer.engine.persist.PropertyTranslator;
import com.vercer.engine.persist.conversion.TypeConverter;
import com.vercer.engine.persist.util.SimpleProperty;
import com.vercer.engine.persist.util.generic.GenericTypeReflector;
import com.vercer.util.Pair;
import com.vercer.util.Reflection;
import com.vercer.util.collections.MergeSet;

/**
 * @author John Patterson <john@vercer.com>
 *
 */
public abstract class ObjectFieldTranslator implements PropertyTranslator
{
	private static final Comparator<Field> comparator = new Comparator<Field>()
	{
		public int compare(Field o1, Field o2)
		{
			return o1.getName().compareTo(o2.getName());
		}
	};
	private final TypeConverter converters;
	private static Map<Class<?>, List<Field>> classFields = new ConcurrentHashMap<Class<?>, List<Field>>();
	private static Map<Pair<Type, Class<?>>, Boolean> superTypes = new ConcurrentHashMap<Pair<Type, Class<?>>, Boolean>();

	public ObjectFieldTranslator(TypeConverter converters)
	{
		this.converters = converters;
	}

	public final Object propertiesToTypesafe(Set<Property> properties, Path path, Type type)
	{
		Class<?> clazz = GenericTypeReflector.erase(type);
		Object instance = createInstance(clazz);
		activate(properties, instance, path);
		return instance;
	}

	private void activate(Set<Property> properties, Object instance, Path path)
	{
		// ensure the properties are sorted
		if (properties instanceof SortedSet<?> == false)
		{
			properties = new TreeSet<Property>(properties);
		}

		// both fields and properties are sorted by name
		List<Field> fields = getSortedFields(instance);
		PeekingIterator<Property> peeking = Iterators.peekingIterator(properties.iterator());
		List<Property> fieldProperties = new ArrayList<Property>();  // reusable instance
		for (Field field : fields)
		{
			alignPropertiesToField(field, peeking, path);

			if (!Modifier.isTransient(field.getModifiers()) && stored(field))
			{
				String name = fieldToPartName(field);

				Path childPath = new Path.Builder(path).field(name).build();

				// get fields starting with part
				while (peeking.hasNext() && peeking.peek().getPath().hasPrefix(childPath))
				{
					fieldProperties.add(peeking.next());
				}

				Property[] elements = fieldProperties.toArray(new Property[fieldProperties.size()]);

				// TODO should be working the original ASS a little harder with offsets
				Set<Property> childProperties = new ArraySortedSet<Property>(elements);
				fieldProperties.clear();  // reuse

				PropertyTranslator translator = translator(field);

				// get the type that we need to store
				Type type = typeFromField(field);

				// create instance
				Object value = translator.propertiesToTypesafe(childProperties, childPath, type);
				if (value != null)
				{
					// if the value was converted to another type we may need to convert it back

					if (!isSuperType(field.getGenericType(), value.getClass()))
					{
						value = converters.convert(value, field.getGenericType());
					}

					try
					{
						field.set(instance, value);
					}
					catch (Exception e)
					{
						throw new IllegalStateException("Could not set value " + value + " to field " + field, e);
					}
				}
			}
		}
	}

	private void alignPropertiesToField(Field field, PeekingIterator<Property> peeking, Path prefix)
	{
		while (peeking.hasNext() && peekFieldName(peeking, prefix).compareTo(field.getName()) < 0)
		{
			peeking.next();
		}
	}

	private String peekFieldName(PeekingIterator<Property> peeking, Path prefix)
	{
		return peeking.peek().getPath().firstPartAfterPrefix(prefix).getName();
	}

	private static boolean isSuperType(Type type, Class<? extends Object> clazz)
	{
		Pair<Type, Class<?>> key = new Pair<Type, Class<?>>(type, clazz);
		Boolean superType = superTypes.get(key);
		if (superType != null)
		{
			return superType;
		}
		else
		{
			boolean result = GenericTypeReflector.isSuperType(type, clazz);
			superTypes.put(key, result);
			return result;
		}
	}

	protected String fieldToPartName(Field field)
	{
		return field.getName();
	}

	protected Type typeFromField(Field field)
	{
		return field.getType();
	}

	protected Object createInstance(Class<?> clazz)
	{
		try
		{
			// use no-args constructor
			Constructor<?> constructor = clazz.getDeclaredConstructor();

			// allow access to private constructor
			if (!constructor.isAccessible())
			{
				constructor.setAccessible(true);
			}
			return constructor.newInstance();
		}
		catch (Exception e)
		{
			throw new IllegalArgumentException("Could not construct instance of: " + clazz, e);
		}
	}

	public final Set<Property> typesafeToProperties(Object object, Path path, boolean indexed)
	{
		try
		{
			List<Field> fields = getSortedFields(object);
			MergeSet<Property> merged = new MergeSet<Property>(fields.size());
			for (Field field : fields)
			{
				// never store transient fields
				if (!Modifier.isTransient(field.getModifiers()) && stored(field))
				{
					// get the type that we need to store
					Type type = typeFromField(field);

					// we may need to convert the object if it is not assignable
					Object value = field.get(object);
					if (value == null)
					{
						if (isNullStored())
						{
							merged.add(new SimpleProperty(path, null, indexed));
						}
						continue;
					}

					if (!isSuperType(type, value.getClass()))
					{
						value = converters.convert(value, type);
					}

					Path childPath = new Path.Builder(path).field(fieldToPartName(field)).build();

					PropertyTranslator translator = translator(field);
					Set<Property> properties = translator.typesafeToProperties(value, childPath, indexed(field));

					merged.addAll(properties);
				}
			}

			return merged;
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private List<Field> getSortedFields(Object object)
	{
		// fields are cached and stored as a map because reading more common than writing
		List<Field> fields = classFields.get(object.getClass());
		if (fields == null)
		{
			fields = Reflection.getAccessibleFields(object.getClass());

			// sort the fields by name
			Collections.sort(fields, comparator);

			// cache because reflection is costly
			classFields.put(object.getClass(), fields);
		}
		return fields;
	}

	protected boolean isNullStored()
	{
		return false;
	}

	protected abstract boolean indexed(Field field);

	protected abstract boolean stored(Field field);

	protected abstract PropertyTranslator translator(Field field);

}