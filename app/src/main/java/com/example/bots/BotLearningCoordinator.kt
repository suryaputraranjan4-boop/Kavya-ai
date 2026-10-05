package com.example.bots

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Team-wide learning coordinator. Optimizes one bot at a time to keep phone
 * CPU/memory usage bounded.
 */
class BotLearningCoordinator(private val ceo: CeoOrchestrator) {
    suspend fun learnFromFailedOrRepeatedTasks(
        botIds: List<String> = KavyaBotTeam.all
            .filter { it.role != BotRole.CEO }
            .map { it.id }
    ): List<SkillOptProposalView> = withContext(Dispatchers.Default) {
        buildList {
            for (botId in botIds.distinct()) {
                ceo.learn(botId)?.let { add(it) }
            }
        }
    }
}
