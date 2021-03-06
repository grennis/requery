/*
 * Copyright 2016 requery.io
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
 */

package io.requery.sql;

import io.requery.Converter;
import io.requery.converter.EnumStringConverter;
import io.requery.converter.LocalDateConverter;
import io.requery.converter.LocalDateTimeConverter;
import io.requery.converter.LocalTimeConverter;
import io.requery.converter.OffsetDateTimeConverter;
import io.requery.converter.URIConverter;
import io.requery.converter.URLConverter;
import io.requery.converter.UUIDConverter;
import io.requery.converter.ZonedDateTimeConverter;
import io.requery.meta.Attribute;
import io.requery.query.Expression;
import io.requery.query.ExpressionType;
import io.requery.sql.type.BigIntType;
import io.requery.sql.type.BinaryType;
import io.requery.sql.type.BlobType;
import io.requery.sql.type.BooleanType;
import io.requery.sql.type.ClobType;
import io.requery.sql.type.DateType;
import io.requery.sql.type.DecimalType;
import io.requery.sql.type.FloatType;
import io.requery.sql.type.IntegerType;
import io.requery.sql.type.JavaDateType;
import io.requery.sql.type.RealType;
import io.requery.sql.type.SmallIntType;
import io.requery.sql.type.TimeStampType;
import io.requery.sql.type.TimeType;
import io.requery.sql.type.TinyIntType;
import io.requery.sql.type.VarBinaryType;
import io.requery.sql.type.VarCharType;
import io.requery.util.ClassMap;
import io.requery.util.LanguageVersion;
import io.requery.util.Objects;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default mapping of Java types to Persisted types.
 *
 * @author Nikhil Purushe
 */
public class GenericMapping implements Mapping {

    private final ClassMap<FieldType> types;
    private final ClassMap<FieldType> fixedTypes;
    private final ClassMap<Converter<?, ?>> converters;
    private final Map<Attribute, FieldType> resolvedTypes;

    public GenericMapping(Platform platform) {
        types = new ClassMap<>();
        types.put(boolean.class, new BooleanType(boolean.class));
        types.put(Boolean.class, new BooleanType(Boolean.class));
        types.put(int.class, new IntegerType(int.class));
        types.put(Integer.class, new IntegerType(Integer.class));
        types.put(short.class, new SmallIntType(short.class));
        types.put(Short.class, new SmallIntType(Short.class));
        types.put(byte.class, new TinyIntType(byte.class));
        types.put(Byte.class, new TinyIntType(Byte.class));
        types.put(long.class, new BigIntType(long.class));
        types.put(Long.class, new BigIntType(Long.class));
        types.put(float.class, new FloatType(float.class));
        types.put(Float.class, new FloatType(Float.class));
        types.put(double.class, new RealType(double.class));
        types.put(Double.class, new RealType(Double.class));
        types.put(BigDecimal.class, new DecimalType());
        types.put(byte[].class, new VarBinaryType());
        types.put(java.util.Date.class, new JavaDateType());
        types.put(java.sql.Date.class, new DateType());
        types.put(Time.class, new TimeType());
        types.put(Timestamp.class, new TimeStampType());
        types.put(String.class, new VarCharType());
        types.put(Blob.class, new BlobType());
        types.put(Clob.class, new ClobType());

        fixedTypes = new ClassMap<>();
        fixedTypes.put(byte[].class, new BinaryType());
        converters = new ClassMap<>();
        resolvedTypes = new IdentityHashMap<>();
        Set<Converter> converters = new HashSet<>();
        converters.add(new EnumStringConverter<>(Enum.class));
        converters.add(new UUIDConverter());
        converters.add(new URIConverter());
        converters.add(new URLConverter());
        if (LanguageVersion.current().atLeast(LanguageVersion.JAVA_1_8)) {
            converters.add(new LocalDateConverter());
            converters.add(new LocalTimeConverter());
            converters.add(new LocalDateTimeConverter());
            converters.add(new ZonedDateTimeConverter());
            converters.add(new OffsetDateTimeConverter());
        }
        platform.addMappings(this);
        for (Converter converter : converters) {
            Class mapped = converter.mappedType();
            if (!types.containsKey(mapped)) {
                this.converters.put(mapped, converter);
            }
        }
    }

    @Override
    public <T> Mapping replaceType(int sqlType, FieldType<T> replacementType) {
        Objects.requireNotNull(replacementType);
        replace(types, sqlType, replacementType);
        replace(fixedTypes, sqlType, replacementType);
        return this;
    }

