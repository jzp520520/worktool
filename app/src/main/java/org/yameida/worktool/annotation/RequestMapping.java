// Copyright 2024-2026 WorkTool
// Licensed under the Apache License, Version 2.0
// SPDX-License-Identifier: Apache-2.0

package org.yameida.worktool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标识方法为请求接口
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface RequestMapping {
}
