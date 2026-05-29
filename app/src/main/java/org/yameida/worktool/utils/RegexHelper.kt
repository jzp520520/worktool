// Copyright 2024-2026 WorkTool
// Licensed under the Apache License, Version 2.0
// SPDX-License-Identifier: Apache-2.0

package org.yameida.worktool.utils

object RegexHelper {

    fun reverseRegexTitle(string: String): String {
        return string.replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("+", "\\+")
            .replace(".", "\\.")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("?", "\\?")
            .replace("^", "\\^")
            .replace("$", "\\$")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("|", "\\|")
            //企微自身存在限制
            .replace("-", "\\-")
            .replace("(", "\\(")
            .replace(")", "\\)")
    }

}