package com.example.offlineroutingapp.nearby

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class LogicalGoManager(
    private val context: Context,
    private val myNodeId: String
) {
    private val prefs = context.getSharedPreferences(
        "logical_go_state",
        Context.MODE_PRIVATE
    )

    private val votes = ConcurrentHashMap<String, MutableSet<String>>()
    private val candidates = ConcurrentHashMap.newKeySet<String>()

    var currentLogicalGoId: String?
        private set

    init {
        currentLogicalGoId = prefs.getString(KEY_LOGICAL_GO_ID, null)
    }

    fun getCurrentGo(): String? {
        return currentLogicalGoId
    }

    fun isMeLogicalGo(): Boolean {
        return currentLogicalGoId == myNodeId
    }

    fun setLogicalGo(nodeId: String) {
        currentLogicalGoId = nodeId
        prefs.edit()
            .putString(KEY_LOGICAL_GO_ID, nodeId)
            .apply()

        Log.d(TAG, "Logical GO set to: $nodeId")
    }

    fun clearElection() {
        votes.clear()
        candidates.clear()
    }

    fun addCandidate(candidateId: String) {
        candidates.add(candidateId)
        Log.d(TAG, "Candidate added: $candidateId")
    }

    fun addVote(candidateId: String, voterId: String) {
        votes.compute(candidateId) { _, voters ->
            val set = voters ?: mutableSetOf()
            set.add(voterId)
            set
        }

        Log.d(TAG, "Vote added: voter=$voterId candidate=$candidateId")
    }

    fun electWinner(activeNodeIds: List<String>): String? {
        val validCandidates = candidates.filter { activeNodeIds.contains(it) }

        if (validCandidates.isEmpty()) {
            val fallback = activeNodeIds.sorted().firstOrNull()
            if (fallback != null) {
                setLogicalGo(fallback)
            }
            return fallback
        }

        val winner = validCandidates.maxWithOrNull(
            compareBy<String> { votes[it]?.size ?: 0 }
                .thenBy { it }
        )

        if (winner != null) {
            setLogicalGo(winner)
        }

        return winner
    }

    fun isCurrentGoAlive(activeNodeIds: List<String>): Boolean {
        val go = currentLogicalGoId ?: return false
        return activeNodeIds.contains(go)
    }

    companion object {
        private const val TAG = "LogicalGoManager"
        private const val KEY_LOGICAL_GO_ID = "current_logical_go_id"
    }
}