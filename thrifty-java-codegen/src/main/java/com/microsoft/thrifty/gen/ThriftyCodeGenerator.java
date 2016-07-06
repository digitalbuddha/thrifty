/*
 * Thrifty
 *
 * Copyright (c) Microsoft Corporation
 *
 * All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * THIS CODE IS PROVIDED ON AN  *AS IS* BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING
 * WITHOUT LIMITATION ANY IMPLIED WARRANTIES OR CONDITIONS OF TITLE,
 * FITNESS FOR A PARTICULAR PURPOSE, MERCHANTABLITY OR NON-INFRINGEMENT.
 *
 * See the Apache Version 2.0 License for specific language governing permissions and limitations under the License.
 */
package com.microsoft.thrifty.gen;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.microsoft.thrifty.Obfuscated;
import com.microsoft.thrifty.Redacted;
import com.microsoft.thrifty.TType;
import com.microsoft.thrifty.ThriftField;
import com.microsoft.thrifty.compiler.spi.TypeProcessor;
import com.microsoft.thrifty.schema.Constant;
import com.microsoft.thrifty.schema.EnumType;
import com.microsoft.thrifty.schema.Field;
import com.microsoft.thrifty.schema.Location;
import com.microsoft.thrifty.schema.Named;
import com.microsoft.thrifty.schema.NamespaceScope;
import com.microsoft.thrifty.schema.Schema;
import com.microsoft.thrifty.schema.Service;
import com.microsoft.thrifty.schema.StructType;
import com.microsoft.thrifty.schema.ThriftType;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.NameAllocator;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class ThriftyCodeGenerator {
    private static final String FILE_COMMENT =
            "Automatically generated by the Thrifty compiler; do not edit!\n"
            + "Generated on: ";

    public static final String ADAPTER_FIELDNAME = "ADAPTER";

    private static final DateTimeFormatter DATE_FORMATTER =
            ISODateTimeFormat.dateTime().withZoneUTC();

    private final TypeResolver typeResolver = new TypeResolver();
    private final Schema schema;
    private final ConstantBuilder constantBuilder;
    private final ServiceBuilder serviceBuilder;
    private TypeProcessor typeProcessor;
    private boolean emitAndroidAnnotations;
    private boolean emitParcelable;

    public ThriftyCodeGenerator(Schema schema) {
        this(
                schema,
                ClassName.get(ArrayList.class),
                ClassName.get(HashSet.class),
                ClassName.get(HashMap.class));
    }

    private ThriftyCodeGenerator(
            Schema schema,
            ClassName listClassName,
            ClassName setClassName,
            ClassName mapClassName) {

        Preconditions.checkNotNull(schema, "schema");
        Preconditions.checkNotNull(listClassName, "listClassName");
        Preconditions.checkNotNull(setClassName, "setClassName");
        Preconditions.checkNotNull(mapClassName, "mapClassName");

        this.schema = schema;
        typeResolver.setListClass(listClassName);
        typeResolver.setSetClass(setClassName);
        typeResolver.setMapClass(mapClassName);

        constantBuilder = new ConstantBuilder(typeResolver, schema);
        serviceBuilder = new ServiceBuilder(typeResolver, constantBuilder);
    }

    public ThriftyCodeGenerator withListType(String listClassName) {
        typeResolver.setListClass(ClassName.bestGuess(listClassName));
        return this;
    }

    public ThriftyCodeGenerator withSetType(String setClassName) {
        typeResolver.setSetClass(ClassName.bestGuess(setClassName));
        return this;
    }

    public ThriftyCodeGenerator withMapType(String mapClassName) {
        typeResolver.setMapClass(ClassName.bestGuess(mapClassName));
        return this;
    }

    public ThriftyCodeGenerator emitAndroidAnnotations(boolean shouldEmit) {
        emitAndroidAnnotations = shouldEmit;
        return this;
    }

    public ThriftyCodeGenerator emitParcelable(boolean emitParcelable) {
        this.emitParcelable = emitParcelable;
        return this;
    }

    public ThriftyCodeGenerator usingTypeProcessor(TypeProcessor typeProcessor) {
        this.typeProcessor = typeProcessor;
        return this;
    }

    public void generate(final File directory) throws IOException {
        generate(new FileWriter() {
            @Override
            public void write(JavaFile file) throws IOException {
                if (file != null) {
                    file.writeTo(directory);
                }
            }
        });
    }

    public void generate(final Appendable appendable) throws IOException {
        generate(new FileWriter() {
            @Override
            public void write(JavaFile file) throws IOException {
                if (file != null) {
                    file.writeTo(appendable);
                }
            }
        });
    }

    public ImmutableList<JavaFile> generateTypes() {
        ImmutableList.Builder<JavaFile> generatedTypes = ImmutableList.builder();

        for (EnumType type : schema.enums()) {
            TypeSpec spec = buildEnum(type);
            JavaFile file = assembleJavaFile(type, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        for (StructType type : schema.structs()) {
            TypeSpec spec = buildStruct(type);
            JavaFile file = assembleJavaFile(type, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        for (StructType type : schema.exceptions()) {
            TypeSpec spec = buildStruct(type);
            JavaFile file = assembleJavaFile(type, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        for (StructType type : schema.unions()) {
            TypeSpec spec = buildStruct(type);
            JavaFile file = assembleJavaFile(type, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        Multimap<String, Constant> constantsByPackage = HashMultimap.create();
        for (Constant constant : schema.constants()) {
            constantsByPackage.put(constant.getNamespaceFor(NamespaceScope.JAVA), constant);
        }

        for (Map.Entry<String, Collection<Constant>> entry : constantsByPackage.asMap().entrySet()) {
            String packageName = entry.getKey();
            Collection<Constant> values = entry.getValue();
            TypeSpec spec = buildConst(values);
            JavaFile file = assembleJavaFile(packageName, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        for (Service type : schema.services()) {
            TypeSpec spec = serviceBuilder.buildServiceInterface(type);
            JavaFile file = assembleJavaFile(type, spec);
            if (file == null) {
                continue;
            }

            generatedTypes.add(file);

            spec = serviceBuilder.buildService(type, spec);
            file = assembleJavaFile(type, spec);
            if (file != null) {
                generatedTypes.add(file);
            }
        }

        return generatedTypes.build();
    }

    private interface FileWriter {
        void write(@Nullable JavaFile file) throws IOException;
    }

    private void generate(FileWriter writer) throws IOException {
        for (JavaFile file : generateTypes()) {
            writer.write(file);
        }
    }

    @Nullable
    private JavaFile assembleJavaFile(Named named, TypeSpec spec) {
        String packageName = named.getNamespaceFor(NamespaceScope.JAVA);
        if (Strings.isNullOrEmpty(packageName)) {
            throw new IllegalArgumentException("A Java package name must be given for java code generation");
        }

        return assembleJavaFile(packageName, spec, named.location());
    }

    @Nullable
    private JavaFile assembleJavaFile(String packageName, TypeSpec spec) {
        return assembleJavaFile(packageName, spec, null);
    }

    @Nullable
    private JavaFile assembleJavaFile(String packageName, TypeSpec spec, Location location) {
        if (typeProcessor != null) {
            spec = typeProcessor.process(spec);
            if (spec == null) {
                return null;
            }
        }

        JavaFile.Builder file = JavaFile.builder(packageName, spec)
                .skipJavaLangImports(true)
                .addFileComment(FILE_COMMENT + DATE_FORMATTER.print(System.currentTimeMillis()));

        if (location != null) {
            file.addFileComment("\nSource: $L", location);
        }

        return file.build();
    }

    TypeSpec buildStruct(StructType type) {
        String packageName = type.getNamespaceFor(NamespaceScope.JAVA);
        ClassName structTypeName = ClassName.get(packageName, type.name());
        ClassName builderTypeName = structTypeName.nestedClass("Builder");

        TypeSpec.Builder structBuilder = TypeSpec.classBuilder(type.name())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        if (type.hasJavadoc()) {
            structBuilder.addJavadoc(type.documentation());
        }

        if (type.isException()) {
            structBuilder.superclass(Exception.class);
        }

        TypeSpec builderSpec = builderFor(type, structTypeName, builderTypeName);
        TypeSpec adapterSpec = adapterFor(type, structTypeName, builderTypeName);

        if (emitParcelable) {
            generateParcelable(type, structTypeName, structBuilder);
        }

        structBuilder.addType(builderSpec);
        structBuilder.addType(adapterSpec);
        structBuilder.addField(FieldSpec.builder(adapterSpec.superinterfaces.get(0), ADAPTER_FIELDNAME)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("new $N()", adapterSpec)
                .build());

        MethodSpec.Builder ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(builderTypeName, "builder");

        for (Field field : type.fields()) {
            String name = field.name();
            ThriftType fieldType = field.type();
            ThriftType trueType = fieldType.getTrueType();
            TypeName fieldTypeName = typeResolver.getJavaClass(trueType);

            // Define field
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(fieldTypeName, name)
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addAnnotation(fieldAnnotation(field));

            if (emitAndroidAnnotations) {
                ClassName anno = field.required() ? TypeNames.NOT_NULL : TypeNames.NULLABLE;
                fieldBuilder.addAnnotation(anno);
            }

            if (field.hasJavadoc()) {
                fieldBuilder = fieldBuilder.addJavadoc(field.documentation());
            }

            if (field.isRedacted()) {
                fieldBuilder = fieldBuilder.addAnnotation(AnnotationSpec.builder(Redacted.class).build());
            }

            if (field.isObfuscated()) {
                fieldBuilder = fieldBuilder.addAnnotation(AnnotationSpec.builder(Obfuscated.class).build());
            }

            structBuilder.addField(fieldBuilder.build());

            // Update the struct ctor

            CodeBlock.Builder assignment = CodeBlock.builder().add("$[this.$N = ", name);

            if (trueType.isList()) {
                if (!field.required()) {
                    assignment.add("builder.$N == null ? null : ", name);
                }
                assignment.add("$T.unmodifiableList(builder.$N)",
                        TypeNames.COLLECTIONS, name);
            } else if (trueType.isSet()) {
                if (!field.required()) {
                    assignment.add("builder.$N == null ? null : ", name);
                }
                assignment.add("$T.unmodifiableSet(builder.$N)",
                        TypeNames.COLLECTIONS, name);
            } else if (trueType.isMap()) {
                if (!field.required()) {
                    assignment.add("builder.$N == null ? null : ", name);
                }
                assignment.add("$T.unmodifiableMap(builder.$N)",
                        TypeNames.COLLECTIONS, name);
            } else {
                assignment.add("builder.$N", name);
            }

            ctor.addCode(assignment.add(";\n$]").build());
        }

        structBuilder.addMethod(ctor.build());
        structBuilder.addMethod(buildEqualsFor(type));
        structBuilder.addMethod(buildHashCodeFor(type));
        structBuilder.addMethod(buildToStringFor(type));

        return structBuilder.build();
    }

    private void generateParcelable(StructType structType, ClassName structName, TypeSpec.Builder structBuilder) {
        structBuilder.addSuperinterface(TypeNames.PARCELABLE);

        structBuilder.addField(FieldSpec.builder(ClassLoader.class, "CLASS_LOADER")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.class.getClassLoader()", structName)
                .build());

        ParameterizedTypeName creatorType = ParameterizedTypeName.get(TypeNames.PARCELABLE_CREATOR, structName);
        TypeSpec creator = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(creatorType)
                .addMethod(MethodSpec.methodBuilder("createFromParcel")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(structName)
                        .addParameter(TypeNames.PARCEL, "source")
                        .addStatement("return new $T(source)", structName)
                        .build())
                .addMethod(MethodSpec.methodBuilder("newArray")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(ArrayTypeName.of(structName))
                        .addParameter(int.class, "size")
                        .addStatement("return new $T[size]", structName)
                        .build())
                .build();

        MethodSpec.Builder parcelCtor = MethodSpec.constructorBuilder()
                .addParameter(TypeNames.PARCEL, "in")
                .addModifiers(Modifier.PRIVATE);

        MethodSpec.Builder parcelWriter = MethodSpec.methodBuilder("writeToParcel")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeNames.PARCEL, "dest")
                .addParameter(int.class, "flags");

        for (Field field : structType.fields()) {
            TypeName fieldType = typeResolver.getJavaClass(field.type().getTrueType());
            parcelCtor.addStatement("this.$N = ($T) in.readValue(CLASS_LOADER)", field.name(), fieldType);

            parcelWriter.addStatement("dest.writeValue(this.$N)", field.name());
        }

        FieldSpec creatorField = FieldSpec.builder(creatorType, "CREATOR")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$L", creator)
                .build();

        structBuilder
                .addField(creatorField)
                .addMethod(MethodSpec.methodBuilder("describeContents")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(int.class)
                        .addStatement("return 0")
                        .build())
                .addMethod(parcelCtor.build())
                .addMethod(parcelWriter.build());

    }

    private TypeSpec builderFor(
            StructType structType,
            ClassName structClassName,
            ClassName builderClassName) {
        TypeName builderSuperclassName = ParameterizedTypeName.get(TypeNames.BUILDER, structClassName);
        TypeSpec.Builder builder = TypeSpec.classBuilder("Builder")
                .addSuperinterface(builderSuperclassName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

        MethodSpec.Builder buildMethodBuilder = MethodSpec.methodBuilder("build")
                .addAnnotation(Override.class)
                .returns(structClassName)
                .addModifiers(Modifier.PUBLIC);

        MethodSpec.Builder resetBuilder = MethodSpec.methodBuilder("reset")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC);

        MethodSpec.Builder copyCtor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(structClassName, "struct");

        MethodSpec.Builder defaultCtor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);

        if (structType.isUnion()) {
            buildMethodBuilder.addStatement("int setFields = 0");
        }

        // Add fields to the struct and set them in the ctor
        NameAllocator allocator = new NameAllocator();
        for (Field field : structType.fields()) {
            allocator.newName(field.name(), field.name());
        }

        AtomicInteger tempNameId = new AtomicInteger(0); // used for generating unique names of temporary values
        for (Field field : structType.fields()) {
            ThriftType fieldType = field.type().getTrueType();
            TypeName javaTypeName = typeResolver.getJavaClass(fieldType);
            String fieldName = field.name();
            FieldSpec.Builder f = FieldSpec.builder(javaTypeName, fieldName, Modifier.PRIVATE);

            if (field.hasJavadoc()) {
                f.addJavadoc(field.documentation());
            }

            if (field.defaultValue() != null) {
                CodeBlock.Builder initializer = CodeBlock.builder();
                constantBuilder.generateFieldInitializer(
                        initializer,
                        allocator,
                        tempNameId,
                        "this." + field.name(),
                        fieldType.getTrueType(),
                        field.defaultValue(),
                        false);
                defaultCtor.addCode(initializer.build());

                resetBuilder.addCode(initializer.build());
            } else {
                resetBuilder.addStatement("this.$N = null", fieldName);
            }

            builder.addField(f.build());

            MethodSpec.Builder setterBuilder = MethodSpec.methodBuilder(fieldName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(builderClassName)
                    .addParameter(javaTypeName, fieldName);

            if (field.required()) {
                setterBuilder.beginControlFlow("if ($N == null)", fieldName);
                setterBuilder.addStatement(
                        "throw new $T(\"Required field '$L' cannot be null\")",
                        NullPointerException.class,
                        fieldName);
                setterBuilder.endControlFlow();
            }

            setterBuilder
                    .addStatement("this.$N = $N", fieldName, fieldName)
                    .addStatement("return this");

            builder.addMethod(setterBuilder.build());

            if (structType.isUnion()) {
                buildMethodBuilder
                        .addStatement("if (this.$N != null) ++setFields", fieldName);
            } else {
                if (field.required()) {
                    buildMethodBuilder.beginControlFlow("if (this.$N == null)", fieldName);
                    buildMethodBuilder.addStatement(
                            "throw new $T($S)",
                            ClassName.get(IllegalStateException.class),
                            "Required field '" + fieldName + "' is missing");
                    buildMethodBuilder.endControlFlow();
                }
            }

            copyCtor.addStatement("this.$N = $N.$N", fieldName, "struct", fieldName);
        }

        if (structType.isUnion()) {
            buildMethodBuilder
                    .beginControlFlow("if (setFields != 1)")
                    .addStatement(
                            "throw new $T($S + setFields + $S)",
                            ClassName.get(IllegalStateException.class),
                            "Invalid union; ",
                            " field(s) were set")
                    .endControlFlow();
        }

        buildMethodBuilder.addStatement("return new $T(this)", structClassName);
        builder.addMethod(defaultCtor.build());
        builder.addMethod(copyCtor.build());
        builder.addMethod(buildMethodBuilder.build());
        builder.addMethod(resetBuilder.build());

        return builder.build();
    }

    private TypeSpec adapterFor(StructType structType, ClassName structClassName, ClassName builderClassName) {
        TypeName adapterSuperclass = ParameterizedTypeName.get(
                TypeNames.ADAPTER,
                structClassName,
                builderClassName);

        final MethodSpec.Builder write = MethodSpec.methodBuilder("write")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeNames.PROTOCOL, "protocol")
                .addParameter(structClassName, "struct")
                .addException(TypeNames.IO_EXCEPTION);

        final MethodSpec.Builder read = MethodSpec.methodBuilder("read")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(typeResolver.getJavaClass(structType.type()))
                .addParameter(TypeNames.PROTOCOL, "protocol")
                .addParameter(builderClassName, "builder")
                .addException(TypeNames.IO_EXCEPTION);

        final MethodSpec readHelper = MethodSpec.methodBuilder("read")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(typeResolver.getJavaClass(structType.type()))
                .addParameter(TypeNames.PROTOCOL, "protocol")
                .addException(TypeNames.IO_EXCEPTION)
                .addStatement("return read(protocol, new $T())", builderClassName)
                .build();

        // First, the writer
        write.addStatement("protocol.writeStructBegin($S)", structType.name());

        // Then, the reader - set up the field-reading loop.
        read.addStatement("protocol.readStructBegin()");
        read.beginControlFlow("while (true)");
        read.addStatement("$T field = protocol.readFieldBegin()", TypeNames.FIELD_METADATA);
        read.beginControlFlow("if (field.typeId == $T.STOP)", TypeNames.TTYPE);
        read.addStatement("break");
        read.endControlFlow();

        if (structType.fields().size() > 0) {
            read.beginControlFlow("switch (field.fieldId)");
        }

        for (Field field : structType.fields()) {
            boolean optional = !field.required(); // could also be default, but same-same to us.
            final ThriftType tt = field.type().getTrueType();
            byte typeCode = typeResolver.getTypeCode(tt);

            // enums are i32 on the wire
            if (typeCode == TType.ENUM) {
                typeCode = TType.I32;
            }

            String typeCodeName = TypeNames.getTypeCodeName(typeCode);

            // Write
            if (optional) {
                write.beginControlFlow("if (struct.$N != null)", field.name());
            }

            write.addStatement(
                    "protocol.writeFieldBegin($S, $L, $T.$L)",
                    field.thriftName(),
                    field.id(),
                    TypeNames.TTYPE,
                    typeCodeName);

            tt.accept(new GenerateWriterVisitor(typeResolver, write, "protocol", "struct", field));

            write.addStatement("protocol.writeFieldEnd()");

            if (optional) {
                write.endControlFlow();
            }

            // Read
            read.beginControlFlow("case $L:", field.id());
            new GenerateReaderVisitor(typeResolver, read, field).generate();
            read.endControlFlow(); // end case block
            read.addStatement("break");
        }

        write.addStatement("protocol.writeFieldStop()");
        write.addStatement("protocol.writeStructEnd()");

        if (structType.fields().size() > 0) {
            read.beginControlFlow("default:");
            read.addStatement("$T.skip(protocol, field.typeId)", TypeNames.PROTO_UTIL);
            read.endControlFlow(); // end default
            read.addStatement("break");
            read.endControlFlow(); // end switch
        }

        read.addStatement("protocol.readFieldEnd()");
        read.endControlFlow(); // end while
        read.addStatement("protocol.readStructEnd()");
        read.addStatement("return builder.build()");

        return TypeSpec.classBuilder(structType.name() + "Adapter")
                .addSuperinterface(adapterSuperclass)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addMethod(write.build())
                .addMethod(read.build())
                .addMethod(readHelper)
                .build();
    }

    private MethodSpec buildEqualsFor(StructType struct) {
        MethodSpec.Builder equals = MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(Object.class, "other")
                .addStatement("if (this == other) return true")
                .addStatement("if (other == null) return false");


        if (struct.fields().size() > 0) {
            equals.addStatement("if (!(other instanceof $L)) return false", struct.name());
            equals.addStatement("$1L that = ($1L) other", struct.name());
        }

        boolean isFirst = true;
        for (Field field : struct.fields()) {
            if (isFirst) {
                equals.addCode("$[return ");
                isFirst = false;
            } else {
                equals.addCode("\n&& ");
            }

            if (field.required()) {
                equals.addCode("(this.$1N == that.$1N || this.$1N.equals(that.$1N))", field.name());
            } else {
                equals.addCode("(this.$1N == that.$1N || (this.$1N != null && this.$1N.equals(that.$1N)))",
                        field.name());
            }
        }

        if (struct.fields().size() > 0) {
            equals.addCode(";\n$]");
        } else {
            equals.addStatement("return other instanceof $L", struct.name());
        }

        return equals.build();
    }

    private MethodSpec buildHashCodeFor(StructType struct) {
        MethodSpec.Builder hashCode = MethodSpec.methodBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(int.class)
                .addStatement("int code = 16777619");

        for (Field field : struct.fields()) {
            if (field.required()) {
                hashCode.addStatement("code ^= this.$N.hashCode()", field.name());
            } else {
                hashCode.addStatement("code ^= (this.$1N == null) ? 0 : this.$1N.hashCode()", field.name());
            }
            hashCode.addStatement("code *= 0x811c9dc5");
        }

        hashCode.addStatement("return code");
        return hashCode.build();
    }

    private static final Pattern REDACTED_PATTERN = Pattern.compile(
            "@redacted", Pattern.CASE_INSENSITIVE);

    /**
     * Builds a #toString() method for the given struct.
     *
     * <p>The goal is to produce a method that performs as few string
     * concatenations as possible.  To do so, we identify what would be
     * consecutive constant strings (i.e. field name followed by '='),
     * collapsing them into "chunks", then using the chunks to generate
     * the actual code.
     *
     * <p>This approach, while more complicated to implement than naive
     * StringBuilder usage, produces more-efficient and "more pleasing" code.
     * Simple structs (e.g. one with only one field, which is redacted) end up
     * with simple constants like {@code return "Foo{ssn=&lt;REDACTED&gt;}";}.
     */
    private MethodSpec buildToStringFor(StructType struct) {
        class Chunk {
            final String format;
            final Object[] args;

            Chunk(String format, Object ...args) {
                this.format = format;
                this.args = args;
            }
        }

        MethodSpec.Builder toString = MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class);

        List<Chunk> chunks = new ArrayList<>();

        StringBuilder sb = new StringBuilder(struct.name()).append("{");
        boolean appendedOneField = false;
        for (Field field : struct.fields()) {
            if (appendedOneField) {
                sb.append(", ");
            } else {
                appendedOneField = true;
            }

            sb.append(field.name()).append("=");

            if (field.isRedacted()) {
                sb.append("<REDACTED>");
            } else if (field.isObfuscated()) {
                chunks.add(new Chunk("$S", sb.toString()));
                sb.setLength(0);

                Chunk chunk;
                ThriftType fieldType = field.type().getTrueType();
                if (fieldType.isList() || fieldType.isSet()) {
                    String type;
                    String elementType;
                    if (fieldType.isList()) {
                        type = "List";
                        elementType = ((ThriftType.ListType) fieldType).elementType().getTrueType().javaName();
                    } else {
                        type = "Set";
                        elementType = ((ThriftType.SetType) fieldType).elementType().getTrueType().javaName();
                    }

                    chunk = new Chunk(
                            "$T.summarizeCollection(this.$L, $S, $S)",
                            TypeNames.OBFUSCATION_UTIL,
                            field.name(),
                            type,
                            elementType);
                } else if (fieldType.isMap()) {
                    ThriftType.MapType mapType = (ThriftType.MapType) fieldType;
                    String keyType = mapType.keyType().getTrueType().javaName();
                    String valueType = mapType.valueType().getTrueType().javaName();

                    chunk = new Chunk(
                            "$T.summarizeMap(this.$L, $S, $S)",
                            TypeNames.OBFUSCATION_UTIL,
                            field.name(),
                            keyType,
                            valueType);
                } else {
                    chunk = new Chunk("$T.hash(this.$L)", TypeNames.OBFUSCATION_UTIL, field.name());
                }

                chunks.add(chunk);
            } else {
                chunks.add(new Chunk("$S", sb.toString()));
                chunks.add(new Chunk("this.$L", field.name()));

                sb.setLength(0);
            }
        }

        sb.append("}");
        chunks.add(new Chunk("$S", sb.toString()));

        CodeBlock.Builder block = CodeBlock.builder();
        boolean firstChunk = true;
        for (Chunk chunk : chunks) {
            if (firstChunk) {
                block.add("$[return ");
                firstChunk = false;
            } else {
                block.add(" + ");
            }

            block.add(chunk.format, chunk.args);
        }

        block.add(";$]\n");

        toString.addCode(block.build());

        return toString.build();
    }

    TypeSpec buildConst(Collection<Constant> constants) {
        TypeSpec.Builder builder = TypeSpec.classBuilder("Constants")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PRIVATE)
                        .addCode("// no instances\n")
                        .build());

        final NameAllocator allocator = new NameAllocator();
        allocator.newName("Constants", "Constants");

        final AtomicInteger scope = new AtomicInteger(0); // used for temporaries in const collections
        final CodeBlock.Builder staticInit = CodeBlock.builder();
        final AtomicBoolean hasStaticInit = new AtomicBoolean(false);

        for (final Constant constant : constants) {
            final ThriftType type = constant.type().getTrueType();

            TypeName javaType = typeResolver.getJavaClass(type);
            if (type.isBuiltin() && type != ThriftType.STRING) {
                javaType = javaType.unbox();
            }
            final FieldSpec.Builder field = FieldSpec.builder(javaType, constant.name())
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);

            if (constant.hasJavadoc()) {
                field.addJavadoc(constant.documentation() + "\n\nGenerated from: " + constant.location());
            }

            type.accept(new SimpleVisitor<Void>() {
                @Override
                public Void visitBuiltin(ThriftType builtinType) {
                    field.initializer(constantBuilder.renderConstValue(null, allocator, scope, type, constant.value()));
                    return null;
                }

                @Override
                public Void visitEnum(ThriftType userType) {
                    field.initializer(constantBuilder.renderConstValue(null, allocator, scope, type, constant.value()));
                    return null;
                }

                @Override
                public Void visitList(ThriftType.ListType listType) {
                    if (constant.value().getAsList().isEmpty()) {
                        field.initializer("$T.emptyList()", TypeNames.COLLECTIONS);
                        return null;
                    }
                    initCollection("list", "unmodifiableList");
                    return null;
                }

                @Override
                public Void visitSet(ThriftType.SetType setType) {
                    if (constant.value().getAsList().isEmpty()) {
                        field.initializer("$T.emptySet()", TypeNames.COLLECTIONS);
                        return null;
                    }
                    initCollection("set", "unmodifiableSet");
                    return null;
                }

                @Override
                public Void visitMap(ThriftType.MapType mapType) {
                    if (constant.value().getAsMap().isEmpty()) {
                        field.initializer("$T.emptyMap()", TypeNames.COLLECTIONS);
                        return null;
                    }
                    initCollection("map", "unmodifiableMap");
                    return null;
                }

                private void initCollection(String tempName, String unmodifiableMethod) {
                    tempName += scope.incrementAndGet();
                    constantBuilder.generateFieldInitializer(
                            staticInit,
                            allocator,
                            scope,
                            tempName,
                            type,
                            constant.value(),
                            true);
                    staticInit.addStatement("$N = $T.$L($N)",
                            constant.name(),
                            TypeNames.COLLECTIONS,
                            unmodifiableMethod,
                            tempName);

                    hasStaticInit.set(true);
                }

                @Override
                public Void visitUserType(ThriftType userType) {
                    throw new UnsupportedOperationException("Struct-type constants are not supported");
                }

                @Override
                public Void visitTypedef(ThriftType.TypedefType typedefType) {
                    throw new AssertionError("Typedefs should have been resolved before now");
                }
            });

            builder.addField(field.build());
        }

        if (hasStaticInit.get()) {
            builder.addStaticBlock(staticInit.build());
        }

        return builder.build();
    }

    private static AnnotationSpec fieldAnnotation(Field field) {
        AnnotationSpec.Builder ann = AnnotationSpec.builder(ThriftField.class)
                .addMember("fieldId", "$L", field.id())
                .addMember("isRequired", "$L", field.required());

        String typedef = field.typedefName();
        if (!Strings.isNullOrEmpty(typedef)) {
            ann = ann.addMember("typedefName", "$S", typedef);
        }

        return ann.build();
    }

    TypeSpec buildEnum(EnumType type) {
        ClassName enumClassName = ClassName.get(
                type.getNamespaceFor(NamespaceScope.JAVA),
                type.name());

        TypeSpec.Builder builder = TypeSpec.enumBuilder(type.name())
                .addModifiers(Modifier.PUBLIC)
                .addField(int.class, "value", Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder()
                        .addParameter(int.class, "value")
                        .addStatement("this.$N = $N", "value", "value")
                        .build());

        if (type.hasJavadoc()) {
            builder.addJavadoc(type.documentation());
        }

        MethodSpec.Builder fromCodeMethod = MethodSpec.methodBuilder("findByValue")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(enumClassName)
                .addParameter(int.class, "value")
                .beginControlFlow("switch (value)");

        for (EnumType.Member member : type.members()) {
            String name = member.name();

            int value = member.value();

            TypeSpec.Builder memberBuilder = TypeSpec.anonymousClassBuilder("$L", value);
            if (member.hasJavadoc()) {
                memberBuilder.addJavadoc(member.documentation());
            }

            builder.addEnumConstant(name, memberBuilder.build());

            fromCodeMethod.addStatement("case $L: return $N", value, name);
        }

        fromCodeMethod
                .addStatement("default: return null")
                .endControlFlow();

        builder.addMethod(fromCodeMethod.build());

        return builder.build();
    }
}
