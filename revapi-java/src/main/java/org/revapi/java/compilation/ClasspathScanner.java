package org.revapi.java.compilation;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import static org.revapi.java.AnalysisConfiguration.MissingClassReporting.ERROR;
import static org.revapi.java.AnalysisConfiguration.MissingClassReporting.REPORT;
import static org.revapi.java.model.JavaElementFactory.elementFor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleElementVisitor8;
import javax.lang.model.util.SimpleTypeVisitor8;
import javax.lang.model.util.Types;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

import org.revapi.Archive;
import org.revapi.java.AnalysisConfiguration;
import org.revapi.java.FlatFilter;
import org.revapi.java.model.AnnotationElement;
import org.revapi.java.model.JavaElementBase;
import org.revapi.java.model.JavaElementFactory;
import org.revapi.java.model.MethodElement;
import org.revapi.java.model.MissingClassElement;
import org.revapi.java.spi.IgnoreCompletionFailures;
import org.revapi.java.spi.UseSite;
import org.revapi.java.spi.Util;
import org.revapi.query.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Lukas Krejci
 * @since 0.11.0
 */
final class ClasspathScanner {
    private static final Logger LOG = LoggerFactory.getLogger(ClasspathScanner.class);

    private static final List<Modifier> ACCESSIBLE_MODIFIERS = Arrays.asList(Modifier.PUBLIC, Modifier.PROTECTED);
    private static final String SYSTEM_CLASSPATH_NAME = "<system classpath>";

    private static final JavaFileManager.Location[] POSSIBLE_SYSTEM_CLASS_LOCATIONS = {
            StandardLocation.CLASS_PATH, StandardLocation.PLATFORM_CLASS_PATH
    };

    private final StandardJavaFileManager fileManager;
    private final ProbingEnvironment environment;
    private final Map<Archive, File> classPath;
    private final Map<Archive, File> additionalClassPath;
    private final AnalysisConfiguration.MissingClassReporting missingClassReporting;
    private final boolean ignoreMissingAnnotations;
    private final InclusionFilter inclusionFilter;
    private final boolean defaultInclusionCase;

    ClasspathScanner(StandardJavaFileManager fileManager, ProbingEnvironment environment,
                     Map<Archive, File> classPath, Map<Archive, File> additionalClassPath,
                     AnalysisConfiguration.MissingClassReporting missingClassReporting,
                     boolean ignoreMissingAnnotations, InclusionFilter inclusionFilter) {
        this.fileManager = fileManager;
        this.environment = environment;
        this.classPath = classPath;
        this.additionalClassPath = additionalClassPath;
        this.missingClassReporting = missingClassReporting == null
                ? ERROR
                : missingClassReporting;
        this.ignoreMissingAnnotations = ignoreMissingAnnotations;
        this.inclusionFilter = inclusionFilter;
        this.defaultInclusionCase = inclusionFilter.defaultCase();
    }

