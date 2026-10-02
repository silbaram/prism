package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.EventDefinitionEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class EventSuggestions(val names: List<String>, val truncated: Boolean)
data class CatalogEvent(val name: String, val description: String, val registered: Boolean,
                        val events: Long, val experiments: Long, val lastOccurredAt: LocalDateTime?)
data class EventCatalog(val items: List<CatalogEvent>, val truncated: Boolean)

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class EventCatalogService(private val definitions: EventDefinitionRepository,
                          private val experiments: ExperimentRepository,
                          private val conversions: ConversionLogRepository) {
    fun suggestions(query: String = ""): EventSuggestions {
        require(query.length <= 255) { "검색어는 255자 이하여야 합니다." }
        val literal = query.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        val page = PageRequest.of(0, 101)
        val searches = listOf(definitions::findNames, experiments::findGoalNames,
            experiments::findGuardrailNames, conversions::findEventNames)
        val names = searches.flatMap { search ->
            val matches = search("%$literal%", page)
            // An exact name can sort after hundreds of substring matches. Probe only truncated sources.
            if (query.isNotBlank() && matches.size == page.pageSize && query !in matches)
                matches + search(literal, PageRequest.of(0, 1))
            else matches
        }.filter(String::isNotBlank).distinct().sortedWith(compareBy<String> { it != query }.thenBy { it })
        return EventSuggestions(names.take(100), names.size > 100)
    }

    fun catalog(query: String): EventCatalog {
        val candidates = suggestions(query)
        if (candidates.names.isEmpty()) return EventCatalog(emptyList(), candidates.truncated)
        val registered = definitions.findByNameIn(candidates.names).associateBy { it.name }
        val observed = conversions.observeEventNames(candidates.names).associateBy { it.eventName }
        return EventCatalog(candidates.names.map { name ->
            val item = observed[name]
            CatalogEvent(name, registered[name]?.description.orEmpty(), name in registered,
                item?.events ?: 0, item?.experiments ?: 0, item?.lastOccurredAt)
        }, candidates.truncated)
    }

    @Transactional
    fun register(name: String, description: String) {
        require(name.isNotBlank() && name.length <= 255 && '\n' !in name && '\r' !in name) {
            "이벤트 이름은 줄바꿈 없는 1–255자여야 합니다. 대소문자와 앞뒤 공백을 구분합니다."
        }
        require(description.length <= 1000) { "설명은 1,000자 이하여야 합니다." }
        require(!definitions.existsByName(name)) { "이미 등록한 이벤트 이름입니다." }
        definitions.saveAndFlush(EventDefinitionEntity(name = name, description = description))
    }
}
