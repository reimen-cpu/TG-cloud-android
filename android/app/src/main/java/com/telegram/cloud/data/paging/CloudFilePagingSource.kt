package com.telegram.cloud.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.telegram.cloud.data.database.CloudFileDao
import com.telegram.cloud.data.database.entities.CloudFileEntity
import com.telegram.cloud.utils.performance.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudFilePagingSource(
    private val dao: CloudFileDao,
    private val query: String? = null,
    private val sortBy: SortOption = SortOption.DATE_MODIFIED,
    private val sortOrder: SortOrder = SortOrder.DESC
) : PagingSource<Int, CloudFileEntity>() {

    enum class SortOption {
        DATE_MODIFIED, DATE_UPLOADED, FILE_NAME, FILE_SIZE
    }

    enum class SortOrder {
        ASC, DESC
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CloudFileEntity> {
        return try {
            val page = params.key ?: 0
            val loadSize = params.loadSize

            PerformanceMonitor.measureSuspendOperation("load_cloud_files_page") {
                val files = withContext(Dispatchers.IO) {
                    if (query.isNullOrBlank()) {
                        dao.getFilesPaged(
                            limit = loadSize,
                            offset = page * loadSize,
                            sortBy = sortBy.name,
                            sortOrder = sortOrder.name
                        )
                    } else {
                        dao.searchFilesPaged(
                            query = "%$query%",
                            limit = loadSize,
                            offset = page * loadSize,
                            sortBy = sortBy.name,
                            sortOrder = sortOrder.name
                        )
                    }
                }

                LoadResult.Page(
                    data = files,
                    prevKey = if (page == 0) null else page - 1,
                    nextKey = if (files.size < loadSize) null else page + 1,
                    itemsBefore = page * loadSize,
                    itemsAfter = null
                )
            }
        } catch (e: Exception) {
            PerformanceMonitor.recordMetric("paging_error", 1)
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, CloudFileEntity>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }
}