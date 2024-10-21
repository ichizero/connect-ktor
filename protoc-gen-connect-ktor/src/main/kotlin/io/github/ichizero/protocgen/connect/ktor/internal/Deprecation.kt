// Copyright 2022-2023 The Connect Authors
// Modifications Copyright 2024 ichizero
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.github.ichizero.protocgen.connect.ktor.internal

import com.google.protobuf.Descriptors
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec

internal fun TypeSpec.Builder.addServiceDeprecation(
    service: Descriptors.ServiceDescriptor,
    file: Descriptors.FileDescriptor,
): TypeSpec.Builder {
    if (service.options.deprecated) {
        this.addAnnotation(
            AnnotationSpec
                .builder(Deprecated::class)
                .addMember("%S", "The service is deprecated in the Protobuf source file.")
                .build(),
        )
    } else if (file.options.deprecated) {
        this.addAnnotation(
            AnnotationSpec
                .builder(Deprecated::class)
                .addMember("%S", "The Protobuf source file that defines this service is deprecated.")
                .build(),
        )
    }
    return this
}

internal fun FunSpec.Builder.addMethodDeprecation(
    method: Descriptors.MethodDescriptor,
): FunSpec.Builder {
    if (method.options.deprecated) {
        this.addAnnotation(
            AnnotationSpec
                .builder(Deprecated::class)
                .addMember("%S", "The method is deprecated in the Protobuf source file.")
                .build(),
        )
    }
    return this
}

internal fun FileSpec.Builder.suppressDeprecationWarnings(
    file: Descriptors.FileDescriptor,
): FileSpec.Builder {
    val hasDeprecated =
        file.options.deprecated ||
            file.services.find { s -> s.options.deprecated || s.methods.find { m -> m.options.deprecated } != null } !=
            null
    if (hasDeprecated) {
        this.addAnnotation(
            AnnotationSpec
                .builder(Suppress::class)
                .addMember("%S", "DEPRECATION")
                .build(),
        )
    }
    return this
}
