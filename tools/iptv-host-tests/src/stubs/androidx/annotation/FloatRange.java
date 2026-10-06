package androidx.annotation;
public @interface FloatRange { double from() default 0; double to() default 0; boolean fromInclusive() default true; boolean toInclusive() default true; }
