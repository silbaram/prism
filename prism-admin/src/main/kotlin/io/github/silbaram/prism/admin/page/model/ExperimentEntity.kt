package io.github.silbaram.prism.admin.page.model

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "experiments")
class ExperimentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(unique = true, nullable = false)
    val key: String, // 예: main_btn_color

    var description: String? = null,

    var trafficPercent: Int = 50, // 0 ~ 100

    var isActive: Boolean = true,

    @ElementCollection(fetch = FetchType.EAGER)
    var variants: MutableList<String> = mutableListOf("A", "B"),

    val createdAt: LocalDateTime = LocalDateTime.now()
)
