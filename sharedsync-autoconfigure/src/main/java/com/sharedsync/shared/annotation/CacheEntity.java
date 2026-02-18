package com.sharedsync.shared.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CacheEntity {

    /**
     * DB 시퀀스 이름. 지정 시 ID Pool 방식으로 양수 ID를 미리 할당합니다.
     * 비어있으면 기존 음수 임시 ID 방식으로 동작합니다.
     */
    String sequenceName() default "";

    /**
     * Pool당 한 번에 할당할 ID 개수 (기본: 50)
     */
    int allocationSize() default 50;
}