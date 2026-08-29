package net.xiidea.enginx.domain.shared;

import java.util.List;
import java.util.function.Function;

/**
 * A page of results. Deliberately independent of Spring Data so that the domain
 * ports stay framework-free (AD-1).
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public PageResult {
        content = List.copyOf(content);
    }

    public int totalPages() {
        return size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(content.stream().map(mapper).toList(), page, size, totalElements);
    }
}
