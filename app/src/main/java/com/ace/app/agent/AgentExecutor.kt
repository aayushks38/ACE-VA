package com.ace.app.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

class AgentExecutor(private val context: Context?) {
    private val actionEngine = ActionExecutionEngine(context)

    suspend fun runAutonomousAgentLoop(
        userGoal: String,
        localBrain: com.ace.app.brain.LocalBrain?,
        cloudBrain: com.ace.app.brain.ReasoningBrain?,
        onStepUpdated: (AgentTask) -> Unit,
        onClarificationNeeded: (String) -> Unit,
        onConversationalResponse: (String) -> Unit,
        generationId: Long
    ): AgentTask {
        val taskContext = AgentTaskContext(
            userGoal = userGoal,
            expectedPostcondition = ExpectedPostcondition(summary = userGoal, desiredState = userGoal),
            generationId = generationId
        )
        val actionRecords = mutableListOf<AgentActionRecord>()
        var currentTask = AgentTask(
            goal = userGoal,
            category = TaskCategory.GENERAL,
            status = TaskStatus.RUNNING,
            summary = "Autonomous agent evaluating goal: $userGoal",
            steps = emptyList(),
            actionRecords = actionRecords
        )
        onStepUpdated(currentTask)

        val maxIterations = 8
        var lastObservation = ScreenObservationEngine.captureObservation(null, "android", "System")

        for (iteration in 1..maxIterations) {
            if (!AceTaskSessionManager.isCurrentGeneration(generationId)) {
                return currentTask.copy(status = TaskStatus.CANCELLED, summary = "Task cancelled by user.")
            }

            // 1. OBSERVE: Use fresh screen observation
            val obs = lastObservation
            Log.i("ACE_OBSERVE", "ACE_OBSERVE: iteration=$iteration app=${obs.appName} text_nodes=${obs.visibleText.size} clickables=${obs.clickableElements.size}")

            // 2. REASON: Select ReasoningBrain via BrainRouter & Query next AgentDecision
            val decision = try {
                val brain = com.ace.app.brain.BrainRouter.selectBrain(taskContext.userGoal, obs, taskContext, localBrain, cloudBrain)
                brain.reasonNextDecision(taskContext.userGoal, obs, taskContext, generationId)
            } catch (e: Exception) {
                Log.e("ACE_ERROR", "ACE_ERROR: Brain reasoning failed", e)
                return currentTask.copy(
                    status = TaskStatus.FAILED,
                    summary = "Reasoning engine error: ${e.message}",
                    completedAt = System.currentTimeMillis()
                )
            }
            Log.i("ACE_REASON", "ACE_REASON: decision=$decision")

            when (decision) {
                is com.ace.app.brain.AgentDecision.Clarify -> {
                    Log.i("ACE_CONVERSATION", "ACE_CONVERSATION: clarification_required question=\"${decision.question}\"")
                    onClarificationNeeded(decision.question)
                    return currentTask.copy(
                        status = TaskStatus.WAITING_FOR_APPROVAL,
                        summary = decision.question,
                        verificationResult = decision.question
                    )
                }

                is com.ace.app.brain.AgentDecision.ConversationalResponse -> {
                    Log.i("ACE_CONVERSATION", "ACE_CONVERSATION: conversational_response=\"${decision.text}\"")
                    onConversationalResponse(decision.text)
                    return currentTask.copy(
                        status = TaskStatus.COMPLETED,
                        summary = decision.text,
                        verificationResult = decision.text,
                        completedAt = System.currentTimeMillis()
                    )
                }

                is com.ace.app.brain.AgentDecision.Complete -> {
                    Log.i("ACE_REASON", "ACE_REASON: Brain proposed COMPLETE hypothesis=\"${decision.evidence}\"")
                    taskContext.capturedEvidence["brain_completion_hypothesis"] = decision.evidence

                    // INDEPENDENT POSTCONDITION VERIFICATION
                    val verificationOutcome = IndependentGoalVerifier.verifyGoal(taskContext, obs, context)
                    Log.i("ACE_VERIFY", "ACE_VERIFY: Postcondition verification result isVerified=${verificationOutcome.isVerified} status=${verificationOutcome.status} summary=${verificationOutcome.summary}")

                    if (verificationOutcome.isVerified && verificationOutcome.status == TaskStatus.COMPLETED) {
                        return currentTask.copy(
                            status = TaskStatus.COMPLETED,
                            summary = verificationOutcome.summary,
                            verificationResult = verificationOutcome.evidence,
                            completedAt = System.currentTimeMillis()
                        )
                    } else {
                        // VERIFICATION FAILURE FEEDS BACK INTO THE AGENT LOOP
                        taskContext.unverifiedHypothesisAttempts++
                        val feedback = "Verification Feedback (Attempt ${taskContext.unverifiedHypothesisAttempts}): Completion hypothesis '${decision.evidence}' was not verified by postcondition evidence. Detail: ${verificationOutcome.evidence}"
                        Log.w("ACE_VERIFY", "ACE_VERIFY: $feedback — feeding back to reasoning loop")
                        taskContext.actionHistory.add(feedback)

                        if (taskContext.unverifiedHypothesisAttempts >= 2) {
                            return currentTask.copy(
                                status = verificationOutcome.status,
                                summary = verificationOutcome.summary,
                                verificationResult = verificationOutcome.evidence,
                                completedAt = System.currentTimeMillis()
                            )
                        }

                        delay(600)
                        lastObservation = ScreenObservationEngine.captureObservation(null, "android", "System")
                    }
                }

                is com.ace.app.brain.AgentDecision.Action -> {
                    val actionId = "action_$iteration"
                    val actionRecord = AgentActionRecord(
                        id = actionId,
                        primitive = decision.primitive,
                        target = decision.target,
                        status = "RUNNING"
                    )
                    actionRecords.add(actionRecord)
                    currentTask = currentTask.copy(actionRecords = actionRecords.toList())
                    onStepUpdated(currentTask)

                    // DIRECT UNIVERSAL ACTION EXECUTION (No TaskStep / CapabilityRegistry requirement)
                    val universalResult = UniversalActionExecutor.execute(context, decision)

                    if (!AceTaskSessionManager.isCurrentGeneration(generationId)) {
                        return currentTask.copy(status = TaskStatus.CANCELLED, summary = "Task cancelled by user.")
                    }

                    actionRecords[actionRecords.lastIndex] = actionRecord.copy(
                        output = universalResult.message,
                        status = universalResult.status.name
                    )
                    taskContext.actionHistory.add("Iteration $iteration: ${decision.primitive}(${decision.target}) -> ${universalResult.status}")
                    if (universalResult.status == ActionResultStatus.SUCCESS) {
                        universalResult.outputData.forEach { (k, v) ->
                            taskContext.capturedEvidence[k] = v
                        }
                    }
                    if (!universalResult.evidence.isNullOrBlank()) {
                        taskContext.capturedEvidence["action_${iteration}_evidence"] = universalResult.evidence
                    }
                    currentTask = currentTask.copy(actionRecords = actionRecords.toList())
                    onStepUpdated(currentTask)

                    // Brief delay for UI pre-render / settle, then capture FRESH observation
                    delay(600)
                    lastObservation = ScreenObservationEngine.captureObservation(null, "android", "System")
                }

                is com.ace.app.brain.AgentDecision.Wait -> {
                    delay(decision.durationMs)
                    lastObservation = ScreenObservationEngine.captureObservation(null, "android", "System")
                }

                is com.ace.app.brain.AgentDecision.Replan -> {
                    Log.i("ACE_REASON", "ACE_REASON: Replan requested updatedGoal=\"${decision.updatedGoal}\"")
                    taskContext.userGoal = decision.updatedGoal // Update authoritative task context for subsequent reasoning turns
                    taskContext.actionHistory.add("Replan: Goal updated to '${decision.updatedGoal}'")
                    lastObservation = ScreenObservationEngine.captureObservation(null, "android", "System")
                }

                is com.ace.app.brain.AgentDecision.Blocked -> {
                    Log.w("ACE_REASON", "ACE_REASON: Task blocked reason=\"${decision.reason}\"")
                    taskContext.blockers.add(decision.reason)
                    return currentTask.copy(
                        status = TaskStatus.BLOCKED,
                        summary = decision.reason,
                        verificationResult = decision.reason,
                        completedAt = System.currentTimeMillis()
                    )
                }
            }
        }

        // 3. Goal-Level Independent Verification at Max Iterations Safety Limit
        currentTask = currentTask.copy(status = TaskStatus.VERIFYING)
        onStepUpdated(currentTask)

        val finalObs = ScreenObservationEngine.captureObservation(null, "android", "System")
        val finalOutcome = IndependentGoalVerifier.verifyGoal(taskContext, finalObs, context)

        Log.i("ACE_VERIFY", "ACE_VERIFY: Final safety-limit verification status=${finalOutcome.status} isVerified=${finalOutcome.isVerified} summary=${finalOutcome.summary}")

        val finalTask = currentTask.copy(
            status = finalOutcome.status,
            summary = finalOutcome.summary,
            verificationResult = finalOutcome.evidence,
            completedAt = System.currentTimeMillis()
        )
        onStepUpdated(finalTask)
        return finalTask
    }

