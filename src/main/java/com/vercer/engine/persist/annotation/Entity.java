package com.vercer.engine.persist.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.TYPE}) 
@Retention(RetentionPolicy.RUNTIME)
public @interface Entity
{
	public enum Relationship { PARENT, CHILD, INDEPENDANT };
	
	Relationship value() default Relationship.INDEPENDANT;
}