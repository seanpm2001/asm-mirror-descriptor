/**
 * Copyright 2019 Jordan Zimmerman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.soabase.asm.mirror.descriptor;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.stream.IntStream;

import static io.soabase.asm.mirror.descriptor.MirrorSignatureReader.Mode.DESCRIPTOR;
import static io.soabase.asm.mirror.descriptor.MirrorSignatureReader.Mode.SIGNATURE;

public class ClassMirrorReader {
    private final ProcessingEnvironment processingEnv;
    private final TypeElement mainElement;
    private final MirrorSignatureReader signatureReader;
    private final int classVersion;
    private final int extraAccessFlags;

    @FunctionalInterface
    private interface VisitAnnotationProc {
        AnnotationVisitor visit(String descriptor, boolean visible);
    }

    @FunctionalInterface
    private interface VisitAnnotationTypeProc {
        AnnotationVisitor visit(int typeRef, TypePath typePath, String descriptor, boolean visible);
    }

    @FunctionalInterface
    private interface VisitParameterAnnotationProc {
        AnnotationVisitor visit(int parameter, String descriptor, boolean visible);
    }

    public ClassMirrorReader(ProcessingEnvironment processingEnv, DeclaredType mainElement) {
        this(processingEnv, (TypeElement) mainElement.asElement());
    }

    public ClassMirrorReader(ProcessingEnvironment processingEnv, TypeElement mainElement) {
        this(processingEnv, mainElement, Opcodes.V1_8, Opcodes.ACC_SUPER);
    }

    public ClassMirrorReader(ProcessingEnvironment processingEnv, TypeElement mainElement, int classVersion, int extraAccessFlags) {
        this.processingEnv = processingEnv;
        this.mainElement = mainElement;
        signatureReader = new MirrorSignatureReader(processingEnv);
        this.classVersion = classVersion;
        this.extraAccessFlags = extraAccessFlags;
    }

    public void accept(ClassVisitor classVisitor) {
        int accessFlags = Util.modifiersToAccessFlags(mainElement.getModifiers()) | extraAccessFlags;
        String thisClass = Util.toSlash(mainElement.getQualifiedName().toString());
        String superClass = getSuperClass();
        String[] interfaces = getInterfaces();
        String signature = Util.hasTypeArguments(mainElement) ? signatureReader.classSignature(mainElement.asType()) : null;
        classVisitor.visit(classVersion, accessFlags, thisClass, signature, superClass, interfaces);

        mainElement.getAnnotationMirrors().forEach(annotation -> readAnnotationValue(annotation, classVisitor::visitAnnotation));

        mainElement.getEnclosedElements().forEach(enclosed -> {
            switch (enclosed.getKind()) {
                case FIELD: {
                    readField(classVisitor, (VariableElement) enclosed);
                    break;
                }

                case CONSTRUCTOR:
                case METHOD: {
                    readMethod(classVisitor, (ExecutableElement) enclosed);
                    break;
                }
            }
        });

        classVisitor.visitEnd();    // TODO
    }

    public void readMethod(ClassVisitor classVisitor, ExecutableElement method) {
        int accessFlags = Util.modifiersToAccessFlags(method.getModifiers());
        boolean isConstructor = Util.isConstructor(method);
        String methodName = method.getSimpleName().toString();
        TypeMirror[] parameters = method.getParameters().stream()
                .map(Element::asType)
                .toArray(TypeMirror[]::new);
        TypeMirror[] typeParameters = method.getTypeParameters().stream()
                .map(Element::asType)
                .toArray(TypeMirror[]::new);
        String descriptor = signatureReader.methodType(typeParameters, parameters, method.getReturnType(), DESCRIPTOR);
        String signature = Util.hasTypeArguments(method) ? signatureReader.methodType(typeParameters, parameters, method.getReturnType(), SIGNATURE) : null;
        String[] exceptions = readExceptions(method);
        MethodVisitor methodVisitor = classVisitor.visitMethod(accessFlags, methodName, descriptor, signature, exceptions);
        if (methodVisitor != null) {
            method.getAnnotationMirrors().forEach(annotation -> {
                readAnnotationValue(annotation, methodVisitor::visitAnnotation);
                if (isConstructor) {
                    // javac seems to infer this
                    if (Arrays.asList(getAnnotationTargets(annotation)).contains(ElementType.TYPE_USE)) {
                        readAnnotationTypeValue(annotation, TypeReference.METHOD_RETURN, methodVisitor::visitTypeAnnotation);
                    }
                }
            });
            method.getReturnType().getAnnotationMirrors().forEach(annotation -> readAnnotationTypeValue(annotation, TypeReference.METHOD_RETURN, methodVisitor::visitTypeAnnotation));

            // do METHOD_FORMAL_PARAMETER first as this appears to be what javac does
            IntStream.range(0, method.getParameters().size()).forEach(parameter -> {
                VariableElement parameterElement = method.getParameters().get(parameter);
                parameterElement.asType().getAnnotationMirrors().forEach(annotation -> readParameterAnnotationTypeValue(annotation, parameter, TypeReference.METHOD_FORMAL_PARAMETER, methodVisitor::visitTypeAnnotation));
            });
            IntStream.range(0, method.getParameters().size()).forEach(parameter -> {
                VariableElement parameterElement = method.getParameters().get(parameter);
                parameterElement.getAnnotationMirrors().forEach(annotation -> readParameterAnnotationValue(annotation, parameter, methodVisitor::visitParameterAnnotation));
            });
        }
    }

    public void readField(ClassVisitor classVisitor, VariableElement field) {
        int accessFlags = Util.modifiersToAccessFlags(field.getModifiers());
        String name = field.getSimpleName().toString();
        TypeMirror type = field.asType();
        String descriptor = signatureReader.type(type, DESCRIPTOR);
        String signature = Util.hasTypeArguments(field) ? signatureReader.type(type, SIGNATURE) : null;
        Object constantValue = field.getConstantValue();
        FieldVisitor fieldVisitor = classVisitor.visitField(accessFlags, name, descriptor, signature, constantValue);
        if (fieldVisitor != null) {
            field.getAnnotationMirrors().forEach(annotation -> readAnnotationValue(annotation, fieldVisitor::visitAnnotation));
            field.asType().getAnnotationMirrors().forEach(annotation -> readAnnotationTypeValue(annotation, TypeReference.FIELD, fieldVisitor::visitTypeAnnotation));
        }
    }

    private ElementType[] getAnnotationTargets(AnnotationMirror annotation) {
        Element element = processingEnv.getTypeUtils().asElement(annotation.getAnnotationType());
        Target target = element.getAnnotation(Target.class);
        return (target != null) ? target.value() : new ElementType[0];
    }

    private boolean isVisibleAnnotation(AnnotationMirror annotation) {
        Element element = processingEnv.getTypeUtils().asElement(annotation.getAnnotationType());
        Retention retention = element.getAnnotation(Retention.class);
        return (retention != null) && (retention.value() == RetentionPolicy.RUNTIME);
    }

    private void readAnnotationTypeValue(AnnotationMirror annotation, int sortType, VisitAnnotationTypeProc visitAnnotationTypeProc) {
        int typeRef = TypeReference.newTypeReference(sortType).getValue();
        readAnnotationValue(annotation, (descriptor, visible) -> visitAnnotationTypeProc.visit(typeRef, null, descriptor, visible));
    }

    private void readParameterAnnotationValue(AnnotationMirror annotation, int parameter, VisitParameterAnnotationProc visitParameterAnnotationProc) {
        readAnnotationValue(annotation, (descriptor, visible) -> visitParameterAnnotationProc.visit(parameter, descriptor, visible));
    }

    private void readParameterAnnotationTypeValue(AnnotationMirror annotation, int parameter, int sortType, VisitAnnotationTypeProc visitAnnotationTypeProc) {
        int typeRef = TypeReference.newTypeParameterReference(sortType, parameter).getValue();
        readAnnotationValue(annotation, (descriptor, visible) -> visitAnnotationTypeProc.visit(typeRef, null, descriptor, visible));
    }

    private void readAnnotationValue(AnnotationMirror annotation, VisitAnnotationProc visitAnnotationProc) {
        String annotationDescriptor = signatureReader.type(annotation.getAnnotationType(), DESCRIPTOR);
        AnnotationVisitor annotationVisitor = visitAnnotationProc.visit(annotationDescriptor, isVisibleAnnotation(annotation));
        if (annotationVisitor != null) {
            annotation.getElementValues().forEach((element, annotationValue) -> {
                AnnotationMirrorValueVisitor mirrorValueVisitor = new AnnotationMirrorValueVisitor(element.getSimpleName().toString(), signatureReader);
                annotationValue.accept(mirrorValueVisitor, annotationVisitor);
            });
            annotationVisitor.visitEnd();
        }
    }

    private String[] readExceptions(ExecutableElement method) {
        if (method.getThrownTypes().isEmpty()) {
            return null;
        }
        return method.getThrownTypes().stream()
                .map(signatureReader::exception)
                .toArray(String[]::new);
    }

    private String[] getInterfaces() {
        return mainElement.getInterfaces().stream()
                .map(type -> {
                    Element element = ((DeclaredType) type).asElement();
                    return Util.toSlash(((TypeElement) element).getQualifiedName().toString());
                })
                .toArray(String[]::new);
    }

    private String getSuperClass() {
        Element element = ((DeclaredType) mainElement.getSuperclass()).asElement();
        return Util.toSlash(((TypeElement) element).getQualifiedName().toString());
    }
}
