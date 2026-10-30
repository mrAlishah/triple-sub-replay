package com.kienhoang.dualsubreplay.assistant

// Fork-owned (docs/development/fork-customizations.md, FC-02): who really ran each request of an answer.

/** What the transports reported for the requests of one answer. */
internal class AiRunTrace {
    private var execution: AiExecution? = null
    private var reportedModel: String? = null
    private var requests = 0

    fun answered(reply: AiReply) {
        requests++
        reply.execution?.let { execution = it }
        reply.model.takeIf { it.isNotBlank() }?.let { reportedModel = it }
    }

    /** A failure counts as a request only when the transport says one was sent. */
    fun failed(error: AiChatException) {
        error.execution?.let {
            execution = it
            requests++
        }
    }

    fun source(
        status: AiResponseStatus,
        selectedProviderId: String,
        wantedModel: String,
    ): AiResponseSource =
        AiResponseSource(
            providerId = execution?.providerId.orEmpty(),
            auth = execution?.auth,
            api = execution?.api,
            requestedModel = execution?.requestedModel ?: wantedModel,
            reportedModel = reportedModel,
            status = status,
            selectedProviderId = selectedProviderId,
            requests = requests,
            planUsageVerified = execution?.planUsageVerified,
        )
}
