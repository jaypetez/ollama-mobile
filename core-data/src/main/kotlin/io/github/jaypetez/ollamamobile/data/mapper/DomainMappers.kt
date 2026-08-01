package io.github.jaypetez.ollamamobile.data.mapper

import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.AttachmentKind
import io.github.jaypetez.ollamamobile.model.AttachmentRef
import io.github.jaypetez.ollamamobile.model.ChatMessage
import io.github.jaypetez.ollamamobile.model.Conversation
import io.github.jaypetez.ollamamobile.model.ConversationId
import io.github.jaypetez.ollamamobile.model.DocumentId
import io.github.jaypetez.ollamamobile.model.GenerationStats
import io.github.jaypetez.ollamamobile.model.MessageId
import io.github.jaypetez.ollamamobile.model.MessageStatus
import io.github.jaypetez.ollamamobile.model.ModelCapability
import io.github.jaypetez.ollamamobile.model.ModelId
import io.github.jaypetez.ollamamobile.model.ModelOrigin
import io.github.jaypetez.ollamamobile.model.ModelRef
import io.github.jaypetez.ollamamobile.model.Quantization
import io.github.jaypetez.ollamamobile.model.Role
import io.github.jaypetez.ollamamobile.model.SamplingParams
import io.github.jaypetez.ollamamobile.model.SecretRef
import io.github.jaypetez.ollamamobile.model.ServerAuth
import io.github.jaypetez.ollamamobile.model.ServerId
import io.github.jaypetez.ollamamobile.model.ServerRef
import io.github.jaypetez.ollamamobile.storage.entity.AttachmentEntity
import io.github.jaypetez.ollamamobile.storage.entity.ConversationEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageEntity
import io.github.jaypetez.ollamamobile.storage.entity.MessageStatusColumn
import io.github.jaypetez.ollamamobile.storage.entity.ModelEntity
import io.github.jaypetez.ollamamobile.storage.entity.ModelOriginColumn
import io.github.jaypetez.ollamamobile.storage.entity.ServerAuthColumn
import io.github.jaypetez.ollamamobile.storage.entity.ServerConfigEntity

// The single translation layer between `:core-storage` rows and `:core-model`
// types.
//
// `internal` on purpose. These functions take Room entities, and a repository
// that leaked one would force `:app` — and every test of it — onto the storage
// module's classpath, which is precisely the coupling the aggregation layer
// exists to absorb.

// ---------------------------------------------------------------------------
// Conversations
// ---------------------------------------------------------------------------

internal fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = ConversationId(id),
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId?.let(::ModelId),
    systemPrompt = systemPrompt,
    sampling = toSampling(),
)

internal fun ConversationEntity.toSampling(): SamplingParams = SamplingParams(
    temperature = temperature,
    topP = topP,
    topK = topK,
    minP = minP,
    repeatPenalty = repeatPenalty,
    repeatLastN = repeatLastN,
    seed = seed,
    numPredict = numPredict,
    numCtx = numCtx,
    stop = stopSequences,
)

internal fun Conversation.toEntity(pinned: Boolean = false, archived: Boolean = false): ConversationEntity =
    ConversationEntity(
        id = id.value,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        modelId = modelId?.value,
        systemPrompt = systemPrompt,
        pinned = pinned,
        archived = archived,
        temperature = sampling.temperature,
        topP = sampling.topP,
        topK = sampling.topK,
        minP = sampling.minP,
        repeatPenalty = sampling.repeatPenalty,
        repeatLastN = sampling.repeatLastN,
        seed = sampling.seed,
        numPredict = sampling.numPredict,
        numCtx = sampling.numCtx,
        stopSequences = sampling.stop,
    )

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------

internal fun MessageEntity.toDomain(attachments: List<AttachmentRef> = emptyList()): ChatMessage = ChatMessage(
    id = MessageId(uuid),
    conversationId = ConversationId(conversationId),
    role = Role.fromWire(role),
    content = content,
    createdAt = createdAt,
    reasoning = reasoning,
    status = toStatus(),
    stats = toStats(),
    attachments = attachments,
)

