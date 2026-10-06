package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.catalogTypeKey
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.stableKey
import kotlinx.coroutines.Job

internal fun HomeViewModel.catalogKey(addonId: String, type: String, catalogId: String): String {
    return catalogRowLegacyKey(addonId, type, catalogId)
}

internal fun HomeViewModel.buildHomeCatalogLoadSignature(addons: List<Addon>): String {
    val addonCatalogSignature = addons
        .flatMap { addon ->
            addon.catalogs.map { catalog ->
                val extraSignature = catalog.extra.joinToString(";") { extra ->
                    listOf(
                        extra.name,
                        extra.isRequired.toString(),
                        extra.options.orEmpty().joinToString("|"),
                        extra.defaultValue.orEmpty(),
                        extra.optionsLimit?.toString().orEmpty()
                    ).joinToString(":")
                }
                listOf(
                    addon.id,
                    addon.baseUrl,
                    addon.version,
                    addon.configVersion?.toString().orEmpty(),
                    addon.manifestLanguage.orEmpty(),
                    addon.rawTypes.joinToString("|"),
                    addon.resources.joinToString("|") { resource ->
                        listOf(
                            resource.name,
                            resource.types.joinToString("/"),
                            resource.idPrefixes.orEmpty().joinToString("/")
                        ).joinToString(":")
                    },
                    addon.idPrefixes.joinToString("|"),
                    catalog.apiType,
                    catalog.id,
                    catalog.name,
                    catalog.showInHome.toString(),
                    catalog.hasExplicitShowInHome.toString(),
                    catalog.pageSize?.toString().orEmpty(),
                    catalog.extraSupported.joinToString("|"),
                    catalog.extraRequired.joinToString("|"),
                    extraSignature
                ).joinToString("|")
            }
        }
        // Keep addon + manifest catalog order significant. Sorting here made a pure reorder
        // produce the same signature, so Home could keep the old row order indefinitely.
        .joinToString(separator = ",")
    val disabledSignature = disabledHomeCatalogKeys
        .asSequence()
        .sorted()
        .joinToString(separator = ",")
    return "$addonCatalogSignature::$disabledSignature"
}

internal fun HomeViewModel.registerCatalogLoadJob(job: Job) {
    synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.add(job)
    }
    job.invokeOnCompletion {
        synchronized(activeCatalogLoadJobs) {
            activeCatalogLoadJobs.remove(job)
        }
    }
}

internal fun HomeViewModel.cancelInFlightCatalogLoads() {
    val jobsToCancel = synchronized(activeCatalogLoadJobs) {
        activeCatalogLoadJobs.toList().also { activeCatalogLoadJobs.clear() }
    }
    jobsToCancel.forEach { it.cancel() }
}

private fun HomeViewModel.reindexCatalogRow(
    key: String,
    previousRow: CatalogRow?,
    updatedRow: CatalogRow?
) {
    previousRow?.items?.forEach { item ->
        val keys = catalogItemKeyIndex[item.id] ?: return@forEach
        keys.remove(key)
        if (keys.isEmpty()) {
            catalogItemKeyIndex.remove(item.id)
        }
    }

    updatedRow?.items?.forEach { item ->
        catalogItemKeyIndex.getOrPut(item.id) { LinkedHashSet() }.add(key)
    }
}

internal fun HomeViewModel.hasAnyCatalogRows(): Boolean = synchronized(catalogStateLock) {
    catalogsMap.isNotEmpty()
}

internal fun HomeViewModel.isCatalogOrderEmpty(): Boolean = synchronized(catalogStateLock) {
    catalogOrder.isEmpty()
}

internal fun HomeViewModel.hasCatalogOrderEntries(): Boolean = synchronized(catalogStateLock) {
    catalogOrder.isNotEmpty()
}

internal fun HomeViewModel.readCatalogRow(key: String): CatalogRow? = synchronized(catalogStateLock) {
    catalogsMap[key]
}

internal fun HomeViewModel.replaceCatalogRow(key: String, row: CatalogRow) {
    synchronized(catalogStateLock) {
        val previousRow = catalogsMap[key]
        val previousById = previousRow?.items?.associateBy { it.id }
        val mergedItems = if (previousById != null) {
            row.items.map { newItem ->
                val prev = previousById[newItem.id]
                if (prev?.mdbListRatings != null && newItem.mdbListRatings == null) {
                    newItem.copy(
                        mdbListRatings = prev.mdbListRatings,
                        mdbListRatingOrder = prev.mdbListRatingOrder,
                        imdbRating = prev.mdbListRatings.imdb?.toFloat() ?: newItem.imdbRating
                    )
                } else newItem
            }
        } else row.items
        val mergedRow = if (mergedItems !== row.items) row.copy(items = mergedItems) else row
        catalogsMap.put(key, mergedRow)
        reindexCatalogRow(key, previousRow, mergedRow)
    }
}

