package io.github.ichizero.connect.ktor

import build.buf.validate.FieldPath
import build.buf.validate.FieldPathElement
import build.buf.validate.Violation
import com.connectrpc.Code
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException
import com.connectrpc.extensions.GoogleJavaJSONStrategy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

class ErrorPayloadTest : FunSpec({
    val detailParser = GoogleJavaJSONStrategy().errorDetailParser()

    val maxLenViolation = Violation
        .newBuilder()
        .setField(
            FieldPath.newBuilder().addElements(FieldPathElement.newBuilder().setFieldName("sentence")),
        ).setConstraintId("string.max_len")
        .setMessage("value length must be at most 100 characters")
        .build()

    val minLenViolation = Violation
        .newBuilder()
        .setField(
            FieldPath.newBuilder().addElements(FieldPathElement.newBuilder().setFieldName("sentence")),
        ).setConstraintId("string.min_len")
        .setMessage("value length must be at least 1 characters")
        .build()

    test("conversion: serialize and deserialize ConnectException with error details") {
        val base = ConnectException(
            code = Code.INVALID_ARGUMENT,
            message = "message",
        ).withErrorDetails(
            detailParser,
            listOf(
                ConnectErrorDetail(
                    maxLenViolation.descriptorForType.fullName,
                    maxLenViolation.toByteArray().toByteString(),
                ),
                ConnectErrorDetail(
                    minLenViolation.descriptorForType.fullName,
                    minLenViolation.toByteArray().toByteString(),
                ),
            ),
        )

        val encodedPayload = base.toErrorJsonBytes().toString(Charsets.UTF_8)

        val decodedPayload = Json.decodeFromString(ErrorPayload.serializer(), encodedPayload)

        val decoded = ConnectException(
            code = Code.fromName(decodedPayload.code),
            message = decodedPayload.message,
        ).withErrorDetails(
            detailParser,
            decodedPayload.details?.map {
                ConnectErrorDetail(
                    type = it.type,
                    payload = it.value.decodeBase64()!!,
                )
            } ?: emptyList(),
        )

        decoded shouldBe base
        decoded.unpackedDetails(Violation::class) shouldContainExactlyInAnyOrder
            listOf(maxLenViolation, minLenViolation)
    }
})