/**
 * Restores the status column.
 *
 * The failure *type* is not persisted — the schema has one `errorMessage`
 * column, not a discriminator plus a payload — so a failure read back after a
 * restart is [AppError.Unexpected] carrying the original sentence. That is the
 * honest classification: at this point nobody can tell whether the original
 * cause was a timeout or a rejected token, and guessing would send the UI to a
 * recovery screen that cannot help.
 */
internal fun MessageEntity.toStatus(): MessageStatus = when (status) {
    MessageStatusColumn.PENDING -> MessageStatus.Pending

    MessageStatusColumn.FAILED -> MessageStatus.Failed(
        AppError.Unexpected(message = errorMessage ?: "The response could not be completed."),
    )

    else -> MessageStatus.Complete
}

/**
 * Rebuilds the counters, or returns null when the row carries none.
 *
 * Null and not [GenerationStats.Empty]: `ChatMessage.stats == null` renders no
 * throughput line at all, whereas an Empty instance is a present-but-blank
 * object that a careless UI turns into "0 tok/s".
 */
internal fun MessageEntity.toStats(): GenerationStats? {
    val stats = GenerationStats(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        promptEvalNanos = promptEvalNanos,
        evalNanos = evalNanos,
        loadNanos = loadNanos,
        totalNanos = totalNanos,
    )
    return stats.takeUnless { it.isEmpty }
}

internal fun ChatMessage.toEntity(modelId: ModelId? = null): MessageEntity = MessageEntity(
    uuid = id.value,
    conversationId = conversationId.value,
    role = role.wireName,
    content = content,
    createdAt = createdAt,
    reasoning = reasoning,
    status = status.toColumn(),
    errorMessage = (status as? MessageStatus.Failed)?.error?.message,
    modelId = modelId?.value,
    promptTokens = stats?.promptTokens,
    completionTokens = stats?.completionTokens,
    promptEvalNanos = stats?.promptEvalNanos,
    evalNanos = stats?.evalNanos,
    loadNanos = stats?.loadNanos,
    totalNanos = stats?.totalNanos,
)

internal fun MessageStatus.toColumn(): String = when (this) {
    is MessageStatus.Pending -> MessageStatusColumn.PENDING
    is MessageStatus.Complete -> MessageStatusColumn.COMPLETE
    is MessageStatus.Failed -> MessageStatusColumn.FAILED
}

internal fun AttachmentEntity.toDomain(): AttachmentRef = AttachmentRef(
    id = id,
    kind = AttachmentKind.entries.firstOrNull { it.name == kind } ?: AttachmentKind.OTHER,
    uri = uri,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    displayName = displayName,
    documentId = documentId?.let(::DocumentId),
)

internal fun AttachmentRef.toEntity(messageId: MessageId): AttachmentEntity = AttachmentEntity(
    id = id,
    messageUuid = messageId.value,
    kind = kind.name,
    uri = uri,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    displayName = displayName,
    documentId = documentId?.value,
)

// ---------------------------------------------------------------------------
// Servers
// ---------------------------------------------------------------------------

internal fun ServerConfigEntity.toDomain(): ServerRef = ServerRef(
    id = ServerId(id),
    label = label,
    baseUrl = baseUrl,
    auth = toAuth(),
    pinnedCertSha256 = pinnedCertSha256,
    allowPinnedSelfSignedTls = allowPinnedSelfSignedTls,
    enabled = enabled,
    lastSeenAt = lastSeenAt,
)

/**
 * A row whose discriminator says "bearer" but whose alias column is null is a
 * half-written edit, and it maps to [ServerAuth.None] rather than to a bearer
 * scheme with no token: an unauthenticated request produces a 401 the UI can
 * explain, while a `SecretRef("")` produces a lookup miss that looks like a
 * Keystore failure.
 */
