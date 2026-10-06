package androidx.annotation;
public @interface RestrictTo { Scope[] value(); enum Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, GROUP_ID, TESTS, SUBCLASSES } }