    private static void replace(ClassMap<FieldType> map, int sqlType, FieldType replace) {
        Set<Class<?>> keys = new LinkedHashSet<>();
        for (Map.Entry<Class<?>, FieldType> entry : map.entrySet()) {
            if (entry.getValue().sqlType() == sqlType) {
                keys.add(entry.getKey());
            }
        }
        for (Class<?> type : keys) {
            map.put(type, replace);
        }
    }

    @Override
    public <T> Mapping putType(Class<? super T> type, FieldType<T> fieldType) {
        if (type == null) {
            throw new IllegalArgumentException();
        }
        if (fieldType == null) {
            throw new IllegalArgumentException();
        }
        types.put(type, fieldType);
        return this;
    }

    Converter<?, ?> converterForType(Class<?> type) {
        Converter<?, ?> converter = converters.get(type);
        if (converter == null && type.isEnum()) {
            converter = converters.get(Enum.class);
        }
        return converter;
    }

    @Override
    public FieldType mapAttribute(Attribute<?, ?> attribute) {

        FieldType fieldType = resolvedTypes.get(attribute);
        if (fieldType != null) {
            return fieldType;
        }
        Class<?> type = attribute.classType();
        if (attribute.isForeignKey()) {
            type = attribute.isAssociation() ?
                attribute.referencedAttribute().get().classType() :
                attribute.classType();
        }
        if (attribute.converter() != null) {
            Converter<?, ?> converter = attribute.converter();
            type = converter.persistedType();
        }
        fieldType = getSubstitutedType(type);
        resolvedTypes.put(attribute, fieldType);
        return fieldType;
    }

    @Override
    public List<FieldType> mapCollectionAttribute(Attribute<?, ?> attribute) {
        List<FieldType> fieldTypes = new LinkedList<>();
        if (attribute.elementClass() != null) {
            if (attribute.mapKeyClass() != null) {
                FieldType keyType = types.get(attribute.mapKeyClass());
                fieldTypes.add(Objects.requireNotNull(keyType));
            }
            FieldType valueType = types.get(attribute.elementClass());
            fieldTypes.add(Objects.requireNotNull(valueType));
        }
        return fieldTypes;
    }

    private FieldType getSubstitutedType(Class<?> type) {
        FieldType fieldType = null;
        // check conversion
        Converter<?, ?> converter = converterForType(type);
        if (converter != null) {
            if (converter.persistedSize() != null) {
                fieldType = fixedTypes.get(converter.persistedType());
            }
            type = converter.persistedType();
        }
        if (fieldType == null) {
            fieldType = types.get(type);
        }
        return fieldType == null ? new VarCharType(): fieldType;
    }

    @Override
    public <A> A read(Expression<A> expression, ResultSet results, int column) throws SQLException {
        Class<A> type;
        Converter<?, ?> converter = null;
        FieldType fieldType;
        if (expression.type() == ExpressionType.ATTRIBUTE) {
            Attribute<?, A> attribute = Attributes.query((Attribute) expression);
            converter = attribute.converter();
            type = attribute.classType();
            fieldType = mapAttribute(attribute);
        } else {
            type = expression.classType();
            fieldType = getSubstitutedType(type);
        }
        if (converter == null && !type.isPrimitive()) {
            converter = converterForType(type);
        }
        Object value = fieldType.read(results, column);
        if (converter != null) {
            value = toMapped((Converter) converter, type, value);
        }
        if (type.isPrimitive()) {
            // cast primitive types only into their boxed type
            @SuppressWarnings("unchecked")
            A boxed = (A) value;
            return boxed;
        }
        return type.cast(value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <A> void write(Expression<A> expression, PreparedStatement statement, int index,
                          A value) throws SQLException {
        Class<?> type;
        Converter converter = null;
        FieldType fieldType;
        if (expression.type() == ExpressionType.ATTRIBUTE) {
            Attribute<?, A> attribute = Attributes.query((Attribute) expression);
            converter = attribute.converter();
            fieldType = mapAttribute(attribute);
            type = attribute.isAssociation() ?
                    attribute.referencedAttribute().get().classType() :
                    attribute.classType();
        } else {
            type = expression.classType();
            fieldType = getSubstitutedType(type);
        }
        if (converter == null && !type.isPrimitive()) {
            converter = converterForType(type);
        }
        Object converted;
        if (converter == null) {
            converted = value;
        } else {
            converted = converter.convertToPersisted(value);
        }
        fieldType.write(statement, index, converted);
    }

    public void addConverter(Converter<?, ?> converter, Class<?>... classes) {
        for (Class<?> type : classes) {
            converters.put(type, converter);
        }
    }

    private static <A, B> A toMapped(Converter<A, B> converter, Class<? extends A> type, B value) {
        return converter.convertToMapped(type, value);
    }
}
