package com.google.code.twig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * When updated, begin or join a transaction to copy the current entity to a
 * new child entity with the kind "backup" and an auto-generated id. A new property
 * will be added to this child entity called "backedup" with the date that the
 * operation was run. This exact same date is used for all backup entities created
 * in the same operation.
 * 
 * @author John Patterson (john@vercer.com)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Backup
{
}