internal fun ServerConfigEntity.toAuth(): ServerAuth = when (authType) {
    ServerAuthColumn.BEARER -> {
        tokenRefAlias
            ?.let { ServerAuth.BearerToken(SecretRef(it)) }
            ?: ServerAuth.None
    }

    ServerAuthColumn.BASIC -> {
        val user = username
        val alias = passwordRefAlias
        if (user != null && alias != null) ServerAuth.BasicAuth(user, SecretRef(alias)) else ServerAuth.None
    }

    else -> {
        ServerAuth.None
    }
}

internal fun ServerRef.toEntity(sortOrder: Int = 0): ServerConfigEntity = ServerConfigEntity(
    id = id.value,
    label = label,
    baseUrl = baseUrl,
    authType = when (auth) {
        is ServerAuth.None -> ServerAuthColumn.NONE
        is ServerAuth.BearerToken -> ServerAuthColumn.BEARER
        is ServerAuth.BasicAuth -> ServerAuthColumn.BASIC
    },
    tokenRefAlias = (auth as? ServerAuth.BearerToken)?.tokenRef?.alias,
    username = (auth as? ServerAuth.BasicAuth)?.username,
    passwordRefAlias = (auth as? ServerAuth.BasicAuth)?.passwordRef?.alias,
    pinnedCertSha256 = pinnedCertSha256,
    allowPinnedSelfSignedTls = allowPinnedSelfSignedTls,
    enabled = enabled,
    lastSeenAt = lastSeenAt,
    sortOrder = sortOrder,
)

// ---------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------

internal fun ModelEntity.toDomain(): ModelRef = ModelRef(
    id = ModelId(id),
    displayName = displayName,
    name = name,
    origin = toOrigin(),
    parameterCount = parameterCount,
    quantization = quantization?.let { label -> Quantization.entries.firstOrNull { it.name == label } },
    sizeBytes = sizeBytes,
    contextLength = contextLength,
    capabilities = capabilities
        .mapNotNull { name -> ModelCapability.entries.firstOrNull { it.name == name } }
        .toSet()
        .ifEmpty { setOf(ModelCapability.CHAT) },
    chatTemplate = chatTemplate,
)

/**
 * A local row with no path, or a remote row with no server, is unusable and
 * becomes a catalogue entry rather than a target the router might pick.
 * Fabricating a [ModelOrigin.Local] with an empty path would put a model in
 * the picker that cannot be loaded by anything.
 */
internal fun ModelEntity.toOrigin(): ModelOrigin = when (originType) {
    ModelOriginColumn.LOCAL -> localPath?.let(ModelOrigin::Local) ?: ModelOrigin.Catalog("", name)
    ModelOriginColumn.REMOTE -> serverId?.let { ModelOrigin.Remote(ServerId(it)) } ?: ModelOrigin.Catalog("", name)
    else -> ModelOrigin.Catalog(catalogRepo.orEmpty(), catalogFile ?: name)
}

internal fun ModelRef.toEntity(installedAt: Long? = null): ModelEntity = ModelEntity(
    id = id.value,
    displayName = displayName,
    name = name,
    originType = when (origin) {
        is ModelOrigin.Local -> ModelOriginColumn.LOCAL
        is ModelOrigin.Remote -> ModelOriginColumn.REMOTE
        is ModelOrigin.Catalog -> ModelOriginColumn.CATALOG
    },
    localPath = (origin as? ModelOrigin.Local)?.path,
    serverId = (origin as? ModelOrigin.Remote)?.serverId?.value,
    catalogRepo = (origin as? ModelOrigin.Catalog)?.repo,
    catalogFile = (origin as? ModelOrigin.Catalog)?.file,
    parameterCount = parameterCount,
    quantization = quantization?.name,
    sizeBytes = sizeBytes,
    contextLength = contextLength,
    capabilities = capabilities.map { it.name }.toSet(),
    chatTemplate = chatTemplate,
    installedAt = installedAt,
)
