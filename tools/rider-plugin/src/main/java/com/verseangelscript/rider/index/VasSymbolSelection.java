package com.verseangelscript.rider.index;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative syntax-only binding, shared by PSI resolution and plain unit tests.
 * Candidates must come from the source file and its include closure, never a project-wide index.
 */
public final class VasSymbolSelection {
    public record Candidate<T>(@NotNull VasSymbol symbol, @NotNull T target) {
    }

    private VasSymbolSelection() {
    }

    public static <T> @NotNull List<T> select(
        @NotNull List<Candidate<T>> source,
        @NotNull List<Candidate<T>> included,
        @NotNull String name,
        int usageOffset,
        @NotNull VasUsageContext context
    ) {
        if (source.stream().anyMatch(candidate -> candidate.symbol().offset() == usageOffset)
            || context.access() == VasUsageContext.Access.UNSUPPORTED
            || context.argumentCount() == VasUsageContext.UNKNOWN_ARGUMENTS) {
            return List.of();
        }
        List<Candidate<T>> visible = new ArrayList<>();
        source.stream().filter(candidate -> candidate.symbol().isProjectVisible()).forEach(visible::add);
        included.stream().filter(candidate -> candidate.symbol().isProjectVisible()).forEach(visible::add);

        List<Candidate<T>> named;
        if (context.access() == VasUsageContext.Access.UNQUALIFIED) {
            named = lookup(source, visible, name, usageOffset, context.container());
        } else {
            String owner = owner(source, visible, usageOffset, context);
            if (owner == null) {
                return List.of();
            }
            named = inContainer(visible, name, owner);
        }
        // Unknown signatures could be the matching overload. Dropping them would make
        // another candidate appear uniquely bound without enough information.
        if (context.argumentCount() >= 0 && named.stream().anyMatch(candidate ->
            candidate.symbol().kind() == VasSymbolKind.FUNCTION
                && candidate.symbol().requiredParameterCount() < 0)) {
            return List.of();
        }
        // Filtering may eliminate every candidate. Never restore rejected declarations.
        return named.stream().filter(candidate -> acceptsArguments(candidate.symbol(), context.argumentCount()))
            .map(Candidate::target).toList();
    }

    private static boolean acceptsArguments(VasSymbol symbol, int arguments) {
        // A class call identifies the type, not a selected constructor overload.
        // Interfaces cannot be instantiated in VAS and remain unsupported here.
        return arguments == VasUsageContext.NOT_A_CALL
            || symbol.kind() == VasSymbolKind.CLASS
            || symbol.kind() == VasSymbolKind.FUNCTION
                && symbol.requiredParameterCount() >= 0
                && arguments >= symbol.requiredParameterCount()
                && arguments <= symbol.parameterCount();
    }

    private static <T> List<Candidate<T>> lookup(
        List<Candidate<T>> source, List<Candidate<T>> visible, String name, int offset, String container
    ) {
        List<Candidate<T>> locals = source.stream()
            .filter(candidate -> candidate.symbol().name().equals(name))
            .filter(candidate -> !candidate.symbol().isProjectVisible() && candidate.symbol().isVisibleAt(offset))
            .toList();
        if (!locals.isEmpty()) {
            int smallestScope = locals.stream().mapToInt(candidate ->
                candidate.symbol().scopeEnd() - candidate.symbol().scopeStart()).min().orElseThrow();
            return locals.stream().filter(candidate ->
                candidate.symbol().scopeEnd() - candidate.symbol().scopeStart() == smallestScope).toList();
        }
        for (String scope : enclosingContainers(container)) {
            List<Candidate<T>> named = inContainer(visible, name, scope);
            if (!named.isEmpty()) {
                return named;
            }
            if (visible.stream().anyMatch(candidate -> candidate.symbol().qualifiedName().equals(scope)
                && !candidate.symbol().baseTypes().isEmpty())) {
                // Inherited member lookup is not implemented. A global of this name
                // must not masquerade as an unrecognized inherited member.
                return List.of();
            }
        }
        return List.of();
    }

    private static <T> String owner(
        List<Candidate<T>> source, List<Candidate<T>> visible, int offset, VasUsageContext context
    ) {
        if (context.access() == VasUsageContext.Access.QUALIFIED) {
            return resolveContainer(visible, context.qualifier(), context.container(), true);
        }
        if ("this".equals(context.qualifier())) {
            return resolveContainer(visible, context.container(), "", false);
        }
        List<Candidate<T>> receivers = lookup(source, visible, context.qualifier(), offset, context.container());
        if (receivers.size() != 1 || receivers.get(0).symbol().kind() != VasSymbolKind.VARIABLE) {
            return null;
        }
        VasSymbol receiver = receivers.get(0).symbol();
        return resolveContainer(visible, receiver.declaredType(),
            receiver.isProjectVisible() ? receiver.container() : context.container(), false);
    }

    private static <T> String resolveContainer(
        List<Candidate<T>> visible, String name, String lexicalContainer, boolean allowNamespace
    ) {
        if (name.isEmpty()) {
            return null;
        }
        for (String scope : enclosingContainers(lexicalContainer)) {
            String qualified = scope.isEmpty() ? name : scope + "::" + name;
            List<Candidate<T>> matches = visible.stream()
                .filter(candidate -> candidate.symbol().qualifiedName().equals(qualified)).toList();
            if (!matches.isEmpty()) {
                // Reopened namespace blocks denote one scope. Duplicate types or aliases do not.
                if (allowNamespace && matches.stream().allMatch(candidate ->
                    candidate.symbol().kind() == VasSymbolKind.NAMESPACE)) {
                    return qualified;
                }
                return matches.size() == 1 && (matches.get(0).symbol().kind() == VasSymbolKind.CLASS
                    || matches.get(0).symbol().kind() == VasSymbolKind.INTERFACE
                    || allowNamespace && matches.get(0).symbol().kind() == VasSymbolKind.ENUM) ? qualified : null;
            }
        }
        return null;
    }

    private static <T> List<Candidate<T>> inContainer(List<Candidate<T>> visible, String name, String container) {
        return visible.stream().filter(candidate -> candidate.symbol().name().equals(name)
            && (candidate.symbol().container().equals(container)
                || candidate.symbol().kind() == VasSymbolKind.ENUM_MEMBER && candidate.symbol().declaredType().equals(container))).toList();
    }

    private static List<String> enclosingContainers(String container) {
        List<String> scopes = new ArrayList<>();
        while (!container.isEmpty()) {
            scopes.add(container);
            int separator = container.lastIndexOf("::");
            container = separator < 0 ? "" : container.substring(0, separator);
        }
        scopes.add("");
        return scopes;
    }
}