internal inline fun HomeViewModel.updateCatalogRow(
    key: String,
    transform: (CatalogRow) -> CatalogRow
): CatalogRow? {
    return synchronized(catalogStateLock) {
        val currentRow = catalogsMap[key] ?: return@synchronized null
        val updatedRow = transform(currentRow)
        if (updatedRow != currentRow) {
            catalogsMap[key] = updatedRow
            reindexCatalogRow(key, currentRow, updatedRow)
        }
        updatedRow
    }
}

internal fun HomeViewModel.clearCatalogData() {
    synchronized(catalogStateLock) {
        catalogsMap.clear()
        catalogItemKeyIndex.clear()
        truncatedRowCache.clear()
        pendingLazyCatalogs.clear()
        placeholderDescriptors.clear()
    }
    lazyLoadRequestedKeys.clear()
}

internal fun HomeViewModel.snapshotCatalogKeys(): Set<String> = synchronized(catalogStateLock) {
    catalogsMap.keys.toSet()
}

internal fun HomeViewModel.snapshotCatalogState(): Pair<List<String>, Map<String, CatalogRow>> = synchronized(catalogStateLock) {
    catalogOrder.toList() to catalogsMap.toMap()
}

// A title can be in several rows with different data: prefer the row the user is on.
internal fun HomeViewModel.findCatalogItemById(itemId: String): MetaPreview? = synchronized(catalogStateLock) {
    val rows = catalogItemKeyIndex[itemId]?.toList().orEmpty().mapNotNull { catalogsMap[it] }
    val focusedRow = liveFocusedRowKey?.let { key -> rows.firstOrNull { it.stableKey() == key } }
    (listOfNotNull(focusedRow) + rows).firstNotNullOfOrNull { row ->
        row.items.firstOrNull { it.id == itemId }
    }
}

internal inline fun HomeViewModel.updateIndexedCatalogItem(
    itemId: String,
    transform: (MetaPreview) -> MetaPreview
): Boolean {
    return synchronized(catalogStateLock) {
        val rowKeys = catalogItemKeyIndex[itemId]?.toList().orEmpty()
        var changed = false

        rowKeys.forEach { key ->
            val row = catalogsMap[key] ?: return@forEach
            val itemIndex = row.items.indexOfFirst { it.id == itemId }
            if (itemIndex < 0) return@forEach

            val updatedItem = transform(row.items[itemIndex])
            if (updatedItem == row.items[itemIndex]) return@forEach

            val mutableItems = row.items.toMutableList()
            mutableItems[itemIndex] = updatedItem
            catalogsMap[key] = row.copy(items = mutableItems)
            truncatedRowCache.remove(key)
            changed = true
        }

        changed
    }
}

internal fun HomeViewModel.getTruncatedRowCacheEntry(key: String): HomeViewModel.TruncatedRowCacheEntry? = synchronized(catalogStateLock) {
    truncatedRowCache[key]
}

internal fun HomeViewModel.putTruncatedRowCacheEntry(key: String, entry: HomeViewModel.TruncatedRowCacheEntry) {
    synchronized(catalogStateLock) {
        truncatedRowCache[key] = entry
    }
}

internal fun HomeViewModel.removeTruncatedRowCacheEntry(key: String) {
    synchronized(catalogStateLock) {
        truncatedRowCache.remove(key)
    }
}

