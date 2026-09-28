package com.example.eco_service.services;

import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.SingularAttribute;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Общие хелперы пагинации и текстового поиска (как в РОИО). */
public final class PageSupport {

    private static final Set<Class<?>> SORTABLE = Set.of(
            String.class,
            Boolean.class, boolean.class,
            Integer.class, int.class,
            Long.class, long.class,
            Double.class, double.class,
            Float.class, float.class,
            LocalDate.class,
            LocalDateTime.class
    );

    private PageSupport() {}

    /** Pageable без Sort — ORDER BY задаётся в Specification (Criteria). */
    public static Pageable pageable(Integer page, Integer size, String ignoredIdField) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 50 : Math.min(size, 500);
        return PageRequest.of(p, s);
    }

    public static boolean wantsPage(Integer page, Integer size) {
        return page != null && size != null;
    }

    public static <T> Specification<T> textSearch(String q, String idAttribute) {
        return textSearch(q, idAttribute, null, null, idAttribute, true);
    }

    public static <T> Specification<T> textSearch(
            String q,
            String idAttribute,
            String sortBy,
            String sortDir,
            String defaultSortField,
            boolean defaultAsc) {
        return (root, query, cb) -> {
            Class<?> resultType = query != null ? query.getResultType() : null;
            boolean isCount = resultType == Long.class || resultType == long.class;
            if (!isCount && query != null) {
                applyBasicOrder(root, query, cb, sortBy, sortDir, defaultSortField, defaultAsc, idAttribute);
            }
            return buildBasicSearchPredicate(root, cb, q, idAttribute);
        };
    }

    private static <T> void applyBasicOrder(
            Root<T> root,
            jakarta.persistence.criteria.CriteriaQuery<?> query,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            String sortBy,
            String sortDir,
            String defaultSortField,
            boolean defaultAsc,
            String idAttribute) {
        String field = resolveSortField(root, sortBy, defaultSortField != null ? defaultSortField : idAttribute);
        boolean asc = resolveAsc(sortBy, sortDir, defaultAsc);
        Path<?> path = root.get(field);
        if ("id_registration".equals(field) && path.getJavaType() == String.class) {
            var registration = path.as(String.class);
            query.orderBy(
                    asc ? cb.asc(cb.length(registration)) : cb.desc(cb.length(registration)),
                    asc ? cb.asc(registration) : cb.desc(registration));
        } else {
            query.orderBy(asc ? cb.asc(path) : cb.desc(path));
        }
    }

    private static <T> Predicate buildBasicSearchPredicate(
            Root<T> root,
            jakarta.persistence.criteria.CriteriaBuilder cb,
            String q,
            String idAttribute) {
        if (q == null || q.isBlank()) {
            return cb.conjunction();
        }
        String like = "%" + q.trim().toLowerCase() + "%";
        List<Predicate> preds = new ArrayList<>();
        root.getModel().getDeclaredSingularAttributes().forEach(attr -> {
            if (attr.getJavaType() == String.class) {
                preds.add(cb.like(cb.lower(root.get(attr.getName())), like));
            }
        });
        String digits = q.trim().replaceAll("\\D", "");
        if (!digits.isEmpty() && idAttribute != null) {
            try {
                long id = Long.parseLong(digits);
                preds.add(cb.equal(root.get(idAttribute), id));
            } catch (IllegalArgumentException ignored) {
                // skip
            }
            root.getModel().getDeclaredSingularAttributes().forEach(attr -> {
                Class<?> t = attr.getJavaType();
                if (t == int.class || t == Integer.class) {
                    try {
                        preds.add(cb.equal(root.get(attr.getName()), Integer.parseInt(digits)));
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                } else if (t == long.class || t == Long.class) {
                    try {
                        preds.add(cb.equal(root.get(attr.getName()), Long.parseLong(digits)));
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
            });
        }
        if (preds.isEmpty()) {
            return cb.conjunction();
        }
        return cb.or(preds.toArray(new Predicate[0]));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean resolveAsc(String sortBy, String sortDir, boolean defaultAsc) {
        if (sortBy != null && !sortBy.isBlank()) {
            return sortDir == null || sortDir.isBlank() || "asc".equalsIgnoreCase(sortDir);
        }
        if (sortDir != null && !sortDir.isBlank()) {
            return "asc".equalsIgnoreCase(sortDir);
        }
        return defaultAsc;
    }

    private static <T> String resolveSortField(Root<T> root, String sortBy, String fallback) {
        if (sortBy != null && !sortBy.isBlank() && isSortable(root, sortBy)) {
            return sortBy;
        }
        if (fallback != null && isSortable(root, fallback)) {
            return fallback;
        }
        for (SingularAttribute<? super T, ?> attr : root.getModel().getSingularAttributes()) {
            if (attr.isId()) {
                return attr.getName();
            }
        }
        return fallback;
    }

    private static <T> boolean isSortable(Root<T> root, String name) {
        try {
            Attribute<? super T, ?> attr = root.getModel().getAttribute(name);
            if (!(attr instanceof SingularAttribute<?, ?> singular)) {
                return false;
            }
            if (singular.isAssociation()) {
                return false;
            }
            return SORTABLE.contains(singular.getJavaType());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
