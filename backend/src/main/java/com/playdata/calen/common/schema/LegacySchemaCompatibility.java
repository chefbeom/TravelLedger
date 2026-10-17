package com.playdata.calen.common.schema;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Preserves the existing startup compatibility switch; not permission to add or retire schema writers. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "app.schema.legacy-updaters", name = "enabled", havingValue = "true", matchIfMissing = true)
public @interface LegacySchemaCompatibility {}
