package io.github.kobakei.iberico;

import android.content.Context;
import android.content.Intent;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;

import io.github.kobakei.iberico.annotation.Extra;
import io.github.kobakei.iberico.annotation.Launcher;

@AutoService(Processor.class)
@SupportedAnnotationTypes({
        "io.github.kobakei.iberico.annotation.Launcher",
        "io.github.kobakei.iberico.annotation.Extra"
})
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class IbericoProcessor extends AbstractProcessor {

    private static final boolean LOGGABLE = true;

    private Filer filer;
    private Messager messager;
    private Elements elements;

    private static final ClassName INTENT_CLASS = ClassName.get(Intent.class);
    private static final ClassName CONTEXT_CLASS = ClassName.get(Context.class);

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
        this.elements = processingEnv.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        Class<Launcher> launcherClass = Launcher.class;
        for (Element element : roundEnv.getElementsAnnotatedWith(launcherClass)) {
            log("Found launcher");
            try {
                generateBuilder(element);
                generateHandler(element);
            } catch (IOException e) {
                logError("IO error");
            }
        }

        return true;
    }

    private void generateBuilder(Element element) throws IOException {
        String className = element.getSimpleName().toString();
        String packageName = elements.getPackageOf(element).getQualifiedName().toString();
        String intentBuilderName = className + "IntentBuilder";
        ClassName targetClass = ClassName.get(packageName, className);

        // Class
        TypeSpec.Builder intentBuilderBuilder = TypeSpec.classBuilder(intentBuilderName)
                .addModifiers(Modifier.PUBLIC);

        // Extras
        List<Element> requiredElements = new ArrayList<>();
        List<Element> optionalElements = new ArrayList<>();
        for (Element elem : element.getEnclosedElements()) {
            Extra extra = elem.getAnnotation(Extra.class);
            if (extra != null) {
                if (hasAnnotation(elem, "Nullable")) {
                    log("Optional");
                    optionalElements.add(elem);
                } else {
                    log("Required");
                    requiredElements.add(elem);
                }
            }
        }

        // fields
        log("Adding fields");
        for (Element e : requiredElements) {
            String fieldName = e.getSimpleName().toString();
            TypeName fieldType = TypeName.get(e.asType());
            log("name is " + fieldName);
            log("type is " + fieldType.toString());
            FieldSpec fieldSpec = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE)
                    .build();
            intentBuilderBuilder.addField(fieldSpec);
        }
        for (Element e : optionalElements) {
            String fieldName = e.getSimpleName().toString();
            TypeName fieldType = TypeName.get(e.asType());
            FieldSpec fieldSpec = FieldSpec.builder(fieldType, fieldName, Modifier.PRIVATE)
                    .build();
            intentBuilderBuilder.addField(fieldSpec);
        }

        // flag field
        FieldSpec flagFieldSpec = FieldSpec.builder(TypeName.INT, "flags", Modifier.PRIVATE)
                .build();
        intentBuilderBuilder.addField(flagFieldSpec);

        // Constructor
        log("Adding constructor");
        MethodSpec.Builder constructorSpecBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);
        for (Element e : requiredElements) {
            String fieldName = e.getSimpleName().toString();
            TypeName fieldType = TypeName.get(e.asType());
            constructorSpecBuilder.addParameter(fieldType, fieldName)
                    .addStatement("this.$L = $L", fieldName, fieldName);
        }
        intentBuilderBuilder.addMethod(constructorSpecBuilder.build());

        // set option value method
        log("Add optional methods");
        for (Element e : optionalElements) {
            String fieldName = e.getSimpleName().toString();
            TypeName fieldType = TypeName.get(e.asType());
            MethodSpec setOptionalSpec = MethodSpec.methodBuilder(fieldName)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(fieldType, fieldName)
                    .returns(ClassName.get(packageName, intentBuilderName))
                    .addStatement("this.$L = $L", fieldName, fieldName)
                    .addStatement("return this")
                    .build();
            intentBuilderBuilder.addMethod(setOptionalSpec);
        }

        // add flags method
        log("Add flags method");
        MethodSpec flagsMethod = MethodSpec.methodBuilder("flags")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeName.INT, "flags")
                .returns(ClassName.get(packageName, intentBuilderName))
                .addStatement("this.flags = flags")
                .addStatement("return this")
                .build();
        intentBuilderBuilder.addMethod(flagsMethod);

        // build method
        log("Add build method");
        MethodSpec.Builder buildSpecBuilder = MethodSpec.methodBuilder("build")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(CONTEXT_CLASS, "context")
                .returns(INTENT_CLASS)
                .addStatement("$T intent = new $T(context, $T.class)", INTENT_CLASS, INTENT_CLASS, targetClass);
        for (Element e : requiredElements) {
            String fieldName = e.getSimpleName().toString();
            buildSpecBuilder.addStatement("intent.putExtra($S, this.$L)", fieldName, fieldName);
        }
        for (Element e : optionalElements) {
            String fieldName = e.getSimpleName().toString();
            buildSpecBuilder.addStatement("intent.putExtra($S, this.$L)", fieldName, fieldName);
        }
        buildSpecBuilder
                .addStatement("intent.addFlags(this.flags)")
                .addStatement("return intent")
                .build();
        intentBuilderBuilder.addMethod(buildSpecBuilder.build());

        // Write
        JavaFile.builder(packageName, intentBuilderBuilder.build())
                .build()
                .writeTo(filer);
    }

    private void generateHandler(Element element) {

    }

    private boolean hasAnnotation(Element e, String name) {
        for (AnnotationMirror annotation : e.getAnnotationMirrors()) {
            if (annotation.getAnnotationType().asElement().getSimpleName().toString().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private void log(String msg) {
        if (LOGGABLE) {
            this.messager.printMessage(Diagnostic.Kind.OTHER, msg);
        }
    }

    private void logError(String msg) {
        this.messager.printMessage(Diagnostic.Kind.ERROR, msg);
    }
}
