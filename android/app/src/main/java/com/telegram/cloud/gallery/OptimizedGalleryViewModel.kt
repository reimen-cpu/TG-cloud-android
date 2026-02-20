package com.telegram.cloud.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import com.telegram.cloud.data.database.CloudFileDao
import com.telegram.cloud.data.paging.CloudFilePagingSource
import com.telegram.cloud.utils.performance.PerformanceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OptimizedGalleryViewModel @Inject constructor(
    private val cloudFileDao: CloudFileDao
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortBy = MutableStateFlow(CloudFilePagingSource.SortOption.DATE_MODIFIED)
    val sortBy: StateFlow<CloudFilePagingSource.SortOption> = _sortBy.asStateFlow()

    private val _sortOrder = MutableStateFlow(CloudFilePagingSource.SortOrder.DESC)
    val sortOrder: StateFlow<CloudFilePagingSource.SortOrder> = _sortOrder.asStateFlow()

    private var pagingJob: Job? = null

    val pager = combine(
        searchQuery.debounce(300),
        sortBy,
        sortOrder
    ) { query, sortBy, sortOrder ->
        createPager(query, sortBy, sortOrder)
    }

    private fun createPager(
        query: String,
        sortBy: CloudFilePagingSource.SortOption,
        sortOrder: CloudFilePagingSource.SortOrder
    ) = Pager(
        config = PagingConfig(
            pageSize = 50,
            enablePlaceholders = false,
            initialLoadSize = 50,
            prefetchDistance = 25
        )
    ) {
        CloudFilePagingSource(
            dao = cloudFileDao,
            query = query,
            sortBy = sortBy,
            sortOrder = sortOrder
        )
    }.flow.cachedIn(viewModelScope)

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        PerformanceMonitor.recordMetric("search_query_update", 1)
    }

    fun updateSortBy(sortBy: CloudFilePagingSource.SortOption) {
        _sortBy.value = sortBy
        PerformanceMonitor.recordMetric("sort_by_update", 1)
    }

    fun updateSortOrder(sortOrder: CloudFilePagingSource.SortOrder) {
        _sortOrder.value = sortOrder
        PerformanceMonitor.recordMetric("sort_order_update", 1)
    }

    fun refresh() {
        PerformanceMonitor.recordMetric("gallery_refresh", 1)
        // Cancel current paging job and start fresh
        pagingJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        pagingJob?.cancel()
        PerformanceMonitor.recordMetric("gallery_viewmodel_cleared", 1)
    }
}