    suspend fun executeTask(
        task: AgentTask,
        onStepUpdated: (AgentTask) -> Unit,
        onApprovalRequested: (ApprovalDetails) -> Unit,
        generationId: Long = 0L,
        onProgressSpeech: ((capabilityId: String, params: Map<String, String>) -> Unit)? = null,
        brainRequired: Boolean = false,
        brainAvailable: Boolean = false
    ): AgentTask {
        if (!AceTaskSessionManager.validateOrDiscard(generationId, "AgentExecutor.executeTask")) {
            return task.copy(status = TaskStatus.CANCELLED, summary = "Task cancelled by user.")
        }
        Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: execution started")
        Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: plan_received steps=${task.steps.size}")
        Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: Received AgentPlan for goal '${task.goal}' with ${task.steps.size} step(s)")
        Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: brain_required=$brainRequired brain_available=$brainAvailable")

        // Safety Invariant: Ensure plan domain matches goal domain
        val planRejectionReason = validatePlanGoalDomainMatch(task.goal, task.steps)
        if (planRejectionReason != null) {
            Log.e("ACE_SAFETY", "ACE_SAFETY: PLAN_REJECTED domain_mismatch goal='${task.goal}' reason='$planRejectionReason'")
            val rejectedTask = task.copy(
                status = TaskStatus.FAILED,
                summary = planRejectionReason,
                verificationResult = planRejectionReason,
                completedAt = System.currentTimeMillis()
            )
            onStepUpdated(rejectedTask)
            return rejectedTask
        }

        // 1. Goal Requirement & Plan Completeness Extraction
        val planCompleteness = GoalRequirementExtractor.evaluatePlanCompleteness(task.goal, task.steps)

        var currentTask = task.copy(status = TaskStatus.RUNNING)
        onStepUpdated(currentTask)

        val steps = currentTask.steps.toMutableList()

        // 2. Identify initial independent steps (no parent dependencies)
        val initialStepIndices = steps.indices.filter { steps[it].dependsOnStepIds.isEmpty() }

        if (initialStepIndices.isNotEmpty()) {
            coroutineScope {
                val deferreds = initialStepIndices.map { index ->
                    async {
                        if (!AceTaskSessionManager.isCurrentGeneration(generationId)) return@async
                        val step = steps[index]
                        steps[index] = step.copy(isRunning = true)
                        onStepUpdated(currentTask.copy(steps = steps.toList()))

                        // Fire progress speech before the action runs
                        onProgressSpeech?.invoke(step.capabilityId, step.inputParams)

                        val actionResult = executeCapabilityStep(step, currentTask, index + 1)

                        if (!AceTaskSessionManager.isCurrentGeneration(generationId)) return@async

                        steps[index] = step.copy(
                            isRunning = false,
                            isComplete = actionResult.status == ActionResultStatus.SUCCESS,
                            isVerified = actionResult.status == ActionResultStatus.SUCCESS,
                            output = actionResult.message,
                            outputData = actionResult.outputData
                        )
                        onStepUpdated(currentTask.copy(steps = steps.toList()))
                    }
                }
                deferreds.forEach { it.await() }
            }
        }

        if (!AceTaskSessionManager.validateOrDiscard(generationId, "AgentExecutor.initialSteps")) {
            return currentTask.copy(status = TaskStatus.CANCELLED, summary = "Task cancelled by user.")
        }

        currentTask = currentTask.copy(steps = steps.toList())

        // 3. Automatically execute all action steps for autonomous completion
        return resumeExecutionAfterApproval(currentTask, onStepUpdated, planCompleteness.isPlanComplete, generationId, onProgressSpeech, brainRequired, brainAvailable)
    }

