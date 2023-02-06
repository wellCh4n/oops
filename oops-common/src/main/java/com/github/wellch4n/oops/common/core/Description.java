package com.github.wellch4n.oops.common.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author wellCh4n
 * @date 2023/2/6
 */


@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Description {
    String title();
}
