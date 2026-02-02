/*
 * Copyright 2026 Sergey S. Chernov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.sergeych.lyng

internal fun extensionCallableName(typeName: String, memberName: String): String {
    return "__ext__${sanitizeExtensionTypeName(typeName)}__${memberName}"
}

internal fun extensionPropertyGetterName(typeName: String, memberName: String): String {
    return "__ext_get__${sanitizeExtensionTypeName(typeName)}__${memberName}"
}

internal fun extensionPropertySetterName(typeName: String, memberName: String): String {
    return "__ext_set__${sanitizeExtensionTypeName(typeName)}__${memberName}"
}

private fun sanitizeExtensionTypeName(typeName: String): String {
    return typeName.replace('.', '_')
}
