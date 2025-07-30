package org.cdpg.dx.database.postgres.models;

import java.util.List;
import org.cdpg.dx.common.util.PaginationInfo;
import org.cdpg.dx.database.postgres.base.entity.BaseEntity;

public record PaginatedResult<T extends BaseEntity<T>>(
    PaginationInfo paginationInfo, List<T> data) {}