internal fun HomeViewModel.rebuildCatalogOrder(addons: List<Addon>) {
    val defaultOrder = buildDefaultCatalogOrder(addons)
    val collectionKeys = collectionsCache.map { "collection_${it.id}" }
    val allAvailable = (defaultOrder + collectionKeys).toSet()

    if (followAddonsOrderEnabled) {
        val savedValid = homeCatalogOrderKeys.asSequence().filter { it in allAvailable }.distinct().toList()
        val collectionKeysSet = collectionKeys.toSet()
        if (savedValid.isNotEmpty()) {
            val result = mutableListOf<String>()
            var addonPointer = 0
            for (savedKey in savedValid) {
                if (savedKey in collectionKeysSet) result.add(savedKey) else {
                    val targetIdx = defaultOrder.indexOf(savedKey)
                    if (targetIdx >= 0) while (addonPointer <= targetIdx) {
                        val ak = defaultOrder[addonPointer]
                        if (ak !in result) result.add(ak)
                        addonPointer++
                    }
                }
            }
            while (addonPointer < defaultOrder.size) {
                val ak = defaultOrder[addonPointer]
                if (ak !in result) result.add(ak)
                addonPointer++
            }
            for (ck in collectionKeys) if (ck !in result) result.add(ck)
            val normalized = normalizeCollectionBoundaries(result, buildAddonKeyOwnerMap(addons))
            synchronized(catalogStateLock) { catalogOrder.clear(); catalogOrder.addAll(normalized) }
        } else synchronized(catalogStateLock) { catalogOrder.clear(); catalogOrder.addAll(defaultOrder + collectionKeys) }
    } else {
        val savedValid = homeCatalogOrderKeys.asSequence().filter { it in allAvailable }.distinct().toList()
        val savedSet = savedValid.toSet()
        val mergedOrder = savedValid + defaultOrder.filterNot { it in savedSet } + collectionKeys.filterNot { it in savedSet }
        synchronized(catalogStateLock) { catalogOrder.clear(); catalogOrder.addAll(mergedOrder) }
    }
}

private fun HomeViewModel.buildDefaultCatalogOrder(addons: List<Addon>): List<String> {
    val orderedKeys = mutableListOf<String>()
    addons.forEach { addon -> addon.catalogs.filterNot {
        !it.shouldShowOnHome() || isCatalogDisabled(addon.baseUrl, addon.id, it.apiType, it.id, it.name)
    }.forEach { catalog ->
        val key = catalogKey(addon.id, catalog.apiType, catalog.id)
        if (key !in orderedKeys) orderedKeys.add(key)
    } }
    return orderedKeys
}

internal fun HomeViewModel.isCatalogDisabled(addonBaseUrl: String, addonId: String, type: String, catalogId: String, catalogName: String): Boolean {
    if (disableCatalogKey(addonBaseUrl, type, catalogId, catalogName) in disabledHomeCatalogKeys) return true
    return catalogKey(addonId, type, catalogId) in disabledHomeCatalogKeys
}

internal fun HomeViewModel.disableCatalogKey(addonBaseUrl: String, type: String, catalogId: String, catalogName: String): String =
    "${addonBaseUrl}_${catalogTypeKey(type)}_${catalogId}_${catalogName}"

internal fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean = extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }
internal fun CatalogDescriptor.shouldShowOnHome(): Boolean = !isSearchOnlyCatalog() && (!hasExplicitShowInHome || showInHome)
internal fun MetaPreview.hasHeroArtwork(): Boolean = !background.isNullOrBlank()
internal fun HomeViewModel.extractYear(releaseInfo: String?): String? = releaseInfo?.takeIf { it.isNotBlank() }?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value }

private fun buildAddonKeyOwnerMap(addons: List<Addon>): Map<String, String> = buildMap {
    addons.forEach { addon -> addon.catalogs.forEach { catalog -> put(catalogRowLegacyKey(addon.id, catalog.apiType, catalog.id), addon.id) } }
}

private fun normalizeCollectionBoundaries(order: List<String>, addonKeyToOwner: Map<String, String>): List<String> {
    val result = order.toMutableList(); var changed = true
    while (changed) {
        changed = false; var i = 0
        while (i < result.size) {
            val key = result[i]
            if (!key.startsWith("collection_")) { i++; continue }
            val prevOwner = findOwnerBefore(result, i, addonKeyToOwner); val nextOwner = findOwnerAfter(result, i, addonKeyToOwner)
            if (prevOwner != null && nextOwner != null && prevOwner == nextOwner) {
                result.removeAt(i); var insertPos = i
                while (insertPos < result.size && !result[insertPos].startsWith("collection_") && addonKeyToOwner[result[insertPos]] == prevOwner) insertPos++
                result.add(insertPos, key); if (insertPos != i) changed = true; i++
            } else i++
        }
    }
    return result
}

private fun findOwnerBefore(order: List<String>, index: Int, owners: Map<String, String>): String? {
    for (j in index - 1 downTo 0) if (!order[j].startsWith("collection_")) return owners[order[j]]
    return null
}
private fun findOwnerAfter(order: List<String>, index: Int, owners: Map<String, String>): String? {
    for (j in index + 1 until order.size) if (!order[j].startsWith("collection_")) return owners[order[j]]
    return null
}
