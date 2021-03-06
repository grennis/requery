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

package io.requery.processor;

import io.requery.Entity;
import io.requery.Factory;
import io.requery.PropertyNameStyle;
import io.requery.ReadOnly;
import io.requery.Table;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.persistence.Cacheable;
import javax.tools.Diagnostic;
import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Processes class level annotations on an abstract entity type.
 *
 * @author Nikhil Purushe
 */
class EntityType extends BaseProcessableElement<TypeElement> implements EntityDescriptor {

    private final ProcessingEnvironment processingEnvironment;
    private final Map<Element, AttributeDescriptor> attributes;
    private final Map<Element, ListenerMethod> listeners;

    EntityType(ProcessingEnvironment processingEnvironment, TypeElement typeElement) {
        super(typeElement);
        this.processingEnvironment = processingEnvironment;
        attributes = new LinkedHashMap<>();
        listeners = new LinkedHashMap<>();
        // create attributes for fields that have no annotations
        Elements elements = processingEnvironment.getElementUtils();
        if (element().getKind().isInterface()) {
            ElementFilter.methodsIn(typeElement.getEnclosedElements()).stream()
                .filter(this::isMethodProcessable)
                .forEach(this::computeAttribute);
        } else {
            List<? extends Element> members = elements.getAllMembers(typeElement);
            for (VariableElement element : ElementFilter.fieldsIn(members)) {
                // skip static/final members
                if (element.getModifiers().contains(Modifier.STATIC) ||
                    element.getModifiers().contains(Modifier.FINAL)) {
                    continue;
                }

                if (!element.getModifiers().contains(Modifier.PRIVATE)) {
                    computeAttribute(element);
                } // private fields are skipped
            }
        }
    }

    @Override
    public Set<ElementValidator> process(ProcessingEnvironment processingEnvironment) {
        Set<ProcessableElement<?>> elements = new LinkedHashSet<>();
        attributes().values().forEach(
            attribute -> elements.add((ProcessableElement<?>) attribute));

        elements.addAll(listeners.values());
        Set<ElementValidator> validations = new LinkedHashSet<>();
        elements.forEach(element -> validations.addAll(element.process(processingEnvironment)));

        ElementValidator validator = new ElementValidator(element(), processingEnvironment);
        Entity entity = annotationOf(Entity.class).orElse(null);
        if (entity != null &&
            !Names.isEmpty(entity.name()) && !SourceVersion.isIdentifier(entity.name())) {
            validator.error("Invalid class identifier " + entity.name(), Entity.class);
        }
        if (element().getNestingKind() == NestingKind.ANONYMOUS) {
            validator.error("Entity annotation cannot be applied to anonymous class");
        }
        if (element().getKind().isClass() && element().getModifiers().contains(Modifier.FINAL)) {
            validator.error("Entity annotation cannot be applied to final class");
        }
        if (element().getKind() == ElementKind.ENUM) {
            validator.error("Entity annotation cannot be applied to an enum class");
        }
        if (attributes.values().isEmpty()) {
            validator.error("Entity contains no attributes");
        }
        if (!isReadOnly() && attributes.values().size() == 1 &&
            attributes.values().iterator().next().isGenerated()) {
            validator.warning(
                "Entity contains only a single generated attribute may fail to persist");
        }
        validations.add(validator);
        return validations;
    }

    private boolean isMethodProcessable(ExecutableElement element) {
        // must be a getter style method with no args
        return element.getReturnType().getKind() != TypeKind.VOID &&
               element.getParameters().isEmpty();
    }