    void initTree() throws IOException {
        List<ArchiveLocation> classPathLocations = classPath.keySet().stream().map(ArchiveLocation::new)
                .collect(toList());

        Scanner scanner = new Scanner();

        for (ArchiveLocation loc : classPathLocations) {
            scanner.scan(loc, classPath.get(loc.getArchive()), true);
        }

        SyntheticLocation allLoc = new SyntheticLocation();
        fileManager.setLocation(allLoc, classPath.values());

        Function<String, JavaFileObject> searchHard = className ->
                Stream.concat(Stream.of(allLoc), Stream.of(POSSIBLE_SYSTEM_CLASS_LOCATIONS))
                        .map(l -> {
                            try {
                                return fileManager.getJavaFileForInput(l, className, JavaFileObject.Kind.CLASS);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .filter(Objects::nonNull)
                        .findFirst().orElse(null);

        Set<TypeElement> lastUnknowns = Collections.emptySet();

        Map<String, ArchiveLocation> cachedArchives = new HashMap<>(additionalClassPath.size());

        while (!scanner.requiredTypes.isEmpty() && !lastUnknowns.equals(scanner.requiredTypes.keySet())) {
            lastUnknowns = new HashSet<>(scanner.requiredTypes.keySet());
            for (TypeElement t : lastUnknowns) {
                String name = environment.getElementUtils().getBinaryName(t).toString();
                JavaFileObject jfo = searchHard.apply(name);
                if (jfo == null) {
                    //this type is really missing
                    continue;
                }

                URI uri = jfo.toUri();
                String path;
                if ("jar".equals(uri.getScheme())) {
                    //we pass our archives as jars, so let's dig only into those
                    path = uri.getSchemeSpecificPart();

                    //jar:file:/path .. let's get rid of the "file:" part
                    int colonIdx = path.indexOf(':');
                    if (colonIdx >= 0) {
                        path = path.substring(colonIdx + 1);
                    }

                    //separate the file path from the in-jar path
                    path = path.substring(0, path.lastIndexOf('!'));

                    //remove superfluous forward slashes at the start of the path, if any
                    int lastSlashIdx = -1;
                    for (int i = 0; i < path.length() - 1; ++i) {
                        if (path.charAt(i) == '/' && path.charAt(i + 1) != '/') {
                            lastSlashIdx = i;
                            break;
                        }
                    }
                    if (lastSlashIdx > 0) {
                        path = path.substring(lastSlashIdx);
                    }
                } else {
                    path = uri.getPath();
                }

                ArchiveLocation loc = cachedArchives.get(path);
                if (loc == null) {
                    Archive ar = null;
                    for (Map.Entry<Archive, File> e : additionalClassPath.entrySet()) {
                        if (e.getValue().getAbsolutePath().equals(path)) {
                            ar = e.getKey();
                            break;
                        }
                    }

                    if (ar != null) {
                        loc = new ArchiveLocation(ar);
                        cachedArchives.put(path, loc);
                    }
                }

                if (loc != null) {
                    scanner.scanClass(loc, t, false);
                }
            }
        }

        //ok, so scanning the archives doesn't give us any new resolved classes that we need in the API...
        //let's scan the system classpath. What will be left after this will be the truly missing classes.

        //making a copy because the required types might be modified during scanning
        Map<TypeElement, Boolean> rts = new HashMap<>(scanner.requiredTypes);

        ArchiveLocation systemClassPath = new ArchiveLocation(new Archive() {
            @Nonnull @Override public String getName() {
                return SYSTEM_CLASSPATH_NAME;
            }

            @Nonnull @Override public InputStream openStream() throws IOException {
                throw new UnsupportedOperationException();
            }
        });

        for (Map.Entry<TypeElement, Boolean> e : rts.entrySet()) {
            if (e.getKey().asType().getKind() != TypeKind.ERROR) {
                scanner.scanClass(systemClassPath, e.getKey(), false);
            }
        }

        scanner.initEnvironment();
    }

    private final class Scanner {
        final Set<TypeElement> processed = new HashSet<>();
        final Map<TypeElement, Boolean> requiredTypes = new IdentityHashMap<>();
        final Map<TypeElement, TypeRecord> types = new IdentityHashMap<>();
        final TypeVisitor<TypeElement, Void> getTypeElement = new SimpleTypeVisitor8<TypeElement, Void>() {
            @Override
            protected TypeElement defaultAction(TypeMirror e, Void ignored) {
                throw new IllegalStateException("Could not determine the element of a type: " + e);
            }

            @Override
            public TypeElement visitDeclared(DeclaredType t, Void ignored) {
                return (TypeElement) t.asElement();
            }

            @Override
            public TypeElement visitTypeVariable(TypeVariable t, Void ignored) {
                return t.getUpperBound().accept(this, null);
            }

            @Override
            public TypeElement visitArray(ArrayType t, Void ignored) {
                return t.getComponentType().accept(this, null);
            }

            @Override
            public TypeElement visitPrimitive(PrimitiveType t, Void ignored) {
                return null;
            }

            @Override
            public TypeElement visitIntersection(IntersectionType t, Void aVoid) {
                return t.getBounds().get(0).accept(this, null);
            }

            @Override
            public TypeElement visitWildcard(WildcardType t, Void aVoid) {
                if (t.getExtendsBound() != null) {
                    return t.getExtendsBound().accept(this, null);
                } else if (t.getSuperBound() != null) {
                    return t.getSuperBound().accept(this, null);
                } else {
                    return environment.getElementUtils().getTypeElement("java.lang.Object");
                }
            }

            @Override
            public TypeElement visitNoType(NoType t, Void aVoid) {
                return null;
            }
        };

        void scan(ArchiveLocation location, File path, boolean primaryApi) throws IOException {
            fileManager.setLocation(location, Collections.singleton(path));

            Iterable<? extends JavaFileObject> jfos = fileManager.list(location, "",
                    EnumSet.of(JavaFileObject.Kind.CLASS), true);

            for (JavaFileObject jfo : jfos) {
                TypeElement type = Util.findTypeByBinaryName(environment.getElementUtils(),
                        fileManager.inferBinaryName(location, jfo));

                //type can be null if it represents an anonymous or member class...
                if (type != null) {
                    scanClass(location, type, primaryApi);
                }
            }
        }

        void scanClass(ArchiveLocation loc, TypeElement type, boolean primaryApi) {
            try {
                if (processed.contains(type)) {
                    return;
                }

                processed.add(type);
                Boolean wasAnno = requiredTypes.remove(type);

                String bn = environment.getElementUtils().getBinaryName(type).toString();
                String cn = type.getQualifiedName().toString();
                boolean includes = inclusionFilter.accepts(bn, cn);
                boolean excludes = inclusionFilter.rejects(bn, cn);

                //technically, we could find this out later on in the method, but doing this here ensures that the
                //javac tries to fully load the class (and therefore throw any completion failures.
                //Doing this then ensures that we get a correct TypeKind after this call.
                TypeElement superType = getTypeElement.visit(IgnoreCompletionFailures.in(type::getSuperclass));

                //type.asType() possibly not completely correct when dealing with inner class of a parameterized class
                TypeMirror typeType = type.asType();
                if (typeType.getKind() == TypeKind.ERROR) {
                    //just re-add the missing type and return. It will be dealt with accordingly
                    //in initEnvironment
                    requiredTypes.put(type, wasAnno == null ? false : wasAnno);
                    return;
                }

                org.revapi.java.model.TypeElement t =
                        new org.revapi.java.model.TypeElement(environment, loc.getArchive(), type,
                                (DeclaredType) type.asType());

                TypeRecord tr = getTypeRecord(type);
                tr.modelElement = t;
                //this will be revisited... in here we're just establishing the types that are in the API for sure...
                tr.inApi = !excludes &&
                        (primaryApi && !shouldBeIgnored(type) && !(type.getEnclosingElement() instanceof TypeElement));
                tr.primaryApi = primaryApi;
                tr.explicitlyExcluded = excludes;
                tr.explicitlyIncluded = includes;

                if (tr.explicitlyExcluded) {
                    return;
                }

                if (superType != null) {
                    addUse(tr, type, superType, UseSite.Type.IS_INHERITED);
                    tr.superTypes.add(getTypeRecord(superType));
                    if (!processed.contains(superType)) {
                        requiredTypes.put(superType, false);
                    }
                }

                IgnoreCompletionFailures.in(type::getInterfaces).stream().map(getTypeElement::visit)
                        .forEach(e -> {
                            if (!processed.contains(e)) {
                                requiredTypes.put(e, false);
                            }
                            addUse(tr, type, e, UseSite.Type.IS_IMPLEMENTED);
                            tr.superTypes.add(getTypeRecord(e));
                        });

                addTypeParamUses(tr, type, type.asType());

                for (Element e : IgnoreCompletionFailures.in(type::getEnclosedElements)) {
                    switch (e.getKind()) {
                        case ANNOTATION_TYPE:
                        case CLASS:
                        case ENUM:
                        case INTERFACE:
                            addUse(tr, type, (TypeElement) e, UseSite.Type.CONTAINS);
                            //the contained classes by default inherit the API status of their containing class
                            scanClass(loc, (TypeElement) e, tr.inApi);
                            break;
                        case CONSTRUCTOR:
                        case METHOD:
                            scanMethod(tr, (ExecutableElement) e);
                            break;
                        case ENUM_CONSTANT:
                        case FIELD:
                            scanField(tr, (VariableElement) e);
                            break;
                    }
                }

                type.getAnnotationMirrors().forEach(a -> scanAnnotation(tr, type, a));
            } catch (Exception e) {
                LOG.error("Failed to scan class " + type.getQualifiedName().toString()
                        + ". Analysis results may be skewed.", e);
                getTypeRecord(type).errored = true;
            }
        }

        void placeInTree(TypeRecord typeRecord) {
            TypeElement type = typeRecord.modelElement.getDeclaringElement();

            if (!(type.getEnclosingElement() instanceof TypeElement)) {
                environment.getTree().getRootsUnsafe().add(typeRecord.modelElement);
            } else {
                ArrayDeque<String> nesting = new ArrayDeque<>();
                type = (TypeElement) type.getEnclosingElement();
                while (type != null) {
                    nesting.push(type.getQualifiedName().toString());
                    type = type.getEnclosingElement() instanceof TypeElement
                            ? (TypeElement) type.getEnclosingElement()
                            : null;
                }

                Function<String, Filter<org.revapi.java.model.TypeElement>> findByCN =
                        cn -> FlatFilter.by(e -> cn.equals(e.getCanonicalName()));

                List<org.revapi.java.model.TypeElement> parents = Collections.emptyList();
                while (parents.isEmpty() && !nesting.isEmpty()) {
                    parents = environment.getTree().searchUnsafe(
                            org.revapi.java.model.TypeElement.class, false, findByCN.apply(nesting.pop()), null);
                }

                org.revapi.java.model.TypeElement parent = parents.isEmpty() ? null : parents.get(0);
                while (!nesting.isEmpty()) {
                    String cn = nesting.pop();
                    parents = environment.getTree().searchUnsafe(
                            org.revapi.java.model.TypeElement.class, false, findByCN.apply(cn),
                            parent);
                    if (parents.isEmpty()) {
                        //we found a "gap" in the parents included in the model. let's start from the top
                        //again
                        do {
                            parents = environment.getTree().searchUnsafe(
                                    org.revapi.java.model.TypeElement.class, false, findByCN.apply(cn), null);
                            if (parents.isEmpty() && !nesting.isEmpty()) {
                                cn = nesting.pop();
                            } else {
                                break;
                            }
                        } while (!nesting.isEmpty());
                    }

                    parent = parents.isEmpty() ? null : parents.get(0);
                }

                if (parent == null) {
                    environment.getTree().getRootsUnsafe().add(typeRecord.modelElement);
                } else {
                    parent.getChildren().add(typeRecord.modelElement);
                }
            }
        }

        void scanField(TypeRecord owningType, VariableElement field) {
            if (shouldBeIgnored(field)) {
                owningType.inaccessibleDeclaredNonClassMembers.add(field);
                return;
            }

            owningType.accessibleDeclaredNonClassMembers.add(field);


            TypeElement fieldType = field.asType().accept(getTypeElement, null);

            //fieldType == null means primitive type, if no type can be found, exception is thrown
            if (fieldType == null) {
                return;
            }


            addUse(owningType, field, fieldType, UseSite.Type.HAS_TYPE);
            addType(fieldType, false);
            addTypeParamUses(owningType, field, field.asType());

            field.getAnnotationMirrors().forEach(a -> scanAnnotation(owningType, field, a));
        }

        void scanMethod(TypeRecord owningType, ExecutableElement method) {
            if (shouldBeIgnored(method)) {
                owningType.inaccessibleDeclaredNonClassMembers.add(method);
                return;
            }

            owningType.accessibleDeclaredNonClassMembers.add(method);

            TypeElement returnType = method.getReturnType().accept(getTypeElement, null);
            if (returnType != null) {
                addUse(owningType, method, returnType, UseSite.Type.RETURN_TYPE);
                addType(returnType, false);
                addTypeParamUses(owningType, method, method.getReturnType());
            }

            int idx = 0;
            for (VariableElement p : method.getParameters()) {
                TypeElement pt = p.asType().accept(getTypeElement, null);
                if (pt != null) {
                    addUse(owningType, method, pt, UseSite.Type.PARAMETER_TYPE, idx++);
                    addType(pt, false);
                    addTypeParamUses(owningType, method, p.asType());
                }

                p.getAnnotationMirrors().forEach(a -> scanAnnotation(owningType, p, a));
            }

            method.getThrownTypes().forEach(t -> {
                TypeElement ex = t.accept(getTypeElement, null);
                if (ex != null) {
                    addUse(owningType, method, ex, UseSite.Type.IS_THROWN);
                    addType(ex, false);
                    addTypeParamUses(owningType, method, t);
                }

                t.getAnnotationMirrors().forEach(a -> scanAnnotation(owningType, method, a));
            });

            method.getAnnotationMirrors().forEach(a -> scanAnnotation(owningType, method, a));
        }

        void scanAnnotation(TypeRecord owningType, Element annotated, AnnotationMirror annotation) {
            TypeElement type = annotation.getAnnotationType().accept(getTypeElement, null);
            if (type != null) {
                addUse(owningType, annotated, type, UseSite.Type.ANNOTATES);
                addType(type, true);
            }
        }

        boolean addType(TypeElement type, boolean isAnnotation) {
            if (processed.contains(type)) {
                return true;
            }

            requiredTypes.put(type, isAnnotation);
            return false;
        }

        void addTypeParamUses(TypeRecord userType, Element user, TypeMirror usedType) {
            HashSet<String> visited = new HashSet<>(4);
            usedType.accept(new SimpleTypeVisitor8<Void, Void>() {
                @Override public Void visitIntersection(IntersectionType t, Void aVoid) {
                    t.getBounds().forEach(b -> b.accept(this, null));
                    return null;
                }

                @Override public Void visitArray(ArrayType t, Void ignored) {
                    return t.getComponentType().accept(this, null);
                }

                @Override public Void visitDeclared(DeclaredType t, Void ignored) {
                    String type = Util.toUniqueString(t);
                    if (!visited.contains(type)) {
                        visited.add(type);
                        if (t != usedType) {
                            TypeElement typeEl = (TypeElement) t.asElement();
                            addType(typeEl, false);
                            addUse(userType, user, typeEl, UseSite.Type.TYPE_PARAMETER_OR_BOUND);
                        }
                        t.getTypeArguments().forEach(a -> a.accept(this, null));
                    }
                    return null;
                }

                @Override public Void visitTypeVariable(TypeVariable t, Void ignored) {
                    return t.getUpperBound().accept(this, null);
                }

                @Override public Void visitWildcard(WildcardType t, Void ignored) {
                    TypeMirror bound = t.getExtendsBound();
                    if (bound != null) {
                        bound.accept(this, null);
                    }
                    bound = t.getSuperBound();
                    if (bound != null) {
                        bound.accept(this, null);
                    }
                    return null;
                }
            }, null);
        }

        void addUse(TypeRecord userType, Element user, TypeElement used, UseSite.Type useType) {
            addUse(userType, user, used, useType, -1);
        }

        void addUse(TypeRecord userType, Element user, TypeElement used, UseSite.Type useType, int indexInParent) {
            TypeRecord usedTr = getTypeRecord(used);
            Set<ClassPathUseSite> sites = usedTr.useSites;
            sites.add(new ClassPathUseSite(useType, user, indexInParent));

            Set<TypeRecord> usedTypes = userType.usedTypes.get(useType);
            if (usedTypes == null) {
                usedTypes = new HashSet<>(4);
                userType.usedTypes.put(useType, usedTypes);
            }

            usedTypes.add(usedTr);
        }

        TypeRecord getTypeRecord(TypeElement type) {
            TypeRecord rec = types.get(type);
            if (rec == null) {
                rec = new TypeRecord();
                rec.javacElement = type;
                int depth = 0;
                Element e = type.getEnclosingElement();
                while (e != null && e instanceof TypeElement) {
                    depth++;
                    e = e.getEnclosingElement();
                }
                rec.nestingDepth = depth;
                types.put(type, rec);
            }

            return rec;
        }

        void initEnvironment() {
            if (ignoreMissingAnnotations && !requiredTypes.isEmpty()) {
                removeAnnotatesUses();
            }

            moveInnerClassesOfPrimariesToApi();
            determineApiStatus();
            initChildren();

            if (missingClassReporting == REPORT && !requiredTypes.isEmpty()) {
                handleMissingClasses(types);
            }

            Set<TypeRecord> types = constructTree();

            if (missingClassReporting == ERROR) {
                List<String> reallyMissing = types.stream().filter(tr -> tr.modelElement instanceof MissingClassElement)
                        .map(tr -> tr.modelElement.getCanonicalName()).collect(toList());
                if (!reallyMissing.isEmpty()) {
                    throw new IllegalStateException(
                            "The following classes that contribute to the public API of " +
                                    environment.getApi() +
                                    " could not be located: " +
                                    reallyMissing);
                }
            }

            environment.setTypeMap(types.stream().collect(toMap(tr -> tr.javacElement, tr -> tr.modelElement)));
        }

        private void handleMissingClasses(Map<TypeElement, TypeRecord> types) {
            Elements els = environment.getElementUtils();

            for (TypeElement t : requiredTypes.keySet()) {
                TypeElement type = els.getTypeElement(t.getQualifiedName());
                if (type == null) {
                    TypeRecord tr = this.types.get(t);
                    String bin = els.getBinaryName(t).toString();
                    MissingClassElement mce = new MissingClassElement(environment, bin,
                            t.getQualifiedName().toString());
                    if (tr == null) {
                        tr = new TypeRecord();
                    }
                    mce.setInApi(tr.inApi);
                    mce.setInApiThroughUse(tr.inApiThroughUse);
                    mce.setRawUseSites(tr.useSites);
                    tr.javacElement = mce.getDeclaringElement();
                    tr.modelElement = mce;
                    types.put(tr.javacElement, tr);
                }
            }
        }

        private Set<TypeRecord> constructTree() {
            Set<TypeRecord> types = new HashSet<>();
            Set<TypeElement> ignored = new HashSet<>();
            Comparator<Map.Entry<TypeElement, TypeRecord>> byNestingDepth = (a, b) -> {
                TypeRecord ar = a.getValue();
                TypeRecord br = b.getValue();
                int ret = ar.nestingDepth - br.nestingDepth;
                if (ret == 0) {
                    ret = a.getKey().getQualifiedName().toString().compareTo(b.getKey().getQualifiedName().toString());
                }

                //the less nested classes need to come first
                return ret;
            };

            this.types.entrySet().stream().sorted(byNestingDepth).forEach(e -> {
                TypeElement t = e.getKey();
                TypeRecord r = e.getValue();

                if (r.errored) {
                    return;
                }

                //the model element will be null for missing types. Additionally, we don't want the system classpath
                //in our tree, because that is superfluous.
                if (r.modelElement != null
                        && (r.modelElement.getArchive() == null
                        || !r.modelElement.getArchive().getName().equals(SYSTEM_CLASSPATH_NAME))) {
                    String cn = t.getQualifiedName().toString();
                    boolean includes = r.explicitlyIncluded;
                    boolean excludes = r.explicitlyExcluded;

                    if (includes) {
                        environment.addExplicitInclusion(cn);
                    }

                    if (excludes) {
                        environment.addExplicitExclusion(cn);
                        ignored.add(t);
                    } else {
                        boolean include = defaultInclusionCase || includes;
                        Element owner = t.getEnclosingElement();

                        if (owner != null && owner instanceof TypeElement) {
                            ArrayDeque<TypeElement> owners = new ArrayDeque<>();
                            while (owner != null && owner instanceof TypeElement) {
                                owners.push((TypeElement) owner);
                                owner = owner.getEnclosingElement();
                            }

                            //find the first owning class that is part of our model
                            List<TypeElement> siblings = environment.getTree().getRootsUnsafe().stream().map(
                                    org.revapi.java.model.TypeElement::getDeclaringElement).collect(Collectors.toList());

                            while (!owners.isEmpty()) {
                                if (ignored.contains(owners.peek()) || siblings.contains(owners.peek())) {
                                    break;
                                }
                                owners.pop();
                            }

                            //if the user doesn't want this type included explicitly, we need to check in the parents
                            //if some of them wasn't explicitly excluded
                            if (!includes && !owners.isEmpty()) {
                                do {
                                    TypeElement o = owners.pop();
                                    include = !ignored.contains(o) && siblings.contains(o);
                                    siblings = ElementFilter.typesIn(o.getEnclosedElements());
                                } while (include && !owners.isEmpty());
                            }
                        }

                        if (include) {
                            placeInTree(r);
                            r.modelElement.setRawUseSites(r.useSites);
                            types.add(r);
                            environment.setSuperTypes(r.javacElement,
                                    r.superTypes.stream().map(tr -> tr.javacElement).collect(toList()));
                            r.modelElement.setInApi(r.inApi);
                            r.modelElement.setInApiThroughUse(r.inApiThroughUse);
                        }
                    }
                }
            });

            return types;
        }

        private void determineApiStatus() {
            Set<TypeRecord> undetermined = new HashSet<>(this.types.values());
            while (!undetermined.isEmpty()) {
                undetermined = undetermined.stream()
                        .filter(tr -> !tr.explicitlyExcluded)
                        .filter(tr -> tr.inApi)
                        .flatMap(tr -> tr.usedTypes.entrySet().stream()
                                .map(e -> new AbstractMap.SimpleImmutableEntry<>(tr, e)))
                        .filter(e -> movesToApi(e.getValue().getKey()))
                        .flatMap(e -> e.getValue().getValue().stream())
                        .filter(usedTr -> !usedTr.inApi)
                        .filter(usedTr -> !usedTr.explicitlyExcluded)
                        .map(usedTr -> {
                            usedTr.inApi = true;
                            usedTr.inApiThroughUse = true;
                            return usedTr;
                        })
                        .collect(toSet());
            }
        }

        private void moveInnerClassesOfPrimariesToApi() {
            Set<TypeRecord> primaries = this.types.values().stream()
                    .filter(tr -> tr.primaryApi)
                    .filter(tr -> tr.inApi)
                    .filter(tr -> tr.nestingDepth == 0)
                    .collect(toSet());

            while (!primaries.isEmpty()) {
                primaries = primaries.stream()
                        .flatMap(tr -> tr.usedTypes.getOrDefault(UseSite.Type.CONTAINS, Collections.emptySet()).stream())
                        .filter(containedTr -> containedTr.modelElement != null)
                        .filter(containedTr -> !shouldBeIgnored(containedTr.modelElement.getDeclaringElement()))
                        .map(containedTr -> {
                            containedTr.inApi = true;
                            return containedTr;
                        })
                        .collect(toSet());
            }
        }

        private void removeAnnotatesUses() {
            Map<TypeElement, Boolean> newTypes = requiredTypes.entrySet().stream().filter(e -> {
                boolean isAnno = e.getValue();
                if (isAnno) {
                    this.types.get(e.getKey()).useSites.clear();
                }
                return !isAnno;
            }).collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
            requiredTypes.clear();
            requiredTypes.putAll(newTypes);
        }

        private void initChildren() {
            for (TypeRecord tr : this.types.values()) {
                if (tr.modelElement == null) {
                    continue;
                }

                //the set of methods' override-sensitive signatures - I.e. this is the list of methods
                //actually visible on a type.
                Set<String> methods = new HashSet<>(8);

                Function<JavaElementBase<?, ?>, JavaElementBase<?, ?>> addOverride = e -> {
                    if (e instanceof MethodElement) {
                        MethodElement me = (MethodElement) e;
                        methods.add(getOverrideMapKey(me.getDeclaringElement()));
                    }
                    return e;
                };

                Function<JavaElementBase<?, ?>, JavaElementBase<?, ?>> initChildren = e -> {
                    initNonClassElementChildrenAndMoveToApi(tr, e, false);
                    return e;
                };

                //add declared stuff
                tr.accessibleDeclaredNonClassMembers.stream()
                        .map(e ->
                                elementFor(e, e.asType(), environment, tr.modelElement.getArchive()))
                        .map(addOverride)
                        .map(initChildren)
                        .forEach(c -> tr.modelElement.getChildren().add(c));

                tr.inaccessibleDeclaredNonClassMembers.stream()
                        .map(e ->
                                elementFor(e, e.asType(), environment, tr.modelElement.getArchive()))
                        .map(addOverride)
                        .map(initChildren)
                        .forEach(c -> tr.modelElement.getChildren().add(c));

                //now add inherited stuff
                tr.superTypes.forEach(str -> addInherited(tr, str, methods));

                //and finally the annotations
                for (AnnotationMirror m : tr.javacElement.getAnnotationMirrors()) {
                    tr.modelElement.getChildren().add(new AnnotationElement(environment, tr.modelElement.getArchive(), m));
                }
            }
        }

        private void addInherited(TypeRecord target, TypeRecord superType, Set<String> methodOverrideMap) {
            Types types = environment.getTypeUtils();

            superType.accessibleDeclaredNonClassMembers.stream()
                    .map(e -> {
                        if (e instanceof ExecutableElement) {
                            ExecutableElement me = (ExecutableElement) e;
                            if (!shouldAddInheritedMethodChild(me, methodOverrideMap)) {
                                return null;
                            }
                        }

                        TypeMirror elementType = types.asMemberOf((DeclaredType) target.javacElement.asType(), e);

                        JavaElementBase<?, ?> element = JavaElementFactory
                                .elementFor(e, elementType, environment, superType.modelElement.getArchive());

                        element.setInherited(true);

                        initNonClassElementChildrenAndMoveToApi(target, element, true);

                        return element;
                    })
                    .filter(e -> e != null)
                    .forEach(c -> target.modelElement.getChildren().add(c));

            for (TypeRecord st : superType.superTypes) {
                addInherited(target, st, methodOverrideMap);
            }
        }

        private boolean shouldAddInheritedMethodChild(ExecutableElement methodElement, Set<String> overrideMap) {
            if (methodElement.getKind() == ElementKind.CONSTRUCTOR) {
                return false;
            }
            String overrideKey = getOverrideMapKey(methodElement);
            boolean alreadyIncludedMethod = overrideMap.contains(overrideKey);
            if (alreadyIncludedMethod) {
                return false;
            } else {
                //remember this to check if the next super type doesn't declare a method this one overrides
                overrideMap.add(overrideKey);
                return true;
            }
        }

        private void initNonClassElementChildrenAndMoveToApi(TypeRecord targetType, JavaElementBase<?, ?> parent,
                                                             boolean inherited) {
            Types types = environment.getTypeUtils();

            if (targetType.inApi && !shouldBeIgnored(parent.getDeclaringElement())) {
                TypeMirror representation = types.asMemberOf(targetType.modelElement.getModelRepresentation(),
                        parent.getDeclaringElement());

                representation.accept(new SimpleTypeVisitor8<Void, Void>() {
                    @Override protected Void defaultAction(TypeMirror e, Void aVoid) {
                        if (e.getKind().isPrimitive() || e.getKind() == TypeKind.VOID) {
                            return null;
                        }

                        TypeElement childType = getTypeElement.visit(e);
                        if (childType != null) {
                            TypeRecord tr = Scanner.this.types.get(childType);
                            if (tr != null && tr.modelElement != null) {
                                if (!tr.inApi) {
                                    tr.inApiThroughUse = true;
                                }
                                tr.inApi = true;
                            }
                        }
                        return null;
                    }

                    @Override public Void visitExecutable(ExecutableType t, Void aVoid) {
                        t.getReturnType().accept(this, null);
                        t.getParameterTypes().forEach(p -> p.accept(this, null));
                        return null;
                    }
                }, null);
            }

            List<? extends Element> children =
                    parent.getDeclaringElement().accept(new SimpleElementVisitor8<List<? extends Element>, Void>() {
                        @Override protected List<? extends Element> defaultAction(Element e, Void aVoid) {
                            return Collections.emptyList();
                        }

                        @Override public List<? extends Element> visitType(TypeElement e, Void aVoid) {
                            return e.getEnclosedElements();
                        }

                        @Override public List<? extends Element> visitExecutable(ExecutableElement e, Void aVoid) {
                            return e.getParameters();
                        }
                    }, null);

            for (Element child : children) {
                if (child.getKind().isClass() || child.getKind().isInterface()) {
                    continue;
                }

                TypeMirror representation;
                if (child.getKind() == ElementKind.METHOD || child.getKind() == ElementKind.CONSTRUCTOR) {
                    representation = types.asMemberOf(targetType.modelElement.getModelRepresentation(),
                            child);
                } else {
                    representation = child.asType();
                }

                JavaElementBase<?, ?> childEl = JavaElementFactory.elementFor(child, representation, environment,
                        parent.getArchive());

                childEl.setInherited(inherited);

                parent.getChildren().add(childEl);

                initNonClassElementChildrenAndMoveToApi(targetType, childEl, inherited);
            }

            for (AnnotationMirror m : parent.getDeclaringElement().getAnnotationMirrors()) {
                parent.getChildren().add(new AnnotationElement(environment, parent.getArchive(), m));
            }
        }
    }

    private static String getOverrideMapKey(ExecutableElement method) {
        return method.getSimpleName() + "#" + Util.toUniqueString(method.asType());
    }

    private static final class ArchiveLocation implements JavaFileManager.Location {
        private final Archive archive;

        private ArchiveLocation(Archive archive) {
            this.archive = archive;
        }

        Archive getArchive() {
            return archive;
        }

        @Override
        public String getName() {
            return "archiveLocation_" + archive.getName();
        }

        @Override
        public boolean isOutputLocation() {
            return false;
        }
    }

    private static final class SyntheticLocation implements JavaFileManager.Location {
        private final String name = "randomName" + (new Random().nextInt());

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isOutputLocation() {
            return false;
        }
    }

    private static final class TypeRecord {
        Set<ClassPathUseSite> useSites = new HashSet<>(2);
        TypeElement javacElement;
        org.revapi.java.model.TypeElement modelElement;
        Map<UseSite.Type, Set<TypeRecord>> usedTypes = new EnumMap<>(UseSite.Type.class);
        Set<Element> accessibleDeclaredNonClassMembers = new HashSet<>(4);
        Set<Element> inaccessibleDeclaredNonClassMembers = new HashSet<>(4);
        //important for this to be a linked hashset so that superclasses are processed prior to implemented interfaces
        Set<TypeRecord> superTypes = new LinkedHashSet<>(2);
        boolean explicitlyExcluded;
        boolean explicitlyIncluded;
        boolean inApi;
        boolean inApiThroughUse;
        boolean primaryApi;
        int nestingDepth;
        boolean errored;

        @Override public String toString() {
            final StringBuilder sb = new StringBuilder("TypeRecord[");
            sb.append("inApi=").append(inApi);
            sb.append(", modelElement=").append(modelElement);
            sb.append(']');
            return sb.toString();
        }
    }

    private static boolean movesToApi(UseSite.Type useType) {
        return useType.isMovingToApi();
    }

    static boolean shouldBeIgnored(Element element) {
        return Collections.disjoint(element.getModifiers(), ACCESSIBLE_MODIFIERS);
    }
}
