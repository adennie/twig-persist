package com.vercer.convert;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;

import com.vercer.generics.Generics;

public class ArrayToIterable extends TypeConverter
{
	private final TypeConverter delegate;

	public ArrayToIterable(TypeConverter delegate)
	{
		this.delegate = delegate;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T convert(Object instance, Type source, Type target)
	{
		Type targetElementType;

		Type sourceElementType = Generics.getArrayComponentType(source);

		if (target instanceof ParameterizedType)
		{
			targetElementType = Generics.getTypeParameter(target,
					Iterable.class.getTypeParameters()[0]);
		}
		else if (target instanceof Class<?>)
		{
			// do not do a conversion
			targetElementType = null;
		}
		else
		{
			throw new IllegalStateException("Could not get element type from " + source);
		}

		int length = Array.getLength(instance);
		ArrayList<Object> result = new ArrayList<Object>(length);
		for (int i = 0; i < length; i++)
		{
			Object value = Array.get(instance, i);

			// we do not know the element type of raw types - try native value
			if (targetElementType != null)
			{
				value = delegate.convert(value, sourceElementType, targetElementType);
			}
			result.add(value);
		}

		return (T) result;
	}

	@Override
	public boolean converts(Type source, Type target)
	{
		return (source instanceof Class<?> && ((Class<?>) source).isArray()
				|| source instanceof GenericArrayType) && 
				Iterable.class.isAssignableFrom(Generics.erase(target));
	}
}
