package com.pms.common.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Generic offset-pagination envelope: the page's content plus enough metadata for a client to
 * request the next page and know when to stop.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}