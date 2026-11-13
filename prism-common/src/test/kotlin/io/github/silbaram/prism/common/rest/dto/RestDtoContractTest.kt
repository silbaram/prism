package io.github.silbaram.prism.common.rest.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.common.rest.dto.config.ConfigResponse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RestDtoContractTest : FunSpec({
    val mapper = jacksonObjectMapper()

    test("ConfigResponse round-trips and keeps default holdout values") {
        val original = ConfigResponse(version = "42", experiments = emptyList())

        val decoded = mapper.readValue(mapper.writeValueAsString(original), ConfigResponse::class.java)

        decoded shouldBe original
        decoded.holdout.key shouldBe "global-v1"
        decoded.holdout.basisPoints shouldBe 0
        decoded.holdout.configured shouldBe false
    }

    test("AssignmentResponse ignores unknown fields and preserves a null variant") {
        val json = """
            {"userId":"user-1","experimentKey":"checkout",
             "variant":null,"resultCode":"0000","resultMessage":"Success","unexpected":"ignored"}
        """.trimIndent()

        val decoded = mapper.readValue(json, AssignmentResponse::class.java)

        decoded.userId shouldBe "user-1"
        decoded.experimentKey shouldBe "checkout"
        decoded.variant shouldBe null
        decoded.resultCode shouldBe "0000"
        decoded.configVersion shouldBe null
    }
}