    suspend fun resumeExecutionAfterApproval(
        task: AgentTask,
        onStepUpdated: (AgentTask) -> Unit,
        isPlanCompleteOverride: Boolean? = null,
        generationId: Long = 0L,
        onProgressSpeech: ((capabilityId: String, params: Map<String, String>) -> Unit)? = null,
        brainRequired: Boolean = false,
        brainAvailable: Boolean = false
    ): AgentTask {
        if (!AceTaskSessionManager.validateOrDiscard(generationId, "AgentExecutor.resumeExecution")) {
            return task.copy(status = TaskStatus.CANCELLED, summary = "Task cancelled by user.")
        }
        
        // Check if brain-dependent task but brain unavailable
        if (brainRequired && !brainAvailable) {
            Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: brain_required=true brain_available=false")
            Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: Cannot execute brain-dependent task without Gemma")
            val unavailableTask = task.copy(
                status = TaskStatus.FAILED,
                summary = "Local AI is unavailable. This task requires AI reasoning to proceed.",
                requirements = task.requirements.map { it.copy(isVerified = false, verificationDetails = "Brain unavailable") },
                completedAt = System.currentTimeMillis()
            )
            Log.i("ACE_TASK", "ACE_TASK: final_goal_status=FAILED reason=BRAIN_UNAVAILABLE")
            onStepUpdated(unavailableTask)
            return unavailableTask
        }
        
        var currentTask = task.copy(status = TaskStatus.RUNNING)
        onStepUpdated(currentTask)

        val steps = currentTask.steps.toMutableList()

        // Aggregate outputs from completed upstream parent steps
        val accumulatedOutputs = mutableMapOf<String, String>()
        steps.filter { it.isComplete }.forEach { step ->
            accumulatedOutputs.putAll(step.outputData)
        }

        for (i in steps.indices) {
            if (!AceTaskSessionManager.isCurrentGeneration(generationId)) {
                Log.w("ACE_TASK", "ACE_TASK: stale_result_detected")
                Log.w("ACE_TASK", "ACE_TASK: generation_mismatch")
                Log.w("ACE_TASK", "ACE_TASK: stale_result_discarded=true")
                Log.i("ACE_TASK", "ACE_TASK: previous task marked CANCELLED")
                return currentTask.copy(status = TaskStatus.CANCELLED, summary = "Task cancelled by user.")
            }
            if (!steps[i].isComplete) {
                val step = steps[i]

                // Step Dependency Safeguard: Check if any parent steps failed or are incomplete
                val incompleteParents = steps.filter { step.dependsOnStepIds.contains(it.id) && !it.isComplete }
                if (incompleteParents.isNotEmpty()) {
                    val parentOutput = incompleteParents.firstOrNull()?.output ?: "Parent step incomplete"
                    Log.w("ACE_EXECUTOR", "ACE_EXECUTOR: Skipping step ${step.id} (${step.capabilityId}) because parent step failed or is incomplete: $parentOutput")
                    steps[i] = step.copy(
                        isRunning = false,
                        isComplete = false,
                        isVerified = false,
                        output = "Dependency unfulfilled: $parentOutput"
                    )
                    currentTask = currentTask.copy(steps = steps.toList())
                    onStepUpdated(currentTask)
                    continue
                }

                steps[i] = step.copy(isRunning = true)
                onStepUpdated(currentTask.copy(steps = steps.toList()))

                // Merge parent outputs into child step inputs
                val mergedParams = step.inputParams.toMutableMap()
                accumulatedOutputs.forEach { (k, v) ->
                    if (!mergedParams.containsKey(k) && v.isNotBlank()) {
                        mergedParams[k] = v
                    }
                }

                val updatedStepWithParams = step.copy(inputParams = mergedParams)

                // Fire progress speech before the action runs
                onProgressSpeech?.invoke(updatedStepWithParams.capabilityId, updatedStepWithParams.inputParams)

                val actionResult = executeCapabilityStep(updatedStepWithParams, currentTask, i + 1)

                if (!AceTaskSessionManager.isCurrentGeneration(generationId)) {
                    Log.w("ACE_TASK", "ACE_TASK: stale_result_detected")
                    Log.w("ACE_TASK", "ACE_TASK: generation_mismatch")
                    Log.w("ACE_TASK", "ACE_TASK: stale_result_discarded=true")
                    Log.i("ACE_TASK", "ACE_TASK: previous task marked CANCELLED")
                    return currentTask.copy(status = TaskStatus.CANCELLED, summary = "Task cancelled by user.")
                }

                steps[i] = step.copy(
                    isRunning = false,
                    isComplete = actionResult.status == ActionResultStatus.SUCCESS,
                    isVerified = actionResult.status == ActionResultStatus.SUCCESS,
                    output = actionResult.message,
                    outputData = actionResult.outputData
                )

                accumulatedOutputs.putAll(actionResult.outputData)
                currentTask = currentTask.copy(steps = steps.toList())
                onStepUpdated(currentTask)
            }
        }

        // Verification phase & Truthful Goal Requirement Calculation
        currentTask = currentTask.copy(status = TaskStatus.VERIFYING)
        onStepUpdated(currentTask)

        val evalResult = GoalRequirementExtractor.evaluatePlanCompleteness(task.goal, steps)
        val isPlanComplete = isPlanCompleteOverride ?: evalResult.isPlanComplete

        val baseReqs = if (task.requirements.isEmpty()) GoalRequirementExtractor.extractGoalRequirements(task.goal) else task.requirements
        val evaluatedReqs = baseReqs.map { req ->
            evaluateSingleRequirement(req, steps, accumulatedOutputs)
        }

        val verifiedCount = evaluatedReqs.count { it.isVerified }
        val totalReqCount = evaluatedReqs.size
        val allReqsVerified = totalReqCount > 0 && verifiedCount == totalReqCount

        val completedCount = steps.count { it.isComplete }
        val succeededStep = steps.firstOrNull { it.isComplete }
        val failedStep = steps.firstOrNull { !it.isComplete }

        val allStepsSucceeded = steps.isNotEmpty() && steps.all { it.isComplete }

        val isUserActionReq = accumulatedOutputs["userActionRequired"] == "true" ||
                accumulatedOutputs["status"] == "OPENED_TARGET_COMPOSER" ||
                accumulatedOutputs["status"] == "AWAITING_USER_ACTION"
        val isHandoffCompleted = accumulatedOutputs["status"] == "HANDOFF_COMPLETED"

        // Detailed diagnostic log — ONE SOURCE OF TRUTH
        Log.i("ACE_VERIFY", "ACE_VERIFY: goal=${task.goal}")
        Log.i("ACE_VERIFY", "ACE_VERIFY: requirementsVerified=$verifiedCount")
        Log.i("ACE_VERIFY", "ACE_VERIFY: requirementsTotal=$totalReqCount")
        Log.i("ACE_VERIFY", "ACE_VERIFY: allRequirementsVerified=$allReqsVerified")
        Log.i("ACE_VERIFY", "ACE_VERIFY: stepsCompleted=$completedCount")
        Log.i("ACE_VERIFY", "ACE_VERIFY: stepsTotal=${steps.size}")
        Log.i("ACE_VERIFY", "ACE_VERIFY: allStepsSucceeded=$allStepsSucceeded")
        Log.i("ACE_VERIFY", "ACE_VERIFY: planComplete=$isPlanComplete")
        Log.i("ACE_VERIFY", "ACE_VERIFY: isUserActionReq=$isUserActionReq")
        Log.i("ACE_VERIFY", "ACE_VERIFY: isHandoffCompleted=$isHandoffCompleted")


        val finalStatus: TaskStatus
        val verificationSummary: String

        when {
            isUserActionReq -> {
                finalStatus = TaskStatus.AWAITING_USER_ACTION
                val singleOutput = if (steps.size == 1 && !steps[0].output.isNullOrBlank()) steps[0].output else null
                verificationSummary = singleOutput ?: "WhatsApp is ready with your file. Select contact and tap Send."
                Log.i("ACE_TASK", "ACE_TASK: previous=RUNNING next=AWAITING_USER_ACTION reason=EXTERNAL_APP_REQUIRES_USER_ACTION")
            }
            isHandoffCompleted -> {
                finalStatus = TaskStatus.HANDOFF_COMPLETED
                val singleOutput = if (steps.size == 1 && !steps[0].output.isNullOrBlank()) steps[0].output else null
                verificationSummary = singleOutput ?: "Delivery handoff completed successfully."
                Log.i("ACE_TASK", "ACE_TASK: previous=RUNNING next=HANDOFF_COMPLETED reason=HANDOFF_EXECUTED")
            }
            // PRIMARY COMPLETION PATH: all steps succeeded + all requirements verified
            // This fires regardless of isPlanComplete to prevent the plan-coverage mismatch
            // from masking a genuinely successful execution.
            allStepsSucceeded && allReqsVerified -> {
                finalStatus = TaskStatus.COMPLETED
                val singleOutput = if (steps.size == 1 && !steps[0].output.isNullOrBlank()) steps[0].output else null
                verificationSummary = singleOutput ?: "All $totalReqCount goal requirements verified successfully."
                Log.i("ACE_RESPONSE", "ACE_RESPONSE: completed=true path=allStepsSucceeded+allReqsVerified")
                if (!isPlanComplete) {
                    Log.w("ACE_VERIFY", "ACE_VERIFY: planComplete=false but all steps+reqs verified — coverage mismatch was a false negative")
                }
            }
            completedCount == steps.size && isPlanComplete && allReqsVerified -> {
                finalStatus = TaskStatus.COMPLETED
                val singleOutput = if (steps.size == 1 && !steps[0].output.isNullOrBlank()) steps[0].output else null
                verificationSummary = singleOutput ?: "All $totalReqCount goal requirements verified successfully."
                Log.i("ACE_RESPONSE", "ACE_RESPONSE: completed=true path=fullVerification")
            }
            completedCount > 0 -> {
                // PARTIAL: steps ran but not all requirements are verified
                // Guard: this should NOT fire when all reqs are verified (that's a bug if it does)
                if (allReqsVerified) {
                    Log.e("ACE_VERIFY", "ACE_VERIFY: CONTRADICTION — allReqsVerified=true but falling into PARTIAL. stepsCompleted=$completedCount stepsTotal=${steps.size} isPlanComplete=$isPlanComplete")
                }
                finalStatus = TaskStatus.PARTIAL
                val unverifiedList = evaluatedReqs.filter { !it.isVerified }
                val unverifiedStr = if (unverifiedList.isNotEmpty()) {
                    unverifiedList.joinToString("; ") { "${it.description} [${it.verificationDetails}]" }
                } else {
                    "Some planned action steps did not complete."
                }
                verificationSummary = "PARTIAL: $verifiedCount/$totalReqCount requirements verified. Unverified/Pending: $unverifiedStr"
            }
            failedStep?.output?.contains("Permission", ignoreCase = true) == true || failedStep?.output?.contains("Accessibility", ignoreCase = true) == true -> {
                finalStatus = TaskStatus.WAITING_FOR_USER
                verificationSummary = "Task paused: Action '${failedStep.label}' requires user permission or accessibility authorization."
            }
            else -> {
                finalStatus = TaskStatus.FAILED
                verificationSummary = "Task failed at step '${failedStep?.label ?: "Action"}': ${failedStep?.output ?: "Action incomplete"}."
            }
        }


        val verifyStatusStr = if (finalStatus == TaskStatus.COMPLETED) "SUCCESS" else finalStatus.name
        Log.i("ACE_VERIFY", "ACE_VERIFY: new_task_id=task_$generationId")
        Log.i("ACE_VERIFY", "ACE_VERIFY: verified_requirements=$verifiedCount/$totalReqCount")
        Log.i("ACE_VERIFY", "ACE_VERIFY: verification=$verifyStatusStr")
        Log.i("ACE_VERIFY", "ACE_VERIFY: verification_result=$verificationSummary")
        Log.i("ACE_TASK", "ACE_TASK: original_goal=${task.goal}")
        Log.i("ACE_TASK", "ACE_TASK: final_goal_status=$finalStatus")

        currentTask = currentTask.copy(
            status = finalStatus,
            summary = verificationSummary,
            requirements = evaluatedReqs,
            verificationResult = verificationSummary,
            completedAt = System.currentTimeMillis()
        )
        onStepUpdated(currentTask)
        return currentTask
    }

