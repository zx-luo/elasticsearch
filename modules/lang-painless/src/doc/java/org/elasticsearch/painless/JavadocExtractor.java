/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.painless;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.github.javaparser.javadoc.description.JavadocDescription;
import com.github.javaparser.javadoc.description.JavadocDescriptionElement;
import com.github.javaparser.javadoc.description.JavadocInlineTag;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class JavadocExtractor {

    private final JavaClassResolver resolver;

    private static final String GPLv2 = "* This code is free software; you can redistribute it and/or modify it"+
        "\n * under the terms of the GNU General Public License version 2 only, as"+
        "\n * published by the Free Software Foundation.";

    public JavadocExtractor(JavaClassResolver resolver) {
        this.resolver = resolver;
    }


    public ParsedJavaClass parseClass(String className) throws IOException {
        InputStream classStream = resolver.openClassFile(className);
        ParsedJavaClass parsed = new ParsedJavaClass(GPLv2);
        if (classStream == null) {
            return parsed;
        }
        ClassFileVisitor visitor = new ClassFileVisitor();
        CompilationUnit cu = StaticJavaParser.parse(classStream);
        visitor.visit(cu, parsed);
        return parsed;
    }

    public static class ParsedJavaClass {
        public final Map<MethodSignature, ParsedMethod> methods;
        public final Map<String, String> fields;
        public final Map<List<String>, ParsedMethod> constructors;
        private final String license;
        private boolean valid = false;
        private boolean validated = false;

        public ParsedJavaClass(String license) {
            methods = new HashMap<>();
            fields = new HashMap<>();
            constructors = new HashMap<>();
            this.license = license;
        }

        public void validateLicense(Optional<Comment> license) {
            if (validated) {
                throw new IllegalStateException("Cannot double validate the license");
            }
            this.valid = license.map(Comment::getContent).orElse("").contains(this.license);
            validated = true;
        }

        public ParsedMethod getMethod(String name, List<String> parameterTypes) {
            return methods.get(new MethodSignature(name, parameterTypes));
        }

        @Override
        public String toString() {
            return "ParsedJavaClass{" +
                "methods=" + methods +
                '}';
        }

        public void putMethod(MethodDeclaration declaration) {
            if (valid == false) {
                return;
            }
            methods.put(
                MethodSignature.fromDeclaration(declaration),
                new ParsedMethod(
                        declaration.getJavadoc().map(JavadocExtractor::clean).orElse(null),
                        declaration.getParameters()
                                .stream()
                                .map(p -> p.getName().asString())
                                .collect(Collectors.toList())
                )
            );
        }

        public void putConstructor(ConstructorDeclaration declaration) {
            if (valid == false) {
                return;
            }
            constructors.put(
                declaration.getParameters().stream().map(p -> stripTypeParameters(p.getType().asString())).collect(Collectors.toList()),
                new ParsedMethod(
                        declaration.getJavadoc().map(JavadocExtractor::clean).orElse(null),
                        declaration.getParameters()
                                .stream()
                                .map(p -> p.getName().asString())
                                .collect(Collectors.toList())
                )
            );
        }

        private static String stripTypeParameters(String type) {
            int start = 0;
            int count = 0;
            for (int i=0; i<type.length(); i++) {
                char c = type.charAt(i);
                if (c == '<') {
                    if (start == 0) {
                        start = i;
                    }
                    count++;
                } else if (c == '>') {
                    count--;
                    if (count == 0) {
                        return type.substring(0, start);
                    }
                }
            }
            return type;
        }

        public ParsedMethod getConstructor(List<String> parameterTypes) {
            return constructors.get(parameterTypes);
        }

        public String getField(String name) {
            return fields.get(name);
        }

        public void putField(FieldDeclaration declaration) {
            if (valid == false) {
                return;
            }
            for (VariableDeclarator var : declaration.getVariables()) {
                fields.put(var.getNameAsString(), declaration.getJavadoc().map(v -> JavadocExtractor.clean(v).description).orElse(""));
            }
        }
    }

    public static class MethodSignature {
        public final String name;
        public final List<String> parameterTypes;

        public MethodSignature(String name, List<String> parameterTypes) {
            this.name = name;
            this.parameterTypes = parameterTypes;
        }

        public static MethodSignature fromDeclaration(MethodDeclaration declaration) {
            return new MethodSignature(
                    declaration.getNameAsString(),
                    declaration.getParameters()
                            .stream()
                            .map(p -> p.getType().asString())
                            .collect(Collectors.toList())
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if ((o instanceof MethodSignature) == false) return false;
            MethodSignature that = (MethodSignature) o;
            return Objects.equals(name, that.name) &&
                Objects.equals(parameterTypes, that.parameterTypes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, parameterTypes);
        }
    }

    public static class ParsedMethod {
        public final ParsedJavadoc javadoc;
        public final List<String> parameterNames;

        public ParsedMethod(ParsedJavadoc javadoc, List<String> parameterNames) {
            this.javadoc = javadoc;
            this.parameterNames = parameterNames;
        }
    }

    public static ParsedJavadoc clean(Javadoc javadoc) {
        JavadocDescription description = javadoc.getDescription();
        List<JavadocBlockTag> tags = javadoc.getBlockTags();
        List<String> cleaned = new ArrayList<>(description.getElements().size() + tags.size());
        cleaned.addAll(stripInlineTags(description));
        ParsedJavadoc parsed = new ParsedJavadoc(cleaned(cleaned));
        for (JavadocBlockTag tag: tags) {
            String tagName = tag.getTagName();
            // https://docs.oracle.com/en/java/javase/14/docs/specs/javadoc/doc-comment-spec.html#standard-tags
            // ignore author, deprecated, hidden, provides, uses, see, serial*, since and version.
            if ("param".equals(tagName)) {
                tag.getName().ifPresent(t -> parsed.param.put(t, cleaned(stripInlineTags(tag.getContent()))));
            } else if ("return".equals(tagName)) {
                parsed.returns = cleaned(stripInlineTags(tag.getContent()));
            } else if ("exception".equals(tagName) || "throws".equals(tagName)) {
                if (tag.getName().isPresent() == false) {
                    throw new IllegalStateException("Missing tag " + tag.toText());
                }
                parsed.thrws.add(org.elasticsearch.common.collect.List.of(tag.getName().get(), cleaned(stripInlineTags(tag.getContent()))));
            }
        }
        return parsed;
    }

    private static String cleaned(List<String> segments) {
        return Jsoup.clean(String.join("", segments), Whitelist.none()).replaceAll("[\n\\s]*\n[\n\\s]*", " ");
    }

    private static List<String> stripInlineTags(JavadocDescription description) {
        List<JavadocDescriptionElement> elements = description.getElements();
        List<String> stripped = new ArrayList<>(elements.size());
        for (JavadocDescriptionElement element: elements) {
            if (element instanceof JavadocInlineTag) {
                stripped.add(((JavadocInlineTag)element).getContent());
            } else {
                stripped.add(element.toText());
            }
        }
        return stripped;
    }

    public static class ParsedJavadoc implements ToXContent {
        public final Map<String, String> param = new HashMap<>();
        public String returns;
        public String description;
        public List<List<String>> thrws = new ArrayList<>();

        public static final ParseField PARAMETERS = new ParseField("parameters");
        public static final ParseField RETURN = new ParseField("return");
        public static final ParseField THROWS = new ParseField("throws");
        public static final ParseField DESCRIPTION = new ParseField("description");

        public ParsedJavadoc(String description) {
            this.description = description;
        }

        public boolean isEmpty() {
            return param.size() == 0 &&
                (description == null || description.isEmpty()) &&
                (returns == null || returns.isEmpty()) &&
                thrws.size() == 0;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            if (description != null && description.isEmpty() == false) {
                builder.field(DESCRIPTION.getPreferredName(), description);
            }
            if (param.isEmpty() == false) {
                builder.field(PARAMETERS.getPreferredName(), param);
            }
            if (returns != null && returns.isEmpty() == false) {
                builder.field(RETURN.getPreferredName(), returns);
            }
            if (thrws.isEmpty() == false) {
                builder.field(THROWS.getPreferredName(), thrws);
            }
            builder.endObject();
            return builder;
        }

        @Override
        public boolean isFragment() {
            return true;
        }
    }

    private static class ClassFileVisitor extends VoidVisitorAdapter<ParsedJavaClass> {
        @Override
        public void visit(CompilationUnit compilationUnit, ParsedJavaClass parsed) {
            parsed.validateLicense(compilationUnit.getComment());
            super.visit(compilationUnit, parsed);
        }

        @Override
        public void visit(MethodDeclaration methodDeclaration, ParsedJavaClass parsed) {
            parsed.putMethod(methodDeclaration);
        }

        @Override
        public void visit(FieldDeclaration fieldDeclaration, ParsedJavaClass parsed) {
            if (fieldDeclaration.hasModifier(Modifier.Keyword.PUBLIC) == false) {
                return;
            }
            parsed.putField(fieldDeclaration);
        }

        @Override
        public void visit(ConstructorDeclaration constructorDeclaration, ParsedJavaClass parsed) {
            parsed.putConstructor(constructorDeclaration);
        }
    }
}