    void addAnnotationElement(TypeElement annotationElement, Element annotatedElement) {
        String qualifiedName = annotationElement.getQualifiedName().toString();
        Class<? extends Annotation> type;
        try {
            type = Class.forName(qualifiedName).asSubclass(Annotation.class);
        } catch (ClassNotFoundException e) {
            return;
        }
        switch (annotatedElement.getKind()) {
            case CLASS:
            case INTERFACE:
                annotations().put(type, annotatedElement.getAnnotation(type));
                break;
            case FIELD:
                if(annotatedElement.getModifiers().contains(Modifier.STATIC) ||
                   annotatedElement.getModifiers().contains(Modifier.FINAL)) {
                    processingEnvironment.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            annotationElement.getSimpleName() +
                                    " not applicable to static or final member", annotatedElement);
                } else {
                    VariableElement element = (VariableElement) annotatedElement;
                    Optional<AttributeMember> attribute = computeAttribute(element);
                    Annotation annotation = annotatedElement.getAnnotation(type);
                    attribute.ifPresent(a -> a.annotations().put(type, annotation));
                }
                break;
            case METHOD:
                ExecutableElement element = (ExecutableElement) annotatedElement;
                Annotation annotation = annotatedElement.getAnnotation(type);

                if (ListenerAnnotations.all().anyMatch(a -> a.equals(type))) {
                    ListenerMethod listener = listeners.computeIfAbsent(element,
                        key -> new ListenerMethod(element));
                    listener.annotations().put(type, annotation);
                } else {
                    if (isMethodProcessable(element)) {
                        Optional<AttributeMember> attribute = computeAttribute(element);
                        attribute.ifPresent(a -> a.annotations().put(type, annotation));
                    }
                }
                break;
        }
    }

    private Optional<AttributeMember> computeAttribute(Element element) {
        if (element.getKind() == ElementKind.METHOD) {
            ExecutableElement executableElement = (ExecutableElement) element;
            TypeMirror returnType = executableElement.getReturnType();
            if (returnType.equals(element().asType())) {
                return Optional.empty();
            }
        }
        return Optional.of((AttributeMember)
            attributes.computeIfAbsent(element, key -> new AttributeMember(element, this)));
    }

    boolean generatesAdditionalTypes() {
        return attributes.values().stream().anyMatch(member -> member.associativeEntity() != null);
    }

    @Override
    public PropertyNameStyle propertyNameStyle() {
        return annotationOf(Entity.class)
            .map(Entity::propertyNameStyle)
            .orElse(PropertyNameStyle.BEAN);
    }

    @Override
    public String modelName() {
        // it's important that the AnnotationMirror is used here since the model name needs to be
        // known before process() is called
        Optional<? extends AnnotationMirror> mirror =
            Mirrors.findAnnotationMirror(element(), Entity.class);
        if (mirror.isPresent()) {
            return Mirrors.findAnnotationValue(mirror.get(), "model")
                .map(value -> value.getValue().toString())
                .filter(name -> !Names.isEmpty(name))
                .orElse("default");
        }
        if (Mirrors.findAnnotationMirror(element(), javax.persistence.Entity.class).isPresent()) {
            Elements elements = processingEnvironment.getElementUtils();
            Name packageName = elements.getPackageOf(element()).getQualifiedName();
            String[] parts = packageName.toString().split("\\.");
            return parts[parts.length - 1];
        } else {
            throw new IllegalStateException();
        }
    }

    @Override
    public QualifiedName typeName() {
        String entityName = annotationOf(Entity.class).map(Entity::name)
            .orElse(annotationOf(javax.persistence.Entity.class)
                .map(javax.persistence.Entity::name)
                .orElse(null));

        Elements elements = processingEnvironment.getElementUtils();
        PackageElement packageElement = elements.getPackageOf(element());
        String packageName = packageElement.getQualifiedName().toString();

        // if set in the annotation just use that
        if (!Names.isEmpty(entityName)) {
            return new QualifiedName(packageName, entityName);
        }

        String typeName = element().getSimpleName().toString();
        if (element().getKind().isInterface()) {
            // maybe I<Something> style
            if (typeName.startsWith("I") && Character.isUpperCase(typeName.charAt(1))) {
                entityName = typeName.substring(1);
            } else {
                entityName = typeName + "Entity";
            }
        } else {
            entityName = Names.removeClassPrefixes(typeName);
            if (entityName.equals(typeName)) {
                entityName = typeName + "Entity";
            }
        }
        return new QualifiedName(packageName, entityName);
    }

    @Override
    public String staticTypeName() {
        return "$TYPE";
    }

    @Override
    public String tableName() {
        return annotationOf(Table.class).map(Table::name).orElse(
               annotationOf(javax.persistence.Table.class)
                   .map(javax.persistence.Table::name).orElse(
            element().getKind().isInterface() ?
                element().getSimpleName().toString() :
                typeName().className()));
    }

    @Override
    public String[] tableAttributes() {
        return annotationOf(Table.class).map(Table::createAttributes).orElse(new String[]{});
    }

    @Override
    public boolean isCacheable() {
        return annotationOf(Entity.class).map(Entity::cacheable)
            .orElse( annotationOf(Cacheable.class)
                    .map(Cacheable::value)
                    .orElse(true));
    }

    @Override
    public boolean isReadOnly() {
        return annotationOf(ReadOnly.class).isPresent();
    }

    @Override
    public boolean isStateless() {
        return annotationOf(Entity.class).map(Entity::stateless).orElse(false);
    }

    @Override
    public String classFactoryName() {
        // use mirror to avoid loading classes not generated yet
        return Mirrors.findAnnotationMirror(element(), Factory.class)
            .flatMap(Mirrors::findAnnotationValue)
            .map(value -> value.getValue().toString()).orElse(null);
    }

    @Override
    public Map<Element, ? extends AttributeDescriptor> attributes() {
        return attributes;
    }

    @Override
    public Map<Element, ? extends ListenerDescriptor> listeners() {
        return listeners;
    }

}