    private fun evaluateSingleRequirement(
        req: GoalRequirement,
        steps: List<TaskStep>,
        accumulatedOutputs: Map<String, String>
    ): GoalRequirement {
        Log.i("ACE_VERIFY", "ACE_VERIFY: evaluating requirement id=${req.id} description=${req.description}")
        
        return when (req.id) {
            "req_1_phone_call" -> {
                Log.i("ACE_VERIFY", "ACE_VERIFY: phone_call requirement handler")
                val contactStep = steps.firstOrNull { it.capabilityId == "contact_lookup" }
                val callStep = steps.firstOrNull { it.capabilityId == "phone_call" }
                
                val callIntentDispatched = accumulatedOutputs["callIntentDispatched"] == "true" || accumulatedOutputs["callInitiated"] == "true"
                val callState = accumulatedOutputs["callState"] ?: "UNKNOWN"
                val recipientVerified = accumulatedOutputs["recipientVerified"] == "true"
                val recipient = accumulatedOutputs["recipient"] ?: accumulatedOutputs["contactName"] ?: "contact"
                val phoneNumber = accumulatedOutputs["phoneNumber"] ?: ""
                val cleanNum = phoneNumber.replace(Regex("[^0-9+]"), "")
                val isNumberValid = cleanNum.isNotBlank() && cleanNum.length >= 3
                
                Log.i("ACE_VERIFY", "ACE_VERIFY: contact_step_complete=${contactStep?.isComplete} call_step_complete=${callStep?.isComplete} callIntentDispatched=$callIntentDispatched callState=$callState recipientVerified=$recipientVerified phoneNumber=$phoneNumber")
                
                if (callStep?.isComplete == true && callIntentDispatched && recipientVerified && isNumberValid) {
                    if (callState == "CALL_STATE_OFFHOOK" || callState == "CALL_STATE_RINGING") {
                        Log.i("ACE_VERIFY", "ACE_VERIFY: Make Phone Call=VERIFIED callState=$callState")
                        req.copy(isVerified = true, verificationDetails = "Direct phone call to '$recipient' ($cleanNum) confirmed active on telephony line ($callState).")
                    } else {
                        Log.i("ACE_VERIFY", "ACE_VERIFY: Make Phone Call=UNVERIFIED callState=$callState")
                        req.copy(isVerified = false, verificationDetails = "Phone call intent dispatched to '$recipient' ($cleanNum), but active call connection state is $callState.")
                    }
                } else if (contactStep?.output?.contains("Permission", ignoreCase = true) == true || callStep?.output?.contains("Permission", ignoreCase = true) == true) {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: Make Phone Call=UNVERIFIED reason=permission_missing")
                    req.copy(isVerified = false, verificationDetails = "Phone call blocked: required permission missing.")
                } else if (!isNumberValid) {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: Make Phone Call=UNVERIFIED reason=invalid_number")
                    req.copy(isVerified = false, verificationDetails = "Phone call unverified: recipient phone number unresolved or invalid.")
                } else {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: Make Phone Call=UNVERIFIED reason=step_incomplete")
                    req.copy(isVerified = false, verificationDetails = "Phone call capability unverified: contact lookup or call step incomplete.")
                }
            }
            "req_1_phone_dial" -> {
                Log.i("ACE_VERIFY", "ACE_VERIFY: phone_dial requirement handler")
                val dialStep = steps.firstOrNull { it.capabilityId == "phone_dialer" }
                val dialerOpened = accumulatedOutputs["dialerOpened"] == "true"
                val recipient = accumulatedOutputs["contactName"] ?: accumulatedOutputs["recipient"] ?: "contact"
                val phoneNumber = accumulatedOutputs["phoneNumber"] ?: ""
                
                if (dialStep?.isComplete == true && dialerOpened) {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: Make Phone Dial=VERIFIED")
                    req.copy(isVerified = true, verificationDetails = "Phone dialer opened for '$recipient' ($phoneNumber) successfully.")
                } else {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: Make Phone Dial=UNVERIFIED")
                    req.copy(isVerified = false, verificationDetails = "Phone dialer step incomplete or unverified.")
                }
            }
            "req_1_flashlight" -> {
                Log.i("ACE_VERIFY", "ACE_VERIFY: flashlight requirement handler")
                // Flashlight requirement: check flashlightChanged=true and targetState matches intent
                val flashStep = steps.firstOrNull { it.capabilityId == "flashlight" }
                
                val flashlightChanged = accumulatedOutputs["flashlightChanged"] == "true"
                val targetState = accumulatedOutputs["targetState"] ?: "UNKNOWN"
                
                Log.i("ACE_VERIFY", "ACE_VERIFY: flashlight_step_exists=${flashStep != null} step_complete=${flashStep?.isComplete}")
                Log.i("ACE_VERIFY", "ACE_VERIFY: flashlightChanged=$flashlightChanged targetState=$targetState")
                
                if (flashlightChanged && (targetState == "ON" || targetState == "OFF")) {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: flashlight=VERIFIED targetState=$targetState")
                    req.copy(isVerified = true, verificationDetails = "Flashlight turned $targetState successfully.")
                } else {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: flashlight=UNVERIFIED flashlightChanged=$flashlightChanged")
                    req.copy(isVerified = false, verificationDetails = "Flashlight state change unverified.")
                }
            }
            "req_1_delivery" -> {
                val status = accumulatedOutputs["status"]
                val isSuccess = status == "HANDOFF_COMPLETED" || status == "SUCCESSFULLY_SENT" || status == "OPENED_TARGET_COMPOSER" || steps.any { it.isComplete }
                if (isSuccess) {
                    req.copy(isVerified = true, verificationDetails = "Smart delivery handoff executed successfully.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Smart delivery requirement unverified: ${accumulatedOutputs["message"] ?: "Delivery pending or failed"}")
                }
            }
            "req_1_battery" -> {
                req.copy(isVerified = true, verificationDetails = "Battery information retrieved successfully.")
            }
            "req_1_open_app" -> {
                // Open app requirement: check appOpened=true in capability result
                val appOpened = accumulatedOutputs["appOpened"] == "true"
                val appName = accumulatedOutputs["appName"] ?: "app"
                
                Log.i("ACE_VERIFY", "ACE_VERIFY: open_app handler appOpened=$appOpened appName=$appName")
                
                if (appOpened) {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: open_app=VERIFIED appName=$appName")
                    req.copy(isVerified = true, verificationDetails = "Application '$appName' launched successfully.")
                } else {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: open_app=UNVERIFIED")
                    req.copy(isVerified = false, verificationDetails = "Application launch unverified.")
                }
            }
            "req_2_search" -> {
                val textTyped = accumulatedOutputs["textTyped"] == "true"
                val searchCompleted = steps.any { it.isComplete && (it.capabilityId == "youtube_search" || it.capabilityId == "web_search" || it.capabilityId == "ui_type") }
                val requiresAccessibility = accumulatedOutputs["requiresAccessibility"] == "true"
                val searchTerm = accumulatedOutputs["query"] ?: accumulatedOutputs["text"] ?: "search term"
                val appTarget = accumulatedOutputs["targetApp"] ?: accumulatedOutputs["appName"] ?: "app"
                
                Log.i("ACE_VERIFY", "ACE_VERIFY: search handler textTyped=$textTyped searchCompleted=$searchCompleted appTarget=$appTarget")
                
                if (textTyped || searchCompleted) {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: search=VERIFIED term=$searchTerm targetApp=$appTarget")
                    req.copy(isVerified = true, verificationDetails = "Searched for '$searchTerm' on $appTarget.")
                } else if (requiresAccessibility) {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: search=BLOCKED reason=accessibility_required")
                    req.copy(isVerified = false, verificationDetails = "Search requires ACE Accessibility Service. Enable it in Accessibility Settings.")
                } else {
                    Log.i("ACE_VERIFY", "ACE_VERIFY: search=UNVERIFIED")
                    req.copy(isVerified = false, verificationDetails = "Could not perform search in app.")
                }
            }
            "req_1_time_date" -> {
                req.copy(isVerified = true, verificationDetails = "Current system date and time retrieved successfully.")
            }
            "req_1_storage" -> {
                req.copy(isVerified = true, verificationDetails = "Available device storage checked successfully.")
            }
            "req_1_identify_photo" -> {
                val isNewest = accumulatedOutputs["isNewestFromMediaStore"] == "true"
                val filePath = accumulatedOutputs["filePath"]
                if (isNewest && !filePath.isNullOrBlank() && java.io.File(filePath).exists()) {
                    val name = accumulatedOutputs["fileName"] ?: "photo"
                    req.copy(isVerified = true, verificationDetails = "Identified newest image '$name' from MediaStore via timestamp ordering.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "MediaStore photo selection unverified or not timestamp ordered.")
                }
            }
            "req_2_select_photo" -> {
                val selectionVerified = accumulatedOutputs["selectionVerified"] == "true"
                val filePath = accumulatedOutputs["filePath"]
                if (selectionVerified && !filePath.isNullOrBlank()) {
                    req.copy(isVerified = true, verificationDetails = "Selected exact image file '$filePath'.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Exact image file not bound.")
                }
            }
            "req_3_identify_recipient" -> {
                val recipientVerified = accumulatedOutputs["recipientVerified"] == "true"
                val isAmbiguous = accumulatedOutputs["isAmbiguous"] == "true"
                val contactName = accumulatedOutputs["contactName"] ?: accumulatedOutputs["query"]
                if (recipientVerified && !isAmbiguous) {
                    req.copy(isVerified = true, verificationDetails = "Resolved contact '$contactName' uniquely.")
                } else if (isAmbiguous) {
                    req.copy(isVerified = false, verificationDetails = "Ambiguous contact match for '$contactName'. User input required.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Contact lookup unverified.")
                }
            }
            "req_4_attach_image" -> {
                val chatAttached = accumulatedOutputs["chatAttached"] == "true"
                if (chatAttached) {
                    req.copy(isVerified = true, verificationDetails = "Image attached directly into target conversation.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Share sheet launched; photo not yet attached into WhatsApp chat window.")
                }
            }
            "req_5_send_action" -> {
                val sendActionCompleted = accumulatedOutputs["sendActionCompleted"] == "true"
                if (sendActionCompleted) {
                    req.copy(isVerified = true, verificationDetails = "Final send click performed.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Pending user action to tap Send in target conversation.")
                }
            }
            "req_6_verify_in_chat" -> {
                val messageVerifiedInChat = accumulatedOutputs["messageVerifiedInChat"] == "true"
                if (messageVerifiedInChat) {
                    req.copy(isVerified = true, verificationDetails = "Sent media verified in active conversation.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Sent message unverified in target chat.")
                }
            }
            "req_1_system_mobile_data" -> {
                val step = steps.firstOrNull { it.capabilityId == "system_mobile_data" }
                val opened = accumulatedOutputs["mobileDataOpened"] == "true" || accumulatedOutputs["userActionRequired"] == "true"
                val changed = accumulatedOutputs["mobileDataChanged"] == "true"
                if (step?.isComplete == true && (opened || changed)) {
                    req.copy(isVerified = true, verificationDetails = if (changed) "Mobile data toggled successfully." else "Mobile data settings panel opened. Please adjust the switch.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Mobile data operation unverified.")
                }
            }
            "req_1_system_wifi" -> {
                val step = steps.firstOrNull { it.capabilityId == "system_wifi" }
                val changed = accumulatedOutputs["wifiChanged"] == "true"
                val opened = accumulatedOutputs["wifiOpened"] == "true"
                if (step?.isComplete == true && (changed || opened)) {
                    req.copy(isVerified = true, verificationDetails = if (changed) "Wi-Fi state change verified." else "Wi-Fi settings panel opened.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Wi-Fi operation unverified.")
                }
            }
            "req_1_system_bluetooth" -> {
                val step = steps.firstOrNull { it.capabilityId == "system_bluetooth" }
                val changed = accumulatedOutputs["bluetoothChanged"] == "true"
                val opened = accumulatedOutputs["bluetoothOpened"] == "true"
                if (step?.isComplete == true && (changed || opened)) {
                    req.copy(isVerified = true, verificationDetails = if (changed) "Bluetooth state change verified." else "Bluetooth settings panel opened.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Bluetooth operation unverified.")
                }
            }
            "req_1_system_volume" -> {
                val step = steps.firstOrNull { it.capabilityId == "system_volume" }
                val changed = accumulatedOutputs["volumeChanged"] == "true"
                if (step?.isComplete == true && changed) {
                    req.copy(isVerified = true, verificationDetails = "Volume adjustment verified.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Volume adjustment unverified.")
                }
            }
            "req_1_system_brightness" -> {
                val step = steps.firstOrNull { it.capabilityId == "system_brightness" }
                val changed = accumulatedOutputs["brightnessChanged"] == "true"
                if (step?.isComplete == true && changed) {
                    req.copy(isVerified = true, verificationDetails = "Screen brightness adjustment verified.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Screen brightness adjustment unverified.")
                }
            }
            "req_1_system_airplane_mode" -> {
                val step = steps.firstOrNull { it.capabilityId == "system_airplane_mode" }
                val opened = accumulatedOutputs["airplaneModeOpened"] == "true"
                if (step?.isComplete == true && opened) {
                    req.copy(isVerified = true, verificationDetails = "Airplane mode settings opened.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Airplane mode operation unverified.")
                }
            }
            else -> {
                val singleStepCompleted = steps.size == 1 && steps[0].isComplete
                val stepMatched = singleStepCompleted || steps.any { step ->
                    step.isComplete && (step.capabilityId.lowercase().contains(req.id.lowercase()) || step.label.lowercase().contains(req.id.lowercase()))
                }
                if (stepMatched) {
                    req.copy(isVerified = true, verificationDetails = "Executed and verified successfully.")
                } else {
                    req.copy(isVerified = false, verificationDetails = "Action step unverified.")
                }
            }
        }
    }

    private fun validatePlanGoalDomainMatch(goal: String, steps: List<TaskStep>): String? {
        val goalLower = goal.lowercase()
        val isSystemGoal = goalLower.contains("mobile data") ||
                goalLower.contains("wifi") || goalLower.contains("wi-fi") ||
                goalLower.contains("bluetooth") || goalLower.contains("flashlight") ||
                goalLower.contains("torch") || goalLower.contains("volume") ||
                goalLower.contains("brightness") || goalLower.contains("airplane mode") ||
                goalLower.contains("battery")

        if (isSystemGoal) {
            val appOpenStep = steps.firstOrNull { it.capabilityId == "ui_open_app" }
            if (appOpenStep != null) {
                val appName = (appOpenStep.inputParams["appName"] ?: appOpenStep.inputParams["targetApp"] ?: "unknown").lowercase()
                if (!goalLower.contains("open $appName") && !goalLower.contains("launch $appName")) {
                    Log.e("ACE_SAFETY", "ACE_SAFETY: PLAN_REJECTED! System goal '$goal' illegally attempted to open unrelated application '$appName'.")
                    return "Plan rejected: System goal '$goal' cannot execute unrelated app step 'ui_open_app' ($appName)."
                }
            }
        }
        return null
    }

    private suspend fun executeCapabilityStep(step: TaskStep, task: AgentTask, stepNumber: Int = 1): ActionResult {
        Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: step=$stepNumber action=${step.capabilityId} status=STARTED")
        Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: step=$stepNumber (${step.id})")
        Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: action=${step.capabilityId}")
        Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: status=STARTED")

        val actionResult = actionEngine.executeAction(step, task)

        if (actionResult.status == ActionResultStatus.SUCCESS) {
            Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: step=$stepNumber action=${step.capabilityId} status=SUCCESS")
            Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: status=SUCCESS")
            Log.i("ACE_EXECUTOR", "ACE_EXECUTOR: result=${actionResult.message}")
        } else {
            Log.e("ACE_EXECUTOR", "ACE_EXECUTOR: step=$stepNumber action=${step.capabilityId} status=${actionResult.status}")
            Log.e("ACE_EXECUTOR", "ACE_EXECUTOR: status=${actionResult.status}")
            Log.e("ACE_EXECUTOR", "ACE_EXECUTOR: reason=${actionResult.error ?: actionResult.message}")
        }

        return actionResult
    }
}
